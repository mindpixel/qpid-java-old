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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.qpid.server.model.ConfiguredObjectFactoryImpl;
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

public class HeadersExchangeTest extends QpidTestCase
{
    private HeadersExchangeImpl _exchange;
    private QueueManagingVirtualHost _virtualHost;
    private TaskExecutor _taskExecutor;
    private ConfiguredObjectFactoryImpl _factory;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();

        _taskExecutor = new CurrentThreadTaskExecutor();
        _taskExecutor.start();
        _virtualHost = mock(QueueManagingVirtualHost.class);

        Broker broker = mock(Broker.class);
        when(broker.getCategoryClass()).thenReturn(Broker.class);
        when(broker.getModel()).thenReturn(BrokerModel.getInstance());

        VirtualHostNode virtualHostNode = mock(VirtualHostNode.class);
        when(virtualHostNode.getCategoryClass()).thenReturn(VirtualHostNode.class);
        when(virtualHostNode.getParent()).thenReturn(broker);
        when(virtualHostNode.getModel()).thenReturn(BrokerModel.getInstance());

        when(_virtualHost.getEventLogger()).thenReturn(new EventLogger());
        when(_virtualHost.getCategoryClass()).thenReturn(VirtualHost.class);
        when(_virtualHost.getTaskExecutor()).thenReturn(_taskExecutor);
        when(_virtualHost.getChildExecutor()).thenReturn(_taskExecutor);
        when(_virtualHost.getState()).thenReturn(State.ACTIVE);

        _factory = new ConfiguredObjectFactoryImpl(BrokerModel.getInstance());
        when(_virtualHost.getObjectFactory()).thenReturn(_factory);
        when(_virtualHost.getModel()).thenReturn(_factory.getModel());
        when(_virtualHost.getParent()).thenReturn(virtualHostNode);
        Map<String,Object> attributes = new HashMap<String, Object>();
        attributes.put(Exchange.ID, UUID.randomUUID());
        attributes.put(Exchange.NAME, "test");
        attributes.put(Exchange.DURABLE, false);

        _exchange = new HeadersExchangeImpl(attributes, _virtualHost);

    }

    public void tearDown() throws Exception
    {
        super.tearDown();
        _taskExecutor.stop();
    }

    protected void routeAndTest(ServerMessage msg, Queue<?>... expected) throws Exception
    {
        List<? extends BaseQueue> results = routeToQueues(msg, "", InstanceProperties.EMPTY);
        List<? extends BaseQueue> unexpected = new ArrayList<BaseQueue>(results);
        unexpected.removeAll(Arrays.asList(expected));
        assertTrue("Message delivered to unexpected queues: " + unexpected, unexpected.isEmpty());
        List<? extends BaseQueue> missing = new ArrayList<BaseQueue>(Arrays.asList(expected));
        missing.removeAll(results);
        assertTrue("Message not delivered to expected queues: " + missing, missing.isEmpty());
        assertTrue("Duplicates " + results, results.size()==(new HashSet<BaseQueue>(results)).size());
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


    private Queue<?> createAndBind(final String name, String... arguments)
            throws Exception
    {
        return createAndBind(name, getArgsMapFromStrings(arguments));
    }

    private Map<String, Object> getArgsMapFromStrings(String... arguments)
    {
        Map<String, Object> map = new HashMap<String,Object>();

        for(String arg : arguments)
        {
            if(arg.contains("="))
            {
                String[] keyValue = arg.split("=",2);
                map.put(keyValue[0],keyValue[1]);
            }
            else
            {
                map.put(arg,null);
            }
        }
        return map;
    }

    private Queue<?> createAndBind(final String name, Map<String, Object> arguments)
            throws Exception
    {
        Queue<?> q = create(name);
        bind(name, arguments, q);
        return q;
    }

    private void bind(String bindingKey, Map<String, Object> arguments, Queue<?> q)
    {
        _exchange.addBinding(bindingKey,q,arguments);
    }

    private Queue<?> create(String name)
    {
        Queue q = mock(Queue.class);
        when(q.getName()).thenReturn(name);
        when(q.toString()).thenReturn(name);
        when(q.getVirtualHost()).thenReturn(_virtualHost);
        when(q.getParent()).thenReturn(_virtualHost);
        when(q.getCategoryClass()).thenReturn(Queue.class);
        when(q.getObjectFactory()).thenReturn(_factory);
        when(q.getModel()).thenReturn(_factory.getModel());
        TaskExecutor taskExecutor = CurrentThreadTaskExecutor.newStartedInstance();
        when(q.getTaskExecutor()).thenReturn(taskExecutor);
        when(q.getChildExecutor()).thenReturn(taskExecutor);
        when(_virtualHost.getAttainedQueue(name)).thenReturn(q);
        final RoutingResult routingResult = new RoutingResult(null);
        routingResult.addQueue(q);
        when(q.route(any(ServerMessage.class), anyString(), any(InstanceProperties.class))).thenReturn(routingResult);
        return q;
    }


    public void testSimple() throws Exception
    {
        Queue<?> q1 = createAndBind("Q1", "F0000");
        Queue<?> q2 = createAndBind("Q2", "F0000=Aardvark");
        Queue<?> q3 = createAndBind("Q3", "F0001");
        Queue<?> q4 = createAndBind("Q4", "F0001=Bear");
        Queue<?> q5 = createAndBind("Q5", "F0000", "F0001");
        Queue<?> q6 = createAndBind("Q6", "F0000=Aardvark", "F0001=Bear");
        Queue<?> q7 = createAndBind("Q7", "F0000", "F0001=Bear");
        Queue<?> q8 = createAndBind("Q8", "F0000=Aardvark", "F0001");

        routeAndTest(mockMessage(getArgsMapFromStrings("F0000")), q1);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark")), q1, q2);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark", "F0001")), q1, q2, q3, q5, q8);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000", "F0001=Bear")), q1, q3, q4, q5, q7);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark", "F0001=Bear")),
                q1, q2, q3, q4, q5, q6, q7, q8);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0002")));

    }

    public void testAny() throws Exception
    {
        Queue<?> q1 = createAndBind("Q1", "F0000", "F0001", "X-match=any");
        Queue<?> q2 = createAndBind("Q2", "F0000=Aardvark", "F0001=Bear", "X-match=any");
        Queue<?> q3 = createAndBind("Q3", "F0000", "F0001=Bear", "X-match=any");
        Queue<?> q4 = createAndBind("Q4", "F0000=Aardvark", "F0001", "X-match=any");
        Queue<?> q5 = createAndBind("Q5", "F0000=Apple", "F0001", "X-match=any");

        routeAndTest(mockMessage(getArgsMapFromStrings("F0000")), q1, q3);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark")), q1, q2, q3, q4);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark", "F0001")), q1, q2, q3, q4, q5);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000", "F0001=Bear")), q1, q2, q3, q4, q5);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark", "F0001=Bear")), q1, q2, q3, q4, q5);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0002")));
    }

    public void testOnUnbind() throws Exception
    {
        Queue<?> q1 = createAndBind("Q1", "F0000");
        Queue<?> q2 = createAndBind("Q2", "F0000=Aardvark");
        Queue<?> q3 = createAndBind("Q3", "F0001");

        routeAndTest(mockMessage(getArgsMapFromStrings("F0000")), q1);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark")), q1, q2);
        routeAndTest(mockMessage(getArgsMapFromStrings("F0001")), q3);

        _exchange.deleteBinding("Q1",q1);

        routeAndTest(mockMessage(getArgsMapFromStrings("F0000")));
        routeAndTest(mockMessage(getArgsMapFromStrings("F0000=Aardvark")), q2);
    }


    public void testWithSelectors() throws Exception
    {
        Queue<?> q1 = create("Q1");
        Queue<?> q2 = create("Q2");
        bind("q1",getArgsMapFromStrings("F"), q1);
        bind("q1select",getArgsMapFromStrings("F", AMQPFilterTypes.JMS_SELECTOR.toString()+"=F='1'"), q1);
        bind("q2",getArgsMapFromStrings("F=1"), q2);

        routeAndTest(mockMessage(getArgsMapFromStrings("F")),q1);

        routeAndTest(mockMessage(getArgsMapFromStrings("F=1")),q1,q2);


        Queue<?> q3 = create("Q3");
        bind("q3select",getArgsMapFromStrings("F", AMQPFilterTypes.JMS_SELECTOR.toString()+"=F='1'"), q3);
        routeAndTest(mockMessage(getArgsMapFromStrings("F=1")),q1,q2,q3);
        routeAndTest(mockMessage(getArgsMapFromStrings("F=2")),q1);
        bind("q3select2",getArgsMapFromStrings("F", AMQPFilterTypes.JMS_SELECTOR.toString()+"=F='2'"), q3);

        routeAndTest(mockMessage(getArgsMapFromStrings("F=2")),q1,q3);

    }

    private ServerMessage mockMessage(final Map<String, Object> headerValues)
    {
        final AMQMessageHeader header = mock(AMQMessageHeader.class);
        when(header.containsHeader(anyString())).then(new Answer<Boolean>()
        {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable
            {
                return headerValues.containsKey((String) invocation.getArguments()[0]);
            }
        });
        when(header.getHeader(anyString())).then(new Answer<Object>()
        {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable
            {
                return headerValues.get((String) invocation.getArguments()[0]);
            }
        });
        when(header.getHeaderNames()).thenReturn(headerValues.keySet());
        when(header.containsHeaders(anySet())).then(new Answer<Boolean>()
        {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable
            {
                final Set names = (Set) invocation.getArguments()[0];
                return headerValues.keySet().containsAll(names);

            }
        });
        final ServerMessage serverMessage = mock(ServerMessage.class);
        when(serverMessage.getMessageHeader()).thenReturn(header);
        when(serverMessage.isResourceAcceptable(any(TransactionLogResource.class))).thenReturn(true);
        return serverMessage;
    }

    public static junit.framework.Test suite()
    {
        return new junit.framework.TestSuite(HeadersExchangeTest.class);
    }
}
