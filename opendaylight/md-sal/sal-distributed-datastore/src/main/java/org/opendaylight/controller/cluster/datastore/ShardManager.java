/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.cluster.datastore;

import akka.actor.ActorPath;
import akka.actor.ActorRef;
import akka.actor.Address;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.cluster.ClusterEvent;
import akka.japi.Creator;
import akka.japi.Function;
import akka.japi.Procedure;
import akka.persistence.RecoveryCompleted;
import akka.persistence.RecoveryFailure;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.opendaylight.controller.cluster.DataPersistenceProvider;
import org.opendaylight.controller.cluster.common.actor.AbstractUntypedPersistentActorWithMetering;
import org.opendaylight.controller.cluster.datastore.identifiers.ShardIdentifier;
import org.opendaylight.controller.cluster.datastore.identifiers.ShardManagerIdentifier;
import org.opendaylight.controller.cluster.datastore.jmx.mbeans.shardmanager.ShardManagerInfo;
import org.opendaylight.controller.cluster.datastore.jmx.mbeans.shardmanager.ShardManagerInfoMBean;
import org.opendaylight.controller.cluster.datastore.messages.ActorInitialized;
import org.opendaylight.controller.cluster.datastore.messages.ActorNotInitialized;
import org.opendaylight.controller.cluster.datastore.messages.FindLocalShard;
import org.opendaylight.controller.cluster.datastore.messages.FindPrimary;
import org.opendaylight.controller.cluster.datastore.messages.LocalShardFound;
import org.opendaylight.controller.cluster.datastore.messages.LocalShardNotFound;
import org.opendaylight.controller.cluster.datastore.messages.PeerAddressResolved;
import org.opendaylight.controller.cluster.datastore.messages.PrimaryFound;
import org.opendaylight.controller.cluster.datastore.messages.PrimaryNotFound;
import org.opendaylight.controller.cluster.datastore.messages.UpdateSchemaContext;
import org.opendaylight.controller.cluster.datastore.utils.Dispatchers;
import org.opendaylight.controller.cluster.notifications.RegisterRoleChangeListener;
import org.opendaylight.controller.cluster.notifications.RoleChangeNotification;
import org.opendaylight.controller.cluster.raft.RaftState;
import org.opendaylight.controller.cluster.raft.base.messages.FollowerInitialSyncUpStatus;
import org.opendaylight.yangtools.yang.model.api.ModuleIdentifier;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.Duration;

/**
 * The ShardManager has the following jobs,
 * <ul>
 * <li> Create all the local shard replicas that belong on this cluster member
 * <li> Find the address of the local shard
 * <li> Find the primary replica for any given shard
 * <li> Monitor the cluster members and store their addresses
 * <ul>
 */
public class ShardManager extends AbstractUntypedPersistentActorWithMetering {

    private final Logger LOG = LoggerFactory.getLogger(getClass());

    // Stores a mapping between a member name and the address of the member
    // Member names look like "member-1", "member-2" etc and are as specified
    // in configuration
    private final Map<String, Address> memberNameToAddress = new HashMap<>();

    // Stores a mapping between a shard name and it's corresponding information
    // Shard names look like inventory, topology etc and are as specified in
    // configuration
    private final Map<String, ShardInformation> localShards = new HashMap<>();

    // The type of a ShardManager reflects the type of the datastore itself
    // A data store could be of type config/operational
    private final String type;

    private final ClusterWrapper cluster;

    private final Configuration configuration;

    private final String shardDispatcherPath;

    private ShardManagerInfo mBean;

    private DatastoreContext datastoreContext;

    private Collection<String> knownModules = Collections.emptySet();

    private final DataPersistenceProvider dataPersistenceProvider;

    private final CountDownLatch waitTillReadyCountdownLatch;

    /**
     */
    protected ShardManager(ClusterWrapper cluster, Configuration configuration,
            DatastoreContext datastoreContext, CountDownLatch waitTillReadyCountdownLatch) {

        this.cluster = Preconditions.checkNotNull(cluster, "cluster should not be null");
        this.configuration = Preconditions.checkNotNull(configuration, "configuration should not be null");
        this.datastoreContext = datastoreContext;
        this.dataPersistenceProvider = createDataPersistenceProvider(datastoreContext.isPersistent());
        this.type = datastoreContext.getDataStoreType();
        this.shardDispatcherPath =
                new Dispatchers(context().system().dispatchers()).getDispatcherPath(Dispatchers.DispatcherType.Shard);
        this.waitTillReadyCountdownLatch = waitTillReadyCountdownLatch;

        // Subscribe this actor to cluster member events
        cluster.subscribeToMemberEvents(getSelf());

        createLocalShards();
    }

    protected DataPersistenceProvider createDataPersistenceProvider(boolean persistent) {
        return (persistent) ? new PersistentDataProvider() : new NonPersistentDataProvider();
    }

    public static Props props(
        final ClusterWrapper cluster,
        final Configuration configuration,
        final DatastoreContext datastoreContext,
        final CountDownLatch waitTillReadyCountdownLatch) {

        Preconditions.checkNotNull(cluster, "cluster should not be null");
        Preconditions.checkNotNull(configuration, "configuration should not be null");
        Preconditions.checkNotNull(waitTillReadyCountdownLatch, "waitTillReadyCountdownLatch should not be null");

        return Props.create(new ShardManagerCreator(cluster, configuration, datastoreContext, waitTillReadyCountdownLatch));
    }

    @Override
    public void postStop() {
        LOG.info("Stopping ShardManager");

        mBean.unregisterMBean();
    }

    @Override
    public void handleCommand(Object message) throws Exception {
        if (FindPrimary.SERIALIZABLE_CLASS.isInstance(message)) {
            findPrimary(FindPrimary.fromSerializable(message));
        } else if(message instanceof FindLocalShard){
            findLocalShard((FindLocalShard) message);
        } else if (message instanceof UpdateSchemaContext) {
            updateSchemaContext(message);
        } else if(message instanceof ActorInitialized) {
            onActorInitialized(message);
        } else if (message instanceof ClusterEvent.MemberUp){
            memberUp((ClusterEvent.MemberUp) message);
        } else if(message instanceof ClusterEvent.MemberRemoved) {
            memberRemoved((ClusterEvent.MemberRemoved) message);
        } else if(message instanceof ClusterEvent.UnreachableMember) {
            ignoreMessage(message);
        } else if(message instanceof DatastoreContext) {
            onDatastoreContext((DatastoreContext)message);
        } else if(message instanceof RoleChangeNotification) {
            onRoleChangeNotification((RoleChangeNotification) message);
        } else if(message instanceof FollowerInitialSyncUpStatus){
            onFollowerInitialSyncStatus((FollowerInitialSyncUpStatus) message);
        } else{
            unknownMessage(message);
        }

    }

    private void onFollowerInitialSyncStatus(FollowerInitialSyncUpStatus status) {
        LOG.info("Received follower initial sync status for {} status sync done {}", status.getName(),
                status.isInitialSyncDone());

        ShardInformation shardInformation = findShardInformation(status.getName());

        if(shardInformation != null) {
            shardInformation.setFollowerSyncStatus(status.isInitialSyncDone());

            mBean.setSyncStatus(isInSync());
        }

    }

    private void onRoleChangeNotification(RoleChangeNotification roleChanged) {
        LOG.info("Received role changed for {} from {} to {}", roleChanged.getMemberId(),
                roleChanged.getOldRole(), roleChanged.getNewRole());

        ShardInformation shardInformation = findShardInformation(roleChanged.getMemberId());
        if(shardInformation != null) {
            shardInformation.setRole(roleChanged.getNewRole());

            if (isReady()) {
                LOG.info("All Shards are ready - data store {} is ready, available count is {}", type,
                        waitTillReadyCountdownLatch.getCount());

                waitTillReadyCountdownLatch.countDown();
            }

            mBean.setSyncStatus(isInSync());
        }
    }


    private ShardInformation findShardInformation(String memberId) {
        for(ShardInformation info : localShards.values()){
            if(info.getShardId().toString().equals(memberId)){
                return info;
            }
        }

        return null;
    }

    private boolean isReady() {
        boolean isReady = true;
        for (ShardInformation info : localShards.values()) {
            if(RaftState.Candidate.name().equals(info.getRole()) || Strings.isNullOrEmpty(info.getRole())){
                isReady = false;
                break;
            }
        }
        return isReady;
    }

    private boolean isInSync(){
        for (ShardInformation info : localShards.values()) {
            if(!info.isInSync()){
                return false;
            }
        }
        return true;
    }

    private void onActorInitialized(Object message) {
        final ActorRef sender = getSender();

        if (sender == null) {
            return; //why is a non-actor sending this message? Just ignore.
        }

        String actorName = sender.path().name();
        //find shard name from actor name; actor name is stringified shardId
        ShardIdentifier shardId = ShardIdentifier.builder().fromShardIdString(actorName).build();

        if (shardId.getShardName() == null) {
            return;
        }
        markShardAsInitialized(shardId.getShardName());
    }

    private void markShardAsInitialized(String shardName) {
        LOG.debug("Initializing shard [{}]", shardName);
        ShardInformation shardInformation = localShards.get(shardName);
        if (shardInformation != null) {
            shardInformation.setActorInitialized();
        }
    }

    @Override
    protected void handleRecover(Object message) throws Exception {
        if(dataPersistenceProvider.isRecoveryApplicable()) {
            if (message instanceof SchemaContextModules) {
                SchemaContextModules msg = (SchemaContextModules) message;
                knownModules = ImmutableSet.copyOf(msg.getModules());
            } else if (message instanceof RecoveryFailure) {
                RecoveryFailure failure = (RecoveryFailure) message;
                LOG.error("Recovery failed", failure.cause());
            } else if (message instanceof RecoveryCompleted) {
                LOG.info("Recovery complete : {}", persistenceId());

                // Delete all the messages from the akka journal except the last one
                deleteMessages(lastSequenceNr() - 1);
            }
        } else {
            if (message instanceof RecoveryCompleted) {
                LOG.info("Recovery complete : {}", persistenceId());

                // Delete all the messages from the akka journal
                deleteMessages(lastSequenceNr());
            }
        }
    }

    private void findLocalShard(FindLocalShard message) {
        final ShardInformation shardInformation = localShards.get(message.getShardName());

        if(shardInformation == null){
            getSender().tell(new LocalShardNotFound(message.getShardName()), getSelf());
            return;
        }

        sendResponse(shardInformation, message.isWaitUntilInitialized(), new Supplier<Object>() {
            @Override
            public Object get() {
                return new LocalShardFound(shardInformation.getActor());
            }
        });
    }

    private void sendResponse(ShardInformation shardInformation, boolean waitUntilInitialized,
            final Supplier<Object> messageSupplier) {
        if (!shardInformation.isShardInitialized()) {
            if(waitUntilInitialized) {
                final ActorRef sender = getSender();
                final ActorRef self = self();
                shardInformation.addRunnableOnInitialized(new Runnable() {
                    @Override
                    public void run() {
                        sender.tell(messageSupplier.get(), self);
                    }
                });
            } else {
                getSender().tell(new ActorNotInitialized(), getSelf());
            }

            return;
        }

        getSender().tell(messageSupplier.get(), getSelf());
    }

    private void memberRemoved(ClusterEvent.MemberRemoved message) {
        memberNameToAddress.remove(message.member().roles().head());
    }

    private void memberUp(ClusterEvent.MemberUp message) {
        String memberName = message.member().roles().head();

        memberNameToAddress.put(memberName, message.member().address());

        for(ShardInformation info : localShards.values()){
            String shardName = info.getShardName();
            info.updatePeerAddress(getShardIdentifier(memberName, shardName),
                getShardActorPath(shardName, memberName));
        }
    }

    private void onDatastoreContext(DatastoreContext context) {
        datastoreContext = context;
        for (ShardInformation info : localShards.values()) {
            if (info.getActor() != null) {
                info.getActor().tell(datastoreContext, getSelf());
            }
        }
    }

    /**
     * Notifies all the local shards of a change in the schema context
     *
     * @param message
     */
    private void updateSchemaContext(final Object message) {
        final SchemaContext schemaContext = ((UpdateSchemaContext) message).getSchemaContext();

        Set<ModuleIdentifier> allModuleIdentifiers = schemaContext.getAllModuleIdentifiers();
        Set<String> newModules = new HashSet<>(128);

        for(ModuleIdentifier moduleIdentifier : allModuleIdentifiers){
            String s = moduleIdentifier.getNamespace().toString();
            newModules.add(s);
        }

        if(newModules.containsAll(knownModules)) {

            LOG.debug("New SchemaContext has a super set of current knownModules - persisting info");

            knownModules = ImmutableSet.copyOf(newModules);

            dataPersistenceProvider.persist(new SchemaContextModules(newModules), new Procedure<SchemaContextModules>() {

                @Override
                public void apply(SchemaContextModules param) throws Exception {
                    LOG.debug("Sending new SchemaContext to Shards");
                    for (ShardInformation info : localShards.values()) {
                        if (info.getActor() == null) {
                            info.setActor(getContext().actorOf(Shard.props(info.getShardId(),
                                    info.getPeerAddresses(), datastoreContext, schemaContext)
                                            .withDispatcher(shardDispatcherPath), info.getShardId().toString()));
                        } else {
                            info.getActor().tell(message, getSelf());
                        }
                        info.getActor().tell(new RegisterRoleChangeListener(), self());
                    }
                }

            });
        } else {
            LOG.debug("Rejecting schema context update - not a super set of previously known modules:\nUPDATE: {}\nKNOWN: {}",
                    newModules, knownModules);
        }

    }

    private void findPrimary(FindPrimary message) {
        String shardName = message.getShardName();

        // First see if the there is a local replica for the shard
        final ShardInformation info = localShards.get(shardName);
        if (info != null) {
            sendResponse(info, message.isWaitUntilInitialized(), new Supplier<Object>() {
                @Override
                public Object get() {
                    return new PrimaryFound(info.getActorPath().toString()).toSerializable();
                }
            });

            return;
        }

        List<String> members = configuration.getMembersFromShardName(shardName);

        if(cluster.getCurrentMemberName() != null) {
            members.remove(cluster.getCurrentMemberName());
        }

        /**
         * FIXME: Instead of sending remote shard actor path back to sender,
         * forward FindPrimary message to remote shard manager
         */
        // There is no way for us to figure out the primary (for now) so assume
        // that one of the remote nodes is a primary
        for(String memberName : members) {
            Address address = memberNameToAddress.get(memberName);
            if(address != null){
                String path =
                    getShardActorPath(shardName, memberName);
                getSender().tell(new PrimaryFound(path).toSerializable(), getSelf());
                return;
            }
        }
        getSender().tell(new PrimaryNotFound(shardName).toSerializable(), getSelf());
    }

    private String getShardActorPath(String shardName, String memberName) {
        Address address = memberNameToAddress.get(memberName);
        if(address != null) {
            StringBuilder builder = new StringBuilder();
            builder.append(address.toString())
                .append("/user/")
                .append(ShardManagerIdentifier.builder().type(type).build().toString())
                .append("/")
                .append(getShardIdentifier(memberName, shardName));
            return builder.toString();
        }
        return null;
    }

    /**
     * Construct the name of the shard actor given the name of the member on
     * which the shard resides and the name of the shard
     *
     * @param memberName
     * @param shardName
     * @return
     */
    private ShardIdentifier getShardIdentifier(String memberName, String shardName){
        return ShardIdentifier.builder().memberName(memberName).shardName(shardName).type(type).build();
    }

    /**
     * Create shards that are local to the member on which the ShardManager
     * runs
     *
     */
    private void createLocalShards() {
        String memberName = this.cluster.getCurrentMemberName();
        List<String> memberShardNames =
            this.configuration.getMemberShardNames(memberName);

        List<String> localShardActorNames = new ArrayList<>();
        for(String shardName : memberShardNames){
            ShardIdentifier shardId = getShardIdentifier(memberName, shardName);
            Map<ShardIdentifier, String> peerAddresses = getPeerAddresses(shardName);
            localShardActorNames.add(shardId.toString());
            localShards.put(shardName, new ShardInformation(shardName, shardId, peerAddresses));
        }

        mBean = ShardManagerInfo.createShardManagerMBean("shard-manager-" + this.type,
                    datastoreContext.getDataStoreMXBeanType(), localShardActorNames);
    }

    /**
     * Given the name of the shard find the addresses of all it's peers
     *
     * @param shardName
     * @return
     */
    private Map<ShardIdentifier, String> getPeerAddresses(String shardName){

        Map<ShardIdentifier, String> peerAddresses = new HashMap<>();

        List<String> members =
            this.configuration.getMembersFromShardName(shardName);

        String currentMemberName = this.cluster.getCurrentMemberName();

        for(String memberName : members){
            if(!currentMemberName.equals(memberName)){
                ShardIdentifier shardId = getShardIdentifier(memberName,
                    shardName);
                String path =
                    getShardActorPath(shardName, currentMemberName);
                peerAddresses.put(shardId, path);
            }
        }
        return peerAddresses;
    }

    @Override
    public SupervisorStrategy supervisorStrategy() {

        return new OneForOneStrategy(10, Duration.create("1 minute"),
            new Function<Throwable, SupervisorStrategy.Directive>() {
                @Override
                public SupervisorStrategy.Directive apply(Throwable t) {
                    LOG.warn("Supervisor Strategy caught unexpected exception - resuming", t);
                    return SupervisorStrategy.resume();
                }
            }
        );

    }

    @Override
    public String persistenceId() {
        return "shard-manager-" + type;
    }

    @VisibleForTesting
    Collection<String> getKnownModules() {
        return knownModules;
    }

    @VisibleForTesting
    DataPersistenceProvider getDataPersistenceProvider() {
        return dataPersistenceProvider;
    }

    @VisibleForTesting
    ShardManagerInfoMBean getMBean(){
        return mBean;
    }

    private class ShardInformation {
        private final ShardIdentifier shardId;
        private final String shardName;
        private ActorRef actor;
        private ActorPath actorPath;
        private final Map<ShardIdentifier, String> peerAddresses;

        // flag that determines if the actor is ready for business
        private boolean actorInitialized = false;

        private boolean followerSyncStatus = false;

        private final List<Runnable> runnablesOnInitialized = Lists.newArrayList();
        private String role ;

        private ShardInformation(String shardName, ShardIdentifier shardId,
                Map<ShardIdentifier, String> peerAddresses) {
            this.shardName = shardName;
            this.shardId = shardId;
            this.peerAddresses = peerAddresses;
        }

        String getShardName() {
            return shardName;
        }

        ActorRef getActor(){
            return actor;
        }

        ActorPath getActorPath() {
            return actorPath;
        }

        void setActor(ActorRef actor) {
            this.actor = actor;
            this.actorPath = actor.path();
        }

        ShardIdentifier getShardId() {
            return shardId;
        }

        Map<ShardIdentifier, String> getPeerAddresses() {
            return peerAddresses;
        }

        void updatePeerAddress(ShardIdentifier peerId, String peerAddress){
            LOG.info("updatePeerAddress for peer {} with address {}", peerId,
                peerAddress);
            if(peerAddresses.containsKey(peerId)){
                peerAddresses.put(peerId, peerAddress);

                if(actor != null) {
                    if(LOG.isDebugEnabled()) {
                        LOG.debug("Sending PeerAddressResolved for peer {} with address {} to {}",
                                peerId, peerAddress, actor.path());
                    }

                    actor.tell(new PeerAddressResolved(peerId, peerAddress), getSelf());
                }
            }
        }

        boolean isShardInitialized() {
            return getActor() != null && actorInitialized;
        }

        void setActorInitialized() {
            this.actorInitialized = true;

            for(Runnable runnable: runnablesOnInitialized) {
                runnable.run();
            }

            runnablesOnInitialized.clear();
        }

        void addRunnableOnInitialized(Runnable runnable) {
            runnablesOnInitialized.add(runnable);
        }

        public void setRole(String newRole) {
            this.role = newRole;
        }

        public String getRole(){
            return this.role;
        }

        public void setFollowerSyncStatus(boolean syncStatus){
            this.followerSyncStatus = syncStatus;
        }

        public boolean isInSync(){
            if(RaftState.Follower.name().equals(this.role)){
                return followerSyncStatus;
            } else if(RaftState.Leader.name().equals(this.role)){
                return true;
            }

            return false;
        }

    }

    private static class ShardManagerCreator implements Creator<ShardManager> {
        private static final long serialVersionUID = 1L;

        final ClusterWrapper cluster;
        final Configuration configuration;
        final DatastoreContext datastoreContext;
        private final CountDownLatch waitTillReadyCountdownLatch;

        ShardManagerCreator(ClusterWrapper cluster,
                            Configuration configuration, DatastoreContext datastoreContext, CountDownLatch waitTillReadyCountdownLatch) {
            this.cluster = cluster;
            this.configuration = configuration;
            this.datastoreContext = datastoreContext;
            this.waitTillReadyCountdownLatch = waitTillReadyCountdownLatch;
        }

        @Override
        public ShardManager create() throws Exception {
            return new ShardManager(cluster, configuration, datastoreContext, waitTillReadyCountdownLatch);
        }
    }

    static class SchemaContextModules implements Serializable {
        private static final long serialVersionUID = -8884620101025936590L;

        private final Set<String> modules;

        SchemaContextModules(Set<String> modules){
            this.modules = modules;
        }

        public Set<String> getModules() {
            return modules;
        }
    }
}



