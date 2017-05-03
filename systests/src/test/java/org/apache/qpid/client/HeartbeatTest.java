/*
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
 */
package org.apache.qpid.client;

import static org.apache.qpid.configuration.ClientProperties.QPID_HEARTBEAT_INTERVAL;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.model.Broker;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TCPTunneler;

public class HeartbeatTest extends QpidBrokerTestCase
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HeartbeatTest.class);

    private static final String CONNECTION_URL_WITH_HEARTBEAT = "amqp://guest:guest@clientid/?brokerlist='localhost:%d?heartbeat='%d''";
    private static final int MAXIMUM_WAIT_TIME = 2900;
    private TestListener _listener = new TestListener("listener", 2, 2);

    @Override
    public void setUp() throws Exception
    {
        if (getName().equals("testHeartbeatsEnabledBrokerSide"))
        {
            getDefaultBrokerConfiguration().setBrokerAttribute(Broker.CONNECTION_HEART_BEAT_DELAY, "1");
        }
        super.setUp();
    }

    public void testHeartbeatsEnabledUsingUrl() throws Exception
    {
        final String url = String.format(CONNECTION_URL_WITH_HEARTBEAT, getDefaultBroker().getAmqpPort(), 1);
        AMQConnection conn = (AMQConnection) getConnection(new AMQConnectionURL(url));
        conn.setHeartbeatListener(_listener);
        conn.start();

        _listener.awaitExpectedHeartbeats(MAXIMUM_WAIT_TIME);

        assertTrue("Too few heartbeats received: "+_listener.getHeartbeatsReceived() +" (expected at least 2)", _listener.getHeartbeatsReceived() >=2);
        assertTrue("Too few heartbeats sent "+_listener.getHeartbeatsSent() +" (expected at least 2)", _listener.getHeartbeatsSent() >=2);

        conn.close();
    }

    public void testHeartbeatsEnabledUsingSystemProperty() throws Exception
    {
        setTestSystemProperty(QPID_HEARTBEAT_INTERVAL, "1");
        AMQConnection conn = (AMQConnection) getConnection();
        conn.setHeartbeatListener(_listener);
        conn.start();

        _listener.awaitExpectedHeartbeats(MAXIMUM_WAIT_TIME);

        assertTrue("Too few heartbeats received: "+_listener.getHeartbeatsReceived() +" (expected at least 2)", _listener.getHeartbeatsReceived() >=2);
        assertTrue("Too few heartbeats sent "+_listener.getHeartbeatsSent() +" (expected at least 2)", _listener.getHeartbeatsSent() >=2);

        conn.close();
    }

    public void testHeartbeatsDisabledUsingSystemProperty() throws Exception
    {
         setTestSystemProperty(QPID_HEARTBEAT_INTERVAL, "0");
         AMQConnection conn = (AMQConnection) getConnection();
         conn.setHeartbeatListener(_listener);
         conn.start();

        _listener.awaitExpectedHeartbeats(2000);

         assertEquals("Heartbeats unexpectedly received", 0, _listener.getHeartbeatsReceived());
         assertEquals("Heartbeats unexpectedly sent ", 0, _listener.getHeartbeatsSent());

         conn.close();
    }

    /**
     * This test carefully arranges message flow so that bytes flow only from producer to broker
     * on the producer side and broker to consumer on the consumer side, deliberately leaving the
     * reverse path quiet so heartbeats will flow.
     */
    public void testUnidirectionalHeartbeating() throws Exception
    {
        setTestSystemProperty(QPID_HEARTBEAT_INTERVAL,"1");
        AMQConnection receiveConn = (AMQConnection) getConnection();
        AMQConnection sendConn = (AMQConnection) getConnection();
        Destination destination = getTestQueue();
        TestListener receiveListener = new TestListener("receiverListener", 2, 0);
        TestListener sendListener = new TestListener("senderListener", 0, 2);


        Session receiveSession = receiveConn.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Session senderSession = sendConn.createSession(false, Session.CLIENT_ACKNOWLEDGE);

        MessageConsumer consumer = receiveSession.createConsumer(destination);
        MessageProducer producer = senderSession.createProducer(destination);

        receiveConn.setHeartbeatListener(receiveListener);
        sendConn.setHeartbeatListener(sendListener);
        receiveConn.start();

        // Start the flow of messages to the consumer
        consumer.receiveNoWait();

        for(int i = 0; i < 5; i++)
        {
            producer.send(senderSession.createTextMessage("Msg " + i));
            Thread.sleep(500);
            assertNotNull("Expected to receive message within " + RECEIVE_TIMEOUT + "ms.", consumer.receive(RECEIVE_TIMEOUT));
            // Consumer does not ack the message in order that no bytes flow from consumer connection back to Broker
        }

        assertTrue("Too few heartbeats sent "+ receiveListener.getHeartbeatsSent() +" (expected at least 2)", receiveListener.getHeartbeatsSent()>=2);
        assertEquals("Unexpected number of heartbeats sent by the sender: ",0,sendListener.getHeartbeatsSent());

        assertTrue("Too few heartbeats received at the sender "+ sendListener.getHeartbeatsReceived() +" (expected at least 2)", sendListener.getHeartbeatsReceived()>=2);
        assertEquals("Unexpected number of heartbeats received by the receiver: ",0,receiveListener.getHeartbeatsReceived());

        receiveConn.close();
        sendConn.close();
    }

    public void testHeartbeatsEnabledBrokerSide() throws Exception
    {

        AMQConnection conn = (AMQConnection) getConnection();
        conn.setHeartbeatListener(_listener);
        conn.start();

        _listener.awaitExpectedHeartbeats(MAXIMUM_WAIT_TIME);

        assertTrue("Too few heartbeats received: "+_listener.getHeartbeatsReceived() +" (expected at least 2)", _listener.getHeartbeatsReceived()>=2);
        assertTrue("Too few heartbeats sent "+ _listener.getHeartbeatsSent() +" (expected at least 2)", _listener.getHeartbeatsSent() >=2);

        conn.close();
    }


    @SuppressWarnings("deprecation")
    public void testHeartbeatsEnabledUsingAmqjLegacySystemProperty() throws Exception
    {
        setTestSystemProperty("amqj.heartbeat.delay", "1");
        AMQConnection conn = (AMQConnection) getConnection();
        conn.setHeartbeatListener(_listener);
        conn.start();

        _listener.awaitExpectedHeartbeats(MAXIMUM_WAIT_TIME);

        assertTrue("Too few heartbeats received: "+_listener.getHeartbeatsReceived()+" (expected at least 2)", _listener.getHeartbeatsReceived()>=2);
        assertTrue("Too few heartbeats sent "+_listener.getHeartbeatsSent() +" (expected at least 2)", _listener.getHeartbeatsSent()>=2);

        conn.close();
    }

    @SuppressWarnings("deprecation")
    public void testHeartbeatsEnabledUsingOlderLegacySystemProperty() throws Exception
    {
        setTestSystemProperty("idle_timeout", "1000");
        AMQConnection conn = (AMQConnection) getConnection();
        conn.setHeartbeatListener(_listener);
        conn.start();

        _listener.awaitExpectedHeartbeats(MAXIMUM_WAIT_TIME);

        assertTrue("Too few heartbeats received: "+_listener.getHeartbeatsReceived() +" (expected at least 2)", _listener.getHeartbeatsReceived()>=2);
        assertTrue("Too few heartbeats sent "+_listener.getHeartbeatsSent() +" (expected at least 2)", _listener.getHeartbeatsSent()>=2);

        conn.close();
    }

    public void testClientStopsSendingHeartbeats_BrokerClosesConnection() throws Exception
    {
        try(TCPTunneler tcpTunneler = new TCPTunneler(getFailingPort(), "localhost", getDefaultBroker().getAmqpPort(), 1))
        {
            tcpTunneler.start();

            final AtomicReference<InetSocketAddress> clientAddressRef = new AtomicReference<>();
            tcpTunneler.addClientListener(new TCPTunneler.TunnelListener()
            {
                @Override
                public void clientConnected(final InetSocketAddress clientAddress)
                {
                    clientAddressRef.set(clientAddress);
                }

                @Override
                public void clientDisconnected(final InetSocketAddress clientAddress)
                {
                }
            });

            final CountDownLatch exceptionLatch = new CountDownLatch(1);
            final String url = String.format(CONNECTION_URL_WITH_HEARTBEAT,  tcpTunneler.getLocalPort(), 1);
            AMQConnection conn = (AMQConnection) getConnection(new AMQConnectionURL(url));
            conn.setHeartbeatListener(_listener);
            conn.setExceptionListener(new ExceptionListener()
            {
                @Override
                public void onException(final JMSException exception)
                {
                    LOGGER.debug("Exception listener got exception", exception);
                    exceptionLatch.countDown();
                }
            });
            conn.start();

            assertNotNull(clientAddressRef.get());

            _listener.awaitExpectedHeartbeats(MAXIMUM_WAIT_TIME);

            assertTrue("Too few heartbeats received: "+_listener.getHeartbeatsReceived() +" (expected at least 2)", _listener.getHeartbeatsReceived() >=2);
            assertTrue("Too few heartbeats sent "+_listener.getHeartbeatsSent() +" (expected at least 2)", _listener.getHeartbeatsSent() >=2);

            tcpTunneler.stopClientToServerForwarding(clientAddressRef.get());

            exceptionLatch.await(5, TimeUnit.SECONDS);
            assertTrue("Connection should be disconnected within timeout", conn.isConnected());
        }
    }

    private class TestListener implements HeartbeatListener
    {
        private final String _name;
        private final AtomicInteger _heartbeatsReceived = new AtomicInteger(0);
        private final AtomicInteger _heartbeatsSent = new AtomicInteger(0);
        private final CountDownLatch _expectedReceivedHeartbeats;
        private final CountDownLatch _expectedSentHeartbeats;

        public TestListener(String name, int expectedSentHeartbeats, int expectedReceivedHeartbeats)
        {
            _name = name;
            _expectedReceivedHeartbeats = new CountDownLatch(expectedReceivedHeartbeats);
            _expectedSentHeartbeats = new CountDownLatch(expectedSentHeartbeats);
        }

        @Override
        public void heartbeatReceived()
        {
            LOGGER.debug(_name + " heartbeat received");
            _heartbeatsReceived.incrementAndGet();
            _expectedReceivedHeartbeats.countDown();
        }

        public int getHeartbeatsReceived()
        {
            return _heartbeatsReceived.get();
        }

        @Override
        public void heartbeatSent()
        {
            LOGGER.debug(_name + " heartbeat sent");
            _heartbeatsSent.incrementAndGet();
            _expectedSentHeartbeats.countDown();
        }

        public int getHeartbeatsSent()
        {
            return _heartbeatsSent.get();
        }

        public void awaitExpectedHeartbeats(final long maximumWaitTime) throws InterruptedException
        {
            long startTime = System.currentTimeMillis();
            _expectedSentHeartbeats.await(maximumWaitTime, TimeUnit.MILLISECONDS);

            long remainingTime = maximumWaitTime - (System.currentTimeMillis() - startTime);
            if (remainingTime > 0)
            {
                _expectedReceivedHeartbeats.await(remainingTime, TimeUnit.MILLISECONDS);
            }
        }
    }
}
