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
package org.apache.qpid.server.virtualhost;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.server.configuration.updater.CurrentThreadTaskExecutor;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.BrokerModel;
import org.apache.qpid.server.model.BrokerTestHelper;
import org.apache.qpid.server.model.ConfiguredObjectFactory;
import org.apache.qpid.server.model.ConfiguredObjectFactoryImpl;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.LifetimePolicy;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.SystemConfig;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.queue.PriorityQueue;
import org.apache.qpid.server.queue.PriorityQueueImpl;
import org.apache.qpid.server.queue.StandardQueueImpl;
import org.apache.qpid.server.security.AccessControl;
import org.apache.qpid.server.store.DurableConfigurationStore;
import org.apache.qpid.server.store.preferences.PreferenceStore;
import org.apache.qpid.test.utils.QpidTestCase;

public class VirtualHostQueueCreationTest extends QpidTestCase
{
    private VirtualHost<?> _virtualHost;
    private VirtualHostNode _virtualHostNode;
    private TaskExecutor _taskExecutor;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public void setUp() throws Exception
    {
        super.setUp();

        EventLogger eventLogger = mock(EventLogger.class);
        ConfiguredObjectFactory objectFactory = new ConfiguredObjectFactoryImpl(BrokerModel.getInstance());

        _taskExecutor = new CurrentThreadTaskExecutor();
        _taskExecutor.start();

        SystemConfig<?> context = mock(SystemConfig.class);
        when(context.getEventLogger()).thenReturn(eventLogger);
        when(context.createPreferenceStore()).thenReturn(mock(PreferenceStore.class));

        Principal systemPrincipal = mock(Principal.class);
        AccessControl accessControl = BrokerTestHelper.createAccessControlMock();

        Broker broker = BrokerTestHelper.mockWithSystemPrincipalAndAccessControl(Broker.class, systemPrincipal, accessControl);
        when(broker.getObjectFactory()).thenReturn(objectFactory);
        when(broker.getCategoryClass()).thenReturn(Broker.class);
        when(broker.getParent()).thenReturn(context);
        when(broker.getModel()).thenReturn(objectFactory.getModel());
        when(broker.getTaskExecutor()).thenReturn(_taskExecutor);
        when(broker.getChildExecutor()).thenReturn(_taskExecutor);

        _virtualHostNode = BrokerTestHelper.mockWithSystemPrincipalAndAccessControl(VirtualHostNode.class, systemPrincipal, accessControl);
        when(_virtualHostNode.getParent()).thenReturn(broker);
        when(_virtualHostNode.getConfigurationStore()).thenReturn(mock(DurableConfigurationStore.class));
        when(_virtualHostNode.getObjectFactory()).thenReturn(objectFactory);
        when(_virtualHostNode.getModel()).thenReturn(objectFactory.getModel());
        when(_virtualHostNode.getTaskExecutor()).thenReturn(_taskExecutor);
        when(_virtualHostNode.getChildExecutor()).thenReturn(_taskExecutor);
        when(_virtualHostNode.getCategoryClass()).thenReturn(VirtualHostNode.class);

        when(_virtualHostNode.createPreferenceStore()).thenReturn(mock(PreferenceStore.class));
        _virtualHost = createHost();
    }

    @Override
    public void tearDown() throws Exception
    {
        try
        {
            _taskExecutor.stopImmediately();
            _virtualHost.close();
        }
        finally
        {
            super.tearDown();
        }
    }
    private VirtualHost<?> createHost()
    {
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(VirtualHost.NAME, getName());
        attributes.put(VirtualHost.TYPE, TestMemoryVirtualHost.VIRTUAL_HOST_TYPE);

        attributes = new HashMap<String, Object>(attributes);
        attributes.put(VirtualHost.ID, UUID.randomUUID());
        TestMemoryVirtualHost host = new TestMemoryVirtualHost(attributes, _virtualHostNode);
        host.create();
        host.start();
        return host;
    }

    private void verifyRegisteredQueueCount(int count)
    {
        assertEquals("Queue was not registered in virtualhost", count, _virtualHost.getChildren(Queue.class).size());
    }


    private void verifyQueueRegistered(String queueName)
    {
        assertNotNull("Queue " + queueName + " was not created", _virtualHost.getChildByName(Queue.class, queueName));
    }

    public void testPriorityQueueRegistration() throws Exception
    {
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, "testPriorityQueue");

        attributes.put(PriorityQueue.PRIORITIES, 5);


        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);

        assertTrue("Queue not a priority queue", queue instanceof PriorityQueueImpl);
        verifyQueueRegistered("testPriorityQueue");
        verifyRegisteredQueueCount(1);
    }


    public void testSimpleQueueRegistration() throws Exception
    {
        String queueName = getName();
        String dlQueueName = queueName + AbstractVirtualHost.DEFAULT_DLQ_NAME_SUFFIX;

        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, queueName);


        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);
        assertTrue("Queue not a simple queue", queue instanceof StandardQueueImpl);
        verifyQueueRegistered(queueName);

        //verify that no alternate exchange or DLQ were produced

        assertNull("Queue should not have an alternate exchange as DLQ wasn't enabled", queue.getAlternateExchange());
        assertNull("The DLQ should not exist", _virtualHost.getChildByName(Queue.class, dlQueueName));

        verifyRegisteredQueueCount(1);
    }

    /**
     * Tests that setting the {@link org.apache.qpid.server.queue.QueueArgumentsConverter#X_QPID_DLQ_ENABLED} argument true does
     * cause the alternate exchange to be set and DLQ to be produced.
     */
    public void testDeadLetterQueueEnabled() throws Exception
    {

        String queueName = "testDeadLetterQueueEnabled";
        String dlExchangeName = queueName + QueueManagingVirtualHost.DEFAULT_DLE_NAME_SUFFIX;
        String dlQueueName = queueName + AbstractVirtualHost.DEFAULT_DLQ_NAME_SUFFIX;

        assertNull("The DLQ should not yet exist", _virtualHost.getChildByName(Queue.class, dlQueueName));
        assertNull("The alternate exchange should not yet exist", _virtualHost.getChildByName(Exchange.class, dlExchangeName));

        Map<String,Object> attributes = new HashMap<String, Object>();

        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, queueName);
        attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, true);

        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);

        Exchange<?> altExchange = queue.getAlternateExchange();
        assertNotNull("Queue should have an alternate exchange as DLQ is enabled", altExchange);
        assertEquals("Alternate exchange name was not as expected", dlExchangeName, altExchange.getName());
        assertEquals("Alternate exchange type was not as expected", ExchangeDefaults.FANOUT_EXCHANGE_CLASS, altExchange.getType());

        assertNotNull("The alternate exchange was not registered as expected", _virtualHost.getChildByName(Exchange.class, dlExchangeName));
        assertEquals("The registered exchange was not the expected exchange instance", altExchange, _virtualHost.getChildByName(Exchange.class, dlExchangeName));

        Queue<?> dlQueue = (Queue<?>) _virtualHost.getChildByName(Queue.class, dlQueueName);
        assertNotNull("The DLQ was not registered as expected", dlQueue);
        assertTrue("DLQ should have been bound to the alternate exchange", altExchange.isBound(dlQueue));
        assertNull("DLQ should have no alternate exchange", dlQueue.getAlternateExchange());
        assertEquals("DLQ should have a zero maximum delivery count", 0, dlQueue.getMaximumDeliveryAttempts());

        //2 queues should have been registered
        verifyRegisteredQueueCount(2);
    }

    /**
     * Tests that the deadLetterQueues/maximumDeliveryCount settings from the configuration
     * are not applied to the DLQ itself.
     */
    public void testDeadLetterQueueDoesNotInheritDLQorMDCSettings() throws Exception
    {

        String queueName = "testDeadLetterQueueEnabled";
        String dlExchangeName = queueName + QueueManagingVirtualHost.DEFAULT_DLE_NAME_SUFFIX;
        String dlQueueName = queueName + AbstractVirtualHost.DEFAULT_DLQ_NAME_SUFFIX;

        assertNull("The DLQ should not yet exist", _virtualHost.getChildByName(Queue.class, dlQueueName));
        assertNull("The alternate exchange should not yet exist", _virtualHost.getChildByName(Exchange.class, dlExchangeName));

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, queueName);
        attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, true);
        attributes.put(Queue.MAXIMUM_DELIVERY_ATTEMPTS, 5);

        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);

        assertEquals("Unexpected maximum delivery count", 5, queue.getMaximumDeliveryAttempts());
        Exchange<?> altExchange = queue.getAlternateExchange();
        assertNotNull("Queue should have an alternate exchange as DLQ is enabled", altExchange);
        assertEquals("Alternate exchange name was not as expected", dlExchangeName, altExchange.getName());
        assertEquals("Alternate exchange type was not as expected", ExchangeDefaults.FANOUT_EXCHANGE_CLASS, altExchange.getType());

        assertNotNull("The alternate exchange was not registered as expected", _virtualHost.getChildByName(Exchange.class, dlExchangeName));
        assertEquals("The registered exchange was not the expected exchange instance", altExchange, _virtualHost.getChildByName(Exchange.class, dlExchangeName));

        Queue<?> dlQueue = (Queue<?>) _virtualHost.getChildByName(Queue.class, dlQueueName);
        assertNotNull("The DLQ was not registered as expected", dlQueue);
        assertTrue("DLQ should have been bound to the alternate exchange", altExchange.isBound(dlQueue));
        assertNull("DLQ should have no alternate exchange", dlQueue.getAlternateExchange());
        assertEquals("DLQ should have a zero maximum delivery count", 0, dlQueue.getMaximumDeliveryAttempts());

        //2 queues should have been registered
        verifyRegisteredQueueCount(2);
    }

    /**
     * Tests that setting the {@link org.apache.qpid.server.queue.QueueArgumentsConverter#X_QPID_DLQ_ENABLED} argument false does not
     * result in the alternate exchange being set and DLQ being created.
     */
    public void testDeadLetterQueueDisabled() throws Exception
    {
        Map<String,Object> attributes = new HashMap<String, Object>();


        String queueName = "testDeadLetterQueueDisabled";
        String dlExchangeName = queueName + QueueManagingVirtualHost.DEFAULT_DLE_NAME_SUFFIX;
        String dlQueueName = queueName + AbstractVirtualHost.DEFAULT_DLQ_NAME_SUFFIX;

        assertNull("The DLQ should not yet exist", _virtualHost.getChildByName(Queue.class, dlQueueName));
        assertNull("The alternate exchange should not exist", _virtualHost.getChildByName(Exchange.class,
                                                                                          dlExchangeName));

        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, queueName);
        attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, false);

        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);

        assertNull("Queue should not have an alternate exchange as DLQ is disabled", queue.getAlternateExchange());
        assertNull("The alternate exchange should still not exist", _virtualHost.getChildByName(Exchange.class, dlExchangeName));
        assertNull("The DLQ should still not exist", _virtualHost.getChildByName(Queue.class, dlQueueName));

        //only 1 queue should have been registered
        verifyRegisteredQueueCount(1);
    }

    /**
     * Tests that setting the {@link org.apache.qpid.server.queue.QueueArgumentsConverter#X_QPID_DLQ_ENABLED} argument true but
     * creating an auto-delete queue, does not result in the alternate exchange
     * being set and DLQ being created.
     */
    public void testDeadLetterQueueNotCreatedForAutodeleteQueues() throws Exception
    {

        String queueName = "testDeadLetterQueueNotCreatedForAutodeleteQueues";
        String dlExchangeName = queueName + QueueManagingVirtualHost.DEFAULT_DLE_NAME_SUFFIX;
        String dlQueueName = queueName + AbstractVirtualHost.DEFAULT_DLQ_NAME_SUFFIX;

        assertNull("The DLQ should not yet exist", _virtualHost.getChildByName(Queue.class, dlQueueName));
        assertNull("The alternate exchange should not exist", _virtualHost.getChildByName(Exchange.class, dlExchangeName));

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, queueName);

        attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, true);
        attributes.put(Queue.LIFETIME_POLICY, LifetimePolicy.DELETE_ON_NO_OUTBOUND_LINKS);

        //create an autodelete queue
        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);
        assertEquals("Queue should be autodelete",
                     LifetimePolicy.DELETE_ON_NO_OUTBOUND_LINKS,
                     queue.getLifetimePolicy());

        //ensure that the autodelete property overrides the request to enable DLQ
        assertNull("Queue should not have an alternate exchange as queue is autodelete", queue.getAlternateExchange());
        assertNull("The alternate exchange should not exist as queue is autodelete", _virtualHost.getChildByName( Exchange.class, dlExchangeName));
        assertNull("The DLQ should not exist as queue is autodelete", _virtualHost.getChildByName(Queue.class, dlQueueName));

        //only 1 queue should have been registered
        verifyRegisteredQueueCount(1);
    }

    /**
     * Tests that setting the {@link org.apache.qpid.server.queue.QueueArgumentsConverter#X_QPID_MAXIMUM_DELIVERY_COUNT} argument has
     * the desired effect.
     */
    public void testMaximumDeliveryCount() throws Exception
    {
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, "testMaximumDeliveryCount");

        attributes.put(Queue.MAXIMUM_DELIVERY_ATTEMPTS, (Object) 5);

        final Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);

        assertNotNull("The queue was not registered as expected ", queue);
        assertEquals("Maximum delivery count not as expected", 5, queue.getMaximumDeliveryAttempts());

        verifyRegisteredQueueCount(1);
    }

    /**
     * Tests that omitting the {@link org.apache.qpid.server.queue.QueueArgumentsConverter#X_QPID_MAXIMUM_DELIVERY_COUNT} argument means
     * that queue is created with a default maximumDeliveryCount of zero (unless set in config).
     */
    public void testMaximumDeliveryCountDefault() throws Exception
    {
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, "testMaximumDeliveryCountDefault");

        final Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);

        assertNotNull("The queue was not registered as expected ", queue);
        assertEquals("Maximum delivery count not as expected", 0, queue.getMaximumDeliveryAttempts());

        verifyRegisteredQueueCount(1);
    }

    /**
     * Tests queue creation with queue name set to null
     */
    public void testQueueNameNullValidation()
    {
        try
        {
            Map<String,Object> attributes = new HashMap<String, Object>();
            attributes.put(Queue.ID, UUID.randomUUID());

            _virtualHost.createChild(Queue.class, attributes);
            fail("queue with null name can not be created!");
        }
        catch (Exception e)
        {
            assertTrue(e instanceof IllegalArgumentException);
            assertTrue(e.getMessage().startsWith("The name attribute is mandatory"));
        }
    }

    /**
     * Tests queue creation with queue name length less 255 characters but
     * corresponding DLQ name length greater than 255.
     */
    public void testQueueNameWithLengthLessThan255ButDLQNameWithLengthGreaterThan255()
    {
        String queueName = "test-" + generateStringWithLength('a', 245);
        try
        {
            // change DLQ name to make its length bigger than exchange name
            setTestSystemProperty(QueueManagingVirtualHost.PROPERTY_DEAD_LETTER_EXCHANGE_SUFFIX, "_DLE");
            setTestSystemProperty(QueueManagingVirtualHost.PROPERTY_DEAD_LETTER_QUEUE_SUFFIX, "_DLQUEUE");

            Map<String,Object> attributes = new HashMap<String, Object>();
            attributes.put(Queue.ID, UUID.randomUUID());
            attributes.put(Queue.NAME, queueName);

            attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, true);

            _virtualHost.createChild(Queue.class, attributes);
            fail("queue with DLQ name having more than 255 characters can not be created!");
        }
        catch (Exception e)
        {
            assertTrue("Unexpected exception is thrown!", e instanceof IllegalArgumentException);
            assertTrue("Unexpected exception message!", e.getMessage().contains("DLQ queue name")
                    && e.getMessage().contains("length exceeds limit of 255"));
        }
    }

    /**
     * Tests queue creation with queue name length less 255 characters but
     * corresponding DL exchange name length greater than 255.
     */
    public void testQueueNameWithLengthLessThan255ButDLExchangeNameWithLengthGreaterThan255()
    {
        String queueName = "test-" + generateStringWithLength('a', 245);
        try
        {
            // change DLQ name to make its length bigger than exchange name
            setTestSystemProperty(QueueManagingVirtualHost.PROPERTY_DEAD_LETTER_EXCHANGE_SUFFIX, "_DLEXCHANGE");
            setTestSystemProperty(QueueManagingVirtualHost.PROPERTY_DEAD_LETTER_QUEUE_SUFFIX, "_DLQ");

            Map<String,Object> attributes = new HashMap<String, Object>();
            attributes.put(Queue.ID, UUID.randomUUID());
            attributes.put(Queue.NAME, queueName);

            attributes.put(AbstractVirtualHost.CREATE_DLQ_ON_CREATION, (Object) true);

            _virtualHost.createChild(Queue.class, attributes);
            fail("queue with DLE name having more than 255 characters can not be created!");
        }
        catch (Exception e)
        {
            assertTrue("Unexpected exception is thrown!", e instanceof IllegalArgumentException);
            assertTrue("Unexpected exception message!", e.getMessage().contains("DL exchange name")
                    && e.getMessage().contains("length exceeds limit of 255"));
        }
    }

    public void testMessageGroupQueue() throws Exception
    {

        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, getTestName());
        attributes.put(Queue.MESSAGE_GROUP_KEY,"mykey");
        attributes.put(Queue.MESSAGE_GROUP_SHARED_GROUPS, true);

        Queue<?> queue = _virtualHost.createChild(Queue.class, attributes);
        assertEquals("mykey", queue.getAttribute(Queue.MESSAGE_GROUP_KEY));
        assertEquals(Boolean.TRUE, queue.getAttribute(Queue.MESSAGE_GROUP_SHARED_GROUPS));
    }

    private String generateStringWithLength(char ch, int length)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++)
        {
            sb.append(ch);
        }
        return sb.toString();
    }


}
