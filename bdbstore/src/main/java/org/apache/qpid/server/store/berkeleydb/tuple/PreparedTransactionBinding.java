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

package org.apache.qpid.server.store.berkeleydb.tuple;

import java.util.UUID;

import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.bind.tuple.TupleInput;
import com.sleepycat.bind.tuple.TupleOutput;
import org.apache.qpid.server.message.EnqueueableMessage;
import org.apache.qpid.server.store.MessageDurability;
import org.apache.qpid.server.store.MessageEnqueueRecord;
import org.apache.qpid.server.store.StoredMessage;
import org.apache.qpid.server.store.Transaction;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.store.berkeleydb.AbstractBDBMessageStore;
import org.apache.qpid.server.store.berkeleydb.entry.PreparedTransaction;

public class PreparedTransactionBinding extends TupleBinding<PreparedTransaction>
{
    @Override
    public PreparedTransaction entryToObject(TupleInput input)
    {
        Transaction.EnqueueRecord[] enqueues = readEnqueueRecords(input);

        Transaction.DequeueRecord[] dequeues = readDequeueRecords(input);

        return new PreparedTransaction(enqueues, dequeues);
    }

    private Transaction.EnqueueRecord[] readEnqueueRecords(TupleInput input)
    {
        Transaction.EnqueueRecord[] records = new Transaction.EnqueueRecord[input.readInt()];
        for(int i = 0; i < records.length; i++)
        {
            records[i] = new EnqueueRecordImpl(new UUID(input.readLong(), input.readLong()), input.readLong());
        }
        return records;
    }

    private Transaction.DequeueRecord[] readDequeueRecords(TupleInput input)
    {
        Transaction.DequeueRecord[] records = new Transaction.DequeueRecord[input.readInt()];
        for(int i = 0; i < records.length; i++)
        {
            records[i] = new DequeueRecordImpl(new UUID(input.readLong(), input.readLong()), input.readLong());
        }
        return records;
    }


    @Override
    public void objectToEntry(PreparedTransaction preparedTransaction, TupleOutput output)
    {
        writeRecords(preparedTransaction.getEnqueues(), output);
        writeRecords(preparedTransaction.getDequeues(), output);

    }

    private void writeRecords(Transaction.EnqueueRecord[] records, TupleOutput output)
    {
        if(records == null)
        {
            output.writeInt(0);
        }
        else
        {
            output.writeInt(records.length);
            for(Transaction.EnqueueRecord record : records)
            {
                UUID id = record.getResource().getId();
                output.writeLong(id.getMostSignificantBits());
                output.writeLong(id.getLeastSignificantBits());
                output.writeLong(record.getMessage().getMessageNumber());
            }
        }
    }

    private void writeRecords(Transaction.DequeueRecord[] records, TupleOutput output)
    {
        if(records == null)
        {
            output.writeInt(0);
        }
        else
        {
            output.writeInt(records.length);
            for(Transaction.DequeueRecord record : records)
            {
                UUID id = record.getEnqueueRecord().getQueueId();
                output.writeLong(id.getMostSignificantBits());
                output.writeLong(id.getLeastSignificantBits());
                output.writeLong(record.getEnqueueRecord().getMessageNumber());
            }
        }
    }

    private static class EnqueueRecordImpl implements Transaction.EnqueueRecord, TransactionLogResource, EnqueueableMessage
    {

        private long _messageNumber;
        private UUID _queueId;

        public EnqueueRecordImpl(UUID queueId, long messageNumber)
        {
            _messageNumber = messageNumber;
            _queueId = queueId;
        }

        public TransactionLogResource getResource()
        {
            return this;
        }

        public EnqueueableMessage getMessage()
        {
            return this;
        }

        public long getMessageNumber()
        {
            return _messageNumber;
        }

        public boolean isPersistent()
        {
            return true;
        }

        public StoredMessage<?> getStoredMessage()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName()
        {
            return _queueId.toString();
        }

        @Override
        public UUID getId()
        {
            return _queueId;
        }

        @Override
        public MessageDurability getMessageDurability()
        {
            return MessageDurability.DEFAULT;
        }
    }

    private static class DequeueRecordImpl implements Transaction.DequeueRecord
    {

        private final AbstractBDBMessageStore.BDBEnqueueRecord _record;

        public DequeueRecordImpl(final UUID queueId, final long messageNumber)
        {
            _record = new AbstractBDBMessageStore.BDBEnqueueRecord(queueId, messageNumber);
        }

        @Override
        public MessageEnqueueRecord getEnqueueRecord()
        {
            return _record;
        }
    }
}
