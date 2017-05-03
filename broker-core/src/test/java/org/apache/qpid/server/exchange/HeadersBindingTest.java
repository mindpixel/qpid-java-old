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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.server.configuration.updater.CurrentThreadTaskExecutor;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.message.AMQMessageHeader;
import org.apache.qpid.server.model.BrokerModel;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.virtualhost.QueueManagingVirtualHost;
import org.apache.qpid.test.utils.QpidTestCase;

/**
 */
public class HeadersBindingTest extends QpidTestCase
{


    private class MockHeader implements AMQMessageHeader
    {

        private final Map<String, Object> _headers = new HashMap<String, Object>();

        public String getCorrelationId()
        {
            return null;
        }

        public long getExpiration()
        {
            return 0;
        }

        public String getUserId()
        {
            return null;
        }

        public String getAppId()
        {
            return null;
        }

        public String getMessageId()
        {
            return null;
        }

        public String getMimeType()
        {
            return null;
        }

        public String getEncoding()
        {
            return null;
        }

        public byte getPriority()
        {
            return 0;
        }

        public long getTimestamp()
        {
            return 0;
        }

        @Override
        public long getNotValidBefore()
        {
            return 0;
        }

        public String getType()
        {
            return null;
        }

        public String getReplyTo()
        {
            return null;
        }

        public Object getHeader(String name)
        {
            return _headers.get(name);
        }

        public boolean containsHeaders(Set<String> names)
        {
            return _headers.keySet().containsAll(names);
        }

        @Override
        public Collection<String> getHeaderNames()
        {
            return _headers.keySet();
        }

        public boolean containsHeader(String name)
        {
            return _headers.containsKey(name);
        }

        public void setString(String key, String value)
        {
            setObject(key,value);
        }

        public void setObject(String key, Object value)
        {
            _headers.put(key,value);
        }
    }

    private Map<String,Object> bindHeaders = new HashMap<String,Object>();
    private MockHeader matchHeaders = new MockHeader();
    private int _count = 0;
    private Queue<?> _queue;
    private Exchange<?> _exchange;

    protected void setUp()
    {
        _count++;
        _queue = mock(Queue.class);
        TaskExecutor executor = new CurrentThreadTaskExecutor();
        executor.start();
        QueueManagingVirtualHost vhost = mock(QueueManagingVirtualHost.class);
        when(_queue.getVirtualHost()).thenReturn(vhost);
        when(_queue.getModel()).thenReturn(BrokerModel.getInstance());
        when(_queue.getTaskExecutor()).thenReturn(executor);
        when(_queue.getChildExecutor()).thenReturn(executor);

        final EventLogger eventLogger = new EventLogger();
        when(vhost.getEventLogger()).thenReturn(eventLogger);
        when(vhost.getTaskExecutor()).thenReturn(executor);
        when(vhost.getChildExecutor()).thenReturn(executor);

        _exchange = mock(Exchange.class);
        when(_exchange.getType()).thenReturn(ExchangeDefaults.HEADERS_EXCHANGE_CLASS);
        when(_exchange.getEventLogger()).thenReturn(eventLogger);
        when(_exchange.getModel()).thenReturn(BrokerModel.getInstance());
        when(_exchange.getTaskExecutor()).thenReturn(executor);
        when(_exchange.getChildExecutor()).thenReturn(executor);

    }

    protected String getQueueName()
    {
        return "Queue" + _count;
    }

    public void testDefault_1()
    {
        bindHeaders.put("A", "Value of A");

        matchHeaders.setString("A", "Value of A");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testDefault_2()
    {
        bindHeaders.put("A", "Value of A");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Value of B");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testDefault_3()
    {
        bindHeaders.put("A", "Value of A");

        matchHeaders.setString("A", "Altered value of A");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertFalse(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAll_1()
    {
        bindHeaders.put("X-match", "all");
        bindHeaders.put("A", "Value of A");

        matchHeaders.setString("A", "Value of A");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAll_2()
    {
        bindHeaders.put("X-match", "all");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertFalse(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAll_3()
    {
        bindHeaders.put("X-match", "all");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Value of B");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAll_4()
    {
        bindHeaders.put("X-match", "all");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Value of B");
        matchHeaders.setString("C", "Value of C");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAll_5()
    {
        bindHeaders.put("X-match", "all");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Altered value of B");
        matchHeaders.setString("C", "Value of C");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertFalse(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAny_1()
    {
        bindHeaders.put("X-match", "any");
        bindHeaders.put("A", "Value of A");

        matchHeaders.setString("A", "Value of A");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAny_2()
    {
        bindHeaders.put("X-match", "any");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAny_3()
    {
        bindHeaders.put("X-match", "any");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Value of B");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAny_4()
    {
        bindHeaders.put("X-match", "any");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Value of B");
        matchHeaders.setString("C", "Value of C");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAny_5()
    {
        bindHeaders.put("X-match", "any");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Value of A");
        matchHeaders.setString("B", "Altered value of B");
        matchHeaders.setString("C", "Value of C");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertTrue(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }

    public void testAny_6()
    {
        bindHeaders.put("X-match", "any");
        bindHeaders.put("A", "Value of A");
        bindHeaders.put("B", "Value of B");

        matchHeaders.setString("A", "Altered value of A");
        matchHeaders.setString("B", "Altered value of B");
        matchHeaders.setString("C", "Value of C");

        AbstractExchange.BindingIdentifier b =
                new AbstractExchange.BindingIdentifier(getQueueName(), _queue);
        assertFalse(new HeadersBinding(b, bindHeaders).matches(matchHeaders));
    }


    public static junit.framework.Test suite()
    {
        return new junit.framework.TestSuite(HeadersBindingTest.class);
    }
}
