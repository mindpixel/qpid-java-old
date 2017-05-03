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

/*
 * This file is auto-generated by Qpid Gentools v.0.1 - do not modify.
 * Supported AMQP version:
 *   8-0
 */

package org.apache.qpid.framing;

import org.apache.qpid.QpidException;
import org.apache.qpid.bytebuffer.QpidByteBuffer;

public class BasicNackBody extends AMQMethodBodyImpl implements EncodableAMQDataBlock, AMQMethodBody
{

    public static final int CLASS_ID =  60;
    public static final int METHOD_ID = 120;

    // Fields declared in specification
    private final long _deliveryTag; // [deliveryTag]
    private final byte _bitfield0; // [multiple]

    public BasicNackBody(
            long deliveryTag,
            boolean multiple,
            boolean requeue
                        )
    {
        _deliveryTag = deliveryTag;
        byte bitfield0 = (byte)0;
        if( multiple )
        {
            bitfield0 = (byte) (((int) bitfield0) | 1);

        }
        if( requeue )
        {
            bitfield0 = (byte) (((int) bitfield0) | 2);
        }
        _bitfield0 = bitfield0;
    }

    public int getClazz()
    {
        return CLASS_ID;
    }

    public int getMethod()
    {
        return METHOD_ID;
    }

    public final long getDeliveryTag()
    {
        return _deliveryTag;
    }

    public final boolean getMultiple()
    {
        return (((int)(_bitfield0)) &  1) != 0;
    }

    public final boolean getRequeue()
    {
        return (((int)(_bitfield0)) &  2 ) != 0;
    }

    protected int getBodySize()
    {
        int size = 9;
        return size;
    }

    public void writeMethodPayload(QpidByteBuffer buffer)
    {
        writeLong( buffer, _deliveryTag );
        writeBitfield( buffer, _bitfield0 );
    }

    public boolean execute(MethodDispatcher dispatcher, int channelId) throws QpidException
	{
        return dispatcher.dispatchBasicNack(this, channelId);
	}

    public String toString()
    {
        StringBuilder buf = new StringBuilder("[BasicNackBodyImpl: ");
        buf.append( "deliveryTag=" );
        buf.append(  getDeliveryTag() );
        buf.append( ", " );
        buf.append( "multiple=" );
        buf.append(  getMultiple() );
        buf.append( ", " );
        buf.append( "requeue=" );
        buf.append(  getRequeue() );
        buf.append("]");
        return buf.toString();
    }

    public static void process(final QpidByteBuffer buffer,
                               final ChannelMethodProcessor dispatcher)
    {

        long deliveryTag = buffer.getLong();
        byte bitfield = buffer.get();
        boolean multiple = (bitfield & 0x01) != 0;
        boolean requeue = (bitfield & 0x02) != 0;
        if(!dispatcher.ignoreAllButCloseOk())
        {
            dispatcher.receiveBasicNack(deliveryTag, multiple, requeue);
        }
    }
}
