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
package org.apache.qpid.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import javax.naming.NamingException;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import org.apache.qpid.client.AMQConnectionFactory;
import org.apache.qpid.client.BrokerDetails;
import org.apache.qpid.codec.ClientDecoder;
import org.apache.qpid.framing.AMQDataBlock;
import org.apache.qpid.framing.AMQFrame;
import org.apache.qpid.framing.AMQFrameDecodingException;
import org.apache.qpid.framing.AMQMethodBodyImpl;
import org.apache.qpid.framing.AMQProtocolVersionException;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.ConnectionCloseBody;
import org.apache.qpid.framing.ConnectionCloseOkBody;
import org.apache.qpid.framing.ConnectionOpenBody;
import org.apache.qpid.framing.ConnectionOpenOkBody;
import org.apache.qpid.framing.ConnectionStartOkBody;
import org.apache.qpid.framing.ConnectionTuneOkBody;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.framing.FrameCreatingMethodProcessor;
import org.apache.qpid.framing.ProtocolVersion;
import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.Protocol;
import org.apache.qpid.server.protocol.v0_8.ProtocolEngineCreator_0_8;
import org.apache.qpid.server.protocol.v0_8.ProtocolEngineCreator_0_9;
import org.apache.qpid.server.protocol.v0_8.ProtocolEngineCreator_0_9_1;
import org.apache.qpid.test.utils.QpidBrokerTestCase;
import org.apache.qpid.test.utils.TestBrokerConfiguration;

public class MaxFrameSizeTest extends QpidBrokerTestCase
{

    @Override
    public void startDefaultBroker() throws Exception
    {
        // broker started by the tests
    }

    public void testTooSmallFrameSize() throws Exception
    {

        getDefaultBrokerConfiguration().setObjectAttribute(AuthenticationProvider.class,
                                                           TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER,
                                                           "secureOnlyMechanisms",
                                                           "[]");
        super.startDefaultBroker();

        if(isBroker010())
        {
            Connection conn = new Connection();
            final ConnectionSettings settings = new ConnectionSettings();
            BrokerDetails brokerDetails = ((AMQConnectionFactory)getConnectionFactory()).getConnectionURL().getAllBrokerDetails().get(0);
            settings.setHost(brokerDetails.getHost());
            settings.setPort(brokerDetails.getPort());
            settings.setUsername(GUEST_USERNAME);
            settings.setPassword(GUEST_PASSWORD);
            final ConnectionDelegate clientDelegate = new TestClientDelegate(settings, 1024);
            conn.setConnectionDelegate(clientDelegate);
            try
            {
                conn.connect(settings);
                fail("Connection should not be possible with a frame size < " + Constant.MIN_MAX_FRAME_SIZE);
            }
            catch(ConnectionException e)
            {
                // pass
            }

        }
        else
        {
            doAMQP08test(1024, new ResultEvaluator()
                                {

                                    @Override
                                    public boolean evaluate(final Socket socket, final List<AMQDataBlock> frames)
                                    {
                                        if(containsFrame(frames, ConnectionOpenOkBody.class))
                                        {
                                            fail("Connection should not be possible with a frame size < " + Constant.MIN_MAX_FRAME_SIZE);
                                            return false;
                                        }
                                        else if(containsFrame(frames, ConnectionCloseBody.class))
                                        {
                                            return false;
                                        }
                                        else
                                        {
                                            return true;
                                        }
                                    }
                                });
        }
    }

    private boolean containsFrame(final List<AMQDataBlock> frames,
                                  final Class<? extends AMQMethodBodyImpl> frameClass)
    {
        for(AMQDataBlock block : frames)
        {
            AMQFrame frame = (AMQFrame) block;
            if(frameClass.isInstance(frame.getBodyFrame()))
            {
                return true;
            }
        }
        return false;
    }


    public void testTooLargeFrameSize() throws Exception
    {
        getDefaultBrokerConfiguration().setObjectAttribute(AuthenticationProvider.class,
                                                           TestBrokerConfiguration.ENTRY_NAME_AUTHENTICATION_PROVIDER,
                                                           "secureOnlyMechanisms",
                                                           "[]");

        super.startDefaultBroker();
        if(isBroker010())
        {
            Connection conn = new Connection();
            final ConnectionSettings settings = new ConnectionSettings();
            BrokerDetails brokerDetails = ((AMQConnectionFactory)getConnectionFactory()).getConnectionURL().getAllBrokerDetails().get(0);
            settings.setHost(brokerDetails.getHost());
            settings.setPort(brokerDetails.getPort());
            settings.setUsername(GUEST_USERNAME);
            settings.setPassword(GUEST_PASSWORD);
            final ConnectionDelegate clientDelegate = new TestClientDelegate(settings, 0xffff);
            conn.setConnectionDelegate(clientDelegate);
            try
            {
                conn.connect(settings);
                fail("Connection should not be possible with a frame size larger than the broker requested");
            }
            catch(ConnectionException e)
            {
                // pass
            }

        }
        else
        {
            doAMQP08test(Broker.DEFAULT_NETWORK_BUFFER_SIZE + 1, new ResultEvaluator()
                                {

                                    @Override
                                    public boolean evaluate(final Socket socket, final List<AMQDataBlock> frames)
                                    {
                                        if(containsFrame(frames, ConnectionOpenOkBody.class))
                                        {
                                            fail("Connection should not be possible with a frame size larger than the broker requested");
                                            return false;
                                        }
                                        else if(containsFrame(frames, ConnectionCloseBody.class))
                                        {
                                            return false;
                                        }
                                        else
                                        {
                                            return true;
                                        }
                                    }
                                });
        }
    }

    private static interface ResultEvaluator
    {
        boolean evaluate(Socket socket, List<AMQDataBlock> frames);
    }

    private void doAMQP08test(int frameSize, ResultEvaluator evaluator)
            throws NamingException, IOException, AMQFrameDecodingException, AMQProtocolVersionException
    {
        BrokerDetails brokerDetails = ((AMQConnectionFactory)getConnectionFactory()).getConnectionURL().getAllBrokerDetails().get(0);

        Socket socket = new Socket(brokerDetails.getHost(), brokerDetails.getPort());
        socket.setTcpNoDelay(true);
        OutputStream os = socket.getOutputStream();

        byte[] protocolHeader;
        ConnectionCloseOkBody closeOk;

        Protocol protocol = getBrokerProtocol();
        switch(protocol)
        {
            case AMQP_0_8:
                protocolHeader = (ProtocolEngineCreator_0_8.getInstance().getHeaderIdentifier());
                closeOk = ConnectionCloseOkBody.CONNECTION_CLOSE_OK_0_8;
                break;
            case AMQP_0_9:
                protocolHeader = (ProtocolEngineCreator_0_9.getInstance().getHeaderIdentifier());
                closeOk = ConnectionCloseOkBody.CONNECTION_CLOSE_OK_0_9;
                break;
            case AMQP_0_9_1:
                protocolHeader = (ProtocolEngineCreator_0_9_1.getInstance().getHeaderIdentifier());
                closeOk = ConnectionCloseOkBody.CONNECTION_CLOSE_OK_0_9;
                break;
            default:
                throw new RuntimeException("Unexpected Protocol Version: " + protocol);
        }
        os.write(protocolHeader);
        InputStream is = socket.getInputStream();

        final byte[] response = new byte[2+GUEST_USERNAME.length()+GUEST_PASSWORD.length()];
        int i = 1;
        for(byte b : GUEST_USERNAME.getBytes(StandardCharsets.US_ASCII))
        {
            response[i++] = b;
        }
        i++;
        for(byte b : GUEST_PASSWORD.getBytes(StandardCharsets.US_ASCII))
        {
            response[i++] = b;
        }

        ConnectionStartOkBody startOK = new ConnectionStartOkBody(new FieldTable(), AMQShortString.valueOf("PLAIN"), response, AMQShortString.valueOf("en_US"));
        TestSender sender = new TestSender(os);
        new AMQFrame(0, startOK).writePayload(sender);
        sender.flush();
        ConnectionTuneOkBody tuneOk = new ConnectionTuneOkBody(256, frameSize, 0);
        new AMQFrame(0, tuneOk).writePayload(sender);
        sender.flush();
        ConnectionOpenBody open = new ConnectionOpenBody(AMQShortString.valueOf(""),AMQShortString.EMPTY_STRING, false);

        try
        {
            new AMQFrame(0, open).writePayload(sender);
            sender.flush();

            socket.setSoTimeout(5000);
        }
        catch (IOException e)
        {
            // ignore - the broker may have closed the socket already
        }

        final FrameCreatingMethodProcessor methodProcessor = new FrameCreatingMethodProcessor(ProtocolVersion.v0_91);
        ClientDecoder decoder = new ClientDecoder(methodProcessor);


        byte[] buffer = new byte[1024];


        int size;
        while((size = is.read(buffer)) > 0)
        {
            decoder.decodeBuffer(ByteBuffer.wrap(buffer, 0, size));
            if(!evaluator.evaluate(socket,methodProcessor.getProcessedMethods()))
            {
                break;
            }
        }


        new AMQFrame(0, closeOk).writePayload(sender);
        sender.flush();


    }

    private static class TestClientDelegate extends ClientDelegate
    {

        private final int _maxFrameSize;

        public TestClientDelegate(final ConnectionSettings settings, final int maxFrameSize)
        {
            super(settings);
            _maxFrameSize = maxFrameSize;
        }

        @Override
        protected SaslClient createSaslClient(final List<Object> brokerMechs) throws ConnectionException, SaslException
        {
            final CallbackHandler handler = new CallbackHandler()
            {
                @Override
                public void handle(final Callback[] callbacks) throws IOException, UnsupportedCallbackException
                {
                    for (int i = 0; i < callbacks.length; i++)
                    {
                        Callback cb = callbacks[i];
                        if (cb instanceof NameCallback)
                        {
                            ((NameCallback)cb).setName(GUEST_USERNAME);
                        }
                        else if (cb instanceof PasswordCallback)
                        {
                            ((PasswordCallback)cb).setPassword(GUEST_PASSWORD.toCharArray());
                        }
                        else
                        {
                            throw new UnsupportedCallbackException(cb);
                        }
                    }

                }
            };
            String[] selectedMechs = {};
            for(String mech : new String[] { "ANONYMOUS", "PLAIN", "CRAM-MD5", "SCRAM-SHA-1", "SCRAM-SHA-256"})
            {
                if(brokerMechs.contains(mech))
                {
                    selectedMechs = new String[] {mech};
                    break;
                }
            }


            return Sasl.createSaslClient(selectedMechs,
                                         null,
                                         getConnectionSettings().getSaslProtocol(),
                                         getConnectionSettings().getSaslServerName(),
                                         Collections.<String,Object>emptyMap(),
                                         handler);

        }

        @Override
        public void connectionTune(Connection conn, ConnectionTune tune)
        {
            int heartbeatInterval = getConnectionSettings().getHeartbeatInterval010();
            float heartbeatTimeoutFactor = getConnectionSettings().getHeartbeatTimeoutFactor();
            int actualHeartbeatInterval = calculateHeartbeatInterval(heartbeatInterval,
                                                                     tune.getHeartbeatMin(),
                                                                     tune.getHeartbeatMax());

            conn.connectionTuneOk(tune.getChannelMax(),
                                  _maxFrameSize,
                                  actualHeartbeatInterval);

            conn.getNetworkConnection().setMaxReadIdleMillis((long)(1000L * actualHeartbeatInterval*heartbeatTimeoutFactor));
            conn.getNetworkConnection().setMaxWriteIdleMillis(1000L * actualHeartbeatInterval);
            conn.setMaxFrameSize(_maxFrameSize);

            int channelMax = tune.getChannelMax();
            conn.setChannelMax(channelMax == 0 ? Connection.MAX_CHANNEL_MAX : channelMax);

            conn.connectionOpen(getConnectionSettings().getVhost(), null, Option.INSIST);
        }

    }
}
