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
package org.apache.qpid.server.protocol.v0_10;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.message.internal.InternalMessageHeader;
import org.apache.qpid.server.model.NamedAddressSpace;
import org.apache.qpid.server.plugin.MessageConverter;
import org.apache.qpid.server.plugin.PluggableService;
import org.apache.qpid.server.store.StoredMessage;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;
import org.apache.qpid.transport.DeliveryProperties;
import org.apache.qpid.transport.Header;
import org.apache.qpid.transport.MessageDeliveryPriority;
import org.apache.qpid.transport.MessageProperties;
import org.apache.qpid.transport.codec.BBEncoder;

@PluggableService
public class MessageConverter_v0_10 implements MessageConverter<ServerMessage, MessageTransferMessage>
{

    @Override
    public Class<ServerMessage> getInputClass()
    {
        return ServerMessage.class;
    }

    @Override
    public Class<MessageTransferMessage> getOutputClass()
    {
        return MessageTransferMessage.class;
    }

    @Override
    public MessageTransferMessage convert(ServerMessage serverMsg, NamedAddressSpace addressSpace)
    {
        return new MessageTransferMessage(convertToStoredMessage(serverMsg), null);
    }

    @Override
    public void dispose(final MessageTransferMessage message)
    {

    }

    private StoredMessage<MessageMetaData_0_10> convertToStoredMessage(final ServerMessage<?> serverMsg)
    {
        final MessageMetaData_0_10 messageMetaData_0_10 = convertMetaData(serverMsg);

        return new StoredMessage<MessageMetaData_0_10>()
                {
                    @Override
                    public MessageMetaData_0_10 getMetaData()
                    {
                        return messageMetaData_0_10;
                    }

                    @Override
                    public long getMessageNumber()
                    {
                        return serverMsg.getMessageNumber();
                    }

                    @Override
                    public Collection<QpidByteBuffer> getContent(final int offset, final int length)
                    {
                        return serverMsg.getContent(offset, length);
                    }

                    @Override
                    public int getContentSize()
                    {
                        return messageMetaData_0_10.getContentSize();
                    }

                    @Override
                    public void remove()
                    {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean isInMemory()
                    {
                        return true;
                    }

                    @Override
                    public boolean flowToDisk()
                    {
                        return false;
                    }
        };
    }

    private MessageMetaData_0_10 convertMetaData(ServerMessage serverMsg)
    {
        DeliveryProperties deliveryProps = new DeliveryProperties();
        MessageProperties messageProps = new MessageProperties();

        int size = (int) serverMsg.getSize();

        deliveryProps.setExpiration(serverMsg.getExpiration());
        deliveryProps.setPriority(MessageDeliveryPriority.get(serverMsg.getMessageHeader().getPriority()));
        deliveryProps.setRoutingKey(serverMsg.getInitialRoutingAddress());
        deliveryProps.setTimestamp(serverMsg.getMessageHeader().getTimestamp());

        messageProps.setContentEncoding(serverMsg.getMessageHeader().getEncoding());
        messageProps.setContentLength(size);
        messageProps.setContentType(serverMsg.getMessageHeader().getMimeType());
        if(serverMsg.getMessageHeader().getCorrelationId() != null)
        {
            messageProps.setCorrelationId(serverMsg.getMessageHeader().getCorrelationId().getBytes());
        }

        Header header = new Header(deliveryProps, messageProps, null);
        return new MessageMetaData_0_10(header, size, serverMsg.getArrivalTime());
    }


    public static byte[] convertToBody(Object object)
    {
        if(object instanceof String)
        {
            return ((String)object).getBytes(StandardCharsets.UTF_8);
        }
        else if(object instanceof byte[])
        {
            return (byte[]) object;
        }
        else if(object instanceof Map)
        {
            BBEncoder encoder = new BBEncoder(1024);
            encoder.writeMap((Map)object);
            ByteBuffer buf = encoder.segment();
            int remaining = buf.remaining();
            byte[] data = new byte[remaining];
            buf.get(data);
            return data;

        }
        else if(object instanceof List)
        {
            BBEncoder encoder = new BBEncoder(1024);
            encoder.writeList((List) object);
            ByteBuffer buf = encoder.segment();
            int remaining = buf.remaining();
            byte[] data = new byte[remaining];
            buf.get(data);
            return data;
        }
        else
        {
            ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
            try
            {
                ObjectOutputStream os = new ObjectOutputStream(bytesOut);
                os.writeObject(object);
                return bytesOut.toByteArray();
            }
            catch (IOException e)
            {
                throw new ConnectionScopedRuntimeException(e);
            }
        }
    }

    public static String getBodyMimeType(Object object, final InternalMessageHeader header)
    {
        if(object instanceof String)
        {
            String mimeType;
            if(header == null || (mimeType = header.getMimeType()) == null || !mimeType.trim().startsWith("text/"))
            {
                return "text/plain";
            }
            else
            {
                return mimeType;
            }
        }
        else if(object instanceof byte[])
        {
            String mimeType;
            if(header == null || (mimeType = header.getMimeType()) == null || "".equals(mimeType.trim()))
            {
                return "application/octet-stream";
            }
            else
            {
                return mimeType;
            }
        }
        else if(object instanceof Map)
        {
            return "amqp/map";
        }
        else if(object instanceof List)
        {
            return "amqp/list";
        }
        else
        {
            return "application/java-object-stream";
        }
    }


    @Override
    public String getType()
    {
        return "Unknown to v0-10";
    }
}
