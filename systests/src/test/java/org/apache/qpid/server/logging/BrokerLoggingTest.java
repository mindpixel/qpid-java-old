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
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.AssertionFailedError;

import org.apache.qpid.server.model.Port;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.test.utils.BrokerHolder;
import org.apache.qpid.test.utils.TestBrokerConfiguration;
import org.apache.qpid.transport.ConnectionException;
import org.apache.qpid.util.LogMonitor;

/**
 * Broker Test Suite
 *
 * The Broker test suite validates that the follow log messages as specified in the Functional Specification.
 *
 * BRK-1001 : Startup : Version: <Version> Build: <Build>
 * BRK-1002 : Starting : Listening on <Transport> port <Port>
 * BRK-1003 : Shutting down : <Transport> port <Port>
 * BRK-1004 : Ready
 * BRK-1005 : Stopped
 * BRK-1006 : Using configuration : <path>
 * BRK-1007 : Using logging configuration : <path>
 *
 * These messages should only occur during startup. The tests need to verify the order of messages. In the case of the BRK-1002 and BRK-1003 the respective ports should only be available between the two log messages.
 */
public class BrokerLoggingTest extends AbstractTestLogging
{
    private static final String BROKER_MESSAGE_LOG_REG_EXP = ".*\\[\\w*\\] (BRK\\-\\d*) .*";
    private static final Pattern BROKER_MESSAGE_LOG_PATTERN = Pattern.compile(BROKER_MESSAGE_LOG_REG_EXP);
    private static final String BRK_LOG_PREFIX = "BRK-";


    @Override
    public void startDefaultBroker()
    {
        // noop. we do not want to start broker
    }

    /**
     * Description:
     * On startup the broker must report the active configuration file. The
     * logging system must output this so that we can know what configuration
     * is being used for this broker instance.
     *
     * Input:
     * The value of -c specified on the command line.
     * Output:
     * <date> MESSAGE BRK-1006 : Using configuration : <config file>
     * Constraints:
     * This MUST BE the first BRK log message.
     *
     * Validation Steps:
     * 1. This is first BRK log message.
     * 2. The BRK ID is correct
     * 3. The config file is the full path to the file specified on
     * the commandline.
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerStartupConfiguration() throws Exception
    {
        String TESTID="BRK-1006";

        if (isJavaBroker())
        {
            super.startDefaultBroker();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());


            String configFilePath = getDefaultBroker().getConfigurationPath();

            // Ensure we wait for TESTID to be logged
            waitAndFindMatches(TESTID);

            List<String> results = waitAndFindMatches(BRK_LOG_PREFIX);
            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                String log = getLogMessage(results, 0);

                //1
                validateMessageID(TESTID, log);

                //2
                results = findMatches(TESTID);
                assertEquals("More than one configuration message found.",
                             1, results.size());

                //3
                assertTrue("Config file details not correctly logged, got "
                        + log + " but expected it to end with " + configFilePath,
                        log.endsWith(configFilePath));
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);

                throw afe;
            }
        }
    }


    /**
     * Description: On startup the broker reports the broker version number and svn build revision. This information is retrieved from the resource 'qpidversion.properties' which is located via the classloader.
     * Input: The 'qpidversion.properties' file located on the classpath.
     * Output:
     *
     * <date> MESSAGE BRK-1001 : Startup : qpid Version: 0.6 Build: 767150
     *
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. This occurs before any BRK-1002 listening messages are reported.
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerStartupStartup() throws Exception
    {
        // This logging startup code only occurs when you run a Apache Qpid Broker for Java,
        // that broker must be started via Main so not an InVM broker.
        if (isJavaBroker())
        {
            String TESTID = "BRK-1001";

            super.startDefaultBroker();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());
            
            // Ensure we wait for TESTID to be logged
            waitAndFindMatches(TESTID);

            // Retrieve all BRK- log messages so we can check for an erroneous
            // BRK-1002 message.
            List<String> results = findMatches(BRK_LOG_PREFIX);

            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                boolean validation = false;
                for (String rawLog : results)
                {
                    if (validation)
                    {
                        //Stop checking once we have got to our startup test
                        break;
                    }
                    String log = getLog(rawLog);

                    // Ensure we do not have a BRK-1002 message
                    if (!getMessageID(log).equals(TESTID))
                    {
                        assertFalse(getMessageID(log).equals("BRK-1002"));
                        continue;
                    }

                    //1
                    validateMessageID(TESTID, log);

                    //2
                    //There will be 2 copies of the startup message (one via SystemOut, and one via Log4J)
                    assertEquals("Unexpected startup message count",
                                 1, findMatches(TESTID).size());

                    validation = true;
                }

                assertTrue("Validation not performed: " + TESTID + " not logged", validation);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);
                
                throw afe;
            }
        }
    }

    /**
     * Description:
     * On startup the broker may listen on a number of ports and protocols. Each of these must be reported as they are made available.
     * Input:
     * The default configuration with no SSL
     * Output:
     *
     * <date> MESSAGE BRK-1002 : Starting : Listening on TCP port 5672
     *
     * Constraints:
     * Additional broker configuration will occur between the Startup(BRK-1001) and Starting(BRK-1002) messages depending on what VirtualHosts are configured.
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. This occurs after the BRK-1001 startup message
     * 3. Using the default configuration a single BRK-1002 will be printed showing values TCP / 5672
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerStartupListeningTCPDefault() throws Exception
    {
        if (isJavaBroker())
        {
            String TESTID = "BRK-1002";

            super.startDefaultBroker();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());

            // Ensure broker has fully started up.
            getConnection();

            // Ensure we wait for TESTID to be logged
            waitAndFindMatches(TESTID);

            // Retrieve all BRK- log messages so we can check for an erroneous
            // BRK-1002 message.
            List<String> results = findMatches(BRK_LOG_PREFIX);
            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                boolean validation = false;
                boolean foundBRK1001 = false;
                for (String rawLog : results)
                {
                    String log = getLog(rawLog);

                    // using custom method to get id as getMessageId() fails to correctly identify id
                    // because of using brackets for protocols
                    String id = getBrokerLogId(log);
                    // Ensure we do not have a BRK-1002 message
                    if (!id.equals(TESTID))
                    {
                        if (id.equals("BRK-1001"))
                        {
                            foundBRK1001 = true;
                        }
                        continue;
                    }

                    assertTrue("BRK-1001 not logged before this message", foundBRK1001);

                    //1
                    assertEquals("Incorrect message", TESTID, id);

                    //2
                    assertEquals("Unexpected listen message count",
                                 1, findMatches(TESTID).size());

                    //3
                    String message = getMessageString(log);
                    assertTrue("Expected Listen log not correct" + message,
                               message.endsWith("Listening on TCP port " + getDefaultBroker().getAmqpPort()));

                    validation = true;
                }

                assertTrue("Validation not performed: " + TESTID + " not logged", validation);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);
                
                throw afe;
            }
        }
    }

    private String getBrokerLogId(String log)
    {
        Matcher m = BROKER_MESSAGE_LOG_PATTERN.matcher(log);
        if (m.matches())
        {
            return m.group(1);
        }
        return getMessageID(log);
    }

    /**
     * Description:
     * On startup the broker may listen on a number of ports and protocols. Each of these must be reported as they are made available.
     * Input:
     * The default configuration with SSL enabled
     * Output:
     *
     * <date> MESSAGE BRK-1002 : Starting : Listening on TCP port 5672
     * <date> MESSAGE BRK-1002 : Starting : Listening on TCP/SSL port 8672
     *
     * Constraints:
     * Additional broker configuration will occur between the Startup(BRK-1001) and Starting(BRK-1002) messages depending on what VirtualHosts are configured.
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. This occurs after the BRK-1001 startup message
     * 3. With SSL enabled in the configuration two BRK-1002 will be printed (order is not specified)
     * 1. One showing values [TCP] 5672
     * 2. One showing values [SSL] 5671
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerStartupListeningTCPSSL() throws Exception
    {
        if (isJavaBroker())
        {
            String TESTID = "BRK-1002";

            // Enable SSL on the connection
            Map<String, Object> sslPortAttributes = new HashMap<String, Object>();
            sslPortAttributes.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));
            sslPortAttributes.put(Port.PORT, DEFAULT_SSL_PORT);
            sslPortAttributes.put(Port.NAME, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT);
            sslPortAttributes.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER);
            sslPortAttributes.put(Port.KEY_STORE, TestBrokerConfiguration.ENTRY_NAME_SSL_KEYSTORE);
            getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, sslPortAttributes);

            super.startDefaultBroker();

            final BrokerHolder defaultBroker = getDefaultBroker();
            int amqpTlsPort =  defaultBroker.getAmqpTlsPort();
            int amqpPort = defaultBroker.getAmqpPort();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());

            // Ensure broker has fully started up.
            getConnection();

            // Ensure we wait for TESTID to be logged
            waitAndFindMatches(TESTID);

            // Retrieve all BRK- log messages so we can check for an erroneous
            // BRK-1002 message.
            List<String> results = findMatches(BRK_LOG_PREFIX);
            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                boolean validation = false;
                boolean foundBRK1001 = false;
                for (String rawLog : results)
                {
                    String log = getLog(rawLog);

                    String id = getBrokerLogId(log);
                    // Ensure we do not have a BRK-1002 message
                    if (!id.equals(TESTID))
                    {
                        if (id.equals("BRK-1001"))
                        {
                            foundBRK1001 = true;
                        }
                        continue;
                    }

                    assertTrue("BRK-1001 not logged before this message", foundBRK1001);

                    //1
                    assertEquals("Incorrect message", TESTID, id);

                    //2
                    //There will be 4 copies of the startup message (two via SystemOut, and two via Log4J)
                    List<String> listenMessages  = findMatches(TESTID);
                    assertEquals("Four listen messages should be found.",
                                 2, listenMessages .size());

                    int tcpStarted = 0;
                    int sslStarted = 0;

                    for (String message : listenMessages)
                    {
                        if (message.endsWith("Listening on TCP port " + amqpPort))
                        {
                            tcpStarted++;
                        }
                        if (message.endsWith("Listening on SSL port " + amqpTlsPort))
                        {
                            sslStarted++;
                        }
                    }

                    assertEquals("Unexpected number of logs 'Listening on TCP port'", 1, tcpStarted);
                    assertEquals("Unexpected number of logs 'Listening on SSL port'", 1, sslStarted);

                    //4 Test ports open
                    testSocketOpen(amqpPort);
                    testSocketOpen(amqpTlsPort);

                    validation = true;
                }

                assertTrue("Validation not performed: " + TESTID + " not logged", validation);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);

                throw afe;
            }
        }
    }

    /**
     * Description:
     * The final message the broker will print when it has performed all initialisation and listener startups will be to log the BRK-1004 Ready message
     * Input:
     * No input, all successful broker startups will show BRK-1004 messages.
     * Output:
     *
     * 2009-07-09 15:50:20 +0100 MESSAGE BRK-1004 : Qpid Broker Ready
     *
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. This occurs after the BRK-1001 startup message
     * 3. This must be the last message the broker prints after startup. Currently, if there is no further interaction with the broker then there should be no more logging.
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerStartupReady() throws Exception
    {
        if (isJavaBroker())
        {
            String TESTID = "BRK-1004";

            super.startDefaultBroker();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());

            //Ensure the broker has fully started up.
            getConnection();
            // Ensure we wait for TESTID to be logged
            waitAndFindMatches(TESTID);

            // Retrieve all BRK- log messages so we can check for an erroneous
            // BRK-1001 message.
            List<String> results = findMatches(BRK_LOG_PREFIX);
            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                boolean validationComplete = false;
                boolean foundBRK1001 = false;
                
                for (int i=0; i < results.size(); i++)
                {
                    String rawLog = results.get(i);
                    String log = getLog(rawLog);

                    // Ensure we do not have a BRK-1001 message
                    if (!getMessageID(log).equals(TESTID))
                    {
                        if (getMessageID(log).equals("BRK-1001"))
                        {
                            foundBRK1001 = true;
                        }
                        continue;
                    }

                    assertTrue("BRK-1001 not logged before this message", foundBRK1001);

                    //1
                    validateMessageID(TESTID, log);

                    //2
                    assertEquals("Ready message not present", "Qpid Broker Ready", getMessageString(log));
                    
                    assertEquals("Unexpected ready message count",
                                 1, findMatches(TESTID).size());
                    assertEquals("The ready messages should have been the last 2 messages", results.size() - 1, i);

                    validationComplete = true;
                    break;
                }

                assertTrue("Validation not performed: " + TESTID + " not logged", validationComplete);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);

                throw afe;
            }
        }
    }

    /**
     * Description:
     * On startup the broker may listen on a number of ports and protocols. Each of these must then report a shutting down message as they stop listening.
     * Input:
     * The default configuration with no SSL
     * Output:
     *
     * <date> MESSAGE BRK-1003 : Shutting down : TCP port 5672
     *
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. Only TCP is reported with the default configuration with no SSL.
     * 3. The default port is correct
     * 4. The port is not accessible after this message
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerShutdownListeningTCPDefault() throws Exception
    {
        if (isJavaBroker() && isInternalBroker())
        {
            String TESTID = "BRK-1003";

            super.startDefaultBroker();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());

            stopDefaultBroker();

            //Give broker time to shutdown and flush log
            checkSocketClosed(getDefaultBroker().getAmqpPort());

            List<String> results = waitAndFindMatches(BRK_LOG_PREFIX);
            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                boolean validation = false;
                boolean foundBRK1001 = false;
                for (String rawLog : results)
                {
                    String log = getLog(rawLog);

                    // Ensure we do not have a BRK-1002 message
                    if (!getMessageID(log).equals(TESTID))
                    {
                        if (getMessageID(log).equals("BRK-1001"))
                        {
                            foundBRK1001 = true;
                        }
                        continue;
                    }

                    assertTrue("BRK-1001 not logged before this message", foundBRK1001);

                    //1
                    validateMessageID(TESTID, log);

                    //2
                    assertEquals("More than one listen message found.",
                                 1, findMatches(TESTID).size());

                    //3
                    String message = getMessageString(log);
                    final int amqpPort = getDefaultBroker().getAmqpPort();
                    assertTrue("Expected shutdown log not correct" + message,
                               message.endsWith("TCP port " + amqpPort));

                    //4
                    checkSocketClosed(amqpPort);

                    validation = true;
                }

                assertTrue("Validation not performed: " + TESTID + " not logged", validation);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);

                throw afe;
            }
        }
    }

    /**
     * Description:
     * On startup the broker may listen on a number of ports and protocols. Each of these must be reported as they are made available.
     * Input:
     * The default configuration with SSL enabled
     * Output:
     *
     * <date> MESSAGE BRK-1002 : Starting : Listening on TCP port 5672
     * <date> MESSAGE BRK-1002 : Starting : Listening on TCP/SSL port 8672
     *
     * Constraints:
     * Additional broker configuration will occur between the Startup(BRK-1001) and Starting(BRK-1002) messages depending on what VirtualHosts are configured.
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. This occurs after the BRK-1001 startup message
     * 3. With SSL enabled in the configuration two BRK-1002 will be printed (order is not specified)
     * 1. One showing values TCP / 5672
     * 2. One showing values TCP/SSL / 5672
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerShutdownListeningTCPSSL() throws Exception
    {
        if (isJavaBroker() && isInternalBroker())
        {
            String TESTID = "BRK-1003";

            // Enable SSL on the connection
            Map<String, Object> sslPortAttributes = new HashMap<String, Object>();
            sslPortAttributes.put(Port.TRANSPORTS, Collections.singleton(Transport.SSL));
            sslPortAttributes.put(Port.PORT, DEFAULT_SSL_PORT);
            sslPortAttributes.put(Port.NAME, TestBrokerConfiguration.ENTRY_NAME_SSL_PORT);
            sslPortAttributes.put(Port.AUTHENTICATION_PROVIDER, TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER);
            sslPortAttributes.put(Port.KEY_STORE, TestBrokerConfiguration.ENTRY_NAME_SSL_KEYSTORE);
            getDefaultBrokerConfiguration().addObjectConfiguration(Port.class, sslPortAttributes);

            super.startDefaultBroker();

            final BrokerHolder defaultBroker = getDefaultBroker();
            int amqpTlsPort = defaultBroker.getAmqpTlsPort();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());


//            //Clear any startup messages as we don't need them for validation
//            _monitor.reset();
            //Stop the broker to get the log messages for testing
            stopDefaultBroker();

            //Give broker time to shutdown and flush log
            final int amqpPort = getDefaultBroker().getAmqpPort();
            checkSocketClosed(amqpPort);

            List<String> results = waitAndFindMatches(TESTID);
            try
            {
                // Validation

                assertTrue(TESTID + " messages not logged", results.size() > 0);

                String log = getLog(results.get(0));

                //1
                validateMessageID(TESTID, log);

                //2
                List<String> listenMessages = findMatches(TESTID);
                assertEquals("Two shutdown messages should be found.",
                             2, listenMessages.size());

                int tcpShuttingDown = 0;
                int sslShuttingDown = 0;

                for (String m : listenMessages)
                {
                    if (m.endsWith("Shutting down : TCP port " + amqpPort))
                    {
                        tcpShuttingDown++;
                    }
                    if (m.endsWith("Shutting down : SSL port " + amqpTlsPort))
                    {
                        sslShuttingDown++;
                    }
                }

                assertEquals("Unexpected number of logs 'Shutting down : TCP port'", 1, tcpShuttingDown);
                assertEquals("Unexpected number of logs 'Shutting down : SSL port'", 1, sslShuttingDown);

                //4
                //Test Port closed
                checkSocketClosed(amqpPort);
                //Test SSL Port closed
                checkSocketClosed(amqpTlsPort);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);

                throw afe;
            }
        }
    }

    /**
     * Description:
     * Input:
     * No input, all clean broker shutdowns will show BRK-1005 messages.
     * Output:
     *
     * <date> MESSAGE BRK-1005 : Stopped
     *
     * Constraints:
     * This is the LAST message the broker will log.
     * Validation Steps:
     *
     * 1. The BRK ID is correct
     * 2. This is the last message the broker will log.
     *
     * @throws Exception caused by broker startup
     */
    public void testBrokerShutdownStopped() throws Exception
    {
        if (isJavaBroker() && isInternalBroker())
        {
            String TESTID = "BRK-1005";

            super.startDefaultBroker();

            // Now we can create the monitor as _outputFile will now be defined
            _monitor = new LogMonitor(getOutputFile());

            getConnection().close();

            stopDefaultBroker();

            final int amqpPort = getDefaultBroker().getAmqpPort();

            // Ensure the broker has shutdown before retreving results
            checkSocketClosed(amqpPort);

            waitAndFindMatches(TESTID);

            List<String> results = waitAndFindMatches(BRK_LOG_PREFIX);
            try
            {
                // Validation

                assertTrue("BRKer message not logged", results.size() > 0);

                boolean validation = false;
                for (String rawLog : results)
                {
                    assertFalse("More broker log statements present after ready message", validation);
                    String log = getLog(rawLog);

                    // Ignore all logs until we get to the test id.
                    if (!getMessageID(log).equals(TESTID))
                    {
                        continue;
                    }

                    //1
                    validateMessageID(TESTID, log);

                    //2
                    assertEquals("More than one ready message found.",
                                 1, findMatches(TESTID).size());

                    //3
                    assertEquals("Stopped message not present", "Stopped", getMessageString(log));

                    validation = true;
                }

                assertTrue("Validation not performed: " + TESTID + " not logged", validation);
            }
            catch (AssertionFailedError afe)
            {
                dumpLogs(results, _monitor);

                throw afe;
            }
        }
    }

    /**
     * Test that a socket on the given port is closed.
     *
     * Does this by attempting to connect to the port and expecting a
     * ConnectionRefused IOException or a ConnectionException
     *
     * @param port the port number
     */
    private void checkSocketClosed(int port)
    {
        try
        {
            Socket socket = new Socket((String) null, port);
            fail("Socket not closed on port:" + port);
        }
        catch (ConnectionException e)
        {
            //normal path
        }
        catch (IOException e)
        {
            if (!e.getMessage().startsWith("Connection refused"))
            {
                fail("Socket not closed on port:" + port + ":" + e.getMessage());
                // Keep stack trace for diagnosis.
                e.printStackTrace(System.err);
            }
        }
    }

    /**
     * Test that a socket on the given port is open.
     *
     * Does this by attempting to connect to the port and expecting a
     * The connection to succeed.
     * It then closes the socket and expects that to work cleanly.
     *
     * @param port the port number
     */
    private void testSocketOpen(int port)
    {
        try
        {
            Socket socket = new Socket((String) null, port);
            socket.close();
        }
        catch (IOException e)
        {
            fail("Unable to open and close socket to port:" + port
                 + ". Due to:" + e.getMessage());
        }
    }

}
