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
package org.apache.qpid.server.exchange;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anySet;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.apache.qpid.common.AMQPFilterTypes;
import org.apache.qpid.server.configuration.updater.CurrentThreadTaskExecutor;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.message.AMQMessageHeader;
import org.apache.qpid.server.message.EnqueueableMessage;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.RoutingResult;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.BrokerModel;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.model.VirtualHostNode;
import org.apache.qpid.server.queue.BaseQueue;
import org.apache.qpid.server.store.MessageEnqueueRecord;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.virtualhost.QueueManagingVirtualHost;
import org.apache.qpid.test.utils.QpidTestCase;

public class FanoutExchangeTest extends QpidTestCase
{
    private FanoutExchangeImpl _exchange;
    private QueueManagingVirtualHost _virtualHost;
    private TaskExecutor _taskExecutor;

    public void setUp()
    {
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Exchange.ID, UUID.randomUUID());
        attributes.put(Exchange.NAME, "test");
        attributes.put(Exchange.DURABLE, false);

        Broker broker = mock(Broker.class);
        when(broker.getCategoryClass()).thenReturn(Broker.class);
        when(broker.getModel()).thenReturn(BrokerModel.getInstance());

        VirtualHostNode virtualHostNode = mock(VirtualHostNode.class);
        when(virtualHostNode.getCategoryClass()).thenReturn(VirtualHostNode.class);
        when(virtualHostNode.getParent()).thenReturn(broker);
        when(virtualHostNode.getModel()).thenReturn(BrokerModel.getInstance());

        _taskExecutor = new CurrentThreadTaskExecutor();
        _taskExecutor.start();
        _virtualHost = mock(QueueManagingVirtualHost.class);

        when(_virtualHost.getEventLogger()).thenReturn(new EventLogger());
        when(_virtualHost.getState()).thenReturn(State.ACTIVE);
        when(_virtualHost.getTaskExecutor()).thenReturn(_taskExecutor);
        when(_virtualHost.getChildExecutor()).thenReturn(_taskExecutor);
        when(_virtualHost.getModel()).thenReturn(BrokerModel.getInstance());
        when(_virtualHost.getParent()).thenReturn(virtualHostNode);
        when(_virtualHost.getCategoryClass()).thenReturn(VirtualHost.class);
        _exchange = new FanoutExchangeImpl(attributes, _virtualHost);
        _exchange.open();
    }

    public void tearDown() throws Exception
    {
        super.tearDown();
        _taskExecutor.stop();
    }

    public void testIsBoundStringMapAMQQueueWhenQueueIsNull()
    {
        assertFalse("calling isBound(AMQShortString,FieldTable,Queue<?>) with null queue should return false",
                _exchange.isBound((String) null, (Map) null, (Queue<?>) null));
    }

    public void testIsBoundStringAMQQueueWhenQueueIsNull()
    {
        assertFalse("calling isBound(AMQShortString,Queue<?>) with null queue should return false",
                _exchange.isBound((String) null, (Queue<?>) null));
    }

    public void testIsBoundAMQQueueWhenQueueIsNull()
    {
        assertFalse("calling isBound(AMQQueue) with null queue should return false", _exchange.isBound((Queue<?>) null));
    }

    public void testIsBoundStringMapAMQQueue()
    {
        Queue<?> queue = bindQueue();
        assertTrue("Should return true for a bound queue",
                _exchange.isBound("matters", null, queue));
    }

    public void testIsBoundStringAMQQueue()
    {
        Queue<?> queue = bindQueue();
        assertTrue("Should return true for a bound queue",
                _exchange.isBound("matters", queue));
    }

    public void testIsBoundAMQQueue()
    {
        Queue<?> queue = bindQueue();
        assertTrue("Should return true for a bound queue",
                _exchange.isBound(queue));
    }

    private Queue<?> bindQueue()
    {
        Queue<?> queue = mockQueue();

        _exchange.addBinding("matters", queue, null);
        return queue;
    }

    private Queue<?> mockQueue()
    {
        Queue queue = mock(Queue.class);
        String name = UUID.randomUUID().toString();
        when(queue.getName()).thenReturn(name);
        when(queue.getVirtualHost()).thenReturn(_virtualHost);
        when(queue.getCategoryClass()).thenReturn(Queue.class);
        when(queue.getModel()).thenReturn(BrokerModel.getInstance());
        TaskExecutor taskExecutor = CurrentThreadTaskExecutor.newStartedInstance();
        when(queue.getTaskExecutor()).thenReturn(taskExecutor);
        when(queue.getChildExecutor()).thenReturn(taskExecutor);
        when(queue.getParent()).thenReturn(_virtualHost);
        when(_virtualHost.getAttainedQueue(eq(name))).thenReturn(queue);
        RoutingResult result = new RoutingResult(null);
        result.addQueue(queue);
        when(queue.route(any(ServerMessage.class),anyString(),any(InstanceProperties.class))).thenReturn(result);
        return queue;
    }

    public void testRoutingWithSelectors() throws Exception
    {
        Queue<?> queue1 = mockQueue();
        Queue<?> queue2 = mockQueue();


        _exchange.addBinding("key",queue1, null);
        _exchange.addBinding("key",queue2, null);

        List<? extends BaseQueue> result;
        result = routeToQueues(mockMessage(true), "", InstanceProperties.EMPTY);

        assertEquals("Expected message to be routed to both queues", 2, result.size());
        assertTrue("Expected queue1 to be routed to", result.contains(queue1));
        assertTrue("Expected queue2 to be routed to", result.contains(queue2));

        _exchange.addBinding("key2",queue2, Collections.singletonMap(AMQPFilterTypes.JMS_SELECTOR.toString(),(Object)"select = True"));

        result = routeToQueues(mockMessage(true), "", InstanceProperties.EMPTY);

        assertEquals("Expected message to be routed to both queues", 2, result.size());
        assertTrue("Expected queue1 to be routed to", result.contains(queue1));
        assertTrue("Expected queue2 to be routed to", result.contains(queue2));

        _exchange.deleteBinding("key",queue2);

        result = routeToQueues(mockMessage(true), "", InstanceProperties.EMPTY);

        assertEquals("Expected message to be routed to both queues", 2, result.size());
        assertTrue("Expected queue1 to be routed to", result.contains(queue1));
        assertTrue("Expected queue2 to be routed to", result.contains(queue2));

        result = routeToQueues(mockMessage(false), "", InstanceProperties.EMPTY);

        assertEquals("Expected message to be routed to queue1 only", 1, result.size());
        assertTrue("Expected queue1 to be routed to", result.contains(queue1));
        assertFalse("Expected queue2 not to be routed to", result.contains(queue2));

        _exchange.addBinding("key",queue2, Collections.singletonMap(AMQPFilterTypes.JMS_SELECTOR.toString(),(Object)"select = False"));

        result = routeToQueues(mockMessage(false), "", InstanceProperties.EMPTY);
        assertEquals("Expected message to be routed to both queues", 2, result.size());
        assertTrue("Expected queue1 to be routed to", result.contains(queue1));
        assertTrue("Expected queue2 to be routed to", result.contains(queue2));


    }

    private List<? extends BaseQueue> routeToQueues(final ServerMessage message,
                                                    final String routingAddress,
                                                    final InstanceProperties instanceProperties)
    {
        RoutingResult result = _exchange.route(message, routingAddress, instanceProperties);
        final List<BaseQueue> resultQueues = new ArrayList<>();
        result.send(new ServerTransaction()
        {
            @Override
            public long getTransactionStartTime()
            {
                return 0;
            }

            @Override
            public long getTransactionUpdateTime()
            {
                return 0;
            }

            @Override
            public void addPostTransactionAction(final Action postTransactionAction)
            {

            }

            @Override
            public void dequeue(final MessageEnqueueRecord record, final Action postTransactionAction)
            {

            }

            @Override
            public void dequeue(final Collection<MessageInstance> messages, final Action postTransactionAction)
            {

            }

            @Override
            public void enqueue(final TransactionLogResource queue,
                                final EnqueueableMessage message,
                                final EnqueueAction postTransactionAction)
            {
                resultQueues.add((BaseQueue) queue);
            }

            @Override
            public void enqueue(final Collection<? extends BaseQueue> queues,
                                final EnqueueableMessage message,
                                final EnqueueAction postTransactionAction)
            {
                resultQueues.addAll(queues);
            }

            @Override
            public void commit()
            {

            }

            @Override
            public void commit(final Runnable immediatePostTransactionAction)
            {

            }

            @Override
            public void rollback()
            {

            }

            @Override
            public boolean isTransactional()
            {
                return false;
            }
        }, null);

        return resultQueues;
    }

    private ServerMessage mockMessage(boolean val)
    {
        final AMQMessageHeader header = mock(AMQMessageHeader.class);
        when(header.containsHeader("select")).thenReturn(true);
        when(header.getHeader("select")).thenReturn(val);
        when(header.getHeaderNames()).thenReturn(Collections.singleton("select"));
        when(header.containsHeaders(anySet())).then(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                final Set names = (Set) invocation.getArguments()[0];
                return names.size() == 1 && names.contains("select");

            }
        });
        final ServerMessage serverMessage = mock(ServerMessage.class);
        when(serverMessage.getMessageHeader()).thenReturn(header);
        when(serverMessage.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        return serverMessage;
    }
}
