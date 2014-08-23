/*
 *
 *  Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Eclipse Public License v1.0 which accompanies this distribution,
 *  and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 */

package org.opendaylight.controller.cluster.datastore;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.opendaylight.controller.cluster.datastore.utils.ActorContext;
import org.opendaylight.controller.sal.core.spi.data.DOMStoreReadTransaction;
import org.opendaylight.controller.sal.core.spi.data.DOMStoreReadWriteTransaction;
import org.opendaylight.controller.sal.core.spi.data.DOMStoreTransaction;
import org.opendaylight.controller.sal.core.spi.data.DOMStoreWriteTransaction;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

public class TransactionChainProxyTest {
    ActorContext actorContext = Mockito.mock(ActorContext.class);
    SchemaContext schemaContext = Mockito.mock(SchemaContext.class);
    @Test
    public void testNewReadOnlyTransaction() throws Exception {

     DOMStoreTransaction dst = new TransactionChainProxy(actorContext, schemaContext).newReadOnlyTransaction();
         Assert.assertTrue(dst instanceof DOMStoreReadTransaction);

    }

    @Test
    public void testNewReadWriteTransaction() throws Exception {
        DOMStoreTransaction dst = new TransactionChainProxy(actorContext, schemaContext).newReadWriteTransaction();
        Assert.assertTrue(dst instanceof DOMStoreReadWriteTransaction);

    }

    @Test
    public void testNewWriteOnlyTransaction() throws Exception {
        DOMStoreTransaction dst = new TransactionChainProxy(actorContext, schemaContext).newWriteOnlyTransaction();
        Assert.assertTrue(dst instanceof DOMStoreWriteTransaction);

    }

    @Test(expected=UnsupportedOperationException.class)
    public void testClose() throws Exception {
        new TransactionChainProxy(actorContext, schemaContext).close();
    }
}
