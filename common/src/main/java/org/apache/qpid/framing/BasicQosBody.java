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

public class BasicQosBody extends AMQMethodBodyImpl implements EncodableAMQDataBlock, AMQMethodBody
{

    public static final int CLASS_ID =  60;
    public static final int METHOD_ID = 10;

    // Fields declared in specification
    private final long _prefetchSize; // [prefetchSize]
    private final int _prefetchCount; // [prefetchCount]
    private final byte _bitfield0; // [global]

    public BasicQosBody(
            long prefetchSize,
            int prefetchCount,
            boolean global
                       )
    {
        _prefetchSize = prefetchSize;
        _prefetchCount = prefetchCount;
        byte bitfield0 = (byte)0;
        if( global )
        {
            bitfield0 = (byte) (((int) bitfield0) | (1 << 0));
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

    public final long getPrefetchSize()
    {
        return _prefetchSize;
    }
    public final int getPrefetchCount()
    {
        return _prefetchCount;
    }
    public final boolean getGlobal()
    {
        return (((int)(_bitfield0)) & ( 1 << 0)) != 0;
    }

    protected int getBodySize()
    {
        int size = 7;
        return size;
    }

    public void writeMethodPayload(QpidByteBuffer buffer)
    {
        writeUnsignedInteger( buffer, _prefetchSize );
        writeUnsignedShort( buffer, _prefetchCount );
        writeBitfield( buffer, _bitfield0 );
    }

    public boolean execute(MethodDispatcher dispatcher, int channelId) throws QpidException
	{
        return dispatcher.dispatchBasicQos(this, channelId);
	}

    public String toString()
    {
        StringBuilder buf = new StringBuilder("[BasicQosBodyImpl: ");
        buf.append( "prefetchSize=" );
        buf.append(  getPrefetchSize() );
        buf.append( ", " );
        buf.append( "prefetchCount=" );
        buf.append(  getPrefetchCount() );
        buf.append( ", " );
        buf.append( "global=" );
        buf.append(  getGlobal() );
        buf.append("]");
        return buf.toString();
    }

    public static void process(final QpidByteBuffer buffer,
                               final ServerChannelMethodProcessor dispatcher)
    {

        long prefetchSize = buffer.getUnsignedInt();
        int prefetchCount = buffer.getUnsignedShort();
        boolean global = (buffer.get() & 0x01) == 0x01;
        if(!dispatcher.ignoreAllButCloseOk())
        {
            dispatcher.receiveBasicQos(prefetchSize, prefetchCount, global);
        }
    }
}
