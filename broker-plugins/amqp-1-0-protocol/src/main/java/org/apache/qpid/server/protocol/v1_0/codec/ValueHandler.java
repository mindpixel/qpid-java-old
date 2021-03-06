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
package org.apache.qpid.server.protocol.v1_0.codec;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.v1_0.type.AmqpErrorException;
import org.apache.qpid.server.protocol.v1_0.type.transport.AmqpError;
import org.apache.qpid.server.protocol.v1_0.type.transport.ConnectionError;

public class ValueHandler implements DescribedTypeConstructorRegistry.Source
{
    private static final byte DESCRIBED_TYPE = (byte)0;

    private final DescribedTypeConstructorRegistry _describedTypeConstructorRegistry;


    private static final TypeConstructor[][] TYPE_CONSTRUCTORS =
            {
                    {},
                    {},
                    {},
                    {},
                    { NullTypeConstructor.getInstance(),   BooleanConstructor.getTrueInstance(),
                      BooleanConstructor.getFalseInstance(), ZeroUIntConstructor.getInstance(),
                      ZeroULongConstructor.getInstance(),  ZeroListConstructor.getInstance()       },
                    { UByteTypeConstructor.getInstance(),  ByteTypeConstructor.getInstance(),
                      SmallUIntConstructor.getInstance(),  SmallULongConstructor.getInstance(),
                      SmallIntConstructor.getInstance(),   SmallLongConstructor.getInstance(),
                      BooleanConstructor.getByteInstance()},
                    { UShortTypeConstructor.getInstance(), ShortTypeConstructor.getInstance()      },
                    { UIntTypeConstructor.getInstance(),   IntTypeConstructor.getInstance(),
                      FloatTypeConstructor.getInstance(),  CharTypeConstructor.getInstance(),
                      DecimalConstructor.getDecimal32Instance()},
                    { ULongTypeConstructor.getInstance(),  LongTypeConstructor.getInstance(),
                      DoubleTypeConstructor.getInstance(), TimestampTypeConstructor.getInstance(),
                      DecimalConstructor.getDecimal64Instance()},
                    { null,                                null,
                      null,                                null,
                      DecimalConstructor.getDecimal128Instance(), null,
                      null,                                null,
                      UUIDTypeConstructor.getInstance()                                            },
                    { BinaryTypeConstructor.getInstance(1),
                      StringTypeConstructor.getInstance(1, Charset.forName("UTF8")),
                      StringTypeConstructor.getInstance(1, Charset.forName("UTF16")),
                      SymbolTypeConstructor.getInstance(1)                                         },
                    { BinaryTypeConstructor.getInstance(4),
                      StringTypeConstructor.getInstance(4, Charset.forName("UTF8")),
                      StringTypeConstructor.getInstance(4, Charset.forName("UTF16")),
                      SymbolTypeConstructor.getInstance(4)                                         },
                    { CompoundTypeConstructor.getInstance(1, CompoundTypeConstructor.LIST_ASSEMBLER_FACTORY),
                      CompoundTypeConstructor.getInstance(1, CompoundTypeConstructor.MAP_ASSEMBLER_FACTORY)  },
                    { CompoundTypeConstructor.getInstance(4, CompoundTypeConstructor.LIST_ASSEMBLER_FACTORY),
                      CompoundTypeConstructor.getInstance(4, CompoundTypeConstructor.MAP_ASSEMBLER_FACTORY)  },
                    {
                      ArrayTypeConstructor.getOneByteSizeTypeConstructor()
                    },
                    {
                      ArrayTypeConstructor.getFourByteSizeTypeConstructor()
                    }
            };


    public ValueHandler(DescribedTypeConstructorRegistry registry)
    {
        _describedTypeConstructorRegistry = registry;
    }


    public Object parse(QpidByteBuffer in) throws AmqpErrorException
    {
        return parse(new ArrayList<>(Arrays.asList(in)));
    }
    public Object parse(final List<QpidByteBuffer> in) throws AmqpErrorException
    {
        TypeConstructor constructor = readConstructor(in);
        return constructor.construct(in, this);
    }


    public TypeConstructor readConstructor(List<QpidByteBuffer> in) throws AmqpErrorException
    {
        if(!QpidByteBufferUtils.hasRemaining(in))
        {
            throw new AmqpErrorException(AmqpError.DECODE_ERROR, "Insufficient data - expected type, no data remaining");
        }
        int firstBufferWithAvailable = 0;
        if(in.size() > 1)
        {
            for(int i = 0; i < in.size(); i++)
            {
                if(in.get(i).hasRemaining())
                {
                    firstBufferWithAvailable = i;
                    break;
                }
            }
        }
        byte formatCode = QpidByteBufferUtils.get(in);

        if(formatCode == DESCRIBED_TYPE)
        {
            int[] originalPositions = new int[in.size()-firstBufferWithAvailable];

            for(int i = firstBufferWithAvailable; i < in.size(); i++)
            {
                int position = in.get(i).position();
                if(i==firstBufferWithAvailable)
                {
                    position--;
                }
                originalPositions[i] = position;
            }

            Object descriptor = parse(in);
            DescribedTypeConstructor describedTypeConstructor = _describedTypeConstructorRegistry.getConstructor(descriptor);
            if(describedTypeConstructor==null)
            {
                describedTypeConstructor=new DefaultDescribedTypeConstructor(descriptor);
            }

            return describedTypeConstructor.construct(descriptor, in, originalPositions, this);

        }
        else
        {
            int subCategory = (formatCode >> 4) & 0x0F;
            int subtype =  formatCode & 0x0F;

            TypeConstructor tc;
            try
            {
                tc = TYPE_CONSTRUCTORS[subCategory][subtype];
            }
            catch(IndexOutOfBoundsException e)
            {
                tc = null;
            }

            if(tc == null)
            {
                throw new AmqpErrorException(ConnectionError.FRAMING_ERROR,"Unknown type format-code 0x%02x", formatCode);
            }

            return tc;
        }
    }


    @Override
    public String toString()
    {
        return "ValueHandler{" +
              ", _describedTypeConstructorRegistry=" + _describedTypeConstructorRegistry +
               '}';
    }


    public DescribedTypeConstructorRegistry getDescribedTypeRegistry()
    {
        return _describedTypeConstructorRegistry;
    }


}
