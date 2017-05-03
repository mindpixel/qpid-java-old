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
package org.apache.qpid.server.protocol.v1_0;

import org.apache.qpid.server.message.MessageDestination;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.protocol.v1_0.type.Outcome;

import org.apache.qpid.server.protocol.v1_0.type.Symbol;
import org.apache.qpid.server.security.SecurityToken;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.Action;

public interface ReceivingDestination extends Destination
{

    Symbol REJECT_UNROUTABLE = Symbol.valueOf("REJECT_UNROUTABLE");
    Symbol DISCARD_UNROUTABLE = Symbol.valueOf("DISCARD_UNROUTABLE");


    Outcome[] getOutcomes();

    Outcome send(ServerMessage<?> message,
                 final String routingAddress,
                 ServerTransaction txn,
                 final Action<MessageInstance> postEnqueueAction);

    int getCredit();

    String getRoutingAddress(Message_1_0 message);

    String getAddress();

    void authorizePublish(SecurityToken securityToken, final String routingAddress);

    MessageDestination getMessageDestination();
}
