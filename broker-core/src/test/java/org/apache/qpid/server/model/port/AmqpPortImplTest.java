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

package org.apache.qpid.server.model.port;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.configuration.updater.CurrentThreadTaskExecutor;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.LogMessage;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.BrokerModel;
import org.apache.qpid.server.model.BrokerTestHelper;
import org.apache.qpid.server.model.Container;
import org.apache.qpid.server.model.Model;
import org.apache.qpid.server.model.SystemConfig;
import org.apache.qpid.test.utils.QpidTestCase;

public class AmqpPortImplTest extends QpidTestCase
{
    private static final String AUTHENTICATION_PROVIDER_NAME = "test";
    private TaskExecutor _taskExecutor;
    private Broker _broker;
    private AmqpPortImpl _port;

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        _taskExecutor = CurrentThreadTaskExecutor.newStartedInstance();
        Model model = BrokerModel.getInstance();
        SystemConfig systemConfig = mock(SystemConfig.class);
        _broker = BrokerTestHelper.mockWithSystemPrincipal(Broker.class, mock(Principal.class));
        when(_broker.getParent()).thenReturn(systemConfig);
        when(_broker.getTaskExecutor()).thenReturn(_taskExecutor);
        when(_broker.getChildExecutor()).thenReturn(_taskExecutor);
        when(_broker.getModel()).thenReturn(model);
        when(_broker.getId()).thenReturn(UUID.randomUUID());
        when(_broker.getCategoryClass()).thenReturn(Broker.class);
        when(_broker.getEventLogger()).thenReturn(new EventLogger());

        AuthenticationProvider<?> provider = mock(AuthenticationProvider.class);
        when(provider.getName()).thenReturn(AUTHENTICATION_PROVIDER_NAME);
        when(provider.getParent()).thenReturn(_broker);
        when(provider.getParent()).thenReturn(_broker);
        when(provider.getMechanisms()).thenReturn(Arrays.asList("PLAIN"));
        when(_broker.getChildren(AuthenticationProvider.class)).thenReturn(Collections.<AuthenticationProvider>singleton(provider));
        when(_broker.getChildByName(AuthenticationProvider.class, AUTHENTICATION_PROVIDER_NAME)).thenReturn(provider);
    }

    @Override
    public void tearDown() throws Exception
    {
        try
        {
            _taskExecutor.stop();
        }
        finally
        {
            if (_port != null)
            {
                while(_port.getConnectionCount() >0)
                {
                    _port.decrementConnectionCount();
                }
                _port.close();
            }
            super.tearDown();
        }
    }

    public void testOnCreateValidation() throws Exception
    {
        ServerSocket socket = openSocket();
        try
        {
            createPort(getTestName(), Collections.<String,Object>singletonMap(AmqpPort.PORT, socket.getLocalPort()));
            fail("Creation should fail due to validation check");
        }
        catch (IllegalConfigurationException e)
        {
            assertEquals("Unexpected exception message",
                         String.format("Cannot bind to port %d and binding address '%s'. Port is already is use.",
                                       socket.getLocalPort(), "*"), e.getMessage());
        }
        finally
        {
            socket.close();
        }

        try
        {
            createPort(getTestName(), Collections.<String, Object>singletonMap(AmqpPort.NUMBER_OF_SELECTORS, "-1"));
            fail("Exception not thrown for negative number of selectors");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
        try
        {
            createPort(getTestName(), Collections.<String, Object>singletonMap(AmqpPort.THREAD_POOL_SIZE, "-1"));
            fail("Exception not thrown for negative thread pool size");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
        try
        {
            createPort(getTestName(),
                       Collections.<String, Object>singletonMap(AmqpPort.NUMBER_OF_SELECTORS,
                                                                AmqpPort.DEFAULT_PORT_AMQP_THREAD_POOL_SIZE));
            fail("Exception not thrown for number of selectors equal to thread pool size");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
    }

    public void testOnChangeThreadPoolValidation() throws Exception
    {
        _port = createPort(getTestName());
        try
        {
            _port.setAttributes(Collections.<String, Object>singletonMap(AmqpPort.NUMBER_OF_SELECTORS, "-1"));
            fail("Exception not thrown for negative number of selectors");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
        try
        {
            _port.setAttributes(Collections.<String, Object>singletonMap(AmqpPort.THREAD_POOL_SIZE, "-1"));
            fail("Exception not thrown for negative thread pool size");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
        try
        {
            _port.setAttributes(Collections.<String, Object>singletonMap(AmqpPort.NUMBER_OF_SELECTORS,
                                                                         AmqpPort.DEFAULT_PORT_AMQP_THREAD_POOL_SIZE));
            fail("Exception not thrown for number of selectors equal to thread pool size");
        }
        catch (IllegalConfigurationException e)
        {
            // pass
        }
    }

    private AmqpPortImpl createPort(final String portName)
    {
        return createPort(portName, Collections.<String, Object>emptyMap());
    }

    private AmqpPortImpl createPort(final String portName, final Map<String, Object> attributes)
    {
        Map<String, Object> portAttributes = new HashMap<>();
        portAttributes.put(AmqpPort.PORT, 0);
        portAttributes.put(AmqpPort.NAME, portName);
        portAttributes.put(AmqpPort.AUTHENTICATION_PROVIDER, AUTHENTICATION_PROVIDER_NAME);
        portAttributes.putAll(attributes);
        AmqpPortImpl port = new AmqpPortImpl(portAttributes, _broker);
        port.create();
        return port;
    }

    private ServerSocket openSocket() throws IOException
    {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(0));
        return serverSocket;
    }

    public void testConnectionCounting()
    {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AmqpPort.PORT, 0);
        attributes.put(AmqpPort.NAME, getTestName());
        attributes.put(AmqpPort.AUTHENTICATION_PROVIDER, AUTHENTICATION_PROVIDER_NAME);
        attributes.put(AmqpPort.MAX_OPEN_CONNECTIONS, 10);
        attributes.put(AmqpPort.CONTEXT, Collections.singletonMap(AmqpPort.OPEN_CONNECTIONS_WARN_PERCENT, "80"));
        _port = new AmqpPortImpl(attributes, _broker);
        _port.create();
        EventLogger mockLogger = mock(EventLogger.class);

        when(_broker.getEventLogger()).thenReturn(mockLogger);

        for(int i = 0; i < 8; i++)
        {
            assertTrue(_port.canAcceptNewConnection(new InetSocketAddress("example.org", 0)));
            _port.incrementConnectionCount();
            assertEquals(i + 1, _port.getConnectionCount());
            verify(mockLogger, never()).message(any(LogSubject.class), any(LogMessage.class));
        }

        assertTrue(_port.canAcceptNewConnection(new InetSocketAddress("example.org", 0)));
        _port.incrementConnectionCount();
        assertEquals(9, _port.getConnectionCount());
        verify(mockLogger, times(1)).message(any(LogSubject.class), any(LogMessage.class));

        assertTrue(_port.canAcceptNewConnection(new InetSocketAddress("example.org", 0)));
        _port.incrementConnectionCount();
        assertEquals(10, _port.getConnectionCount());
        verify(mockLogger, times(1)).message(any(LogSubject.class), any(LogMessage.class));

        assertFalse(_port.canAcceptNewConnection(new InetSocketAddress("example.org", 0)));


    }
}
