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

import static org.apache.qpid.server.logging.subjects.LogSubjectFormat.CHANNEL_FORMAT;

import java.security.AccessControlContext;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.Subject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.connection.SessionPrincipal;
import org.apache.qpid.server.consumer.ScheduledConsumerTargetSet;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.LogMessage;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.logging.messages.ChannelMessages;
import org.apache.qpid.server.logging.subjects.ChannelLogSubject;
import org.apache.qpid.server.message.MessageDestination;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.MessageInstanceConsumer;
import org.apache.qpid.server.message.MessageSource;
import org.apache.qpid.server.model.AbstractConfigurationChangeListener;
import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.ConfigurationChangeListener;
import org.apache.qpid.server.model.ConfiguredObject;
import org.apache.qpid.server.model.Consumer;
import org.apache.qpid.server.model.Exchange;
import org.apache.qpid.server.model.NamedAddressSpace;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.model.Session;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.protocol.CapacityChecker;
import org.apache.qpid.server.protocol.ConsumerListener;
import org.apache.qpid.server.protocol.LinkRegistry;
import org.apache.qpid.server.protocol.v1_0.codec.QpidByteBufferUtils;
import org.apache.qpid.server.protocol.v1_0.framing.OversizeFrameException;
import org.apache.qpid.server.protocol.v1_0.type.AmqpErrorException;
import org.apache.qpid.server.protocol.v1_0.type.Binary;
import org.apache.qpid.server.protocol.v1_0.type.DeliveryState;
import org.apache.qpid.server.protocol.v1_0.type.ErrorCondition;
import org.apache.qpid.server.protocol.v1_0.type.FrameBody;
import org.apache.qpid.server.protocol.v1_0.type.LifetimePolicy;
import org.apache.qpid.server.protocol.v1_0.type.Symbol;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedInteger;
import org.apache.qpid.server.protocol.v1_0.type.messaging.DeleteOnClose;
import org.apache.qpid.server.protocol.v1_0.type.messaging.DeleteOnNoLinks;
import org.apache.qpid.server.protocol.v1_0.type.messaging.DeleteOnNoLinksOrMessages;
import org.apache.qpid.server.protocol.v1_0.type.messaging.DeleteOnNoMessages;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Source;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Target;
import org.apache.qpid.server.protocol.v1_0.type.messaging.TerminusDurability;
import org.apache.qpid.server.protocol.v1_0.type.transaction.Coordinator;
import org.apache.qpid.server.protocol.v1_0.type.transaction.TxnCapability;
import org.apache.qpid.server.protocol.v1_0.type.transport.AmqpError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Attach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Begin;
import org.apache.qpid.server.protocol.v1_0.type.transport.ConnectionError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Detach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Disposition;
import org.apache.qpid.server.protocol.v1_0.type.transport.End;
import org.apache.qpid.server.protocol.v1_0.type.transport.Error;
import org.apache.qpid.server.protocol.v1_0.type.transport.Flow;
import org.apache.qpid.server.protocol.v1_0.type.transport.LinkError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Role;
import org.apache.qpid.server.protocol.v1_0.type.transport.Transfer;
import org.apache.qpid.server.security.SecurityToken;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.transport.AMQPConnection;
import org.apache.qpid.server.txn.AutoCommitTransaction;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;
import org.apache.qpid.transport.network.Ticker;

public class Session_1_0 implements AMQSessionModel<Session_1_0, ConsumerTarget_1_0>, LogSubject
{
    public static final Symbol DELAYED_DELIVERY = Symbol.valueOf("DELAYED_DELIVERY");
    private static final Logger _logger = LoggerFactory.getLogger(Session_1_0.class);
    private static final Symbol LIFETIME_POLICY = Symbol.valueOf("lifetime-policy");
    private static final EnumSet<SessionState> END_STATES =
            EnumSet.of(SessionState.END_RECVD, SessionState.END_PIPE, SessionState.END_SENT, SessionState.ENDED);
    private final AccessControlContext _accessControllerContext;
    private final SecurityToken _securityToken;
    private final ChannelLogSubject _logSubject;
    private AutoCommitTransaction _transaction;

    private final LinkedHashMap<Integer, ServerTransaction> _openTransactions =
            new LinkedHashMap<Integer, ServerTransaction>();

    private final CopyOnWriteArrayList<Action<? super Session_1_0>> _taskList =
            new CopyOnWriteArrayList<Action<? super Session_1_0>>();

    private final AMQPConnection_1_0 _connection;
    private UUID _id = UUID.randomUUID();
    private AtomicBoolean _closed = new AtomicBoolean();
    private final Subject _subject = new Subject();

    private final CopyOnWriteArrayList<Consumer<?, ConsumerTarget_1_0>> _consumers = new CopyOnWriteArrayList<>();

    private final ConfigurationChangeListener _consumerClosedListener = new ConsumerClosedListener();
    private final CopyOnWriteArrayList<ConsumerListener> _consumerListeners = new CopyOnWriteArrayList<ConsumerListener>();
    private Session<?> _modelObject;
    private final Set<ConsumerTarget_1_0> _consumersWithPendingWork = new ScheduledConsumerTargetSet<>();
    private Iterator<ConsumerTarget_1_0> _processPendingIterator;

    private SessionState _state;

    private final Map<String, SendingLinkEndpoint> _sendingLinkMap = new HashMap<>();
    private final Map<String, ReceivingLinkEndpoint> _receivingLinkMap = new HashMap<>();
    private final Map<LinkEndpoint, UnsignedInteger> _localLinkEndpoints = new HashMap<>();
    private final Map<UnsignedInteger, LinkEndpoint> _remoteLinkEndpoints = new HashMap<>();
    private long _lastAttachedTime;

    private short _receivingChannel;
    private short _sendingChannel = -1;

    private final CapacityCheckAction _capacityCheckAction = new CapacityCheckAction();


    // has to be a power of two
    private static final int DEFAULT_SESSION_BUFFER_SIZE = 1 << 11;
    private static final int BUFFER_SIZE_MASK = DEFAULT_SESSION_BUFFER_SIZE - 1;



    private int _nextOutgoingDeliveryId;

    private UnsignedInteger _outgoingSessionCredit;
    private UnsignedInteger _initialOutgoingId = UnsignedInteger.valueOf(0);
    private SequenceNumber _nextIncomingTransferId;
    private SequenceNumber _nextOutgoingTransferId = new SequenceNumber(_initialOutgoingId.intValue());

    private LinkedHashMap<UnsignedInteger,Delivery> _outgoingUnsettled = new LinkedHashMap<>(DEFAULT_SESSION_BUFFER_SIZE);
    private LinkedHashMap<UnsignedInteger,Delivery> _incomingUnsettled = new LinkedHashMap<>(DEFAULT_SESSION_BUFFER_SIZE);

    private int _availableIncomingCredit = DEFAULT_SESSION_BUFFER_SIZE;
    private int _availableOutgoingCredit = DEFAULT_SESSION_BUFFER_SIZE;
    private UnsignedInteger _lastSentIncomingLimit;

    private final Error _sessionEndedLinkError =
            new Error(LinkError.DETACH_FORCED,
                      "Force detach the link because the session is remotely ended.");

    private final String _primaryDomain;
    private final Set<Object> _blockingEntities = Collections.newSetFromMap(new ConcurrentHashMap<Object,Boolean>());
    private volatile long _startedTransactions;
    private volatile long _committedTransactions;
    private volatile long _rolledBackTransactions;
    private volatile int _unacknowledgedMessages;


    public Session_1_0(final AMQPConnection_1_0 connection)
    {
        this(connection, SessionState.INACTIVE, null);
    }

    public Session_1_0(final AMQPConnection_1_0 connection, Begin begin)
    {
        this(connection, SessionState.BEGIN_RECVD, new SequenceNumber(begin.getNextOutgoingId().intValue()));
    }


    private Session_1_0(final AMQPConnection_1_0 connection, SessionState state, SequenceNumber nextIncomingId)
    {

        _state = state;
        _nextIncomingTransferId = nextIncomingId;
        _connection = connection;
        _subject.getPrincipals().addAll(connection.getSubject().getPrincipals());
        _subject.getPrincipals().add(new SessionPrincipal(this));
        _accessControllerContext = connection.getAccessControlContextFromSubject(_subject);
        _securityToken = connection.getAddressSpace() instanceof ConfiguredObject
                ? ((ConfiguredObject)connection.getAddressSpace()).newToken(_subject)
                : connection.getBroker().newToken(_subject);
        _logSubject = new ChannelLogSubject(this);
        _primaryDomain = getPrimaryDomain();
    }

    public void setReceivingChannel(final short receivingChannel)
    {
        _receivingChannel = receivingChannel;
        _logSubject.updateSessionDetails();
        switch(_state)
        {
            case INACTIVE:
                _state = SessionState.BEGIN_RECVD;
                break;
            case BEGIN_SENT:
                _state = SessionState.ACTIVE;
                break;
            case END_PIPE:
                _state = SessionState.END_SENT;
                break;
            default:
                // TODO error

        }
    }

    public void sendDetach(final Detach detach)
    {
        send(detach);
    }

    public void receiveAttach(final Attach attach)
    {
        if(_state == SessionState.ACTIVE)
        {
            UnsignedInteger handle = attach.getHandle();
            if(_remoteLinkEndpoints.containsKey(handle))
            {
                // TODO - Error - handle busy?
            }
            else
            {
                Map<String, ? extends LinkEndpoint> linkMap =
                        attach.getRole() == Role.RECEIVER ? _sendingLinkMap : _receivingLinkMap;
                LinkEndpoint endpoint = linkMap.get(attach.getName());
                if(endpoint == null)
                {
                    endpoint = attach.getRole() == Role.RECEIVER
                               ? new SendingLinkEndpoint(this, attach)
                               : new ReceivingLinkEndpoint(this, attach);

                    if(_blockingEntities.contains(this) && attach.getRole() == Role.SENDER)
                    {
                        endpoint.setStopped(true);
                    }

                    // TODO : fix below - distinguish between local and remote owned
                    endpoint.setSource(attach.getSource());
                    endpoint.setTarget(attach.getTarget());
                    ((Map<String,LinkEndpoint>)linkMap).put(attach.getName(), endpoint);
                }
                else
                {
                    endpoint.receiveAttach(attach);
                }

                if(attach.getRole() == Role.SENDER)
                {
                    endpoint.setDeliveryCount(attach.getInitialDeliveryCount());
                }

                _remoteLinkEndpoints.put(handle, endpoint);

                if(!_localLinkEndpoints.containsKey(endpoint))
                {
                    UnsignedInteger localHandle = findNextAvailableHandle();
                    endpoint.setLocalHandle(localHandle);
                    _localLinkEndpoints.put(endpoint, localHandle);

                    remoteLinkCreation(endpoint);

                }
                else
                {
                    // TODO - error already attached
                }
            }
        }
    }

    public void updateDisposition(final Role role,
                                  final UnsignedInteger first,
                                  final UnsignedInteger last,
                                  final DeliveryState state, final boolean settled)
    {


        Disposition disposition = new Disposition();
        disposition.setRole(role);
        disposition.setFirst(first);
        disposition.setLast(last);
        disposition.setSettled(settled);

        disposition.setState(state);

        if (settled)
        {
            final LinkedHashMap<UnsignedInteger, Delivery> unsettled =
                    role == Role.RECEIVER ? _incomingUnsettled : _outgoingUnsettled;
            SequenceNumber pos = new SequenceNumber(first.intValue());
            SequenceNumber end = new SequenceNumber(last.intValue());
            while (pos.compareTo(end) <= 0)
            {
                unsettled.remove(new UnsignedInteger(pos.intValue()));
                pos.incr();
            }
        }

        send(disposition);
        //TODO - check send flow
    }

    public boolean hasCreditToSend()
    {
        boolean b = _outgoingSessionCredit != null && _outgoingSessionCredit.intValue() > 0;
        boolean b1 = getOutgoingWindowSize() != null && getOutgoingWindowSize().compareTo(UnsignedInteger.ZERO) > 0;
        return b && b1;
    }

    public void end()
    {
        end(new End());
    }

    public void sendTransfer(final Transfer xfr, final SendingLinkEndpoint endpoint, final boolean newDelivery)
    {
        _nextOutgoingTransferId.incr();
        UnsignedInteger deliveryId;
        final boolean settled = Boolean.TRUE.equals(xfr.getSettled());
        if (newDelivery)
        {
            deliveryId = UnsignedInteger.valueOf(_nextOutgoingDeliveryId++);
            endpoint.setLastDeliveryId(deliveryId);
            if (!settled)
            {
                final Delivery delivery = new Delivery(xfr, endpoint);
                _outgoingUnsettled.put(deliveryId, delivery);
                _outgoingSessionCredit = _outgoingSessionCredit.subtract(UnsignedInteger.ONE);
                endpoint.addUnsettled(delivery);
            }
        }
        else
        {
            deliveryId = endpoint.getLastDeliveryId();
            final Delivery delivery = _outgoingUnsettled.get(deliveryId);
            if (delivery != null)
            {
                if (!settled)
                {
                    delivery.addTransfer(xfr);
                    _outgoingSessionCredit = _outgoingSessionCredit.subtract(UnsignedInteger.ONE);
                }
                else
                {
                    _outgoingSessionCredit = _outgoingSessionCredit.add(new UnsignedInteger(delivery.getNumberOfTransfers()));
                    endpoint.settle(delivery.getDeliveryTag());
                    _outgoingUnsettled.remove(deliveryId);
                }
            }
        }
        xfr.setDeliveryId(deliveryId);

        try
        {
            List<QpidByteBuffer> payload = xfr.getPayload();
            final long remaining = QpidByteBufferUtils.remaining(payload);
            int payloadSent = _connection.sendFrame(_sendingChannel, xfr, payload);

            if(payload != null && payloadSent < remaining && payloadSent >= 0)
            {
                // TODO - should make this iterative and not recursive

                Transfer secondTransfer = new Transfer();

                secondTransfer.setDeliveryTag(xfr.getDeliveryTag());
                secondTransfer.setHandle(xfr.getHandle());
                secondTransfer.setSettled(xfr.getSettled());
                secondTransfer.setState(xfr.getState());
                secondTransfer.setMessageFormat(xfr.getMessageFormat());
                secondTransfer.setPayload(payload);

                sendTransfer(secondTransfer, endpoint, false);

                secondTransfer.dispose();
            }

            if (payload != null)
            {
                for (QpidByteBuffer buf : payload)
                {
                    buf.dispose();
                }
            }
        }
        catch (OversizeFrameException e)
        {
            throw new ConnectionScopedRuntimeException(e);
        }
    }

    public boolean isActive()
    {
        return _state == SessionState.ACTIVE;
    }

    public void receiveEnd(final End end)
    {
        switch (_state)
        {
            case END_SENT:
                _state = SessionState.ENDED;
                break;
            case ACTIVE:
                detachLinks();
                remoteEnd(end);
                short sendChannel = _sendingChannel;
                _connection.sendEnd(sendChannel, new End(), true);
                _state = SessionState.ENDED;
                break;
            default:
                sendChannel = _sendingChannel;
                End reply = new End();
                Error error = new Error();
                error.setCondition(AmqpError.ILLEGAL_STATE);
                error.setDescription("END called on Session which has not been opened");
                reply.setError(error);
                _connection.sendEnd(sendChannel, reply, true);
                break;
        }
    }

    public UnsignedInteger getNextOutgoingId()
    {
        return UnsignedInteger.valueOf(_nextOutgoingTransferId.intValue());
    }

    public void sendFlowConditional()
    {
        if(_nextIncomingTransferId != null)
        {
            UnsignedInteger clientsCredit =
                    _lastSentIncomingLimit.subtract(UnsignedInteger.valueOf(_nextIncomingTransferId.intValue()));
            int i = UnsignedInteger.valueOf(_availableIncomingCredit).subtract(clientsCredit).compareTo(clientsCredit);
            if (i >= 0)
            {
                sendFlow();
            }
        }

    }

    public UnsignedInteger getOutgoingWindowSize()
    {
        return UnsignedInteger.valueOf(_availableOutgoingCredit);
    }

    public void receiveFlow(final Flow flow)
    {
        UnsignedInteger handle = flow.getHandle();
        final LinkEndpoint endpoint = handle == null ? null : _remoteLinkEndpoints.get(handle);

        final UnsignedInteger nextOutgoingId =
                flow.getNextIncomingId() == null ? _initialOutgoingId : flow.getNextIncomingId();
        int limit = (nextOutgoingId.intValue() + flow.getIncomingWindow().intValue());
        _outgoingSessionCredit = UnsignedInteger.valueOf(limit - _nextOutgoingTransferId.intValue());

        if (endpoint != null)
        {
            endpoint.receiveFlow(flow);
        }
        else
        {
            final Collection<LinkEndpoint> allLinkEndpoints = _remoteLinkEndpoints.values();
            for (LinkEndpoint le : allLinkEndpoints)
            {
                le.flowStateChanged();
            }
        }
    }

    public void setNextIncomingId(final UnsignedInteger nextIncomingId)
    {
        _nextIncomingTransferId = new SequenceNumber(nextIncomingId.intValue());

    }

    public void receiveDisposition(final Disposition disposition)
    {
        Role dispositionRole = disposition.getRole();

        LinkedHashMap<UnsignedInteger, Delivery> unsettledTransfers;

        if(dispositionRole == Role.RECEIVER)
        {
            unsettledTransfers = _outgoingUnsettled;
        }
        else
        {
            unsettledTransfers = _incomingUnsettled;

        }

        UnsignedInteger deliveryId = disposition.getFirst();
        UnsignedInteger last = disposition.getLast();
        if(last == null)
        {
            last = deliveryId;
        }


        while(deliveryId.compareTo(last)<=0)
        {

            Delivery delivery = unsettledTransfers.get(deliveryId);
            if(delivery != null)
            {
                delivery.getLinkEndpoint().receiveDeliveryState(delivery,
                                                                disposition.getState(),
                                                                disposition.getSettled());
                if (Boolean.TRUE.equals(disposition.getSettled()))
                {
                    unsettledTransfers.remove(deliveryId);
                }
            }
            deliveryId = deliveryId.add(UnsignedInteger.ONE);
        }
        if(Boolean.TRUE.equals(disposition.getSettled()))
        {
            //TODO - check send flow
        }

    }

    public SessionState getState()
    {
        return _state;
    }

    public void sendFlow()
    {
        sendFlow(new Flow());
    }

    public void setSendingChannel(final short sendingChannel)
    {
        _sendingChannel = sendingChannel;
        _logSubject.updateSessionDetails();
        switch(_state)
        {
            case INACTIVE:
                _state = SessionState.BEGIN_SENT;
                break;
            case BEGIN_RECVD:
                _state = SessionState.ACTIVE;
                break;
            default:
                // TODO error

        }

        AccessController.doPrivileged((new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                _connection.getEventLogger().message(ChannelMessages.CREATE());

                return null;
            }
        }), _accessControllerContext);
    }

    public void sendFlow(final Flow flow)
    {
        if(_nextIncomingTransferId != null)
        {
            final int nextIncomingId = _nextIncomingTransferId.intValue();
            flow.setNextIncomingId(UnsignedInteger.valueOf(nextIncomingId));
            _lastSentIncomingLimit = UnsignedInteger.valueOf(nextIncomingId + _availableIncomingCredit);
        }
        flow.setIncomingWindow(UnsignedInteger.valueOf(_availableIncomingCredit));

        flow.setNextOutgoingId(UnsignedInteger.valueOf(_nextOutgoingTransferId.intValue()));
        flow.setOutgoingWindow(UnsignedInteger.valueOf(_availableOutgoingCredit));
        send(flow);
    }

    public void setOutgoingSessionCredit(final UnsignedInteger outgoingSessionCredit)
    {
        _outgoingSessionCredit = outgoingSessionCredit;
    }

    public void receiveDetach(final Detach detach)
    {
        UnsignedInteger handle = detach.getHandle();
        detach(handle, detach);
    }

    public void sendAttach(final Attach attach)
    {
        send(attach);
    }

    private void send(final FrameBody frameBody)
    {
        _connection.sendFrame(_sendingChannel, frameBody);
    }

    public boolean isSyntheticError(final Error error)
    {
        return error == _sessionEndedLinkError;
    }

    public void end(final End end)
    {
        switch (_state)
        {
            case BEGIN_SENT:
                _connection.sendEnd(_sendingChannel, end, false);
                _state = SessionState.END_PIPE;
                break;
            case ACTIVE:
                detachLinks();
                short sendChannel = _sendingChannel;
                _connection.sendEnd(sendChannel, end, true);
                _state = SessionState.END_SENT;
                break;
            default:
                sendChannel = _sendingChannel;
                End reply = new End();
                Error error = new Error();
                error.setCondition(AmqpError.ILLEGAL_STATE);
                error.setDescription("END called on Session which has not been opened");
                reply.setError(error);
                _connection.sendEnd(sendChannel, reply, true);
                break;


        }
    }

    public void receiveTransfer(final Transfer transfer)
    {
        _nextIncomingTransferId.incr();

        UnsignedInteger handle = transfer.getHandle();


        LinkEndpoint linkEndpoint = _remoteLinkEndpoints.get(handle);

        if (linkEndpoint == null)
        {
            Error error = new Error();
            error.setCondition(AmqpError.ILLEGAL_STATE);
            error.setDescription("TRANSFER called on Session for link handle " + handle + " which is not attached");
            _connection.close(error);

        }
        else if(!(linkEndpoint instanceof ReceivingLinkEndpoint))
        {

            Error error = new Error();
            error.setCondition(ConnectionError.FRAMING_ERROR);
            error.setDescription("TRANSFER called on Session for link handle " + handle + " which is a sending ink not a receiving link");
            _connection.close(error);

        }
        else
        {
            ReceivingLinkEndpoint endpoint = ((ReceivingLinkEndpoint) linkEndpoint);

            UnsignedInteger deliveryId = transfer.getDeliveryId();
            if (deliveryId == null)
            {
                deliveryId = endpoint.getLastDeliveryId();
            }

            Delivery delivery = _incomingUnsettled.get(deliveryId);
            if (delivery == null)
            {
                delivery = new Delivery(transfer, endpoint);
                _incomingUnsettled.put(deliveryId, delivery);

                if (Boolean.TRUE.equals(transfer.getMore()))
                {
                    endpoint.setLastDeliveryId(transfer.getDeliveryId());
                }
            }
            else
            {
                if (delivery.getDeliveryId().equals(deliveryId))
                {
                    delivery.addTransfer(transfer);

                    if (!Boolean.TRUE.equals(transfer.getMore()))
                    {
                        endpoint.setLastDeliveryId(null);
                    }
                }
                else
                {
                    End reply = new End();

                    Error error = new Error();
                    error.setCondition(AmqpError.ILLEGAL_STATE);
                    error.setDescription("TRANSFER called on Session for link handle "
                                         + handle
                                         + " with incorrect delivery id "
                                         + transfer.getDeliveryId());
                    reply.setError(error);
                    _connection.sendEnd(_sendingChannel, reply, true);

                    return;

                }
            }

            Error error = endpoint.receiveTransfer(transfer, delivery);
            if(error != null)
            {
                endpoint.close(error);
            }
            if ((delivery.isComplete() && delivery.isSettled() || Boolean.TRUE.equals(transfer.getAborted())))
            {
                _incomingUnsettled.remove(deliveryId);
            }
        }
    }

    private Collection<LinkEndpoint> getLocalLinkEndpoints()
    {
        return new ArrayList<>(_localLinkEndpoints.keySet());
    }

    boolean isEnded()
    {
        return _state == SessionState.ENDED || _connection.isClosed();
    }

    UnsignedInteger getIncomingWindowSize()
    {
        return UnsignedInteger.valueOf(_availableIncomingCredit);
    }

    AccessControlContext getAccessControllerContext()
    {
        return _accessControllerContext;
    }

    public void remoteLinkCreation(final LinkEndpoint endpoint)
    {
        Destination destination;
        Link_1_0 link = null;
        Error error = null;

        final LinkRegistry linkRegistry = getAddressSpace().getLinkRegistry(getConnection().getRemoteContainerId());
        Set<Symbol> capabilities = new HashSet<>();

        if (endpoint.getRole() == Role.SENDER)
        {

            final SendingLink_1_0 previousLink =
                    (SendingLink_1_0) linkRegistry.getDurableSendingLink(endpoint.getName());

            if (previousLink == null)
            {

                Target target = (Target) endpoint.getTarget();
                Source source = (Source) endpoint.getSource();


                if (source != null)
                {
                    if (Boolean.TRUE.equals(source.getDynamic()))
                    {
                        MessageSource tempQueue = createDynamicSource(source.getDynamicNodeProperties());
                        source.setAddress(tempQueue.getName());
                    }
                    String addr = source.getAddress();
                    if (!addr.startsWith("/") && addr.contains("/"))
                    {
                        String[] parts = addr.split("/", 2);
                        Exchange<?> exchg = getExchange(parts[0]);
                        if (exchg != null)
                        {
                            ExchangeDestination exchangeDestination =
                                    new ExchangeDestination(exchg,
                                                            source.getDurable(),
                                                            source.getExpiryPolicy(),
                                                            parts[0],
                                                            target.getCapabilities());
                            exchangeDestination.setInitialRoutingAddress(parts[1]);
                            destination = exchangeDestination;
                            target.setCapabilities(exchangeDestination.getCapabilities());
                        }
                        else
                        {
                            endpoint.setSource(null);
                            destination = null;
                        }
                    }
                    else
                    {
                        MessageSource queue = getAddressSpace().getAttainedMessageSource(addr);
                        if (queue != null)
                        {
                            destination = new MessageSourceDestination(queue);
                        }
                        else
                        {
                            Exchange<?> exchg = getExchange(addr);
                            if (exchg != null)
                            {
                                ExchangeDestination exchangeDestination =
                                              new ExchangeDestination(exchg,
                                                                      source.getDurable(),
                                                                      source.getExpiryPolicy(),
                                                                      addr,
                                                                      target.getCapabilities());
                                destination = exchangeDestination;
                                target.setCapabilities(exchangeDestination.getCapabilities());
                            }
                            else
                            {
                                endpoint.setSource(null);
                                destination = null;
                            }
                        }
                    }

                }
                else
                {
                    destination = null;
                }

                if (destination != null)
                {
                    final SendingLinkEndpoint sendingLinkEndpoint = (SendingLinkEndpoint) endpoint;
                    try
                    {
                        final SendingLink_1_0 sendingLink =
                                new SendingLink_1_0(new SendingLinkAttachment(this, sendingLinkEndpoint),
                                                    getAddressSpace(),
                                                    (SendingDestination) destination
                                );

                        sendingLinkEndpoint.setLink(sendingLink);
                        registerConsumer(sendingLink);

                        link = sendingLink;
                        if (TerminusDurability.UNSETTLED_STATE.equals(source.getDurable())
                            || TerminusDurability.CONFIGURATION.equals(source.getDurable()))
                        {
                            linkRegistry.registerSendingLink(endpoint.getName(), sendingLink);
                        }
                    }
                    catch (AmqpErrorException e)
                    {
                        _logger.error("Error creating sending link", e);
                        destination = null;
                        sendingLinkEndpoint.setSource(null);
                        error = e.getError();
                    }
                }
            }
            else
            {
                Source newSource = (Source) endpoint.getSource();

                Source oldSource = (Source) previousLink.getEndpoint().getSource();
                final TerminusDurability newSourceDurable = newSource == null ? null : newSource.getDurable();
                if (newSourceDurable != null)
                {
                    oldSource.setDurable(newSourceDurable);
                    if (newSourceDurable.equals(TerminusDurability.NONE))
                    {
                        linkRegistry.unregisterSendingLink(endpoint.getName());
                    }
                }
                endpoint.setSource(oldSource);
                SendingLinkEndpoint sendingLinkEndpoint = (SendingLinkEndpoint) endpoint;
                previousLink.setLinkAttachment(new SendingLinkAttachment(this, sendingLinkEndpoint));
                sendingLinkEndpoint.setLink(previousLink);
                link = previousLink;
                endpoint.setLocalUnsettled(previousLink.getUnsettledOutcomeMap());
                registerConsumer(previousLink);

            }
        }
        else if (endpoint.getTarget() instanceof Coordinator)
        {
            Coordinator coordinator = (Coordinator) endpoint.getTarget();
            TxnCapability[] coordinatorCapabilities = coordinator.getCapabilities();
            boolean localTxn = false;
            boolean multiplePerSession = false;
            if (coordinatorCapabilities != null)
            {
                for (TxnCapability capability : coordinatorCapabilities)
                {
                    if (capability.equals(TxnCapability.LOCAL_TXN))
                    {
                        localTxn = true;
                    }
                    else if (capability.equals(TxnCapability.MULTI_TXNS_PER_SSN))
                    {
                        multiplePerSession = true;
                    }
                    else
                    {
                        error = new Error();
                        error.setCondition(AmqpError.NOT_IMPLEMENTED);
                        error.setDescription("Unsupported capability: " + capability);
                        break;
                    }
                }
            }

       /*         if(!localTxn)
                {
                    coordinatorCapabilities.add(TxnCapabilities.LOCAL_TXN);
                }*/

            final ReceivingLinkEndpoint receivingLinkEndpoint = (ReceivingLinkEndpoint) endpoint;
            final TxnCoordinatorReceivingLink_1_0 coordinatorLink =
                    new TxnCoordinatorReceivingLink_1_0(getAddressSpace(),
                                                        this,
                                                        receivingLinkEndpoint,
                                                        _openTransactions);
            receivingLinkEndpoint.setLink(coordinatorLink);
            link = coordinatorLink;
        }
        else // standard  (non-Coordinator) receiver
        {

            StandardReceivingLink_1_0 previousLink =
                    (StandardReceivingLink_1_0) linkRegistry.getDurableReceivingLink(endpoint.getName());

            if (previousLink == null)
            {

                Target target = (Target) endpoint.getTarget();

                if (target != null)
                {
                    if (Boolean.TRUE.equals(target.getDynamic()))
                    {

                        MessageDestination tempQueue = createDynamicDestination(target.getDynamicNodeProperties());
                        target.setAddress(tempQueue.getName());
                    }

                    String addr = target.getAddress();
                    if (addr == null || "".equals(addr.trim()))
                    {
                        MessageDestination messageDestination = getAddressSpace().getDefaultDestination();
                        destination = new NodeReceivingDestination(messageDestination, target.getDurable(),
                                                                   target.getExpiryPolicy(), "",
                                                                   target.getCapabilities(),
                                                                   _connection.getEventLogger());
                        target.setCapabilities(destination.getCapabilities());

                        if (_blockingEntities.contains(messageDestination))
                        {
                            endpoint.setStopped(true);
                        }
                    }
                    else if (!addr.startsWith("/") && addr.contains("/"))
                    {
                        String[] parts = addr.split("/", 2);
                        Exchange<?> exchange = getExchange(parts[0]);
                        if (exchange != null)
                        {
                            ExchangeDestination exchangeDestination =
                                    new ExchangeDestination(exchange,
                                                            target.getDurable(),
                                                            target.getExpiryPolicy(),
                                                            parts[0],
                                                            target.getCapabilities());

                            exchangeDestination.setInitialRoutingAddress(parts[1]);
                            target.setCapabilities(exchangeDestination.getCapabilities());
                            destination = exchangeDestination;
                        }
                        else
                        {
                            endpoint.setTarget(null);
                            destination = null;
                        }
                    }
                    else
                    {
                        MessageDestination messageDestination =
                                getAddressSpace().getAttainedMessageDestination(addr);
                        if (messageDestination != null)
                        {
                            destination =
                                    new NodeReceivingDestination(messageDestination,
                                                                 target.getDurable(),
                                                                 target.getExpiryPolicy(),
                                                                 addr,
                                                                 target.getCapabilities(),
                                                                 _connection.getEventLogger());
                            target.setCapabilities(destination.getCapabilities());
                        }
                        else
                        {
                            Queue<?> queue = getQueue(addr);
                            if (queue != null)
                            {

                                destination = new QueueDestination(queue, addr);
                            }
                            else
                            {
                                endpoint.setTarget(null);
                                destination = null;
                            }
                        }
                    }
                }
                else
                {
                    destination = null;
                }
                if (destination != null)
                {
                    final ReceivingDestination receivingDestination = (ReceivingDestination) destination;
                    MessageDestination messageDestination = receivingDestination.getMessageDestination();
                    if(!(messageDestination instanceof Queue) || ((Queue<?>)messageDestination).isHoldOnPublishEnabled())
                    {
                        capabilities.add(DELAYED_DELIVERY);
                    }
                    final ReceivingLinkEndpoint receivingLinkEndpoint = (ReceivingLinkEndpoint) endpoint;
                    final StandardReceivingLink_1_0 receivingLink =
                            new StandardReceivingLink_1_0(new ReceivingLinkAttachment(this, receivingLinkEndpoint),
                                                          getAddressSpace(),
                                                          receivingDestination);

                    receivingLinkEndpoint.setLink(receivingLink);
                    link = receivingLink;
                    if (TerminusDurability.UNSETTLED_STATE.equals(target.getDurable())
                        || TerminusDurability.CONFIGURATION.equals(target.getDurable()))
                    {
                        linkRegistry.registerReceivingLink(endpoint.getName(), receivingLink);
                    }
                }
            }
            else
            {
                ReceivingLinkEndpoint receivingLinkEndpoint = (ReceivingLinkEndpoint) endpoint;
                previousLink.setLinkAttachment(new ReceivingLinkAttachment(this, receivingLinkEndpoint));
                receivingLinkEndpoint.setLink(previousLink);
                link = previousLink;
                endpoint.setLocalUnsettled(previousLink.getUnsettledOutcomeMap());
            }
        }


        endpoint.setCapabilities(capabilities);
        endpoint.attach();

        if (link == null)
        {
            if (error == null)
            {
                error = new Error();
                error.setCondition(AmqpError.NOT_FOUND);
            }
            endpoint.close(error);
        }
        else
        {
            link.start();
        }
    }


    private void registerConsumer(final SendingLink_1_0 link)
    {
        MessageInstanceConsumer consumer = link.getConsumer();
        if(consumer instanceof Consumer<?,?>)
        {
            Consumer<?,ConsumerTarget_1_0> modelConsumer = (Consumer<?,ConsumerTarget_1_0>) consumer;
            _consumers.add(modelConsumer);
            modelConsumer.addChangeListener(_consumerClosedListener);
            consumerAdded(modelConsumer);
        }
    }


    private MessageSource createDynamicSource(Map properties)
    {
        final String queueName = _primaryDomain + "TempQueue" + UUID.randomUUID().toString();
        MessageSource queue = null;
        try
        {
            Map<String, Object> attributes = convertDynamicNodePropertiesToAttributes(properties, queueName);



            queue = getAddressSpace().createMessageSource(MessageSource.class, attributes);
        }
        catch (AccessControlException e)
        {
            Error error = new Error();
            error.setCondition(AmqpError.UNAUTHORIZED_ACCESS);
            error.setDescription(e.getMessage());

            _connection.close(error);
        }
        catch (AbstractConfiguredObject.DuplicateNameException e)
        {
            _logger.error("A temporary queue was created with a name which collided with an existing queue name");
            throw new ConnectionScopedRuntimeException(e);
        }

        return queue;
    }


    private MessageDestination createDynamicDestination(Map properties)
    {
        final String queueName = _primaryDomain + "TempQueue" + UUID.randomUUID().toString();
        MessageDestination queue = null;
        try
        {
            Map<String, Object> attributes = convertDynamicNodePropertiesToAttributes(properties, queueName);



            queue = getAddressSpace().createMessageDestination(MessageDestination.class, attributes);
        }
        catch (AccessControlException e)
        {
            Error error = new Error();
            error.setCondition(AmqpError.UNAUTHORIZED_ACCESS);
            error.setDescription(e.getMessage());

            _connection.close(error);
        }
        catch (AbstractConfiguredObject.DuplicateNameException e)
        {
            _logger.error("A temporary queue was created with a name which collided with an existing queue name");
            throw new ConnectionScopedRuntimeException(e);
        }

        return queue;
    }

    private Map<String, Object> convertDynamicNodePropertiesToAttributes(final Map properties, final String queueName)
    {
        // TODO convert AMQP 1-0 node properties to queue attributes
        LifetimePolicy lifetimePolicy = properties == null
                                        ? null
                                        : (LifetimePolicy) properties.get(LIFETIME_POLICY);
        Map<String,Object> attributes = new HashMap<>();
        attributes.put(Queue.ID, UUID.randomUUID());
        attributes.put(Queue.NAME, queueName);
        attributes.put(Queue.DURABLE, false);

        if(lifetimePolicy instanceof DeleteOnNoLinks)
        {
            attributes.put(Queue.LIFETIME_POLICY,
                           org.apache.qpid.server.model.LifetimePolicy.DELETE_ON_NO_LINKS);
        }
        else if(lifetimePolicy instanceof DeleteOnNoLinksOrMessages)
        {
            attributes.put(Queue.LIFETIME_POLICY,
                           org.apache.qpid.server.model.LifetimePolicy.IN_USE);
        }
        else if(lifetimePolicy instanceof DeleteOnClose)
        {
            attributes.put(Queue.LIFETIME_POLICY,
                           org.apache.qpid.server.model.LifetimePolicy.DELETE_ON_CONNECTION_CLOSE);
        }
        else if(lifetimePolicy instanceof DeleteOnNoMessages)
        {
            attributes.put(Queue.LIFETIME_POLICY,
                           org.apache.qpid.server.model.LifetimePolicy.IN_USE);
        }
        else
        {
            attributes.put(Queue.LIFETIME_POLICY,
                           org.apache.qpid.server.model.LifetimePolicy.DELETE_ON_CONNECTION_CLOSE);
        }
        return attributes;
    }

    ServerTransaction getTransaction(Binary transactionId)
    {

        ServerTransaction transaction = _openTransactions.get(binaryToInteger(transactionId));
        if(transactionId == null)
        {
            if(_transaction == null)
            {
                _transaction = new AutoCommitTransaction(_connection.getAddressSpace().getMessageStore());
            }
            transaction = _transaction;
        }
        return transaction;
    }

    void remoteEnd(End end)
    {
        // TODO - if the end has a non empty error we should log it
        Iterator<Map.Entry<Integer, ServerTransaction>> iter = _openTransactions.entrySet().iterator();

        while(iter.hasNext())
        {
            Map.Entry<Integer, ServerTransaction> entry = iter.next();
            entry.getValue().rollback();
            iter.remove();
        }

        for(LinkEndpoint linkEndpoint : getLocalLinkEndpoints())
        {
            linkEndpoint.remoteDetached(new Detach());
        }

        _connection.sessionEnded(this);
        performCloseTasks();
        if(_modelObject != null)
        {
            _modelObject.delete();
        }

    }

    Integer binaryToInteger(final Binary txnId)
    {
        if(txnId == null)
        {
            return null;
        }

        byte[] data = txnId.getArray();
        if(data.length > 4)
        {
            throw new IllegalArgumentException();
        }

        int id = 0;
        for(int i = 0; i < data.length; i++)
        {
            id <<= 8;
            id |= ((int)data[i] & 0xff);
        }

        return id;

    }

    Binary integerToBinary(final int txnId)
    {
        byte[] data = new byte[4];
        data[3] = (byte) (txnId & 0xff);
        data[2] = (byte) ((txnId & 0xff00) >> 8);
        data[1] = (byte) ((txnId & 0xff0000) >> 16);
        data[0] = (byte) ((txnId & 0xff000000) >> 24);
        return new Binary(data);
    }

    @Override
    public UUID getId()
    {
        return _id;
    }

    @Override
    public AMQPConnection<?> getAMQPConnection()
    {
        return _connection;
    }

    @Override
    public void close()
    {
        performCloseTasks();
        end();
        if(_modelObject != null)
        {
            _modelObject.delete();
        }
    }

    private void performCloseTasks()
    {

        if(_closed.compareAndSet(false, true))
        {
            List<Action<? super Session_1_0>> taskList = new ArrayList<Action<? super Session_1_0>>(_taskList);
            _taskList.clear();
            for(Action<? super Session_1_0> task : taskList)
            {
                task.performAction(this);
            }
            getAMQPConnection().getEventLogger().message(_logSubject,ChannelMessages.CLOSE());
        }
    }


    public void close(ErrorCondition condition, String message)
    {
        performCloseTasks();
        final End end = new End();
        final Error theError = new Error();
        theError.setDescription(message);
        theError.setCondition(condition);
        end.setError(theError);
        end(end);
    }

    @Override
    public void transportStateChanged()
    {
        for(SendingLinkEndpoint endpoint : _sendingLinkMap.values())
        {
            Link_1_0 link = endpoint.getLink();
            ConsumerTarget_1_0 target = ((SendingLink_1_0)link).getConsumerTarget();
            target.flowStateChanged();
        }

        if (!_consumersWithPendingWork.isEmpty() && !getAMQPConnection().isTransportBlockedForWriting())
        {
            getAMQPConnection().notifyWork(this);
        }

    }

    @Override
    public LogSubject getLogSubject()
    {
        return this;
    }

    @Override
    public void block(final Queue<?> queue)
    {
        getAMQPConnection().doOnIOThreadAsync(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        doBlock(queue);
                    }
                });
    }

    private void doBlock(final Queue<?> queue)
    {
        if(_blockingEntities.add(queue))
        {
            messageWithSubject(ChannelMessages.FLOW_ENFORCED(queue.getName()));

            for (ReceivingLinkEndpoint endpoint : _receivingLinkMap.values())
            {
                StandardReceivingLink_1_0 link = (StandardReceivingLink_1_0) endpoint.getLink();

                if (isQueueDestinationForLink(queue, link.getDestination()))
                {
                    endpoint.setStopped(true);
                }
            }

        }
    }

    private boolean isQueueDestinationForLink(final Queue<?> queue, final ReceivingDestination recvDest)
    {
        return (recvDest instanceof NodeReceivingDestination
                && queue == ((NodeReceivingDestination) recvDest).getDestination())
               || recvDest instanceof QueueDestination && queue == ((QueueDestination) recvDest).getQueue();
    }

    @Override
    public void unblock(final Queue<?> queue)
    {
        getAMQPConnection().doOnIOThreadAsync(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        doUnblock(queue);
                    }
                });
    }

    private void doUnblock(final Queue<?> queue)
    {
        if(_blockingEntities.remove(queue) && !_blockingEntities.contains(this))
        {
            if(_blockingEntities.isEmpty())
            {
                messageWithSubject(ChannelMessages.FLOW_REMOVED());
            }
            for (ReceivingLinkEndpoint endpoint : _receivingLinkMap.values())
            {
                StandardReceivingLink_1_0 link = (StandardReceivingLink_1_0) endpoint.getLink();
                if (isQueueDestinationForLink(queue, link.getDestination()))
                {
                    endpoint.setStopped(false);
                }
            }
        }
    }

    @Override
    public void block()
    {
        getAMQPConnection().doOnIOThreadAsync(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        doBlock();
                    }
                });
    }

    private void doBlock()
    {
        if(_blockingEntities.add(this))
        {
            messageWithSubject(ChannelMessages.FLOW_ENFORCED("** All Queues **"));

            for(LinkEndpoint endpoint : _receivingLinkMap.values())
            {
                endpoint.setStopped(true);
            }
        }
    }



    @Override
    public void unblock()
    {
        getAMQPConnection().doOnIOThreadAsync(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        doUnblock();
                    }
                });
    }

    private void doUnblock()
    {
        if(_blockingEntities.remove(this))
        {
            if(_blockingEntities.isEmpty())
            {
                messageWithSubject(ChannelMessages.FLOW_REMOVED());
            }
            for(ReceivingLinkEndpoint endpoint : _receivingLinkMap.values())
            {
                StandardReceivingLink_1_0 link = (StandardReceivingLink_1_0) endpoint.getLink();
                if(!_blockingEntities.contains(link.getDestination()))
                {
                    endpoint.setStopped(false);
                }
            }
        }
    }

    @Override
    public boolean getBlocking()
    {
        return !_blockingEntities.isEmpty();
    }

    private void messageWithSubject(final LogMessage operationalLogMessage)
    {
        getEventLogger().message(_logSubject, operationalLogMessage);
    }

    public EventLogger getEventLogger()
    {
        return getConnection().getEventLogger();
    }

    @Override
    public Object getConnectionReference()
    {
        return getConnection().getReference();
    }

    @Override
    public int getUnacknowledgedMessageCount()
    {
        return _unacknowledgedMessages;
    }

    @Override
    public long getTxnStart()
    {
        return _startedTransactions;
    }

    @Override
    public long getTxnCommits()
    {
        return _committedTransactions;
    }

    @Override
    public long getTxnRejects()
    {
        return _rolledBackTransactions;
    }

    @Override
    public int getChannelId()
    {
        return _sendingChannel;
    }

    @Override
    public int getConsumerCount()
    {
        return getConsumers().size();
    }

    @Override
    public String toLogString()
    {
        final AMQPConnection<?> amqpConnection = getAMQPConnection();
        long connectionId = amqpConnection.getConnectionId();

        String remoteAddress = amqpConnection.getRemoteAddressString();
        final String authorizedPrincipal = amqpConnection.getAuthorizedPrincipal() == null ? "?" : amqpConnection.getAuthorizedPrincipal().getName();
        return "[" +
               MessageFormat.format(CHANNEL_FORMAT,
                                    connectionId,
                                    authorizedPrincipal,
                                    remoteAddress,
                                    getAddressSpace().getName(),
                                    _sendingChannel) + "] ";
    }

    @Override
    public int compareTo(AMQSessionModel o)
    {
        return getId().compareTo(o.getId());
    }

    public AMQPConnection_1_0 getConnection()
    {
        return _connection;
    }

    @Override
    public void addDeleteTask(final Action<? super Session_1_0> task)
    {
        if(!_closed.get())
        {
            _taskList.add(task);
        }
    }

    @Override
    public void removeDeleteTask(final Action<? super Session_1_0> task)
    {
        _taskList.remove(task);
    }

    public Subject getSubject()
    {
        return _subject;
    }

    private NamedAddressSpace getAddressSpace()
    {
        return _connection.getAddressSpace();
    }

    public SecurityToken getSecurityToken()
    {
        return _securityToken;
    }

    @Override
    public Collection<Consumer<?, ConsumerTarget_1_0>> getConsumers()
    {
        return Collections.unmodifiableCollection(_consumers);
    }

    @Override
    public void addConsumerListener(final ConsumerListener listener)
    {
        _consumerListeners.add(listener);
    }

    @Override
    public void removeConsumerListener(final ConsumerListener listener)
    {
        _consumerListeners.remove(listener);
    }

    @Override
    public void setModelObject(final Session<?> session)
    {
        _modelObject = session;
    }

    @Override
    public Session<?> getModelObject()
    {
        return _modelObject;
    }

    @Override
    public long getTransactionStartTime()
    {
        return 0L;
    }

    @Override
    public long getTransactionUpdateTime()
    {
        return 0L;
    }

    @Override
    public boolean processPending()
    {
        if (!getAMQPConnection().isIOThread() || END_STATES.contains(getState()))
        {
            return false;
        }


        if(!_consumersWithPendingWork.isEmpty() && !getAMQPConnection().isTransportBlockedForWriting())
        {
            if (_processPendingIterator == null || !_processPendingIterator.hasNext())
            {
                _processPendingIterator = _consumersWithPendingWork.iterator();
            }

            if(_processPendingIterator.hasNext())
            {
                ConsumerTarget_1_0 target = _processPendingIterator.next();
                _processPendingIterator.remove();
                if (target.processPending())
                {
                    _consumersWithPendingWork.add(target);
                }
            }
        }

        return !_consumersWithPendingWork.isEmpty() && !getAMQPConnection().isTransportBlockedForWriting();
    }

    @Override
    public void addTicker(final Ticker ticker)
    {
        getConnection().getAggregateTicker().addTicker(ticker);
        // trigger a wakeup to ensure the ticker will be taken into account
        getAMQPConnection().notifyWork();
    }

    @Override
    public void removeTicker(final Ticker ticker)
    {
        getConnection().getAggregateTicker().removeTicker(ticker);
    }

    @Override
    public void notifyWork(final ConsumerTarget_1_0 target)
    {
        if(_consumersWithPendingWork.add(target))
        {
            getAMQPConnection().notifyWork(this);
        }
    }

    @Override
    public void doTimeoutAction(final String reason)
    {
        getAMQPConnection().closeSessionAsync(this, AMQPConnection.CloseReason.TRANSACTION_TIMEOUT, reason);
    }

    private void consumerAdded(Consumer<?, ConsumerTarget_1_0> consumer)
    {
        for(ConsumerListener l : _consumerListeners)
        {
            l.consumerAdded(consumer);
        }
    }

    private void consumerRemoved(Consumer<?, ConsumerTarget_1_0> consumer)
    {
        for(ConsumerListener l : _consumerListeners)
        {
            l.consumerRemoved(consumer);
        }
    }

    void incrementStartedTransactions()
    {
        _startedTransactions++;
    }

    void incrementCommittedTransactions()
    {
        _committedTransactions++;
    }

    void incrementRolledBackTransactions()
    {
        _rolledBackTransactions++;
    }

    void incrementUnacknowledged()
    {
        _unacknowledgedMessages++;
    }

    void decrementUnacknowledged()
    {
        _unacknowledgedMessages--;
    }

    public CapacityCheckAction getCapacityCheckAction()
    {
        return _capacityCheckAction;
    }

    private class ConsumerClosedListener extends AbstractConfigurationChangeListener
    {
        @Override
        public void stateChanged(final ConfiguredObject object, final org.apache.qpid.server.model.State oldState, final org.apache.qpid.server.model.State newState)
        {
            if(newState == org.apache.qpid.server.model.State.DELETED)
            {
                consumerRemoved((Consumer<?, ConsumerTarget_1_0>)object);
            }
        }
    }

    @Override
    public String toString()
    {
        return "Session_1_0[" + _connection + ": " + _sendingChannel + ']';
    }


    private void detach(UnsignedInteger handle, Detach detach)
    {
        if(_remoteLinkEndpoints.containsKey(handle))
        {
            LinkEndpoint endpoint = _remoteLinkEndpoints.remove(handle);

            endpoint.remoteDetached(detach);

            _localLinkEndpoints.remove(endpoint);

            if (Boolean.TRUE.equals(detach.getClosed()))
            {
                Map<String, ? extends LinkEndpoint> linkMap = endpoint.getRole() == Role.SENDER ? _sendingLinkMap : _receivingLinkMap;
                linkMap.remove(endpoint.getName());
            }
        }
        else
        {
            // TODO
        }
    }

    private void detachLinks()
    {
        Collection<UnsignedInteger> handles = new ArrayList<UnsignedInteger>(_remoteLinkEndpoints.keySet());
        for(UnsignedInteger handle : handles)
        {
            Detach detach = new Detach();
            detach.setClosed(false);
            detach.setHandle(handle);
            detach.setError(_sessionEndedLinkError);
            detach(handle, detach);
        }

        final LinkRegistry linkRegistry = getAddressSpace().getLinkRegistry(getConnection().getRemoteContainerId());

        for(LinkEndpoint<?> linkEndpoint : _sendingLinkMap.values())
        {
            final SendingLink_1_0 link = (SendingLink_1_0) linkRegistry.getDurableSendingLink(linkEndpoint.getName());

            if (link != null)
            {
                synchronized (link)
                {
                    if (link.getEndpoint() == linkEndpoint)
                    {
                        link.setLinkAttachment(new SendingLinkAttachment(null, (SendingLinkEndpoint) linkEndpoint));
                    }
                }
            }
        }

        for(LinkEndpoint<?> linkEndpoint : _receivingLinkMap.values())
        {
            final StandardReceivingLink_1_0
                    link = (StandardReceivingLink_1_0) linkRegistry.getDurableReceivingLink(linkEndpoint.getName());

            if (link != null)
            {
                synchronized (link)
                {
                    if (link.getEndpoint() == linkEndpoint)
                    {
                        link.setLinkAttachment(new ReceivingLinkAttachment(null, (ReceivingLinkEndpoint) linkEndpoint));
                    }
                }
            }
        }
    }


    private UnsignedInteger findNextAvailableHandle()
    {
        int i = 0;
        do
        {
            if(!_localLinkEndpoints.containsValue(UnsignedInteger.valueOf(i)))
            {
                return UnsignedInteger.valueOf(i);
            }
        }
        while(++i != 0);

        // TODO
        throw new RuntimeException();
    }


    private Exchange<?> getExchange(String name)
    {
        MessageDestination destination = getAddressSpace().getAttainedMessageDestination(name);
        return destination instanceof Exchange ? (Exchange<?>) destination : null;
    }

    private Queue<?> getQueue(String name)
    {
        MessageSource source = getAddressSpace().getAttainedMessageSource(name);
        return source instanceof Queue ? (Queue<?>) source : null;
    }

    private String getPrimaryDomain()
    {
        String primaryDomain = "";
        final List<String> globalAddressDomains = getAddressSpace().getGlobalAddressDomains();
        if (globalAddressDomains != null && !globalAddressDomains.isEmpty())
        {
            primaryDomain = globalAddressDomains.get(0);
            if(primaryDomain != null)
            {
                primaryDomain = primaryDomain.trim();
                if(!primaryDomain.endsWith("/"))
                {
                    primaryDomain += "/";
                }
            }
        }
        return primaryDomain;
    }

    private final class CapacityCheckAction implements Action<MessageInstance>
    {
        @Override
        public void performAction(final MessageInstance entry)
        {
            TransactionLogResource queue = entry.getOwningResource();
            if(queue instanceof CapacityChecker)
            {
                ((CapacityChecker)queue).checkCapacity(Session_1_0.this);
            }
        }
    }
}
