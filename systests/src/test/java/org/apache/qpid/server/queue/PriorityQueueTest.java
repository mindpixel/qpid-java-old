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
package org.apache.qpid.server.queue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.naming.NamingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.QpidException;
import org.apache.qpid.client.AMQDestination;
import org.apache.qpid.client.AMQSession;
import org.apache.qpid.server.model.LifetimePolicy;
import org.apache.qpid.test.utils.QpidBrokerTestCase;

public class PriorityQueueTest extends QpidBrokerTestCase
{
    private static final int TIMEOUT = 1500;

    protected final String QUEUE = "PriorityQueue";

    private static final int MSG_COUNT = 50;

    private Connection producerConnection;
    private MessageProducer producer;
    private Session producerSession;
    private Queue queue;
    private Connection consumerConnection;
    private Session consumerSession;

    private MessageConsumer consumer;

    protected void setUp() throws Exception
    {
        super.setUp();

        producerConnection = getConnection();
        producerSession = producerConnection.createSession(true, Session.AUTO_ACKNOWLEDGE);

        producerConnection.start();

        consumerConnection = getConnection();
        consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);

    }

    protected void tearDown() throws Exception
    {
        producerConnection.close();
        consumerConnection.close();
        super.tearDown();
    }

    public void testPriority() throws JMSException, NamingException, QpidException
    {
        final int priorities = 10;
        createPriorityQueue(priorities);

        for (int msg = 0; msg < MSG_COUNT; msg++)
        {
            producer.setPriority(msg % 10);
            producer.send(nextMessage(msg, false, producerSession, producer));
        }
        producerSession.commit();
        producer.close();
        producerSession.close();
        producerConnection.close();

        consumer = consumerSession.createConsumer(queue);
        consumerConnection.start();
        Message received;
        int receivedCount = 0;
        Message previous = null;
        int messageCount = 0;
        while((received = consumer.receive(1000))!=null)
        {
            messageCount++;
            if(previous != null)
            {
                assertTrue("Messages arrived in unexpected order " + messageCount + " " + previous.getIntProperty("msg") + " " + received.getIntProperty("msg") + " " + previous.getJMSPriority() + " " + received.getJMSPriority(), (previous.getJMSPriority() > received.getJMSPriority()) || ((previous.getJMSPriority() == received.getJMSPriority()) && previous.getIntProperty("msg") < received.getIntProperty("msg")) );
            }

            previous = received;
            receivedCount++;
        }

        assertEquals("Incorrect number of message received", 50, receivedCount);
    }

    private void createPriorityQueue(final int priorities) throws QpidException, JMSException
    {
        if(isBroker10())
        {
            final Map<String, Object> attributes = new HashMap<>();
            attributes.put(PriorityQueue.PRIORITIES, priorities);
            attributes.put(PriorityQueue.DURABLE, false);
            attributes.put(PriorityQueue.LIFETIME_POLICY, LifetimePolicy.DELETE_ON_NO_OUTBOUND_LINKS.toString());
            createEntityUsingAmqpManagement(getTestQueueName(), producerSession, "org.apache.qpid.PriorityQueue", attributes);
            queue = producerSession.createQueue(getTestQueueName());
        }
        else
        {

            final Map<String, Object> arguments = new HashMap<String, Object>();
            arguments.put("x-qpid-priorities", priorities);
            ((AMQSession) producerSession).createQueue(getTestQueueName(), true, false, false, arguments);
            queue = (Queue) producerSession.createQueue("direct://amq.direct/"
                                                        + getTestQueueName()
                                                        + "/"
                                                        + getTestQueueName()
                                                        + "?durable='false'&autodelete='true'");

            ((AMQSession) producerSession).declareAndBind((AMQDestination) queue);
        }
        producer = producerSession.createProducer(queue);
    }

    public void testOddOrdering() throws QpidException, JMSException
    {
        createPriorityQueue(3);

        // In order ABC
        producer.setPriority(9);
        producer.send(nextMessage(1, false, producerSession, producer));
        producer.setPriority(4);
        producer.send(nextMessage(2, false, producerSession, producer));
        producer.setPriority(1);
        producer.send(nextMessage(3, false, producerSession, producer));

        // Out of order BAC
        producer.setPriority(4);
        producer.send(nextMessage(4, false, producerSession, producer));
        producer.setPriority(9);
        producer.send(nextMessage(5, false, producerSession, producer));
        producer.setPriority(1);
        producer.send(nextMessage(6, false, producerSession, producer));

        // Out of order BCA
        producer.setPriority(4);
        producer.send(nextMessage(7, false, producerSession, producer));
        producer.setPriority(1);
        producer.send(nextMessage(8, false, producerSession, producer));
        producer.setPriority(9);
        producer.send(nextMessage(9, false, producerSession, producer));

        // Reverse order CBA
        producer.setPriority(1);
        producer.send(nextMessage(10, false, producerSession, producer));
        producer.setPriority(4);
        producer.send(nextMessage(11, false, producerSession, producer));
        producer.setPriority(9);
        producer.send(nextMessage(12, false, producerSession, producer));
        producerSession.commit();

        consumer = consumerSession.createConsumer(queue);
        consumerConnection.start();

        Message msg = consumer.receive(TIMEOUT);
        assertEquals(1, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(5, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(9, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(12, msg.getIntProperty("msg"));

        msg = consumer.receive(TIMEOUT);
        assertEquals(2, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(4, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(7, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(11, msg.getIntProperty("msg"));

        msg = consumer.receive(TIMEOUT);
        assertEquals(3, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(6, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(8, msg.getIntProperty("msg"));
        msg = consumer.receive(TIMEOUT);
        assertEquals(10, msg.getIntProperty("msg"));
    }

    private Message nextMessage(int msg, boolean first, Session producerSession, MessageProducer producer) throws JMSException
    {
        Message send = producerSession.createTextMessage("Message: " + msg);
        send.setIntProperty("msg", msg);

        return send;
    }

    /**
     * Test that after sending an initial  message with priority 0, it is able to be repeatedly reflected back to the queue using
     * default priority and then consumed again, with separate transacted sessions with prefetch 1 for producer and consumer.
     *
     * Highlighted defect with PriorityQueues resolved in QPID-3927.
     */
    public void testMessageReflectionWithPriorityIncreaseOnTransactedSessionsWithPrefetch1() throws Exception
    {
        Connection conn = getConnectionWithPrefetch(1);
        conn.start();
        final Session producerSess = conn.createSession(true, Session.SESSION_TRANSACTED);
        final Session consumerSess = conn.createSession(true, Session.SESSION_TRANSACTED);

        createPriorityQueue(10);

        //create the consumer, producer, add message listener
        CountDownLatch latch = new CountDownLatch(5);
        MessageConsumer cons = producerSess.createConsumer(queue);
        MessageProducer producer = producerSess.createProducer(queue);

        ReflectingMessageListener listener = new ReflectingMessageListener(producerSess,producer,consumerSess,latch);
        cons.setMessageListener(listener);

        //Send low priority 0 message to kick start the asynchronous reflection process
        producer.setPriority(0);
        producer.send(nextMessage(1, true, producerSess, producer));
        producerSess.commit();

        //wait for the reflection process to complete
        assertTrue("Test process failed to complete in allowed time", latch.await(10, TimeUnit.SECONDS));
        assertNull("Unexpected throwable encountered", listener.getThrown());
    }

    private static class ReflectingMessageListener implements MessageListener
    {
        private static final Logger _logger = LoggerFactory.getLogger(PriorityQueueTest.ReflectingMessageListener.class);

        private Session _prodSess;
        private Session _consSess;
        private CountDownLatch _latch;
        private MessageProducer _prod;
        private long _origCount;
        private Throwable _lastThrown;

        public ReflectingMessageListener(final Session prodSess, final MessageProducer prod,
                final Session consSess, final CountDownLatch latch)
        {
            _latch = latch;
            _origCount = _latch.getCount();
            _prodSess = prodSess;
            _consSess = consSess;
            _prod = prod;
        }

        @Override
        public void onMessage(final Message message)
        {
            try
            {
                _latch.countDown();
                long msgNum = _origCount - _latch.getCount();
                _logger.info("Received message " + msgNum + " with ID: " + message.getIntProperty("msg"));

                if(_latch.getCount() > 0)
                {
                    //reflect the message, updating its ID and using default priority
                    message.clearProperties();
                    message.setIntProperty("msg", (int) msgNum + 1);
                    _prod.setPriority(Message.DEFAULT_PRIORITY);
                    _prod.send(message);
                    _prodSess.commit();
                }

                //commit the consumer session to consume the message
                _consSess.commit();
            }
            catch(Throwable t)
            {
                _logger.error(t.getMessage(), t);
                _lastThrown = t;
            }
        }

        public Throwable getThrown()
        {
            return _lastThrown;
        }
    }
}
