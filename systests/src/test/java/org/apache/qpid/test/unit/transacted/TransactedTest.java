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
package org.apache.qpid.test.unit.transacted;


import javax.jms.Connection;
import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.test.utils.QpidBrokerTestCase;

public class TransactedTest extends QpidBrokerTestCase
{
    private Queue queue1;

    private Connection con;
    private Session session;
    private MessageConsumer consumer1;
    private MessageProducer producer2;

    private Connection prepCon;
    private Session prepSession;
    private MessageProducer prepProducer1;

    private Connection testCon;
    private Session testSession;
    private MessageConsumer testConsumer1;
    private MessageConsumer testConsumer2;
    private static final Logger _logger = LoggerFactory.getLogger(TransactedTest.class);

    protected void setUp() throws Exception
    {
        try
        {
            super.setUp();
            _logger.info("Create Connection");
            con = getConnection("guest", "guest");
            _logger.info("Create Session");
            session = con.createSession(true, Session.SESSION_TRANSACTED);
            _logger.info("Create Q1");
            queue1 = createTestQueue(session, "Q1");
            _logger.info("Create Q2");
            Queue queue2 = createTestQueue(session, "Q2");
            session.commit();

            _logger.info("Create Consumer of Q1");
            consumer1 = session.createConsumer(queue1);
            // Dummy just to create the queue.
            _logger.info("Create Consumer of Q2");
            MessageConsumer consumer2 = session.createConsumer(queue2);
            _logger.info("Close Consumer of Q2");
            consumer2.close();

            _logger.info("Create producer to Q2");
            producer2 = session.createProducer(queue2);

            _logger.info("Start Connection");
            con.start();

            _logger.info("Create prep connection");
            prepCon = getConnection("guest", "guest");

            _logger.info("Create prep session");
            prepSession = prepCon.createSession(false, Session.AUTO_ACKNOWLEDGE);

            _logger.info("Create prep producer to Q1");
            prepProducer1 = prepSession.createProducer(queue1);

            _logger.info("Create prep connection start");
            prepCon.start();

            _logger.info("Create test connection");
            testCon = getConnection("guest", "guest");
            _logger.info("Create test session");
            testSession = testCon.createSession(false, Session.AUTO_ACKNOWLEDGE);
            _logger.info("Create test consumer of q2");
            testConsumer2 = testSession.createConsumer(queue2);
        }
        catch (Exception e)
        {
            _logger.error("setup error",e);
            startDefaultBroker();
            throw e;
        }
    }

    protected void tearDown() throws Exception
    {
        try
        {
            _logger.info("Close connection");
            con.close();
            _logger.info("Close test connection");
            testCon.close();
            _logger.info("Close prep connection");
            prepCon.close();
        }
        catch (Exception e)
        {
            _logger.error("tear down error",e);
        }
        finally
        {
            super.tearDown();
        }
    }

    public void testCommit() throws Exception
    {
        _logger.info("Send prep A");
        prepProducer1.send(prepSession.createTextMessage("A"));
        _logger.info("Send prep B");
        prepProducer1.send(prepSession.createTextMessage("B"));
        _logger.info("Send prep C");
        prepProducer1.send(prepSession.createTextMessage("C"));

        // send and receive some messages
        _logger.info("Send X to Q2");
        producer2.send(session.createTextMessage("X"));
        _logger.info("Send Y to Q2");
        producer2.send(session.createTextMessage("Y"));
        _logger.info("Send Z to Q2");
        producer2.send(session.createTextMessage("Z"));

        _logger.info("Read A from Q1");
        expect("A", consumer1.receive(1000));
        _logger.info("Read B from Q1");
        expect("B", consumer1.receive(1000));
        _logger.info("Read C from Q1");
        expect("C", consumer1.receive(1000));

        // commit
        _logger.info("session commit");
        session.commit();
        _logger.info("Start test Connection");
        testCon.start();

        // ensure sent messages can be received and received messages are gone
        _logger.info("Read X from Q2");
        expect("X", testConsumer2.receive(1000));
        _logger.info("Read Y from Q2");
        expect("Y", testConsumer2.receive(1000));
        _logger.info("Read Z from Q2");
        expect("Z", testConsumer2.receive(1000));

        _logger.info("create test session on Q1");
        testConsumer1 = testSession.createConsumer(queue1);
        _logger.info("Read null from Q1");
        assertTrue(null == testConsumer1.receive(1000));
        _logger.info("Read null from Q2");
        assertTrue(null == testConsumer2.receive(1000));
    }

    public void testRollback() throws Exception
    {
        // add some messages
        _logger.info("Send prep RB_A");
        prepProducer1.send(prepSession.createTextMessage("RB_A"));
        _logger.info("Send prep RB_B");
        prepProducer1.send(prepSession.createTextMessage("RB_B"));
        _logger.info("Send prep RB_C");
        prepProducer1.send(prepSession.createTextMessage("RB_C"));

        _logger.info("Sending RB_X RB_Y RB_Z");
        producer2.send(session.createTextMessage("RB_X"));
        producer2.send(session.createTextMessage("RB_Y"));
        producer2.send(session.createTextMessage("RB_Z"));
        _logger.info("Receiving RB_A RB_B");
        expect("RB_A", consumer1.receive(1000));
        expect("RB_B", consumer1.receive(1000));
        // Don't consume 'RB_C' leave it in the prefetch cache to ensure rollback removes it.
        // Quick sleep to ensure 'RB_C' gets pre-fetched
        Thread.sleep(500);

        // rollback
        _logger.info("rollback");
        session.rollback();

        _logger.info("Receiving RB_A RB_B RB_C");
        // ensure sent messages are not visible and received messages are requeued
        expect("RB_A", consumer1.receive(1000), true);
        expect("RB_B", consumer1.receive(1000), true);
        expect("RB_C", consumer1.receive(1000), (isBroker010()||isBroker10())?false:true);
        _logger.info("Starting new connection");
        testCon.start();
        testConsumer1 = testSession.createConsumer(queue1);
        _logger.info("Testing we have no messages left");
        assertTrue(null == testConsumer1.receive(1000));
        assertTrue(null == testConsumer2.receive(1000));

        session.commit();

        _logger.info("Testing we have no messages left after commit");
        assertTrue(null == testConsumer1.receive(1000));
        assertTrue(null == testConsumer2.receive(1000));
    }

    public void testResendsMsgsAfterSessionClose() throws Exception
    {
        Connection con = getConnection("guest", "guest");

        Session consumerSession = con.createSession(true, Session.SESSION_TRANSACTED);
        Queue queue3 = createTestQueue(session, "Q3");
        session.commit();
        MessageConsumer consumer = consumerSession.createConsumer(queue3);

        Connection con2 = getConnection("guest", "guest");
        Session producerSession = con2.createSession(true, Session.SESSION_TRANSACTED);
        MessageProducer producer = producerSession.createProducer(queue3);

        _logger.info("Sending four messages");
        producer.send(producerSession.createTextMessage("msg1"));
        producer.send(producerSession.createTextMessage("msg2"));
        producer.send(producerSession.createTextMessage("msg3"));
        producer.send(producerSession.createTextMessage("msg4"));

        producerSession.commit();

        _logger.info("Starting connection");
        con.start();
        TextMessage tm = (TextMessage) consumer.receive();
        assertNotNull(tm);
        assertEquals("msg1", tm.getText());

        consumerSession.commit();

        _logger.info("Received and committed first message");
        tm = (TextMessage) consumer.receive(1000);
        assertNotNull(tm);
        assertEquals("msg2", tm.getText());

        tm = (TextMessage) consumer.receive(1000);
        assertNotNull(tm);
        assertEquals("msg3", tm.getText());

        tm = (TextMessage) consumer.receive(1000);
        assertNotNull(tm);
        assertEquals("msg4", tm.getText());

        _logger.info("Received all four messages. Closing connection with three outstanding messages");

        consumerSession.close();

        consumerSession = con.createSession(true, Session.SESSION_TRANSACTED);

        consumer = consumerSession.createConsumer(queue3);

        // no ack for last three messages so when I call recover I expect to get three messages back
        tm = (TextMessage) consumer.receive(3000);
        assertNotNull(tm);
        assertEquals("msg2", tm.getText());
        assertTrue("Message is not redelivered", tm.getJMSRedelivered());

        tm = (TextMessage) consumer.receive(3000);
        assertNotNull(tm);
        assertEquals("msg3", tm.getText());
        assertTrue("Message is not redelivered", tm.getJMSRedelivered());

        tm = (TextMessage) consumer.receive(3000);
        assertNotNull(tm);
        assertEquals("msg4", tm.getText());
        assertTrue("Message is not redelivered", tm.getJMSRedelivered());

        _logger.info("Received redelivery of three messages. Committing");

        consumerSession.commit();

        _logger.info("Called commit");

        tm = (TextMessage) consumer.receive(1000);
        assertNull(tm);

        _logger.info("No messages redelivered as is expected");

        con.close();
        con2.close();
    }

    public void testCommitOnClosedConnection() throws Exception
    {
        Connection connnection = getConnection();
        javax.jms.Session transactedSession = connnection.createSession(true, Session.SESSION_TRANSACTED);
        connnection.close();
        try
        {
            transactedSession.commit();
            fail("Commit on closed connection should throw IllegalStateException!");
        }
        catch(IllegalStateException e)
        {
            // passed
        }
    }

    public void testCommitOnClosedSession() throws Exception
    {
        Connection connnection = getConnection();
        Session transactedSession = connnection.createSession(true, Session.SESSION_TRANSACTED);
        transactedSession.close();
        try
        {
            transactedSession.commit();
            fail("Commit on closed session should throw IllegalStateException!");
        }
        catch(IllegalStateException e)
        {
            // passed
        }
    }

    public void testRollbackOnClosedSession() throws Exception
    {
        Connection connnection = getConnection();
        Session transactedSession = connnection.createSession(true, Session.SESSION_TRANSACTED);
        transactedSession.close();
        try
        {
            transactedSession.rollback();
            fail("Rollback on closed session should throw IllegalStateException!");
        }
        catch(IllegalStateException e)
        {
            // passed
        }
    }

    public void testGetTransactedOnClosedSession() throws Exception
    {
        Connection connnection = getConnection();
        Session transactedSession = connnection.createSession(true, Session.SESSION_TRANSACTED);
        transactedSession.close();
        try
        {
            transactedSession.getTransacted();
            fail("According to Sun TCK invocation of Session#getTransacted on closed session should throw IllegalStateException!");
        }
        catch(IllegalStateException e)
        {
            // passed
        }
    }

    private void expect(String text, Message msg) throws JMSException
    {
        expect(text, msg, false);
    }

    private void expect(String text, Message msg, boolean requeued) throws JMSException
    {
        assertNotNull("Message should not be null", msg);
        assertTrue("Message should be a text message", msg instanceof TextMessage);
        assertEquals("Message content does not match expected", text, ((TextMessage) msg).getText());
        assertEquals("Message should " + (requeued ? "" : "not") + " be requeued", requeued, msg.getJMSRedelivered());
    }

    public static junit.framework.Test suite()
    {
        return new junit.framework.TestSuite(TransactedTest.class);
    }
}
