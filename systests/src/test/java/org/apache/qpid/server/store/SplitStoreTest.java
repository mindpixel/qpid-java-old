/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.store;

import java.util.Collections;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.qpid.configuration.ClientProperties;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.virtualhostnode.AbstractVirtualHostNode;
import org.apache.qpid.server.virtualhostnode.JsonVirtualHostNode;
import org.apache.qpid.server.virtualhostnode.JsonVirtualHostNodeImpl;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class SplitStoreTest extends QpidBrokerTestCase
{
    private String _messageStorePath;
    private String _configStorePath;

    @Override
    protected void setUp() throws Exception
    {
        super.setUp();

        String virtualHostWorkDir = "${json:qpid.work_dir}${json:file.separator}${this:name}${json:file.separator}";
        _messageStorePath =  virtualHostWorkDir  + "messageStore";
        _configStorePath =  virtualHostWorkDir  + "configStore";
    }

    @Override
    public void startDefaultBroker() throws Exception
    {
        // Overridden to prevent QBTC starting the Broker.
    }

    public void testJsonConfigurationStoreWithPersistentMessageStore() throws Exception
    {
        doTest(JsonVirtualHostNodeImpl.VIRTUAL_HOST_NODE_TYPE, getTestProfileVirtualHostNodeType());
    }

    public void testSeparateConfigurationAndMessageStoresOfTheSameType() throws Exception
    {
        doTest(getTestProfileVirtualHostNodeType(), getTestProfileVirtualHostNodeType());
    }

    private void configureAndStartBroker(String virtualHostNodeType, String virtualHostType) throws Exception
    {
        final String blueprint = String.format(
           "{ \"type\" : \"%s\",  \"storePath\" : \"%s\" }", virtualHostType, _messageStorePath);
        final Map<String, String> contextMap = Collections.singletonMap(AbstractVirtualHostNode.VIRTUALHOST_BLUEPRINT_CONTEXT_VAR,
                                                                        blueprint);

        TestBrokerConfiguration config = getDefaultBrokerConfiguration();
        config.setObjectAttribute(VirtualHostNode.class, TestBrokerConfiguration.ENTRY_NAME_VIRTUAL_HOST, VirtualHostNode.TYPE, virtualHostNodeType);
        config.setObjectAttribute(VirtualHostNode.class, TestBrokerConfiguration.ENTRY_NAME_VIRTUAL_HOST, VirtualHostNode.CONTEXT, contextMap);
        config.setObjectAttribute(VirtualHostNode.class, TestBrokerConfiguration.ENTRY_NAME_VIRTUAL_HOST, JsonVirtualHostNode.STORE_PATH, _configStorePath);

        super.startDefaultBroker();
    }

    private void doTest(String nodeType, String path) throws Exception
    {
        configureAndStartBroker(nodeType, path);

        Connection connection = getConnection();
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        Queue queue = session.createQueue(getTestQueueName());
        session.createConsumer(queue).close(); // Create durable queue by side effect
        sendMessage(session, queue, 1);
        connection.close();

        restartDefaultBroker();

        setTestSystemProperty(ClientProperties.QPID_DECLARE_QUEUES_PROP_NAME, "false");
        connection = getConnection();
        connection.start();
        session = connection.createSession(true, Session.SESSION_TRANSACTED);
        MessageConsumer consumer = session.createConsumer(queue);
        Message message = consumer.receive(1000);
        session.commit();

        assertNotNull("Message was not received after first restart", message);
        assertEquals("Unexpected message received after first restart", 0, message.getIntProperty(INDEX));

        stopDefaultBroker();
    }

}
