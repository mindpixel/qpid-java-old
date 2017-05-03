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
package org.apache.qpid.server.protocol.converter.v0_8_v1_0;

import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.BasicContentHeaderProperties;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.server.plugin.PluggableService;
import org.apache.qpid.server.protocol.v0_8.AMQMessage;
import org.apache.qpid.server.protocol.v1_0.MessageConverter_to_1_0;
import org.apache.qpid.server.protocol.v1_0.MessageMetaData_1_0;
import org.apache.qpid.server.protocol.v1_0.messaging.SectionEncoder;
import org.apache.qpid.server.protocol.v1_0.type.Binary;
import org.apache.qpid.server.protocol.v1_0.type.Symbol;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedByte;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedInteger;
import org.apache.qpid.server.protocol.v1_0.type.messaging.ApplicationProperties;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Data;
import org.apache.qpid.server.protocol.v1_0.type.messaging.EncodingRetainingSection;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Header;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Properties;
import org.apache.qpid.url.AMQBindingURL;
import org.apache.qpid.util.GZIPUtils;

@PluggableService
public class MessageConverter_0_8_to_1_0 extends MessageConverter_to_1_0<AMQMessage>
{
    @Override
    public Class<AMQMessage> getInputClass()
    {
        return AMQMessage.class;
    }

    protected MessageMetaData_1_0 convertMetaData(final AMQMessage serverMessage,
                                                  final EncodingRetainingSection<?> bodySection,
                                                  SectionEncoder sectionEncoder)
    {
        Header header = new Header();

        header.setDurable(serverMessage.isPersistent());

        BasicContentHeaderProperties contentHeader =
                  serverMessage.getContentHeaderBody().getProperties();

        header.setPriority(UnsignedByte.valueOf(contentHeader.getPriority()));
        final long expiration = serverMessage.getExpiration();
        final long arrivalTime = serverMessage.getArrivalTime();

        if(expiration > arrivalTime)
        {
            header.setTtl(UnsignedInteger.valueOf(expiration - arrivalTime));
        }


        Properties props = new Properties();

        /*
            TODO: The following properties are not currently set:

            creationTime
            groupId
            groupSequence
            replyToGroupId
            to
        */

        if(!GZIPUtils.GZIP_CONTENT_ENCODING.equals(contentHeader.getEncodingAsString()) && bodySection instanceof Data)
        {
            props.setContentEncoding(Symbol.valueOf(contentHeader.getEncodingAsString()));
        }

        props.setContentType(Symbol.valueOf(contentHeader.getContentTypeAsString()));

        // Modify the content type when we are dealing with java object messages produced by the Qpid 0.x client
        if(props.getContentType() == Symbol.valueOf("application/java-object-stream"))
        {
            props.setContentType(Symbol.valueOf("application/x-java-serialized-object"));
        }

        final AMQShortString correlationId = contentHeader.getCorrelationId();
        if(correlationId != null)
        {
            props.setCorrelationId(new Binary(correlationId.getBytes()));
        }

        final AMQShortString messageId = contentHeader.getMessageId();
        if(messageId != null)
        {
            props.setMessageId(new Binary(messageId.getBytes()));
        }
        final String originalReplyTo = String.valueOf(contentHeader.getReplyTo());
        try
        {
            AMQBindingURL burl = new AMQBindingURL(originalReplyTo);
            String replyTo;

            if(burl.getExchangeName() != null && !burl.getExchangeName().equals(""))
            {
                replyTo = burl.getExchangeName();

                if(burl.getRoutingKey() != null)
                {
                    replyTo += "/" + burl.getRoutingKey();
                }

            }
            else if(burl.getQueueName() != null && !burl.getQueueName().equals(""))
            {
                replyTo = burl.getQueueName();
            }
            else if(burl.getRoutingKey() != null)
            {
                replyTo = burl.getRoutingKey();
            }
            else
            {
                replyTo = originalReplyTo;
            }

            props.setReplyTo(replyTo);
        }
        catch (URISyntaxException e)
        {
            props.setReplyTo(originalReplyTo);
        }



        props.setSubject(serverMessage.getInitialRoutingAddress());
        if(contentHeader.getUserId() != null)
        {
            props.setUserId(new Binary(contentHeader.getUserId().getBytes()));
        }

        Map<String, Object> applicationPropertiesMap = FieldTable.convertToMap(contentHeader.getHeaders());

        if(applicationPropertiesMap.containsKey("qpid.subject"))
        {
            props.setSubject(String.valueOf(applicationPropertiesMap.get("qpid.subject")));
            applicationPropertiesMap = new LinkedHashMap<>(applicationPropertiesMap);
            applicationPropertiesMap.remove("qpid.subject");
        }
        // TODO: http://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-messaging-v1.0-os.html#type-application
        // Adhere to "the values are restricted to be of simple types only, that is, excluding map, list, and array types".
        // 0-8..0-91 for instance supported field tables with maps as values.
        final ApplicationProperties applicationProperties = new ApplicationProperties(applicationPropertiesMap);

        return new MessageMetaData_1_0(header.createEncodingRetainingSection(sectionEncoder),
                                       null,
                                       null,
                                       props.createEncodingRetainingSection(sectionEncoder),
                                       applicationProperties.createEncodingRetainingSection(sectionEncoder),
                                       null,
                                       serverMessage.getArrivalTime(),
                                       bodySection.getEncodedSize());
    }

    @Override
    public String getType()
    {
        return "v0-8 to v1-0";
    }
}
