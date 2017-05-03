/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 *
 */
package org.apache.qpid.server.exchange;

import static org.apache.qpid.common.AMQPFilterTypes.JMS_SELECTOR;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;

import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.server.message.AMQMessageHeader;
import org.apache.qpid.server.message.EnqueueableMessage;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.MessageReference;
import org.apache.qpid.server.message.RoutingResult;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.model.BrokerTestHelper;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.queue.BaseQueue;
import org.apache.qpid.server.store.MessageEnqueueRecord;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.test.utils.QpidTestCase;

public class TopicExchangeTest extends QpidTestCase
{

    private TopicExchangeImpl _exchange;
    private VirtualHost<?> _vhost;


    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        BrokerTestHelper.setUp();
        _vhost = BrokerTestHelper.createVirtualHost(getName());
        Map<String,Object> attributes = new HashMap<>();
        attributes.put(Exchange.NAME, "test");
        attributes.put(Exchange.DURABLE, false);
        attributes.put(Exchange.TYPE, ExchangeDefaults.TOPIC_EXCHANGE_CLASS);

        _exchange = (TopicExchangeImpl) _vhost.createChild(Exchange.class, attributes);
        _exchange.open();
    }

    @Override
    public void tearDown() throws Exception
    {
        try
        {
            if (_vhost != null)
            {
                _vhost.close();
            }
        }
        finally
        {
            BrokerTestHelper.tearDown();
            super.tearDown();
        }
    }

    private Queue<?> createQueue(String name)
    {
        Map<String,Object> attributes = new HashMap<>();
        attributes.put(Queue.NAME, name);
        return _vhost.createChild(Queue.class, attributes);
    }

    public void testNoRoute() throws Exception
    {
        Queue<?> queue = createQueue("a*#b");
        _exchange.bind(queue.getName(), "a.*.#.b", null, false);

        routeMessage("a.b", 0l);

        Assert.assertEquals(0, queue.getQueueDepthMessages());
    }

    public void testDirectMatch() throws Exception
    {
        Queue<?> queue = createQueue("ab");
        _exchange.bind(queue.getName(), "a.b", null, false);

        routeMessage("a.b",0l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 0l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

        int queueCount = routeMessage("a.c",1l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());
    }


    public void testStarMatch() throws Exception
    {
        Queue<?> queue = createQueue("a*");
        _exchange.bind(queue.getName(), "a.*", null, false);

        routeMessage("a.b",0l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 0l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());


        routeMessage("a.c",1l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 1l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

        int queueCount = routeMessage("a",2l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());
    }

    public void testHashMatch() throws Exception
    {
        Queue<?> queue = createQueue("a#");
        _exchange.bind(queue.getName(), "a.#", null, false);

        routeMessage("a.b.c",0l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 0l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

        routeMessage("a.b",1l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 1l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());


        routeMessage("a.c",2l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 2l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

        routeMessage("a",3l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 3l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());


        int queueCount = routeMessage("b", 4l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());
    }


    public void testMidHash() throws Exception
    {
        Queue<?> queue = createQueue("a");
        _exchange.bind(queue.getName(), "a.*.#.b", null, false);

        routeMessage("a.c.d.b",0l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 0l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

        routeMessage("a.c.b",1l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 1l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }

    public void testMatchAfterHash() throws Exception
    {
        Queue<?> queue = createQueue("a#");
        _exchange.bind(queue.getName(), "a.*.#.b.c", null, false);

        int queueCount = routeMessage("a.c.b.b",0l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());


        routeMessage("a.a.b.c",1l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received",
                            1l,
                            queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

        queueCount = routeMessage("a.b.c.b",2l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());

        routeMessage("a.b.c.b.c",3l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 3l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }


    public void testHashAfterHash() throws Exception
    {
        Queue<?> queue = createQueue("a#");
        _exchange.bind(queue.getName(), "a.*.#.b.c.#.d", null, false);

        int queueCount = routeMessage("a.c.b.b.c",0l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());

        routeMessage("a.a.b.c.d",1l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 1l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }

    public void testHashHash() throws Exception
    {
        Queue<?> queue = createQueue("a#");
        _exchange.bind(queue.getName(), "a.#.*.#.d", null, false);

        int queueCount = routeMessage("a.c.b.b.c",0l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());

        routeMessage("a.a.b.c.d",1l);

        Assert.assertEquals(1, queue.getQueueDepthMessages());

        Assert.assertEquals("Wrong message received", 1l, queue.getMessagesOnTheQueue().get(0).getMessage().getMessageNumber());

        queue.clearQueue();
        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }

    public void testSubMatchFails() throws Exception
    {
        Queue<?> queue = createQueue("a");
        _exchange.bind(queue.getName(), "a.b.c.d", null, false);

        int queueCount = routeMessage("a.b.c",0l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }

    public void testMoreRouting() throws Exception
    {
        Queue<?> queue = createQueue("a");
        _exchange.bind(queue.getName(), "a.b", null, false);

        int queueCount = routeMessage("a.b.c",0l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }

    public void testMoreQueue() throws Exception
    {
        Queue<?> queue = createQueue("a");
        _exchange.bind(queue.getName(), "a.b", null, false);

        int queueCount = routeMessage("a",0l);
        Assert.assertEquals("Message should not route to any queues", 0, queueCount);

        Assert.assertEquals(0, queue.getQueueDepthMessages());

    }

    public void testRouteWithJMSSelector() throws Exception
    {
        Queue<?> queue = createQueue("queue1");
        final String bindingKey = "bindingKey";

        Map<String, Object> bindArgs = Collections.<String, Object>singletonMap(JMS_SELECTOR.toString(), "arg > 5");
        _exchange.bind(queue.getName(), bindingKey, bindArgs, false);

        ServerMessage matchMsg1 = mock(ServerMessage.class);
        when(matchMsg1.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        AMQMessageHeader msgHeader1 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 6));
        when(matchMsg1.getMessageHeader()).thenReturn(msgHeader1);
        routeMessage(matchMsg1, bindingKey, 1);
        Assert.assertEquals("First message should be routed to queue", 1, queue.getQueueDepthMessages());

        ServerMessage nonmatchMsg2 = mock(ServerMessage.class);
        when(nonmatchMsg2.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);

        AMQMessageHeader msgHeader2 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 5));
        when(nonmatchMsg2.getMessageHeader()).thenReturn(msgHeader2);
        routeMessage(nonmatchMsg2, bindingKey, 2);
        Assert.assertEquals("Second message should not be routed to queue", 1, queue.getQueueDepthMessages());

        ServerMessage nonmatchMsg3 = mock(ServerMessage.class);
        when(nonmatchMsg3.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);

        AMQMessageHeader msgHeader3 = createMessageHeader(Collections.<String, Object>emptyMap());
        when(nonmatchMsg3.getMessageHeader()).thenReturn(msgHeader3);
        routeMessage(nonmatchMsg3, bindingKey, 3);
        Assert.assertEquals("Third message should not be routed to queue", 1, queue.getQueueDepthMessages());

        ServerMessage matchMsg4 = mock(ServerMessage.class);
        when(matchMsg4.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);

        AMQMessageHeader msgHeader4 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 7));
        when(matchMsg4.getMessageHeader()).thenReturn(msgHeader4);
        routeMessage(matchMsg4, bindingKey, 4);
        Assert.assertEquals("First message should be routed to queue", 2, queue.getQueueDepthMessages());

    }

    public void testUpdateBindingReplacingSelector() throws Exception
    {
        Queue<?> queue = createQueue("queue1");
        final String bindingKey = "a";

        Map<String, Object> originalArgs = Collections.<String, Object>singletonMap(JMS_SELECTOR.toString(), "arg > 5");
        _exchange.bind(queue.getName(), bindingKey, originalArgs, false);

        AMQMessageHeader mgsHeader1 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 6));
        ServerMessage msg1 = mock(ServerMessage.class);
        when(msg1.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        when(msg1.getMessageHeader()).thenReturn(mgsHeader1);

        routeMessage(msg1, bindingKey, 1);
        Assert.assertEquals(1, queue.getQueueDepthMessages());

        // Update the binding
        Map<String, Object> newArgs = Collections.<String, Object>singletonMap(JMS_SELECTOR.toString(), "arg > 6");
        _exchange.replaceBinding(bindingKey, queue, newArgs);

        // Message that would have matched the original selector but not the new
        AMQMessageHeader mgsHeader2 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 6));
        ServerMessage msg2 = mock(ServerMessage.class);
        when(msg2.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        when(msg2.getMessageHeader()).thenReturn(mgsHeader2);

        routeMessage(msg2, bindingKey, 2);
        Assert.assertEquals(1, queue.getQueueDepthMessages());

        // Message that matches only the second
        AMQMessageHeader mgsHeader3 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 7));
        ServerMessage msg3 = mock(ServerMessage.class);
        when(msg3.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        when(msg3.getMessageHeader()).thenReturn(mgsHeader3);

        routeMessage(msg3, bindingKey, 2);
        Assert.assertEquals(2, queue.getQueueDepthMessages());

    }

    // This demonstrates QPID-5785.  Deleting the exchange after this combination of binding
    // updates generated a NPE
    public void testUpdateBindingAddingSelector() throws Exception
    {
        Queue<?> queue = createQueue("queue1");
        final String bindingKey = "a";

        _exchange.bind(queue.getName(), bindingKey, null, false);

        ServerMessage msg1 = mock(ServerMessage.class);
        when(msg1.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        routeMessage(msg1, bindingKey, 1);
        Assert.assertEquals(1, queue.getQueueDepthMessages());

        // Update the binding adding selector
        Map<String, Object> newArgs = Collections.<String, Object>singletonMap(JMS_SELECTOR.toString(), "arg > 6");
        _exchange.replaceBinding(bindingKey, queue, newArgs);

        // Message that does not match the new selector
        AMQMessageHeader mgsHeader2 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 6));
        ServerMessage msg2 = mock(ServerMessage.class);
        when(msg2.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        when(msg2.getMessageHeader()).thenReturn(mgsHeader2);

        routeMessage(msg2, bindingKey, 2);
        Assert.assertEquals(1, queue.getQueueDepthMessages());

        // Message that matches the selector
        AMQMessageHeader mgsHeader3 = createMessageHeader(Collections.<String, Object>singletonMap("arg", 7));
        ServerMessage msg3 = mock(ServerMessage.class);
        when(msg3.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);

        when(msg3.getMessageHeader()).thenReturn(mgsHeader3);

        routeMessage(msg3, bindingKey, 2);
        Assert.assertEquals(2, queue.getQueueDepthMessages());

        _exchange.delete();
    }

    private int routeMessage(String routingKey, long messageNumber)
    {
        ServerMessage message = mock(ServerMessage.class);
        when(message.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        return routeMessage(message, routingKey, messageNumber);
    }

    private int routeMessage(ServerMessage message, String routingKey, long messageNumber)
    {
        when(message.getInitialRoutingAddress()).thenReturn(routingKey);
        List<? extends BaseQueue> queues = routeToQueues(message, routingKey, InstanceProperties.EMPTY);
        MessageReference ref = mock(MessageReference.class);
        when(ref.getMessage()).thenReturn(message);
        when(message.newReference()).thenReturn(ref);
        when(message.newReference(any(TransactionLogResource.class))).thenReturn(ref);
        when(message.getMessageNumber()).thenReturn(messageNumber);
        for(BaseQueue q : queues)
        {
            q.enqueue(message, null, null);
        }

        return queues.size();
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

    private AMQMessageHeader createMessageHeader(Map<String, Object> headers)
    {
        AMQMessageHeader messageHeader = mock(AMQMessageHeader.class);
        for(Map.Entry<String, Object> entry : headers.entrySet())
        {
            String key = entry.getKey();
            Object value = entry.getValue();

            when(messageHeader.containsHeader(key)).thenReturn(true);
            when(messageHeader.getHeader(key)).thenReturn(value);
        }
        return messageHeader;
    }


}
