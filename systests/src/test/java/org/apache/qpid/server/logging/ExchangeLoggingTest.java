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
package org.apache.qpid.server.logging;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

/**
 * Exchange
 *
 * The Exchange test suite validates that the follow log messages as specified in the Functional Specification.
 *
 * This suite of tests validate that the Exchange messages occur correctly and according to the following format:
 *
 * EXH-1001 : Create : [Durable] Type:<value> Name:<value>
 * EXH-1002 : Deleted
 */
public class ExchangeLoggingTest extends AbstractTestLogging
{

    static final String EXH_PREFIX = "EXH-";

    private Connection _connection;
    private Session _session;
    private Topic _topic;
    private String _name;
    private String _type;
    private String _topicName;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();

        _connection = getConnection();

        _session = _connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        _type = "direct";
        _name = getTestQueueName()+ "-exchange";

        _topicName = isBroker10() ? _name + "/queue" : "ADDR: " + _name + "/queue" ;
        _topic = _session.createTopic(_topicName);

    }

    /**
     * Description:
     * When a durable exchange is created an EXH-1001 message is logged with the Durable tag. This will be the first message from this exchange.
     * Input:
     *
     * 1. Running broker
     * 2. Client requests a durable exchange be created.
     * Output:
     *
     * <date> EXH-1001 : Create : Durable Type:<value> Name:<value>
     *
     * Validation Steps:
     * 3. The EXH ID is correct
     * 4. The Durable tag is present in the message
     */

    public void testExchangeCreateDurable() throws JMSException, IOException
    {

        //Ignore broker startup messages
        _monitor.markDiscardPoint();

        createExchangeUsingAmqpManagement(_name, _type, true);

        // Ensure we have received the EXH log msg.
        waitForMessage("EXH-1001");

        List<String> results = findMatches(EXH_PREFIX);

        assertTrue("No Results found for Exchange.", results.size()==1);

        validateExchangeCreate(results, true, true);
    }

    private void createExchangeUsingAmqpManagement(final String name, final String type, final boolean durable)
            throws JMSException
    {
        final Map<String, Object> attributes = new LinkedHashMap();
        attributes.put("object-path", name);
        attributes.put("qpid-type", type);
        attributes.put("durable", durable);

        createEntityUsingAmqpManagement(name, _session, "org.apache.qpid.Exchange", attributes);
    }

    /**
     * Description:
     * When an exchange is created an EXH-1001 message is logged. This will be the first message from this exchange.
     * Input:
     *
     * 1. Running broker
     * 2. Client requests an exchange be created.
     * Output:
     *
     * <date> EXH-1001 : Create : Type:<value> Name:<value>
     *
     * Validation Steps:
     * 3. The EXH ID is correct
     */
    public void testExchangeCreate() throws JMSException, IOException
    {
        //Ignore broker startup messages
        _monitor.markDiscardPoint();

        createExchangeUsingAmqpManagement(_name, _type, false);
        // Ensure we have received the EXH log msg.
        waitForMessage("EXH-1001");

        List<String> results = findMatches(EXH_PREFIX);

        assertEquals("Result set larger than expected.", 1, results.size());

        validateExchangeCreate(results, false, true);
    }

    private void validateExchangeCreate(List<String> results, boolean durable, boolean checkNameAndType)
    {
        String log = getLogMessage(results, 0);
        String message = getMessageString(fromMessage(log));
        
        validateMessageID("EXH-1001", log);
        
        assertTrue("Log Message does not start with create:" + message,
                   message.startsWith("Create"));

        assertEquals("Unexpected Durable state:" + message, durable,
                message.contains("Durable"));
        
        if(checkNameAndType)
        {
            assertTrue("Log Message does not contain Type:" + message,
                    message.contains("Type: " + _type));
            assertTrue("Log Message does not contain Name:" + message,
                    message.contains("Name: " + _name));
        }
    }

    /**
     * Description:
     * An Exchange can be deleted through an AMQP ExchangeDelete method. When this is successful an EXH-1002 Delete message will be logged. This will be the last message from this exchange.
     * Input:
     *
     * 1. Running broker
     * 2. A new Exchange has been created
     * 3. Client requests that the new exchange be deleted.
     * Output:
     *
     * <date> EXH-1002 : Deleted
     *
     * Validation Steps:
     * 4. The EXH ID is correct
     * 5. There is a corresponding EXH-1001 Create message logged.
     */
    public void testExchangeDelete() throws Exception, IOException
    {
        //Ignore broker startup messages
        _monitor.markDiscardPoint();

        createExchangeUsingAmqpManagement(_name, _type, false);
        deleteEntityUsingAmqpManagement(_name, _session, "org.apache.qpid.Exchange");

        //Wait and ensure we get our last EXH-1002 msg
        waitForMessage("EXH-1002");

        List<String> results = findMatches(EXH_PREFIX);

        assertEquals("Result set larger than expected.", 2, results.size());

        validateExchangeCreate(results, false, false);

        String log = getLogMessage(results, 1);
        validateMessageID("EXH-1002", log);

        String message = getMessageString(fromMessage(log));
        assertEquals("Log Message not as expected", "Deleted", message);

    }

    public void testDiscardedMessage() throws Exception
    {
        //Ignore broker startup messages
        _monitor.markDiscardPoint();
        createExchangeUsingAmqpManagement(_name, _type, false);

        if (!(isBroker010() || isBroker10()))
        {
            // Default 0-8..-0-9-1 behaviour is for messages to be rejected (returned to client).
            setTestClientSystemProperty("qpid.default_mandatory", "false");
        }

        _session = _connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

        // Do not create consumer so queue is not created and message will be discarded.
        final MessageProducer producer = _session.createProducer(_topic);

        // Sending message
        final TextMessage msg = _session.createTextMessage("msg");
        producer.send(msg);

        final String expectedMessageBody = "Discarded Message : Name: \"" + _name + "\" Routing Key: \"queue\"";

        // Ensure we have received the EXH log msg.
        waitForMessage("EXH-1003");

        List<String> results = findMatches(EXH_PREFIX);
        assertEquals("Result set larger than expected.", 2, results.size());

        final String log = getLogMessage(results, 1);
        validateMessageID("EXH-1003", log);

        final String message = getMessageString(fromMessage(log));
        assertEquals("Log Message not as expected", expectedMessageBody, message);
    }
}
