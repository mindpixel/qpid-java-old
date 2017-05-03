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
package org.apache.qpid.server.protocol.v0_8;

import static org.apache.qpid.transport.util.Functions.hex;

import java.security.AccessControlContext;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.security.auth.Subject;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.AMQConnectionException;
import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.common.AMQPFilterTypes;
import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.framing.*;
import org.apache.qpid.protocol.ErrorCodes;
import org.apache.qpid.server.connection.SessionPrincipal;
import org.apache.qpid.server.consumer.ConsumerOption;
import org.apache.qpid.server.consumer.ConsumerTarget;
import org.apache.qpid.server.consumer.ScheduledConsumerTargetSet;
import org.apache.qpid.server.filter.AMQInvalidArgumentException;
import org.apache.qpid.server.filter.ArrivalTimeFilter;
import org.apache.qpid.server.filter.FilterManager;
import org.apache.qpid.server.filter.FilterManagerFactory;
import org.apache.qpid.server.filter.Filterable;
import org.apache.qpid.server.filter.MessageFilter;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.EventLoggerProvider;
import org.apache.qpid.server.logging.LogMessage;
import org.apache.qpid.server.logging.LogSubject;
import org.apache.qpid.server.logging.messages.ChannelMessages;
import org.apache.qpid.server.logging.messages.ExchangeMessages;
import org.apache.qpid.server.logging.subjects.ChannelLogSubject;
import org.apache.qpid.server.message.InstanceProperties;
import org.apache.qpid.server.message.MessageDestination;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.MessageInstanceConsumer;
import org.apache.qpid.server.message.MessageReference;
import org.apache.qpid.server.message.MessageSource;
import org.apache.qpid.server.message.RoutingResult;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.model.*;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.protocol.CapacityChecker;
import org.apache.qpid.server.protocol.ConsumerListener;
import org.apache.qpid.server.protocol.PublishAuthorisationCache;
import org.apache.qpid.server.protocol.v0_8.UnacknowledgedMessageMap.Visitor;
import org.apache.qpid.server.queue.QueueArgumentsConverter;
import org.apache.qpid.server.security.SecurityToken;
import org.apache.qpid.server.store.MessageHandle;
import org.apache.qpid.server.store.MessageStore;
import org.apache.qpid.server.store.StoredMessage;
import org.apache.qpid.server.store.TransactionLogResource;
import org.apache.qpid.server.transport.AMQPConnection;
import org.apache.qpid.server.txn.AsyncAutoCommitTransaction;
import org.apache.qpid.server.txn.LocalTransaction;
import org.apache.qpid.server.txn.LocalTransaction.ActivityTimeAccessor;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.ServerScopedRuntimeException;
import org.apache.qpid.server.virtualhost.ExchangeIsAlternateException;
import org.apache.qpid.server.virtualhost.RequiredExchangeException;
import org.apache.qpid.server.virtualhost.ReservedExchangeNameException;
import org.apache.qpid.transport.network.Ticker;

public class AMQChannel
        implements AMQSessionModel<AMQChannel, ConsumerTarget_0_8>,
                   AsyncAutoCommitTransaction.FutureRecorder,
                   ServerChannelMethodProcessor,
                   EventLoggerProvider, CreditRestorer
{
    public static final int DEFAULT_PREFETCH = 4096;

    private static final Logger _logger = LoggerFactory.getLogger(AMQChannel.class);
    private static final InfiniteCreditCreditManager INFINITE_CREDIT_CREDIT_MANAGER = new InfiniteCreditCreditManager();
    private static final Function<MessageConsumerAssociation, MessageInstance>
            MESSAGE_INSTANCE_FUNCTION = new Function<MessageConsumerAssociation, MessageInstance>()
    {
        @Override
        public MessageInstance apply(final MessageConsumerAssociation input)
        {
            return input.getMessageInstance();
        }
    };
    private final DefaultQueueAssociationClearingTask
            _defaultQueueAssociationClearingTask = new DefaultQueueAssociationClearingTask();

    private final int _channelId;


    private final Pre0_10CreditManager _creditManager;
    private final AccessControlContext _accessControllerContext;
    private final SecurityToken _token;

    private final PublishAuthorisationCache _publishAuthCahe;



    /**
     * The delivery tag is unique per channel. This is pre-incremented before putting into the deliver frame so that
     * value of this represents the <b>last</b> tag sent out
     */
    private long _deliveryTag = 0;

    /** A channel has a default queue (the last declared) that is used when no queue name is explicitly set */
    private volatile Queue<?> _defaultQueue;

    /** This tag is unique per subscription to a queue. The server returns this in response to a basic.consume request. */
    private int _consumerTag;

    /**
     * The current message - which may be partial in the sense that not all frames have been received yet - which has
     * been received by this channel. As the frames are received the message gets updated and once all frames have been
     * received the message can then be routed.
     */
    private IncomingMessage _currentMessage;

    /** Maps from consumer tag to subscription instance. Allows us to unsubscribe from a queue. */
    private final Map<AMQShortString, ConsumerTarget_0_8> _tag2SubscriptionTargetMap = new HashMap<AMQShortString, ConsumerTarget_0_8>();

    private final Set<ConsumerTarget_0_8> _consumersWithPendingWork = new ScheduledConsumerTargetSet<>();

    private Iterator<ConsumerTarget_0_8> _processPendingIterator;

    private final MessageStore _messageStore;

    private final LinkedList<AsyncCommand> _unfinishedCommandsQueue = new LinkedList<AsyncCommand>();

    private final UnacknowledgedMessageMap _unacknowledgedMessageMap;

    private final AtomicBoolean _suspended = new AtomicBoolean(false);

    private ServerTransaction _transaction;

    private final AtomicLong _txnStarts = new AtomicLong(0);
    private final AtomicLong _txnCommits = new AtomicLong(0);
    private final AtomicLong _txnRejects = new AtomicLong(0);
    private final AtomicLong _txnCount = new AtomicLong(0);

    private final AMQPConnection_0_8 _connection;
    private AtomicBoolean _closing = new AtomicBoolean(false);

    private final Set<Object> _blockingEntities = Collections.synchronizedSet(new HashSet<Object>());

    private final AtomicBoolean _blocking = new AtomicBoolean(false);


    private LogSubject _logSubject;
    private volatile boolean _rollingBack;

    private List<MessageConsumerAssociation> _resendList = new ArrayList<>();
    private static final
    AMQShortString IMMEDIATE_DELIVERY_REPLY_TEXT = new AMQShortString("Immediate delivery is not possible.");

    private final ClientDeliveryMethod _clientDeliveryMethod;

    private final UUID _id = UUID.randomUUID();

    private final List<Action<? super AMQChannel>> _taskList =
            new CopyOnWriteArrayList<Action<? super AMQChannel>>();


    private final CapacityCheckAction _capacityCheckAction = new CapacityCheckAction();
    private final ImmediateAction _immediateAction = new ImmediateAction();
    private final Subject _subject;
    private final CopyOnWriteArrayList<Consumer<?, ConsumerTarget_0_8>> _consumers = new CopyOnWriteArrayList<>();
    private final ConfigurationChangeListener _consumerClosedListener = new ConsumerClosedListener();
    private final CopyOnWriteArrayList<ConsumerListener> _consumerListeners = new CopyOnWriteArrayList<ConsumerListener>();
    private Session<?> _modelObject;
    private long _blockTime;
    private long _blockingTimeout;
    private boolean _confirmOnPublish;
    private long _confirmedMessageCounter;
    private volatile long _uncommittedMessageSize;
    private final List<StoredMessage<MessageMetaData>> _uncommittedMessages = new ArrayList<>();
    private long _maxUncommittedInMemorySize;

    private boolean _wireBlockingState;

    /** Flag recording if this channel has already written operational logging for prefetch size */
    private boolean _prefetchLoggedForChannel = false;

    /**
     * Handles special case where consumer is polling for messages using qos/flow. Avoids the per-message
     * production of channel flow and prefetch operational logging.
     */
    private boolean _logChannelFlowMessages = true;

    private final CachedFrame _txCommitOkFrame;
    private boolean _channelFlow = true;

    public AMQChannel(AMQPConnection_0_8 connection, int channelId, final MessageStore messageStore)
    {
        _creditManager = new Pre0_10CreditManager(0L, 0L,
                                                  connection.getContextValue(Long.class, AMQPConnection_0_8.HIGH_PREFETCH_LIMIT),
                                                  connection.getContextValue(Long.class, AMQPConnection_0_8.BATCH_LIMIT));
        _unacknowledgedMessageMap = new UnacknowledgedMessageMapImpl(DEFAULT_PREFETCH, this);
        _connection = connection;
        _channelId = channelId;

        _subject = new Subject(false, connection.getSubject().getPrincipals(),
                               connection.getSubject().getPublicCredentials(),
                               connection.getSubject().getPrivateCredentials());
        _subject.getPrincipals().add(new SessionPrincipal(this));

        _accessControllerContext = connection.getAccessControlContextFromSubject(_subject);
        _token = (_connection.getAddressSpace() instanceof ConfiguredObject)
                ? ((ConfiguredObject)_connection.getAddressSpace()).newToken(_subject)
                :_connection.getBroker().newToken(_subject);

        _maxUncommittedInMemorySize = connection.getContextProvider().getContextValue(Long.class, Connection.MAX_UNCOMMITTED_IN_MEMORY_SIZE);
        _logSubject = new ChannelLogSubject(this);
        _publishAuthCahe = new PublishAuthorisationCache(_token,
                                                         connection.getContextValue(Long.class, Session.PRODUCER_AUTH_CACHE_TIMEOUT),
                                                         connection.getContextValue(Integer.class, Session.PRODUCER_AUTH_CACHE_SIZE));
        _messageStore = messageStore;
        _blockingTimeout = connection.getBroker().getContextValue(Long.class,
                                                                  Broker.CHANNEL_FLOW_CONTROL_ENFORCEMENT_TIMEOUT);
        // by default the session is non-transactional
        _transaction = new AsyncAutoCommitTransaction(_messageStore, this);

        MethodRegistry methodRegistry = _connection.getMethodRegistry();
        AMQMethodBody responseBody = methodRegistry.createTxCommitOkBody();
        _txCommitOkFrame = new CachedFrame(responseBody.generateFrame(_channelId));

        _clientDeliveryMethod = connection.createDeliveryMethod(_channelId);

        AccessController.doPrivileged((new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                message(ChannelMessages.CREATE());

                return null;
            }
        }),_accessControllerContext);

    }

    @Override
    public void doTimeoutAction(String reason)
    {
        _connection.sendConnectionCloseAsync(AMQPConnection.CloseReason.TRANSACTION_TIMEOUT, reason);
    }

    private void message(final LogMessage message)
    {
        getEventLogger().message(message);
    }

    public AccessControlContext getAccessControllerContext()
    {
        return _accessControllerContext;
    }

    private boolean performGet(final MessageSource queue,
                               final boolean acks)
            throws MessageSource.ExistingConsumerPreventsExclusive,
                   MessageSource.ExistingExclusiveConsumer, MessageSource.ConsumerAccessRefused,
                   MessageSource.QueueDeleted
    {
        final GetDeliveryMethod getDeliveryMethod =
                new GetDeliveryMethod(queue);

        ConsumerTarget_0_8 target;
        EnumSet<ConsumerOption> options = EnumSet.of(ConsumerOption.TRANSIENT, ConsumerOption.ACQUIRES,
                                                     ConsumerOption.SEES_REQUEUES);
        if (acks)
        {

            target = ConsumerTarget_0_8.createGetAckTarget(this,
                                                           AMQShortString.EMPTY_STRING, null,
                                                           INFINITE_CREDIT_CREDIT_MANAGER, getDeliveryMethod);
        }
        else
        {
            target = ConsumerTarget_0_8.createGetNoAckTarget(this,
                                                             AMQShortString.EMPTY_STRING, null,
                                                             INFINITE_CREDIT_CREDIT_MANAGER, getDeliveryMethod);
        }

        MessageInstanceConsumer<ConsumerTarget_0_8> sub = queue.addConsumer(target, null, AMQMessage.class, "", options, null);
        target.updateNotifyWorkDesired();
        target.sendNextMessage();
        target.close();
        return getDeliveryMethod.hasDeliveredMessage();
    }

    /** Sets this channel to be part of a local transaction */
    private void setLocalTransactional()
    {
        _transaction = new LocalTransaction(_messageStore, new ActivityTimeAccessor()
        {
            @Override
            public long getActivityTime()
            {
                return _connection.getLastReadTime();
            }
        });
        _txnStarts.incrementAndGet();
    }

    private boolean isTransactional()
    {
        return _transaction.isTransactional();
    }

    public void receivedComplete()
    {
        AccessController.doPrivileged(new PrivilegedAction<Void>()
        {
            @Override
            public Void run()
            {
                sync();
                return null;
            }
        }, getAccessControllerContext());

    }

    private void incrementOutstandingTxnsIfNecessary()
    {
        if(isTransactional())
        {
            //There can currently only be at most one outstanding transaction
            //due to only having LocalTransaction support. Set value to 1 if 0.
            _txnCount.compareAndSet(0,1);
        }
    }

    private void decrementOutstandingTxnsIfNecessary()
    {
        if(isTransactional())
        {
            //There can currently only be at most one outstanding transaction
            //due to only having LocalTransaction support. Set value to 0 if 1.
            _txnCount.compareAndSet(1,0);
        }
    }

    @Override
    public long getTxnCommits()
    {
        return _txnCommits.get();
    }

    @Override
    public long getTxnRejects()
    {
        return _txnRejects.get();
    }

    @Override
    public long getTxnStart()
    {
        return _txnStarts.get();
    }

    @Override
    public int getChannelId()
    {
        return _channelId;
    }

    private void setPublishFrame(MessagePublishInfo info, final MessageDestination e)
    {
        _currentMessage = new IncomingMessage(info);
        _currentMessage.setMessageDestination(e);
    }

    private void publishContentHeader(ContentHeaderBody contentHeaderBody)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug("Content header received on channel " + _channelId);
        }

        _currentMessage.setContentHeaderBody(contentHeaderBody);

        deliverCurrentMessageIfComplete();
    }

    private void deliverCurrentMessageIfComplete()
    {
        // check and deliver if header says body length is zero
        if (_currentMessage.allContentReceived())
        {
            MessagePublishInfo info = _currentMessage.getMessagePublishInfo();
            String routingKey = AMQShortString.toString(info.getRoutingKey());

            try
            {
                final MessageDestination destination = _currentMessage.getDestination();

                ContentHeaderBody contentHeader = _currentMessage.getContentHeader();
                _connection.checkAuthorizedMessagePrincipal(AMQShortString.toString(contentHeader.getProperties().getUserId()));

                _publishAuthCahe.authorisePublish(destination, routingKey, info.isImmediate(), _connection.getLastReadTime());

                if (_confirmOnPublish)
                {
                    _confirmedMessageCounter++;
                }
                Runnable finallyAction = null;

                long bodySize = _currentMessage.getSize();
                long timestamp = contentHeader.getProperties().getTimestamp();

                try
                {

                    final MessagePublishInfo messagePublishInfo = _currentMessage.getMessagePublishInfo();

                    final MessageMetaData messageMetaData =
                            new MessageMetaData(messagePublishInfo,
                                                contentHeader,
                                                getConnection().getLastReadTime());

                    final MessageHandle<MessageMetaData> handle = _messageStore.addMessage(messageMetaData);
                    int bodyCount = _currentMessage.getBodyCount();
                    if (bodyCount > 0)
                    {
                        for (int i = 0; i < bodyCount; i++)
                        {
                            ContentBody contentChunk = _currentMessage.getContentChunk(i);
                            handle.addContent(contentChunk.getPayload());
                            contentChunk.dispose();
                        }
                    }
                    final StoredMessage<MessageMetaData> storedMessage = handle.allContentAdded();

                    final AMQMessage amqMessage = createAMQMessage(storedMessage);
                    MessageReference reference = amqMessage.newReference();
                    try
                    {

                        _currentMessage = null;


                        final boolean immediate = messagePublishInfo.isImmediate();

                        final InstanceProperties instanceProperties =
                                new InstanceProperties()
                                {
                                    @Override
                                    public Object getProperty(final Property prop)
                                    {
                                        switch (prop)
                                        {
                                            case EXPIRATION:
                                                return amqMessage.getExpiration();
                                            case IMMEDIATE:
                                                return immediate;
                                            case PERSISTENT:
                                                return amqMessage.isPersistent();
                                            case MANDATORY:
                                                return messagePublishInfo.isMandatory();
                                            case REDELIVERED:
                                                return false;
                                        }
                                        return null;
                                    }
                                };

                        final RoutingResult<AMQMessage> result =
                                destination.route(amqMessage,
                                                  amqMessage.getInitialRoutingAddress(),
                                                  instanceProperties);

                        int enqueues = result.send(_transaction, immediate ? _immediateAction : _capacityCheckAction);
                        if (enqueues == 0)
                        {
                            finallyAction = handleUnroutableMessage(amqMessage);
                        }
                        else
                        {
                            if (_confirmOnPublish)
                            {
                                BasicAckBody responseBody = _connection.getMethodRegistry()
                                        .createBasicAckBody(_confirmedMessageCounter, false);
                                _connection.writeFrame(responseBody.generateFrame(_channelId));
                            }
                            incrementUncommittedMessageSize(storedMessage);
                            incrementOutstandingTxnsIfNecessary();
                        }

                    }
                    finally
                    {
                        reference.release();
                        if (finallyAction != null)
                        {
                            finallyAction.run();
                        }
                    }

                }
                finally
                {
                    _connection.registerMessageReceived(bodySize, timestamp);
                    _currentMessage = null;
                }
            }
            catch (AccessControlException e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());
            }

        }

    }

    private void incrementUncommittedMessageSize(final StoredMessage<MessageMetaData> handle)
    {
        if (isTransactional())
        {
            _uncommittedMessageSize += handle.getContentSize();
            if (_uncommittedMessageSize > getMaxUncommittedInMemorySize())
            {
                handle.flowToDisk();
                if(!_uncommittedMessages.isEmpty() || _uncommittedMessageSize == handle.getContentSize())
                {
                    messageWithSubject(ChannelMessages.LARGE_TRANSACTION_WARN(_uncommittedMessageSize));
                }

                if(!_uncommittedMessages.isEmpty())
                {
                    for (StoredMessage<MessageMetaData> uncommittedHandle : _uncommittedMessages)
                    {
                        uncommittedHandle.flowToDisk();
                    }
                    _uncommittedMessages.clear();
                }
            }
            else
            {
                _uncommittedMessages.add(handle);
            }
        }
    }

    /**
     * Either throws a {@link AMQConnectionException} or returns the message
     *
     * Pre-requisite: the current message is judged to have no destination queues.
     *
     * @throws AMQConnectionException if the message is mandatory close-on-no-route
     * @see AMQPConnection_0_8Impl#isCloseWhenNoRoute()
     */
    private Runnable handleUnroutableMessage(AMQMessage message)
    {
        boolean mandatory = message.isMandatory();

        String exchangeName = AMQShortString.toString(message.getMessagePublishInfo().getExchange());
        String routingKey = AMQShortString.toString(message.getMessagePublishInfo().getRoutingKey());

        final String description = String.format(
                "[Exchange: %s, Routing key: %s]",
                exchangeName,
                routingKey);

        boolean closeOnNoRoute = _connection.isCloseWhenNoRoute();
        Runnable returnVal = null;
        if(_logger.isDebugEnabled())
        {
            _logger.debug(String.format(
                    "Unroutable message %s, mandatory=%s, transactionalSession=%s, closeOnNoRoute=%s",
                    description, mandatory, isTransactional(), closeOnNoRoute));
        }

        if (mandatory && isTransactional() && !_confirmOnPublish && _connection.isCloseWhenNoRoute())
        {
            returnVal = new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                _connection.sendConnectionClose(ErrorCodes.NO_ROUTE,
                                        "No route for message " + description, _channelId);

                            }
                        };
        }
        else
        {
            if (mandatory || message.isImmediate())
            {
                if(_confirmOnPublish)
                {
                    _connection.writeFrame(new AMQFrame(_channelId, new BasicNackBody(_confirmedMessageCounter, false, false)));
                }
                _transaction.addPostTransactionAction(new WriteReturnAction(ErrorCodes.NO_ROUTE,
                                                                            "No Route for message "
                                                                            + description,
                                                                            message));
            }
            else
            {

                message(ExchangeMessages.DISCARDMSG(exchangeName, routingKey));
            }
        }
        return returnVal;
    }

    private void publishContentBody(ContentBody contentBody)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug(debugIdentity() + " content body received on channel " + _channelId);
        }

        try
        {
            long currentSize = _currentMessage.addContentBodyFrame(contentBody);
            if(currentSize > _currentMessage.getSize())
            {
                _connection.sendConnectionClose(ErrorCodes.FRAME_ERROR,
                                                "More message data received than content header defined",
                                                _channelId);
            }
            else
            {
                deliverCurrentMessageIfComplete();
            }
        }
        catch (RuntimeException e)
        {
            // we want to make sure we don't keep a reference to the message in the
            // event of an error
            _currentMessage = null;
            throw e;
        }
    }

    public long getNextDeliveryTag()
    {
        return ++_deliveryTag;
    }

    private int getNextConsumerTag()
    {
        return ++_consumerTag;
    }


    /**
     * Subscribe to a queue. We register all subscriptions in the channel so that if the channel is closed we can clean
     * up all subscriptions, even if the client does not explicitly unsubscribe from all queues.
     *
     *
     * @param tag       the tag chosen by the client (if null, server will generate one)
     * @param sources     the queues to subscribe to
     * @param acks      Are acks enabled for this subscriber
     * @param arguments   Filters to apply to this subscriber
     *
     * @param exclusive Flag requesting exclusive access to the queue
     * @return the consumer tag. This is returned to the subscriber and used in subsequent unsubscribe requests
     */
    private AMQShortString consumeFromSource(AMQShortString tag, Collection<MessageSource> sources, boolean acks,
                                            FieldTable arguments, boolean exclusive, boolean noLocal)
            throws MessageSource.ExistingConsumerPreventsExclusive,
                   MessageSource.ExistingExclusiveConsumer,
                   AMQInvalidArgumentException,
                   MessageSource.ConsumerAccessRefused, ConsumerTagInUseException, MessageSource.QueueDeleted
    {
        if (tag == null)
        {
            tag = new AMQShortString("sgen_" + getNextConsumerTag());
        }

        if (_tag2SubscriptionTargetMap.containsKey(tag))
        {
            throw new ConsumerTagInUseException("Consumer already exists with same tag: " + tag);
        }

        ConsumerTarget_0_8 target;
        EnumSet<ConsumerOption> options = EnumSet.noneOf(ConsumerOption.class);
        final boolean multiQueue = sources.size()>1;
        if(arguments != null && Boolean.TRUE.equals(arguments.get(AMQPFilterTypes.NO_CONSUME.getValue())))
        {
            target = ConsumerTarget_0_8.createBrowserTarget(this, tag, arguments,
                                                            INFINITE_CREDIT_CREDIT_MANAGER, multiQueue);
        }
        else if(acks)
        {
            target = ConsumerTarget_0_8.createAckTarget(this, tag, arguments, _creditManager, multiQueue);
            options.add(ConsumerOption.ACQUIRES);
            options.add(ConsumerOption.SEES_REQUEUES);
        }
        else
        {
            target = ConsumerTarget_0_8.createNoAckTarget(this, tag, arguments,
                                                          INFINITE_CREDIT_CREDIT_MANAGER, multiQueue);
            options.add(ConsumerOption.ACQUIRES);
            options.add(ConsumerOption.SEES_REQUEUES);
        }

        if(exclusive)
        {
            options.add(ConsumerOption.EXCLUSIVE);
        }


        // So to keep things straight we put before the call and catch all exceptions from the register and tidy up.
        // We add before we register as the Async Delivery process may AutoClose the subscriber
        // so calling _cT2QM.remove before we have done put which was after the register succeeded.
        // So to keep things straight we put before the call and catch all exceptions from the register and tidy up.

        _tag2SubscriptionTargetMap.put(tag, target);

        try
        {
            FilterManager filterManager = FilterManagerFactory.createManager(FieldTable.convertToMap(arguments));
            if(noLocal)
            {
                if(filterManager == null)
                {
                    filterManager = new FilterManager();
                }
                MessageFilter filter = new NoLocalFilter();
                filterManager.add(filter.getName(), filter);
            }

            if(arguments != null && arguments.containsKey(AMQPFilterTypes.REPLAY_PERIOD.toString()))
            {
                Object value = arguments.get(AMQPFilterTypes.REPLAY_PERIOD.toString());
                final long period;
                if(value instanceof Number)
                {
                    period = ((Number)value).longValue();
                }
                else if(value instanceof String)
                {
                    try
                    {
                        period = Long.parseLong(value.toString());
                    }
                    catch (NumberFormatException e)
                    {
                        throw new AMQInvalidArgumentException("Cannot parse value " + value + " as a number for filter " + AMQPFilterTypes.REPLAY_PERIOD.toString());
                    }
                }
                else
                {
                    throw new AMQInvalidArgumentException("Cannot parse value " + value + " as a number for filter " + AMQPFilterTypes.REPLAY_PERIOD.toString());
                }

                final long startingFrom = System.currentTimeMillis() - (1000l * period);
                if(filterManager == null)
                {
                    filterManager = new FilterManager();
                }
                MessageFilter filter = new ArrivalTimeFilter(startingFrom, period==0);
                filterManager.add(filter.getName(), filter);

            }

            Integer priority = null;
            if(arguments != null && arguments.containsKey("x-priority"))
            {
                Object value = arguments.get("x-priority");
                if(value instanceof Number)
                {
                    priority = ((Number)value).intValue();
                }
                else if(value instanceof String || value instanceof AMQShortString)
                {
                    try
                    {
                        priority = Integer.parseInt(value.toString());
                    }
                    catch (NumberFormatException e)
                    {
                        // use default vlaue
                    }
                }

            }



            for(MessageSource source : sources)
            {
                MessageInstanceConsumer<ConsumerTarget_0_8> sub =
                        source.addConsumer(target,
                                           filterManager,
                                           AMQMessage.class,
                                           AMQShortString.toString(tag),
                                           options, priority);
                if (sub instanceof Consumer<?, ?>)
                {
                    final Consumer<?,ConsumerTarget_0_8> modelConsumer = (Consumer<?,ConsumerTarget_0_8>) sub;
                    consumerAdded(modelConsumer);
                    modelConsumer.addChangeListener(_consumerClosedListener);
                    _consumers.add(modelConsumer);
                }
            }
            target.updateNotifyWorkDesired();
        }
        catch (AccessControlException
                | MessageSource.ExistingExclusiveConsumer
                | MessageSource.ExistingConsumerPreventsExclusive
                | MessageSource.QueueDeleted
                | AMQInvalidArgumentException
                | MessageSource.ConsumerAccessRefused e)
        {
            _tag2SubscriptionTargetMap.remove(tag);
            throw e;
        }
        return tag;
    }

    /**
     * Unsubscribe a consumer from a queue.
     * @param consumerTag
     * @return true if the consumerTag had a mapped queue that could be unregistered.
     */
    private boolean unsubscribeConsumer(AMQShortString consumerTag)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug("Unsubscribing consumer '{}' on channel {}", consumerTag, this);
        }

        ConsumerTarget_0_8 target = _tag2SubscriptionTargetMap.remove(consumerTag);
        Collection<MessageInstanceConsumer> subs = target == null ? null : target.getConsumers();
        if (subs != null)
        {
            for(MessageInstanceConsumer sub : subs)
            {
                if (sub instanceof Consumer<?,?>)
                {
                    _consumers.remove(sub);
                }
            }
            target.close();
            return true;
        }
        else
        {
            _logger.warn("Attempt to unsubscribe consumer with tag '" + consumerTag + "' which is not registered.");
        }
        return false;
    }

    @Override
    public void close()
    {
        close(0, null);
    }

    public void close(int cause, String message)
    {
        if(!_closing.compareAndSet(false, true))
        {
            //Channel is already closing
            return;
        }

        try
        {
            unsubscribeAllConsumers();
            setDefaultQueue(null);
            if(_modelObject != null)
            {
                _modelObject.delete();
            }
            for (Action<? super AMQChannel> task : _taskList)
            {
                task.performAction(this);
            }

            _transaction.rollback();

            requeue();
        }
        finally
        {
            LogMessage operationalLogMessage = cause == 0?
                    ChannelMessages.CLOSE() :
                    ChannelMessages.CLOSE_FORCED(cause, message);
            messageWithSubject(operationalLogMessage);
        }
    }

    private void messageWithSubject(final LogMessage operationalLogMessage)
    {
        getEventLogger().message(_logSubject, operationalLogMessage);
    }

    private void unsubscribeAllConsumers()
    {
        if (_logger.isDebugEnabled())
        {
            if (!_tag2SubscriptionTargetMap.isEmpty())
            {
                _logger.debug("Unsubscribing all consumers on channel " + toString());
            }
            else
            {
                _logger.debug("No consumers to unsubscribe on channel " + toString());
            }
        }

        Set<AMQShortString> subscriptionTags = new HashSet<>(_tag2SubscriptionTargetMap.keySet());
        for (AMQShortString tag : subscriptionTags)
        {
            unsubscribeConsumer(tag);
        }
    }

    /**
     * Add a message to the channel-based list of unacknowledged messages
     *  @param entry       the record of the message on the queue that was delivered
     * @param deliveryTag the delivery tag used when delivering the message (see protocol spec for description of the
     *                    delivery tag)
     * @param consumer The consumer that is to acknowledge this message.
     * @param usesCredit
     */
    public void addUnacknowledgedMessage(MessageInstance entry,
                                         long deliveryTag,
                                         MessageInstanceConsumer consumer,
                                         final boolean usesCredit)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug(debugIdentity() + " Adding unacked message(" + entry.getMessage().toString() + " DT:" + deliveryTag
                               + ") for " + consumer + " on " + entry.getOwningResource().getName());
        }

        _unacknowledgedMessageMap.add(deliveryTag, entry, consumer, usesCredit);

    }

    private final String id = "(" + System.identityHashCode(this) + ")";

    private String debugIdentity()
    {
        return _channelId + id;
    }

    /**
     * Called to attempt re-delivery all outstanding unacknowledged messages on the channel. May result in delivery to
     * this same channel or to other subscribers.
     *
     */
    private void requeue()
    {
        final Map<Long, MessageConsumerAssociation> copy = new LinkedHashMap<>();
        _unacknowledgedMessageMap.visit(new Visitor()
        {
            @Override
            public boolean callback(final long deliveryTag, final MessageConsumerAssociation messageConsumerPair)
            {
                copy.put(deliveryTag, messageConsumerPair);
                return false;
            }

            @Override
            public void visitComplete()
            {

            }
        });

        if (!copy.isEmpty())
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("Requeuing {} unacked messages", copy.size());
            }
        }

        for (Map.Entry<Long, MessageConsumerAssociation> entry : copy.entrySet())
        {
            MessageInstance unacked = entry.getValue().getMessageInstance();
            MessageInstanceConsumer consumer = entry.getValue().getConsumer();
            // Mark message redelivered
            unacked.setRedelivered();
            // here we wish to restore credit
            _unacknowledgedMessageMap.remove(entry.getKey(), true);
            // Ensure message is released for redelivery
            unacked.release(consumer);
        }

    }

    /**
     * Requeue a single message
     *
     * @param deliveryTag The message to requeue
     *
     */
    private void requeue(long deliveryTag)
    {

        final MessageConsumerAssociation association = _unacknowledgedMessageMap.remove(deliveryTag, true);

        if (association != null)
        {
            MessageInstance unacked = association.getMessageInstance();
            // Mark message redelivered
            unacked.setRedelivered();

            // Ensure message is released for redelivery
            unacked.release(association.getConsumer());
        }
        else
        {
            _logger.warn("Requested requeue of message: {} but no such delivery tag exists.", deliveryTag);
        }

    }

    private boolean isMaxDeliveryCountEnabled(final long deliveryTag)
    {
        final MessageInstance queueEntry = _unacknowledgedMessageMap.get(deliveryTag);
        if (queueEntry != null)
        {
            final int maximumDeliveryCount = queueEntry.getMaximumDeliveryCount();
            return maximumDeliveryCount > 0;
        }

        return false;
    }

    private boolean isDeliveredTooManyTimes(final long deliveryTag)
    {
        final MessageInstance queueEntry = _unacknowledgedMessageMap.get(deliveryTag);
        if (queueEntry != null)
        {
            final int maximumDeliveryCount = queueEntry.getMaximumDeliveryCount();
            final int numDeliveries = queueEntry.getDeliveryCount();
            return maximumDeliveryCount != 0 && numDeliveries >= maximumDeliveryCount;
        }

        return false;
    }

    /**
     * Called to resend all outstanding unacknowledged messages to this same channel.
     *
     */
    private void resend()
    {
        final Map<Long, MessageConsumerAssociation> msgToRequeue = new LinkedHashMap<>();
        final Map<Long, MessageConsumerAssociation> msgToResend = new LinkedHashMap<>();

        if (_logger.isDebugEnabled())
        {
            _logger.debug("Unacknowledged messages: {}", _unacknowledgedMessageMap.size());
        }

        _unacknowledgedMessageMap.visit(new Visitor()
        {
            @Override
            public boolean callback(final long deliveryTag, final MessageConsumerAssociation association)
            {

                if (association.getConsumer().isClosed())
                {
                    // consumer has gone
                    msgToRequeue.put(deliveryTag, association);
                }
                else
                {
                    // Consumer still exists
                    msgToResend.put(deliveryTag,  association);
                }
                return false;
            }

            @Override
            public void visitComplete()
            {
            }
        });


        for (Map.Entry<Long, MessageConsumerAssociation> entry : msgToResend.entrySet())
        {
            long deliveryTag = entry.getKey();
            MessageInstance message = entry.getValue().getMessageInstance();
            MessageInstanceConsumer consumer = entry.getValue().getConsumer();

            // Without any details from the client about what has been processed we have to mark
            // all messages in the unacked map as redelivered.
            message.setRedelivered();

            if (message.makeAcquisitionUnstealable(consumer))
            {
                message.decrementDeliveryCount();

                consumer.getTarget().send(consumer, message, false);
                // remove from unacked map - don't want to restore credit though(!)
                _unacknowledgedMessageMap.remove(deliveryTag, false);
            }
            else
            {
                msgToRequeue.put(deliveryTag, entry.getValue());
            }
        }

        // Process Messages to Requeue at the front of the queue
        for (Map.Entry<Long, MessageConsumerAssociation> entry : msgToRequeue.entrySet())
        {
            long deliveryTag = entry.getKey();
            MessageInstance message = entry.getValue().getMessageInstance();
            MessageInstanceConsumer consumer = entry.getValue().getConsumer();

            //Amend the delivery counter as the client hasn't seen these messages yet.
            message.decrementDeliveryCount();

            // here we do wish to restore credit
            _unacknowledgedMessageMap.remove(deliveryTag, true);

            message.setRedelivered();
            message.release(consumer);
        }
    }


    private UnacknowledgedMessageMap getUnacknowledgedMessageMap()
    {
        return _unacknowledgedMessageMap;
    }

    /**
     * Called from the ChannelFlowHandler to suspend this Channel
     * @param suspended boolean, should this Channel be suspended
     */
    private void setSuspended(boolean suspended)
    {
        boolean wasSuspended = _suspended.getAndSet(suspended);
        if (wasSuspended != suspended)
        {
            // Log Flow Started before we start the subscriptions
            if (!suspended && _logChannelFlowMessages)
            {
                messageWithSubject(ChannelMessages.FLOW("Started"));
            }

            // Here we have become unsuspended and so we ask each the queue to
            // perform an Async delivery for each of the subscriptions in this
            // Channel. The alternative would be to ensure that the subscription
            // had received the change in suspension state. That way the logic
            // behind deciding to start an async delivery was located with the
            // Subscription.
            if (wasSuspended)
            {
                // may need to deliver queued messages
                for (ConsumerTarget_0_8 s : getConsumerTargets())
                {
                    for(MessageInstanceConsumer sub : s.getConsumers())
                    {
                        sub.externalStateChange();
                    }
                }
            }

            // Log Suspension only after we have confirmed all suspensions are
            // stopped.
            if (suspended && _logChannelFlowMessages)
            {
                messageWithSubject(ChannelMessages.FLOW("Stopped"));
            }

        }
    }

    private void commit(final Runnable immediateAction, boolean async)
    {


        if(async && _transaction instanceof LocalTransaction)
        {

            ((LocalTransaction)_transaction).commitAsync(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        immediateAction.run();
                    }
                    finally
                    {
                        _txnCommits.incrementAndGet();
                        _txnStarts.incrementAndGet();
                        decrementOutstandingTxnsIfNecessary();
                    }
                }
            });
        }
        else
        {
            _transaction.commit(immediateAction);

            _txnCommits.incrementAndGet();
            _txnStarts.incrementAndGet();
            decrementOutstandingTxnsIfNecessary();
        }
        resetUncommittedMessages();
    }

    private void resetUncommittedMessages()
    {
        _uncommittedMessageSize = 0l;
        _uncommittedMessages.clear();
    }

    private void rollback(Runnable postRollbackTask)
    {

        // stop all subscriptions
        _rollingBack = true;
        boolean requiresSuspend = _suspended.compareAndSet(false,true);  // TODO This is probably superfluous owing to the
        // message assignment suspended logic in NBC.

        try
        {
            _transaction.rollback();
        }
        finally
        {
            _rollingBack = false;

            _txnRejects.incrementAndGet();
            _txnStarts.incrementAndGet();
            decrementOutstandingTxnsIfNecessary();
            resetUncommittedMessages();
        }

        postRollbackTask.run();

        for(MessageConsumerAssociation association : _resendList)
        {
            final MessageInstance messageInstance = association.getMessageInstance();
            final MessageInstanceConsumer consumer = association.getConsumer();
            if (consumer.isClosed())
            {
                messageInstance.release(consumer);
            }
            else
            {
                if (messageInstance.makeAcquisitionUnstealable(consumer)
                    && _creditManager.useCreditForMessage(association.getSize()))
                {
                    consumer.getTarget().send(consumer, messageInstance, false);
                }
                else
                {
                    messageInstance.release(consumer);
                }
            }
        }
        _resendList.clear();

        if(requiresSuspend)
        {
            _suspended.set(false);
            for(ConsumerTarget_0_8 target : getConsumerTargets())
            {
                for(MessageInstanceConsumer sub : target.getConsumers())
                {
                    sub.externalStateChange();
                }
            }

        }
    }

    @Override
    public String toString()
    {
        return "("+ _suspended.get() + ", " + _closing.get() + ", " + _connection.isClosing() + ") "+"["+ _connection.toString()+":"+_channelId+"]";
    }

    boolean isClosing()
    {
        return _closing.get();
    }

    public AMQPConnection_0_8<?> getConnection()
    {
        return _connection;
    }

    private void setCredit(final long prefetchSize, final int prefetchCount)
    {
        if (!_prefetchLoggedForChannel)
        {
            message(ChannelMessages.PREFETCH_SIZE(prefetchSize, prefetchCount));
            _prefetchLoggedForChannel = true;
        }

        if (prefetchCount <= 1 && prefetchSize == 0 )
        {
            _logChannelFlowMessages = false;
        }
        boolean hasCredit = _creditManager.hasCredit();
        _creditManager.setCreditLimits(prefetchSize, prefetchCount);
        if(hasCredit != _creditManager.hasCredit())
        {
            updateAllConsumerNotifyWorkDesired();
        }
    }

    public ClientDeliveryMethod getClientDeliveryMethod()
    {
        return _clientDeliveryMethod;
    }


    private AMQMessage createAMQMessage(StoredMessage<MessageMetaData> handle)
    {

        AMQMessage message = new AMQMessage(handle, _connection.getReference());

        return message;
    }

    @Override
    public UUID getId()
    {
        return _id;
    }

    @Override
    public AMQPConnection_0_8<?> getAMQPConnection()
    {
        return _connection;
    }

    public LogSubject getLogSubject()
    {
        return _logSubject;
    }

    @Override
    public int compareTo(AMQSessionModel o)
    {
        return getId().compareTo(o.getId());
    }

    @Override
    public void addDeleteTask(final Action<? super AMQChannel> task)
    {
        _taskList.add(task);
    }

    @Override
    public void removeDeleteTask(final Action<? super AMQChannel> task)
    {
        _taskList.remove(task);
    }

    public Subject getSubject()
    {
        return _subject;
    }

    private boolean hasCurrentMessage()
    {
        return _currentMessage != null;
    }

    private long getMaxUncommittedInMemorySize()
    {
        return _maxUncommittedInMemorySize;
    }

    public boolean isChannelFlow()
    {
        return _channelFlow;
    }

    private class NoLocalFilter implements MessageFilter
    {

        private final Object _connectionReference;

        public NoLocalFilter()
        {
            _connectionReference = getConnectionReference();
        }

        @Override
        public String getName()
        {
            return AMQPFilterTypes.NO_LOCAL.toString();
        }

        @Override
        public boolean matches(final Filterable message)
        {
            return message.getConnectionReference() != _connectionReference;
        }

        @Override
        public boolean startAtTail()
        {
            return false;
        }

        @Override
        public String toString()
        {
            return "NoLocalFilter[]";
        }
    }

    private class GetDeliveryMethod implements ClientDeliveryMethod
    {
        private final MessageSource _queue;
        private boolean _deliveredMessage;

        public GetDeliveryMethod(final MessageSource queue)
        {
            _queue = queue;
        }

        @Override
        public long deliverToClient(final ConsumerTarget_0_8 target, final ServerMessage message,
                                    final InstanceProperties props, final long deliveryTag)
        {

            int queueSize = _queue instanceof Queue ? ((Queue<?>)_queue).getQueueDepthMessages() : 0;
            long size = _connection.getProtocolOutputConverter().writeGetOk(message,
                                                                            props,
                                                                            AMQChannel.this.getChannelId(),
                                                                            deliveryTag,
                                                                            queueSize);

            _deliveredMessage = true;
            return size;
        }

        public boolean hasDeliveredMessage()
        {
            return _deliveredMessage;
        }
    }


    private class ImmediateAction implements Action<MessageInstance>
    {

        public ImmediateAction()
        {
        }

        public void performAction(MessageInstance entry)
        {
            TransactionLogResource queue = entry.getOwningResource();

            if (!entry.getDeliveredToConsumer() && entry.acquire())
            {

                ServerTransaction txn = new LocalTransaction(_messageStore);
                final AMQMessage message = (AMQMessage) entry.getMessage();
                MessageReference ref = message.newReference();
                try
                {
                    entry.delete();
                    txn.dequeue(entry.getEnqueueRecord(),
                                new ServerTransaction.Action()
                                {
                                    @Override
                                    public void postCommit()
                                    {
                                        final ProtocolOutputConverter outputConverter =
                                                    _connection.getProtocolOutputConverter();

                                        outputConverter.writeReturn(message.getMessagePublishInfo(),
                                                                    message.getContentHeaderBody(),
                                                                    message,
                                                                    _channelId,
                                                                    ErrorCodes.NO_CONSUMERS,
                                                                    IMMEDIATE_DELIVERY_REPLY_TEXT);

                                    }

                                    @Override
                                    public void onRollback()
                                    {

                                    }
                                }
                               );
                    txn.commit();
                }
                finally
                {
                    ref.release();
                }


            }
            else
            {
                if(queue instanceof CapacityChecker)
                {
                    ((CapacityChecker)queue).checkCapacity(AMQChannel.this);
                }
            }

        }
    }

    private final class CapacityCheckAction implements Action<MessageInstance>
    {
        @Override
        public void performAction(final MessageInstance entry)
        {
            TransactionLogResource queue = entry.getOwningResource();
            if(queue instanceof CapacityChecker)
            {
                ((CapacityChecker)queue).checkCapacity(AMQChannel.this);
            }
        }
    }

    private class MessageAcknowledgeAction implements ServerTransaction.Action
    {
        private Collection<MessageConsumerAssociation> _ackedMessages;

        public MessageAcknowledgeAction(Collection<MessageConsumerAssociation> ackedMessages)
        {
            _ackedMessages = ackedMessages;
        }

        @Override
        public void postCommit()
        {
            try
            {
                for(MessageConsumerAssociation association : _ackedMessages)
                {
                    association.getMessageInstance().delete();
                }
            }
            finally
            {
                _ackedMessages = Collections.emptySet();
            }

        }

        @Override
        public void onRollback()
        {
            // explicit rollbacks resend the message after the rollback-ok is sent
            if(_rollingBack)
            {
                for(MessageConsumerAssociation association : _ackedMessages)
                {
                    association.getMessageInstance().makeAcquisitionStealable();
                }
                _resendList.addAll(_ackedMessages);
            }
            else
            {
                try
                {
                    for(MessageConsumerAssociation association : _ackedMessages)
                    {
                        final MessageInstance messageInstance = association.getMessageInstance();
                        messageInstance.release(association.getConsumer());
                    }
                }
                finally
                {
                    _ackedMessages = Collections.emptySet();
                }
            }

        }
    }

    private class WriteReturnAction implements ServerTransaction.Action
    {
        private final int _errorCode;
        private final String _description;
        private final MessageReference<AMQMessage> _reference;

        public WriteReturnAction(int errorCode,
                                 String description,
                                 AMQMessage message)
        {
            _errorCode = errorCode;
            _description = description;
            _reference = message.newReference();
        }

        @Override
        public void postCommit()
        {
            AMQMessage message = _reference.getMessage();
            _connection.getProtocolOutputConverter().writeReturn(message.getMessagePublishInfo(),
                                                          message.getContentHeaderBody(),
                                                          message,
                                                          _channelId,
                                                          _errorCode,
                                                          AMQShortString.validValueOf(_description));
            _reference.release();
        }

        public void onRollback()
        {
            _reference.release();
        }
    }

    @Override
    public synchronized void block()
    {
        if(_blockingEntities.add(this))
        {

            if(_blocking.compareAndSet(false,true))
            {
                messageWithSubject(ChannelMessages.FLOW_ENFORCED("** All Queues **"));


                getConnection().notifyWork(this);
            }
        }
    }

    @Override
    public synchronized void unblock()
    {
        if(_blockingEntities.remove(this))
        {
            if(_blockingEntities.isEmpty() && _blocking.compareAndSet(true,false))
            {
                messageWithSubject(ChannelMessages.FLOW_REMOVED());
                getConnection().notifyWork(this);
            }
        }
    }

    @Override
    public synchronized void block(Queue<?> queue)
    {
        if(_blockingEntities.add(queue))
        {

            if(_blocking.compareAndSet(false,true))
            {
                messageWithSubject(ChannelMessages.FLOW_ENFORCED(queue.getName()));
                getConnection().notifyWork(this);

            }
        }
    }

    @Override
    public synchronized void unblock(Queue<?> queue)
    {
        if(_blockingEntities.remove(queue))
        {
            if(_blockingEntities.isEmpty() && _blocking.compareAndSet(true,false) && !isClosing())
            {
                messageWithSubject(ChannelMessages.FLOW_REMOVED());
                getConnection().notifyWork(this);
            }
        }
    }

    @Override
    public void transportStateChanged()
    {
        updateAllConsumerNotifyWorkDesired();
        _creditManager.restoreCredit(0, 0);
        INFINITE_CREDIT_CREDIT_MANAGER.restoreCredit(0, 0);
        if (!_consumersWithPendingWork.isEmpty() && !getAMQPConnection().isTransportBlockedForWriting())
        {
            getAMQPConnection().notifyWork(this);
        }
    }

    void updateAllConsumerNotifyWorkDesired()
    {
        for(ConsumerTarget_0_8 target : _tag2SubscriptionTargetMap.values())
        {
            target.updateNotifyWorkDesired();
        }
    }

    @Override
    public Object getConnectionReference()
    {
        return getConnection().getReference();
    }

    @Override
    public int getUnacknowledgedMessageCount()
    {
        return getUnacknowledgedMessageMap().size();
    }

    private void sendFlow(boolean flow)
    {
        MethodRegistry methodRegistry = _connection.getMethodRegistry();
        AMQMethodBody responseBody = methodRegistry.createChannelFlowBody(flow);
        _connection.writeFrame(responseBody.generateFrame(_channelId));
    }

    @Override
    public boolean getBlocking()
    {
        return _blocking.get();
    }

    public NamedAddressSpace getAddressSpace()
    {
        return getConnection().getAddressSpace();
    }

    private void deadLetter(long deliveryTag)
    {
        final UnacknowledgedMessageMap unackedMap = getUnacknowledgedMessageMap();
        final MessageConsumerAssociation association = unackedMap.remove(deliveryTag, true);

        if (association == null)
        {
            _logger.warn("No message found, unable to DLQ delivery tag: " + deliveryTag);
        }
        else
        {

            final MessageInstance messageInstance = association.getMessageInstance();
            final ServerMessage msg = messageInstance.getMessage();
            int requeues = 0;
            if (messageInstance.makeAcquisitionUnstealable(association.getConsumer()))
            {
                requeues = messageInstance.routeToAlternate(new Action<MessageInstance>()
                {
                    @Override
                    public void performAction(final MessageInstance requeueEntry)
                    {
                        messageWithSubject(ChannelMessages.DEADLETTERMSG(msg.getMessageNumber(),
                                                                         requeueEntry.getOwningResource()
                                                                               .getName()));
                    }
                }, null);
            }

            if(requeues == 0)
            {

                final TransactionLogResource owningResource = messageInstance.getOwningResource();
                if(owningResource instanceof Queue)
                {
                    final Queue<?> queue = (Queue<?>) owningResource;

                    final Exchange altExchange = queue.getAlternateExchange();

                    if (altExchange == null)
                    {
                        if (_logger.isDebugEnabled())
                        {
                            _logger.debug(
                                    "No alternate exchange configured for queue, must discard the message as unable to DLQ: delivery tag: "
                                    + deliveryTag);
                        }
                        messageWithSubject(ChannelMessages.DISCARDMSG_NOALTEXCH(msg.getMessageNumber(),
                                                                                queue.getName(),
                                                                                msg.getInitialRoutingAddress()));

                    }
                    else
                    {
                        if (_logger.isDebugEnabled())
                        {
                            _logger.debug(
                                    "Routing process provided no queues to enqueue the message on, must discard message as unable to DLQ: delivery tag: "
                                    + deliveryTag);
                        }
                        messageWithSubject(ChannelMessages.DISCARDMSG_NOROUTE(msg.getMessageNumber(),
                                                                              altExchange.getName()));
                    }
                }
            }

        }
    }

    @Override
    public void recordFuture(final ListenableFuture<Void> future, final ServerTransaction.Action action)
    {
        _unfinishedCommandsQueue.add(new AsyncCommand(future, action));
    }

    private void sync()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("sync() called on channel " + debugIdentity());
        }

        AsyncCommand cmd;
        while((cmd = _unfinishedCommandsQueue.poll()) != null)
        {
            cmd.complete();
        }
        if(_transaction instanceof LocalTransaction)
        {
            ((LocalTransaction)_transaction).sync();
        }
    }

    private static class AsyncCommand
    {
        private final ListenableFuture<Void> _future;
        private ServerTransaction.Action _action;

        public AsyncCommand(final ListenableFuture<Void> future, final ServerTransaction.Action action)
        {
            _future = future;
            _action = action;
        }

        void complete()
        {
            boolean interrupted = false;
            try
            {
                while (true)
                {
                    try
                    {
                        _future.get();
                        break;
                    }
                    catch (InterruptedException e)
                    {
                        interrupted = true;
                    }

                }
            }
            catch(ExecutionException e)
            {
                if(e.getCause() instanceof RuntimeException)
                {
                    throw (RuntimeException)e.getCause();
                }
                else if(e.getCause() instanceof Error)
                {
                    throw (Error) e.getCause();
                }
                else
                {
                    throw new ServerScopedRuntimeException(e.getCause());
                }
            }
            if(interrupted)
            {
                Thread.currentThread().interrupt();
            }
            _action.postCommit();
            _action = null;
        }
    }

    @Override
    public int getConsumerCount()
    {
        return _tag2SubscriptionTargetMap.size();
    }

    @Override
    public Collection<Consumer<?,ConsumerTarget_0_8>> getConsumers()
    {
        return Collections.unmodifiableCollection(_consumers);
    }

    private class ConsumerClosedListener extends AbstractConfigurationChangeListener
    {
        @Override
        public void stateChanged(final ConfiguredObject object, final State oldState, final State newState)
        {
            if(newState == State.DELETED)
            {
                consumerRemoved((Consumer<?,?>)object);
            }
        }
    }

    private void consumerAdded(final Consumer<?,?> consumer)
    {
        for(ConsumerListener l : _consumerListeners)
        {
            l.consumerAdded(consumer);
        }
    }

    private void consumerRemoved(final Consumer<?,?> consumer)
    {
        for(ConsumerListener l : _consumerListeners)
        {
            l.consumerRemoved(consumer);
        }
    }

    @Override
    public void addConsumerListener(ConsumerListener listener)
    {
        _consumerListeners.add(listener);
    }

    @Override
    public void removeConsumerListener(ConsumerListener listener)
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
        ServerTransaction serverTransaction = _transaction;
        if (serverTransaction.isTransactional())
        {
            return serverTransaction.getTransactionStartTime();
        }
        else
        {
            return 0L;
        }
    }

    @Override
    public long getTransactionUpdateTime()
    {
        ServerTransaction serverTransaction = _transaction;
        if (serverTransaction.isTransactional())
        {
            return serverTransaction.getTransactionUpdateTime();
        }
        else
        {
            return 0L;
        }
    }

    @Override
    public void receiveAccessRequest(final AMQShortString realm,
                                     final boolean exclusive,
                                     final boolean passive,
                                     final boolean active, final boolean write, final boolean read)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] AccessRequest[" +" realm: " + realm +
                          " exclusive: " + exclusive +
                          " passive: " + passive +
                          " active: " + active +
                          " write: " + write + " read: " + read + " ]");
        }

        MethodRegistry methodRegistry = _connection.getMethodRegistry();

        if (ProtocolVersion.v0_91.equals(_connection.getProtocolVersion()))
        {
            _connection.sendConnectionClose(ErrorCodes.COMMAND_INVALID,
                                            "AccessRequest not present in AMQP versions other than 0-8, 0-9",
                                            _channelId);
        }
        else
        {
            // We don't implement access control class, but to keep clients happy that expect it
            // always use the "0" ticket.
            AccessRequestOkBody response = methodRegistry.createAccessRequestOkBody(0);
            sync();
            _connection.writeFrame(response.generateFrame(_channelId));
        }
    }

    @Override
    public void receiveBasicAck(final long deliveryTag, final boolean multiple)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicAck[" +" deliveryTag: " + deliveryTag + " multiple: " + multiple + " ]");
        }

        Collection<MessageConsumerAssociation> ackedMessages = _unacknowledgedMessageMap.acknowledge(deliveryTag, multiple);
        final Collection<MessageInstance> messages = Collections2.transform(ackedMessages, MESSAGE_INSTANCE_FUNCTION);
        _transaction.dequeue(messages, new MessageAcknowledgeAction(ackedMessages));
    }

    @Override
    public void receiveBasicCancel(final AMQShortString consumerTag, final boolean nowait)
    {

        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicCancel[" +" consumerTag: " + consumerTag + " noWait: " + nowait + " ]");
        }

        unsubscribeConsumer(consumerTag);
        if (!nowait)
        {
            MethodRegistry methodRegistry = _connection.getMethodRegistry();
            BasicCancelOkBody cancelOkBody = methodRegistry.createBasicCancelOkBody(consumerTag);
            sync();
            _connection.writeFrame(cancelOkBody.generateFrame(_channelId));
        }
    }

    @Override
    public void receiveBasicConsume(final AMQShortString queue,
                                    final AMQShortString consumerTag,
                                    final boolean noLocal,
                                    final boolean noAck,
                                    final boolean exclusive, final boolean nowait, final FieldTable arguments)
    {

        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicConsume[" +" queue: " + queue +
                          " consumerTag: " + consumerTag +
                          " noLocal: " + noLocal +
                          " noAck: " + noAck +
                          " exclusive: " + exclusive + " nowait: " + nowait + " arguments: " + arguments + " ]");
        }

        AMQShortString consumerTag1 = consumerTag;
        NamedAddressSpace vHost = _connection.getAddressSpace();
        sync();
        String queueName = AMQShortString.toString(queue);

        MessageSource queue1 = queueName == null ? getDefaultQueue() : vHost.getAttainedMessageSource(queueName);
        final Collection<MessageSource> sources = new HashSet<>();

        if (arguments != null && arguments.get("x-multiqueue") instanceof Collection)
        {
            for (Object object : (Collection<Object>) arguments.get("x-multiqueue"))
            {
                String sourceName = String.valueOf(object);
                sourceName = sourceName.trim();
                if (sourceName.length() != 0)
                {
                    MessageSource source = vHost.getAttainedMessageSource(sourceName);
                    if (source == null)
                    {
                        sources.clear();
                        break;
                    }
                    else
                    {
                        sources.add(source);
                    }
                }
            }
            queueName = arguments.get("x-multiqueue").toString();
        }
        else if (queue1 != null)
        {
            sources.add(queue1);
        }


        if (sources.isEmpty())
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("No queue for '" + queueName + "'");
            }
            if (queueName != null)
            {
                closeChannel(ErrorCodes.NOT_FOUND, "No such queue, '" + queueName + "'");
            }
            else
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                                                "No queue name provided, no default queue defined.", _channelId);
            }
        }
        else
        {
            try
            {
                consumerTag1 = consumeFromSource(consumerTag1,
                                                 sources,
                                                 !noAck,
                                                 arguments,
                                                 exclusive,
                                                 noLocal);
                if (!nowait)
                {
                    MethodRegistry methodRegistry = _connection.getMethodRegistry();
                    AMQMethodBody responseBody = methodRegistry.createBasicConsumeOkBody(consumerTag1);
                    _connection.writeFrame(responseBody.generateFrame(_channelId));

                }
            }
            catch (ConsumerTagInUseException cte)
            {

                _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                        "Non-unique consumer tag, '" + consumerTag1
                                + "'", _channelId);
            }
            catch (AMQInvalidArgumentException ise)
            {
                _connection.sendConnectionClose(ErrorCodes.ARGUMENT_INVALID, ise.getMessage(), _channelId);


            }
            catch (Queue.ExistingExclusiveConsumer e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED,
                        "Cannot subscribe to queue '"
                                + queue1.getName()
                                + "' as it already has an existing exclusive consumer", _channelId);

            }
            catch (Queue.ExistingConsumerPreventsExclusive e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED,
                        "Cannot subscribe to queue '"
                                + queue1.getName()
                                + "' exclusively as it already has a consumer", _channelId);

            }
            catch (AccessControlException e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, "Cannot subscribe to queue '"
                                                                           + queue1.getName()
                                                                           + "' permission denied", _channelId);

            }
            catch (MessageSource.ConsumerAccessRefused consumerAccessRefused)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED,
                        "Cannot subscribe to queue '"
                                + queue1.getName()
                                + "' as it already has an incompatible exclusivity policy", _channelId);

            }
            catch (MessageSource.QueueDeleted queueDeleted)
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_FOUND,
                                                "Cannot subscribe to queue '"
                                                + queue1.getName()
                                                + "' as it has been deleted", _channelId);
            }
        }
    }

    @Override
    public void receiveBasicGet(final AMQShortString queueName, final boolean noAck)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicGet[" +" queue: " + queueName + " noAck: " + noAck + " ]");
        }

        NamedAddressSpace vHost = _connection.getAddressSpace();
        sync();
        MessageSource queue = queueName == null ? getDefaultQueue() : vHost.getAttainedMessageSource(queueName.toString());
        if (queue == null)
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("No queue for '" + queueName + "'");
            }
            if (queueName != null)
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_FOUND, "No such queue, '" + queueName + "'", _channelId);

            }
            else
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                                                "No queue name provided, no default queue defined.", _channelId);

            }
        }
        else
        {

            try
            {
                if (!performGet(queue, !noAck))
                {
                    MethodRegistry methodRegistry = _connection.getMethodRegistry();

                    BasicGetEmptyBody responseBody = methodRegistry.createBasicGetEmptyBody(null);

                    _connection.writeFrame(responseBody.generateFrame(_channelId));
                }
            }
            catch (AccessControlException e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), _channelId);
            }
            catch (MessageSource.ExistingExclusiveConsumer e)
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Queue has an exclusive consumer", _channelId);
            }
            catch (MessageSource.ExistingConsumerPreventsExclusive e)
            {
                _connection.sendConnectionClose(ErrorCodes.INTERNAL_ERROR,
                        "The GET request has been evaluated as an exclusive consumer, " +
                                "this is likely due to a programming error in the Qpid broker", _channelId);
            }
            catch (MessageSource.ConsumerAccessRefused consumerAccessRefused)
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                                                "Queue has an incompatible exclusivity policy", _channelId);
            }
            catch (MessageSource.QueueDeleted queueDeleted)
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_FOUND, "Queue has been deleted", _channelId);
            }
        }
    }

    @Override
    public void receiveBasicPublish(final AMQShortString exchangeName,
                                    final AMQShortString routingKey,
                                    final boolean mandatory,
                                    final boolean immediate)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicPublish[" +" exchange: " + exchangeName +
                          " routingKey: " + routingKey +
                          " mandatory: " + mandatory +
                          " immediate: " + immediate + " ]");
        }



        NamedAddressSpace vHost = _connection.getAddressSpace();

        if(blockingTimeoutExceeded())
        {
            message(ChannelMessages.FLOW_CONTROL_IGNORED());
            closeChannel(ErrorCodes.MESSAGE_TOO_LARGE,
                         "Channel flow control was requested, but not enforced by sender");
        }
        else
        {
            MessageDestination destination;

            if (isDefaultExchange(exchangeName))
            {
                destination = vHost.getDefaultDestination();
            }
            else
            {
                destination = vHost.getAttainedMessageDestination(exchangeName.toString());
            }

            // if the exchange does not exist we raise a channel exception
            if (destination == null)
            {
                closeChannel(ErrorCodes.NOT_FOUND, "Unknown exchange name: '" + exchangeName + "'");
            }
            else
            {

                MessagePublishInfo info = new MessagePublishInfo(exchangeName,
                                                                 immediate,
                                                                 mandatory,
                                                                 routingKey);

                try
                {
                    setPublishFrame(info, destination);
                }
                catch (AccessControlException e)
                {
                    _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());

                }
            }
        }
    }

    @Override
    public EventLogger getEventLogger()
    {
        return getConnection().getEventLogger();
    }

    private boolean blockingTimeoutExceeded()
    {

        return _wireBlockingState && (System.currentTimeMillis() - _blockTime) > _blockingTimeout;
    }

    @Override
    public void receiveBasicQos(final long prefetchSize, final int prefetchCount, final boolean global)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicQos[" +" prefetchSize: " + prefetchSize + " prefetchCount: " + prefetchCount + " global: " + global + " ]");
        }

        sync();
        setCredit(prefetchSize, prefetchCount);

        MethodRegistry methodRegistry = _connection.getMethodRegistry();
        AMQMethodBody responseBody = methodRegistry.createBasicQosOkBody();
        _connection.writeFrame(responseBody.generateFrame(getChannelId()));

    }

    @Override
    public void receiveBasicRecover(final boolean requeue, final boolean sync)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicRecover[" + " requeue: " + requeue + " sync: " + sync + " ]");
        }

        if (requeue)
        {
            requeue();
        }
        else
        {
            resend();
        }

        if (sync)
        {
            MethodRegistry methodRegistry = _connection.getMethodRegistry();
            AMQMethodBody recoverOk = methodRegistry.createBasicRecoverSyncOkBody();
            sync();
            _connection.writeFrame(recoverOk.generateFrame(getChannelId()));

        }

    }

    @Override
    public void receiveBasicReject(final long deliveryTag, final boolean requeue)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicReject[" +" deliveryTag: " + deliveryTag + " requeue: " + requeue + " ]");
        }

        MessageInstance message = getUnacknowledgedMessageMap().get(deliveryTag);

        if (message == null)
        {
            _logger.warn("Dropping reject request as message is null for tag:" + deliveryTag);
        }
        else
        {

            if (message.getMessage() == null)
            {
                _logger.warn("Message has already been purged, unable to Reject.");
            }
            else
            {

                if (_logger.isDebugEnabled())
                {
                    _logger.debug("Rejecting: DT:" + deliveryTag
                                                             + "-" + message.getMessage() +
                                  ": Requeue:" + requeue
                                  +
                                  " on channel:" + debugIdentity());
                }

                if (requeue)
                {
                    message.decrementDeliveryCount();

                    requeue(deliveryTag);
                }
                else
                {
                    // Since the JMS client abuses the reject flag for requeing after rollback, we won't set reject here
                    // as it would prevent redelivery
                    // message.reject();

                    final boolean maxDeliveryCountEnabled = isMaxDeliveryCountEnabled(deliveryTag);
                    if (_logger.isDebugEnabled())
                    {
                        _logger.debug("maxDeliveryCountEnabled: "
                                      + maxDeliveryCountEnabled
                                      + " deliveryTag "
                                      + deliveryTag);
                    }
                    if (maxDeliveryCountEnabled)
                    {
                        final boolean deliveredTooManyTimes = isDeliveredTooManyTimes(deliveryTag);
                        if (_logger.isDebugEnabled())
                        {
                            _logger.debug("deliveredTooManyTimes: "
                                          + deliveredTooManyTimes
                                          + " deliveryTag "
                                          + deliveryTag);
                        }
                        if (deliveredTooManyTimes)
                        {
                            deadLetter(deliveryTag);
                        }
                        else
                        {
                            //this requeue represents a message rejected because of a recover/rollback that we
                            //are not ready to DLQ. We rely on the reject command to resend from the unacked map
                            //and therefore need to increment the delivery counter so we cancel out the effect
                            //of the AMQChannel#resend() decrement.
                            message.incrementDeliveryCount();
                        }
                    }
                    else
                    {
                        requeue(deliveryTag);
                    }
                }
            }
        }
    }

    @Override
    public void receiveChannelClose(final int replyCode,
                                    final AMQShortString replyText,
                                    final int classId,
                                    final int methodId)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ChannelClose[" +" replyCode: " + replyCode + " replyText: " + replyText + " classId: " + classId + " methodId: " + methodId + " ]");
        }


        sync();
        _connection.closeChannel(this);

        _connection.writeFrame(new AMQFrame(getChannelId(),
                                            _connection.getMethodRegistry().createChannelCloseOkBody()));
    }

    @Override
    public void receiveChannelCloseOk()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ChannelCloseOk");
        }

        _connection.closeChannelOk(getChannelId());
    }

    @Override
    public void receiveMessageContent(final QpidByteBuffer data)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] MessageContent[" +" data: " + hex(data,_connection.getBinaryDataLimit()) + " ] ");
        }

        if(hasCurrentMessage())
        {
            publishContentBody(new ContentBody(data));
        }
        else
        {
            _connection.sendConnectionClose(ErrorCodes.COMMAND_INVALID,
                                            "Attempt to send a content header without first sending a publish frame",
                                            _channelId);
        }
    }

    @Override
    public void receiveMessageHeader(final BasicContentHeaderProperties properties, final long bodySize)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] MessageHeader[ properties: {" + properties + "} bodySize: " + bodySize + " ]");
        }

        if(hasCurrentMessage())
        {
            if(bodySize > _connection.getMaxMessageSize())
            {
                closeChannel(ErrorCodes.MESSAGE_TOO_LARGE,
                             "Message size of " + bodySize + " greater than allowed maximum of " + _connection.getMaxMessageSize());
            }
            publishContentHeader(new ContentHeaderBody(properties, bodySize));
        }
        else
        {
            _connection.sendConnectionClose(ErrorCodes.COMMAND_INVALID,
                                            "Attempt to send a content header without first sending a publish frame",
                                            _channelId);
        }
    }

    @Override
    public boolean ignoreAllButCloseOk()
    {
        return _connection.ignoreAllButCloseOk() || _connection.channelAwaitingClosure(_channelId);
    }

    @Override
    public void receiveBasicNack(final long deliveryTag, final boolean multiple, final boolean requeue)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] BasicNack[" +" deliveryTag: " + deliveryTag + " multiple: " + multiple + " requeue: " + requeue + " ]");
        }

        Map<Long, MessageConsumerAssociation> nackedMessageMap = new LinkedHashMap<>();
        _unacknowledgedMessageMap.collect(deliveryTag, multiple, nackedMessageMap);

        for(MessageConsumerAssociation unackedMessageConsumerAssociation : nackedMessageMap.values())
        {

            if (unackedMessageConsumerAssociation == null)
            {
                _logger.warn("Ignoring nack request as message is null for tag:" + deliveryTag);
            }
            else
            {
                MessageInstance message = unackedMessageConsumerAssociation.getMessageInstance();
                if (message.getMessage() == null)
                {
                    _logger.warn("Message has already been purged, unable to nack.");
                }
                else
                {
                    if (_logger.isDebugEnabled())
                    {
                        _logger.debug("Nack-ing: DT:" + deliveryTag
                                      + "-" + message.getMessage() +
                                      ": Requeue:" + requeue
                                      +
                                      " on channel:" + debugIdentity());
                    }

                    if (requeue)
                    {
                        message.decrementDeliveryCount();

                        requeue(deliveryTag);
                    }
                    else
                    {
                        message.reject();

                        final boolean maxDeliveryCountEnabled = isMaxDeliveryCountEnabled(deliveryTag);
                        if (_logger.isDebugEnabled())
                        {
                            _logger.debug("maxDeliveryCountEnabled: "
                                          + maxDeliveryCountEnabled
                                          + " deliveryTag "
                                          + deliveryTag);
                        }
                        if (maxDeliveryCountEnabled)
                        {
                            final boolean deliveredTooManyTimes = isDeliveredTooManyTimes(deliveryTag);
                            if (_logger.isDebugEnabled())
                            {
                                _logger.debug("deliveredTooManyTimes: "
                                              + deliveredTooManyTimes
                                              + " deliveryTag "
                                              + deliveryTag);
                            }
                            if (deliveredTooManyTimes)
                            {
                                deadLetter(deliveryTag);
                            }
                            else
                            {
                                message.incrementDeliveryCount();
                            }
                        }
                        else
                        {
                            requeue(deliveryTag);
                        }
                    }
                }
            }

        }

    }

    @Override
    public void receiveChannelFlow(final boolean active)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ChannelFlow[" +" active: " + active + " ]");
        }


        sync();
        if(_channelFlow != active)
        {
            _channelFlow = active;
            // inform consumer targets
            updateAllConsumerNotifyWorkDesired();
        }

        setSuspended(!active);

        MethodRegistry methodRegistry = _connection.getMethodRegistry();
        AMQMethodBody responseBody = methodRegistry.createChannelFlowOkBody(active);
        _connection.writeFrame(responseBody.generateFrame(getChannelId()));

    }

    @Override
    public void receiveChannelFlowOk(final boolean active)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ChannelFlowOk[" +" active: " + active + " ]");
        }

        // TODO - should we do anything here?
    }

    @Override
    public void receiveExchangeBound(final AMQShortString exchangeName,
                                     final AMQShortString routingKey,
                                     final AMQShortString queueName)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ExchangeBound[" +" exchange: " + exchangeName + " routingKey: " +
                          routingKey + " queue: " + queueName + " ]");
        }

        NamedAddressSpace virtualHost = _connection.getAddressSpace();
        MethodRegistry methodRegistry = _connection.getMethodRegistry();

        sync();

        int replyCode;
        String replyText;

        if (isDefaultExchange(exchangeName))
        {
            if (routingKey == null)
            {
                if (queueName == null)
                {
                    replyCode = virtualHost.hasMessageSources()
                            ? ExchangeBoundOkBody.OK
                            : ExchangeBoundOkBody.NO_BINDINGS;
                    replyText = null;

                }
                else
                {
                    MessageSource queue = virtualHost.getAttainedMessageSource(queueName.toString());
                    if (queue == null)
                    {
                        replyCode = ExchangeBoundOkBody.QUEUE_NOT_FOUND;
                        replyText = "Queue '" + queueName + "' not found";
                    }
                    else
                    {
                        replyCode = ExchangeBoundOkBody.OK;
                        replyText = null;
                    }
                }
            }
            else
            {
                if (queueName == null)
                {
                    replyCode = virtualHost.getAttainedMessageDestination(routingKey.toString()) instanceof Queue
                            ? ExchangeBoundOkBody.OK
                            : ExchangeBoundOkBody.NO_QUEUE_BOUND_WITH_RK;
                    replyText = null;
                }
                else
                {
                    MessageDestination destination = virtualHost.getAttainedMessageDestination(queueName.toString());
                    Queue<?> queue = destination instanceof Queue ? (Queue) destination : null;
                    if (queue == null)
                    {

                        replyCode = ExchangeBoundOkBody.QUEUE_NOT_FOUND;
                        replyText = "Queue '" + queueName + "' not found";
                    }
                    else
                    {
                        replyCode = queueName.equals(routingKey)
                                ? ExchangeBoundOkBody.OK
                                : ExchangeBoundOkBody.SPECIFIC_QUEUE_NOT_BOUND_WITH_RK;
                        replyText = null;
                    }
                }
            }
        }
        else
        {
            Exchange<?> exchange = getExchange(exchangeName.toString());
            if (exchange == null)
            {

                replyCode = ExchangeBoundOkBody.EXCHANGE_NOT_FOUND;
                replyText = "Exchange '" + exchangeName + "' not found";
            }
            else if (routingKey == null)
            {
                if (queueName == null)
                {
                    if (exchange.hasBindings())
                    {
                        replyCode = ExchangeBoundOkBody.OK;
                        replyText = null;
                    }
                    else
                    {
                        replyCode = ExchangeBoundOkBody.NO_BINDINGS;
                        replyText = null;
                    }
                }
                else
                {
                    Queue<?> queue = getQueue(queueName.toString());
                    if (queue == null)
                    {
                        replyCode = ExchangeBoundOkBody.QUEUE_NOT_FOUND;
                        replyText = "Queue '" + queueName + "' not found";
                    }
                    else
                    {
                        if (exchange.isBound(queue))
                        {
                            replyCode = ExchangeBoundOkBody.OK;
                            replyText = null;
                        }
                        else
                        {
                            replyCode = ExchangeBoundOkBody.QUEUE_NOT_BOUND;
                            replyText = "Queue '"
                                        + queueName
                                        + "' not bound to exchange '"
                                        + exchangeName
                                        + "'";
                        }
                    }
                }
            }
            else if (queueName != null)
            {
                Queue<?> queue = getQueue(queueName.toString());
                if (queue == null)
                {
                    replyCode = ExchangeBoundOkBody.QUEUE_NOT_FOUND;
                    replyText = "Queue '" + queueName + "' not found";
                }
                else
                {
                    String bindingKey = routingKey == null ? null : routingKey.toString();
                    if (exchange.isBound(bindingKey, queue))
                    {

                        replyCode = ExchangeBoundOkBody.OK;
                        replyText = null;
                    }
                    else
                    {
                        replyCode = ExchangeBoundOkBody.SPECIFIC_QUEUE_NOT_BOUND_WITH_RK;
                        replyText = "Queue '" + queueName + "' not bound with routing key '" +
                                    routingKey + "' to exchange '" + exchangeName + "'";

                    }
                }
            }
            else
            {
                if (exchange.isBound(routingKey == null ? "" : routingKey.toString()))
                {

                    replyCode = ExchangeBoundOkBody.OK;
                    replyText = null;
                }
                else
                {
                    replyCode = ExchangeBoundOkBody.NO_QUEUE_BOUND_WITH_RK;
                    replyText =
                            "No queue bound with routing key '" + routingKey + "' to exchange '" + exchangeName + "'";
                }
            }
        }

        ExchangeBoundOkBody exchangeBoundOkBody =
                methodRegistry.createExchangeBoundOkBody(replyCode, AMQShortString.validValueOf(replyText));

        _connection.writeFrame(exchangeBoundOkBody.generateFrame(getChannelId()));

    }

    @Override
    public void receiveExchangeDeclare(final AMQShortString exchangeName,
                                       final AMQShortString type,
                                       final boolean passive,
                                       final boolean durable,
                                       final boolean autoDelete,
                                       final boolean internal,
                                       final boolean nowait,
                                       final FieldTable arguments)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ExchangeDeclare[" +" exchange: " + exchangeName +
                          " type: " + type +
                          " passive: " + passive +
                          " durable: " + durable +
                          " autoDelete: " + autoDelete +
                          " internal: " + internal + " nowait: " + nowait + " arguments: " + arguments + " ]");
        }

        final MethodRegistry methodRegistry = _connection.getMethodRegistry();
        final AMQMethodBody declareOkBody = methodRegistry.createExchangeDeclareOkBody();

        Exchange<?> exchange;
        NamedAddressSpace virtualHost = _connection.getAddressSpace();

        if (isDefaultExchange(exchangeName))
        {
            if (!new AMQShortString(ExchangeDefaults.DIRECT_EXCHANGE_CLASS).equals(type))
            {
                _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Attempt to redeclare default exchange: "
                                                                        + " of type "
                                                                        + ExchangeDefaults.DIRECT_EXCHANGE_CLASS
                                                                        + " to " + type + ".", getChannelId());
            }
            else if (!nowait)
            {
                sync();
                _connection.writeFrame(declareOkBody.generateFrame(getChannelId()));
            }

        }
        else
        {
            if (passive)
            {
                exchange = getExchange(exchangeName.toString());
                if (exchange == null)
                {
                    closeChannel(ErrorCodes.NOT_FOUND, "Unknown exchange: '" + exchangeName + "'");
                }
                else if (!(type == null || type.length() == 0) && !exchange.getType().equals(type.toString()))
                {

                    _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Attempt to redeclare exchange: '"
                                                                            + exchangeName
                                                                            + "' of type "
                                                                            + exchange.getType()
                                                                            + " to "
                                                                            + type
                                                                            + ".", getChannelId());
                }
                else if (!nowait)
                {
                    sync();
                    _connection.writeFrame(declareOkBody.generateFrame(getChannelId()));
                }

            }
            else
            {
                String name = exchangeName.toString();
                String typeString = type == null ? null : type.toString();
                try
                {

                    Map<String, Object> attributes = new HashMap<String, Object>();
                    if (arguments != null)
                    {
                        attributes.putAll(FieldTable.convertToMap(arguments));
                    }
                    attributes.put(Exchange.NAME, name);
                    attributes.put(Exchange.TYPE, typeString);
                    attributes.put(Exchange.DURABLE, durable);
                    attributes.put(Exchange.LIFETIME_POLICY,
                                   autoDelete ? LifetimePolicy.DELETE_ON_NO_LINKS : LifetimePolicy.PERMANENT);
                    if (!attributes.containsKey(Exchange.ALTERNATE_EXCHANGE))
                    {
                        attributes.put(Exchange.ALTERNATE_EXCHANGE, null);
                    }
                    exchange = virtualHost.createMessageDestination(Exchange.class, attributes);

                    if (!nowait)
                    {
                        sync();
                        _connection.writeFrame(declareOkBody.generateFrame(getChannelId()));
                    }

                }
                catch (ReservedExchangeNameException e)
                {
                    Exchange existing = getExchange(name);
                    if (existing == null || !existing.getType().equals(typeString))
                    {
                        _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                                                        "Attempt to declare exchange: '" + exchangeName +
                                                        "' which begins with reserved prefix.", getChannelId());
                    }
                    else if(!nowait)
                    {
                        sync();
                        _connection.writeFrame(declareOkBody.generateFrame(getChannelId()));
                    }
                }
                catch (AbstractConfiguredObject.DuplicateNameException e)
                {
                    exchange = (Exchange<?>) e.getExisting();
                    if (!exchange.getType().equals(typeString))
                    {
                        _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Attempt to redeclare exchange: '"
                                                                                + exchangeName + "' of type "
                                                                                + exchange.getType()
                                                                                + " to " + type + ".", getChannelId());
                    }
                    else
                    {
                        if (!nowait)
                        {
                            sync();
                            _connection.writeFrame(declareOkBody.generateFrame(getChannelId()));
                        }
                    }
                }
                catch (NoFactoryForTypeException e)
                {
                    _connection.sendConnectionClose(ErrorCodes.COMMAND_INVALID, "Unknown exchange type '"
                                                                                + e.getType()
                                                                                + "' for exchange '"
                                                                                + exchangeName
                                                                                + "'", getChannelId());

                }
                catch (AccessControlException e)
                {
                    _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());

                }
                catch (UnknownConfiguredObjectException e)
                {
                    // note - since 0-8/9/9-1 can't set the alt. exchange this exception should never occur
                    final String message = "Unknown alternate exchange "
                                           + (e.getName() != null
                            ? "name: '" + e.getName() + "'"
                            : "id: " + e.getId());
                    _connection.sendConnectionClose(ErrorCodes.NOT_FOUND, message, getChannelId());

                }
                catch (IllegalArgumentException e)
                {
                    _connection.sendConnectionClose(ErrorCodes.COMMAND_INVALID, "Error creating exchange '"
                                                                                + exchangeName
                                                                                + "': "
                                                                                + e.getMessage(), getChannelId());

                }
            }
        }

    }

    @Override
    public void receiveExchangeDelete(final AMQShortString exchangeStr, final boolean ifUnused, final boolean nowait)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ExchangeDelete[" +" exchange: " + exchangeStr + " ifUnused: " + ifUnused + " nowait: " + nowait + " ]");
        }


        NamedAddressSpace virtualHost = _connection.getAddressSpace();
        sync();

        if (isDefaultExchange(exchangeStr))
        {
            _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                                            "Default Exchange cannot be deleted", getChannelId());

        }

        else
        {
            final String exchangeName = exchangeStr.toString();

            final Exchange<?> exchange = getExchange(exchangeName);
            if (exchange == null)
            {
                closeChannel(ErrorCodes.NOT_FOUND, "No such exchange: '" + exchangeStr + "'");
            }
            else
            {
                if (ifUnused && exchange.hasBindings())
                {
                    closeChannel(ErrorCodes.IN_USE, "Exchange has bindings");
                }
                else
                {
                    try
                    {
                        exchange.delete();


                        if (!nowait)
                        {
                            ExchangeDeleteOkBody responseBody = _connection.getMethodRegistry().createExchangeDeleteOkBody();
                            _connection.writeFrame(responseBody.generateFrame(getChannelId()));
                        }
                    }
                    catch (ExchangeIsAlternateException e)
                    {
                        closeChannel(ErrorCodes.NOT_ALLOWED, "Exchange in use as an alternate exchange");
                    }
                    catch (RequiredExchangeException e)
                    {
                        closeChannel(ErrorCodes.NOT_ALLOWED, "Exchange '" + exchangeStr + "' cannot be deleted");
                    }
                    catch (AccessControlException e)
                    {
                        _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());
                    }
                }
            }
        }
    }

    @Override
    public void receiveQueueBind(final AMQShortString queueName,
                                 final AMQShortString exchange,
                                 AMQShortString bindingKey,
                                 final boolean nowait,
                                 final FieldTable argumentsTable)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] QueueBind[" +" queue: " + queueName +
                          " exchange: " + exchange +
                          " bindingKey: " + bindingKey +
                          " nowait: " + nowait + " arguments: " + argumentsTable + " ]");
        }

        NamedAddressSpace virtualHost = _connection.getAddressSpace();
        Queue<?> queue;
        if (queueName == null)
        {

            queue = getDefaultQueue();

            if (queue != null)
            {
                if (bindingKey == null)
                {
                    bindingKey = AMQShortString.valueOf(queue.getName());
                }
            }
        }
        else
        {
            queue = getQueue(queueName.toString());
        }

        if (queue == null)
        {
            String message = queueName == null
                    ? "No default queue defined on channel and queue was null"
                    : "Queue " + queueName + " does not exist.";
                closeChannel(ErrorCodes.NOT_FOUND, message);
        }
        else if (isDefaultExchange(exchange))
        {
            _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                    "Cannot bind the queue '" + queueName + "' to the default exchange", getChannelId());

        }
        else
        {

            final String exchangeName = exchange.toString();

            final Exchange<?> exch = getExchange(exchangeName);
            if (exch == null)
            {
                closeChannel(ErrorCodes.NOT_FOUND,
                             "Exchange '" + exchangeName + "' does not exist.");
            }
            else
            {

                try
                {

                    Map<String, Object> arguments = FieldTable.convertToMap(argumentsTable);
                    String bindingKeyStr = AMQShortString.toString(bindingKey);

                    if (!exch.isBound(bindingKeyStr, arguments, queue))
                    {

                        if (!exch.addBinding(bindingKeyStr, queue, arguments)
                            && ExchangeDefaults.TOPIC_EXCHANGE_CLASS.equals(
                                exch.getType()))
                        {
                            exch.replaceBinding(bindingKeyStr, queue, arguments);
                        }
                    }

                    if (_logger.isDebugEnabled())
                    {
                        _logger.debug("Binding queue "
                                     + queue
                                     + " to exchange "
                                     + exch
                                     + " with routing key "
                                     + bindingKeyStr);
                    }
                    if (!nowait)
                    {
                        sync();
                        MethodRegistry methodRegistry = _connection.getMethodRegistry();
                        AMQMethodBody responseBody = methodRegistry.createQueueBindOkBody();
                        _connection.writeFrame(responseBody.generateFrame(getChannelId()));

                    }
                }
                catch (AccessControlException e)
                {
                    _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());
                }
            }
        }
    }

    @Override
    public void receiveQueueDeclare(final AMQShortString queueStr,
                                    final boolean passive,
                                    final boolean durable,
                                    final boolean exclusive,
                                    final boolean autoDelete,
                                    final boolean nowait,
                                    final FieldTable arguments)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] QueueDeclare[" +" queue: " + queueStr +
                          " passive: " + passive +
                          " durable: " + durable +
                          " exclusive: " + exclusive +
                          " autoDelete: " + autoDelete + " nowait: " + nowait + " arguments: " + arguments + " ]");
        }

        NamedAddressSpace virtualHost = _connection.getAddressSpace();

        final AMQShortString queueName;

        // if we aren't given a queue name, we create one which we return to the client
        if ((queueStr == null) || (queueStr.length() == 0))
        {
            queueName = new AMQShortString("tmp_" + UUID.randomUUID());
        }
        else
        {
            queueName = queueStr;
        }

        Queue<?> queue;

        //TODO: do we need to check that the queue already exists with exactly the same "configuration"?


        if (passive)
        {
            queue = getQueue(queueName.toString());
            if (queue == null)
            {
                closeChannel(ErrorCodes.NOT_FOUND,
                                                     "Queue: '"
                                                     + queueName
                                                     + "' not found on VirtualHost '"
                                                     + virtualHost.getName()
                                                     + "'.");
            }
            else
            {
                if (!queue.verifySessionAccess(this))
                {
                    _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Queue '"
                                                                            + queue.getName()
                                                                            + "' is exclusive, but not created on this Connection.", getChannelId());
                }
                else
                {
                    //set this as the default queue on the channel:
                    setDefaultQueue(queue);
                    if (!nowait)
                    {
                        sync();
                        MethodRegistry methodRegistry = _connection.getMethodRegistry();
                        QueueDeclareOkBody responseBody =
                                methodRegistry.createQueueDeclareOkBody(queueName,
                                                                        queue.getQueueDepthMessages(),
                                                                        queue.getConsumerCount());
                        _connection.writeFrame(responseBody.generateFrame(getChannelId()));

                        if (_logger.isDebugEnabled())
                        {
                            _logger.debug("Queue " + queueName + " declared successfully");
                        }
                    }
                }
            }
        }
        else
        {

            try
            {
                Map<String, Object> attributes =
                        QueueArgumentsConverter.convertWireArgsToModel(FieldTable.convertToMap(arguments));
                final String queueNameString = AMQShortString.toString(queueName);
                attributes.put(Queue.NAME, queueNameString);
                attributes.put(Queue.DURABLE, durable);

                LifetimePolicy lifetimePolicy;
                ExclusivityPolicy exclusivityPolicy;

                if (exclusive)
                {
                    lifetimePolicy = autoDelete
                            ? LifetimePolicy.DELETE_ON_NO_OUTBOUND_LINKS
                            : durable ? LifetimePolicy.PERMANENT : LifetimePolicy.DELETE_ON_CONNECTION_CLOSE;
                    exclusivityPolicy = durable ? ExclusivityPolicy.CONTAINER : ExclusivityPolicy.CONNECTION;
                }
                else
                {
                    lifetimePolicy = autoDelete ? LifetimePolicy.DELETE_ON_NO_OUTBOUND_LINKS : LifetimePolicy.PERMANENT;
                    exclusivityPolicy = ExclusivityPolicy.NONE;
                }

                if(!attributes.containsKey(Queue.EXCLUSIVE))
                {
                    attributes.put(Queue.EXCLUSIVE, exclusivityPolicy);
                }
                if(!attributes.containsKey(Queue.LIFETIME_POLICY))
                {
                    attributes.put(Queue.LIFETIME_POLICY, lifetimePolicy);
                }

                queue = virtualHost.createMessageSource(Queue.class, attributes);

                setDefaultQueue(queue);

                if (!nowait)
                {
                    sync();
                    MethodRegistry methodRegistry = _connection.getMethodRegistry();
                    QueueDeclareOkBody responseBody =
                            methodRegistry.createQueueDeclareOkBody(queueName,
                                                                    queue.getQueueDepthMessages(),
                                                                    queue.getConsumerCount());
                    _connection.writeFrame(responseBody.generateFrame(getChannelId()));

                    if (_logger.isDebugEnabled())
                    {
                        _logger.debug("Queue " + queueName + " declared successfully");
                    }
                }
            }
            catch (AbstractConfiguredObject.DuplicateNameException qe)
            {

                queue = (Queue<?>) qe.getExisting();

                if (!queue.verifySessionAccess(this))
                {
                    _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Queue '"
                                                                            + queue.getName()
                                                                            + "' is exclusive, but not created on this Connection.", getChannelId());

                }
                else if (queue.isExclusive() != exclusive)
                {

                    closeChannel(ErrorCodes.ALREADY_EXISTS,
                                                         "Cannot re-declare queue '"
                                                         + queue.getName()
                                                         + "' with different exclusivity (was: "
                                                         + queue.isExclusive()
                                                         + " requested "
                                                         + exclusive
                                                         + ")");
                }
                else if ((autoDelete
                          && queue.getLifetimePolicy() == LifetimePolicy.PERMANENT)
                         || (!autoDelete && queue.getLifetimePolicy() != ((exclusive
                                                                           && !durable)
                        ? LifetimePolicy.DELETE_ON_CONNECTION_CLOSE
                        : LifetimePolicy.PERMANENT)))
                {
                    closeChannel(ErrorCodes.ALREADY_EXISTS,
                                                         "Cannot re-declare queue '"
                                                         + queue.getName()
                                                         + "' with different lifetime policy (was: "
                                                         + queue.getLifetimePolicy()
                                                         + " requested autodelete: "
                                                         + autoDelete
                                                         + ")");
                }
                else
                {
                    setDefaultQueue(queue);
                    if (!nowait)
                    {
                        sync();
                        MethodRegistry methodRegistry = _connection.getMethodRegistry();
                        QueueDeclareOkBody responseBody =
                                methodRegistry.createQueueDeclareOkBody(queueName,
                                                                        queue.getQueueDepthMessages(),
                                                                        queue.getConsumerCount());
                        _connection.writeFrame(responseBody.generateFrame(getChannelId()));

                        if (_logger.isDebugEnabled())
                        {
                            _logger.debug("Queue " + queueName + " declared successfully");
                        }
                    }
                }
            }
            catch (AccessControlException e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());
            }

        }
    }

    @Override
    public void receiveQueueDelete(final AMQShortString queueName,
                                   final boolean ifUnused,
                                   final boolean ifEmpty,
                                   final boolean nowait)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] QueueDelete[" +" queue: " + queueName + " ifUnused: " + ifUnused + " ifEmpty: " + ifEmpty + " nowait: " + nowait + " ]");
        }

        NamedAddressSpace virtualHost = _connection.getAddressSpace();
        sync();
        Queue<?> queue;
        if (queueName == null)
        {

            //get the default queue on the channel:
            queue = getDefaultQueue();
        }
        else
        {
            queue = getQueue(queueName.toString());
        }

        if (queue == null)
        {
            closeChannel(ErrorCodes.NOT_FOUND, "Queue '" + queueName + "' does not exist.");

        }
        else
        {
            if (ifEmpty && !queue.isEmpty())
            {
                closeChannel(ErrorCodes.IN_USE, "Queue: '" + queueName + "' is not empty.");
            }
            else if (ifUnused && !queue.isUnused())
            {
                // TODO - Error code
                closeChannel(ErrorCodes.IN_USE, "Queue: '" + queueName + "' is still used.");
            }
            else
            {
                if (!queue.verifySessionAccess(this))
                {
                    _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Queue '"
                                                                            + queue.getName()
                                                                            + "' is exclusive, but not created on this Connection.", getChannelId());

                }
                else
                {
                    try
                    {
                        int purged = queue.deleteAndReturnCount();

                        if (!nowait || _connection.isSendQueueDeleteOkRegardless())
                        {
                            MethodRegistry methodRegistry = _connection.getMethodRegistry();
                            QueueDeleteOkBody responseBody = methodRegistry.createQueueDeleteOkBody(purged);
                            _connection.writeFrame(responseBody.generateFrame(getChannelId()));
                        }
                    }
                    catch (AccessControlException e)
                    {
                        _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());

                    }
                }
            }
        }
    }

    @Override
    public void receiveQueuePurge(final AMQShortString queueName, final boolean nowait)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] QueuePurge[" +" queue: " + queueName + " nowait: " + nowait + " ]");
        }

        NamedAddressSpace virtualHost = _connection.getAddressSpace();
        Queue<?> queue = null;
        if (queueName == null && (queue = getDefaultQueue()) == null)
        {

            _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "No queue specified.", getChannelId());
        }
        else if ((queueName != null) && (queue = getQueue(queueName.toString())) == null)
        {
            closeChannel(ErrorCodes.NOT_FOUND, "Queue '" + queueName + "' does not exist.");
        }
        else if (!queue.verifySessionAccess(this))
        {
            _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED,
                                            "Queue is exclusive, but not created on this Connection.", getChannelId());
        }
        else
        {
            try
            {
                long purged = queue.clearQueue();
                if (!nowait)
                {
                    sync();
                    MethodRegistry methodRegistry = _connection.getMethodRegistry();
                    AMQMethodBody responseBody = methodRegistry.createQueuePurgeOkBody(purged);
                    _connection.writeFrame(responseBody.generateFrame(getChannelId()));

                }
            }
            catch (AccessControlException e)
            {
                _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());

            }

        }
    }

    @Override
    public void receiveQueueUnbind(final AMQShortString queueName,
                                   final AMQShortString exchange,
                                   final AMQShortString bindingKey,
                                   final FieldTable arguments)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] QueueUnbind[" +" queue: " + queueName +
                          " exchange: " + exchange +
                          " bindingKey: " + bindingKey +
                          " arguments: " + arguments + " ]");
        }

        NamedAddressSpace virtualHost = _connection.getAddressSpace();


        final boolean useDefaultQueue = queueName == null;
        final Queue<?> queue = useDefaultQueue
                ? getDefaultQueue()
                : getQueue(queueName.toString());


        if (queue == null)
        {
            String message = useDefaultQueue
                    ? "No default queue defined on channel and queue was null"
                    : "Queue '" + queueName + "' does not exist.";
            closeChannel(ErrorCodes.NOT_FOUND, message);
        }
        else if (isDefaultExchange(exchange))
        {
            _connection.sendConnectionClose(ErrorCodes.NOT_ALLOWED, "Cannot unbind the queue '"
                                                                    + queue.getName()
                                                                    + "' from the default exchange", getChannelId());

        }
        else
        {
            final Exchange<?> exch = getExchange(exchange.toString());

            if (exch == null)
            {
                closeChannel(ErrorCodes.NOT_FOUND, "Exchange '" + exchange + "' does not exist.");
            }
            else if (!exch.hasBinding(AMQShortString.toString(bindingKey), queue))
            {
                closeChannel(ErrorCodes.NOT_FOUND, "No such binding");
            }
            else
            {
                try
                {
                    exch.deleteBinding(AMQShortString.toString(bindingKey), queue);

                    final AMQMethodBody responseBody = _connection.getMethodRegistry().createQueueUnbindOkBody();
                    sync();
                    _connection.writeFrame(responseBody.generateFrame(getChannelId()));
                }
                catch (AccessControlException e)
                {
                    _connection.sendConnectionClose(ErrorCodes.ACCESS_REFUSED, e.getMessage(), getChannelId());

                }
            }

        }
    }

    @Override
    public void receiveTxSelect()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] TxSelect");
        }

        setLocalTransactional();

        MethodRegistry methodRegistry = _connection.getMethodRegistry();
        TxSelectOkBody responseBody = methodRegistry.createTxSelectOkBody();
        _connection.writeFrame(responseBody.generateFrame(_channelId));

    }

    @Override
    public void receiveTxCommit()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] TxCommit");
        }


        if (!isTransactional())
        {
            closeChannel(ErrorCodes.COMMAND_INVALID,
                         "Fatal error: commit called on non-transactional channel");
        }
        commit(new Runnable()
        {

            @Override
            public void run()
            {
                _connection.writeFrame(_txCommitOkFrame);
            }
        }, true);

    }

    @Override
    public void receiveTxRollback()
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] TxRollback");
        }

        if (!isTransactional())
        {
            closeChannel(ErrorCodes.COMMAND_INVALID,
                         "Fatal error: rollback called on non-transactional channel");
        }

        final MethodRegistry methodRegistry = _connection.getMethodRegistry();
        final AMQMethodBody responseBody = methodRegistry.createTxRollbackOkBody();

        Runnable task = new Runnable()
        {

            public void run()
            {
                _connection.writeFrame(responseBody.generateFrame(_channelId));
            }
        };

        rollback(task);

        // TODO: This is not spec compliant but we currently seem to rely on this behaviour
        resend();
    }

    @Override
    public void receiveConfirmSelect(final boolean nowait)
    {
        if(_logger.isDebugEnabled())
        {
            _logger.debug("RECV[" + _channelId + "] ConfirmSelect [ nowait: " + nowait + " ]");
        }
        _confirmOnPublish = true;

        if(!nowait)
        {
            _connection.writeFrame(new AMQFrame(_channelId, ConfirmSelectOkBody.INSTANCE));
        }
    }



    private void closeChannel(int cause, final String message)
    {
        _connection.closeChannelAndWriteFrame(this, cause, message);
    }


    private boolean isDefaultExchange(final AMQShortString exchangeName)
    {
        return exchangeName == null || AMQShortString.EMPTY_STRING.equals(exchangeName);
    }

    private void setDefaultQueue(Queue<?> queue)
    {
        Queue<?> currentDefaultQueue = _defaultQueue;
        if (queue != currentDefaultQueue)
        {
            if (currentDefaultQueue != null)
            {
                currentDefaultQueue.removeDeleteTask(_defaultQueueAssociationClearingTask);
            }
            if (queue != null)
            {
                queue.addDeleteTask(_defaultQueueAssociationClearingTask);
            }
        }
        _defaultQueue = queue;
    }

    private Queue<?> getDefaultQueue()
    {
        return _defaultQueue;
    }

    private class DefaultQueueAssociationClearingTask implements Action<Queue<?>>
    {
        @Override
        public void performAction(final Queue<?> queue)
        {
            if ( queue == _defaultQueue)
            {
                _defaultQueue = null;
            }
        }
    }

    @Override
    public boolean processPending()
    {
        if (!getAMQPConnection().isIOThread() || isClosing())
        {
            return false;
        }

        boolean desiredBlockingState = _blocking.get();
        if (desiredBlockingState != _wireBlockingState)
        {
            _wireBlockingState = desiredBlockingState;
            sendFlow(!desiredBlockingState);
            _blockTime = desiredBlockingState ? System.currentTimeMillis() : 0;
        }

        if(!_consumersWithPendingWork.isEmpty() && !getAMQPConnection().isTransportBlockedForWriting())
        {
            if (_processPendingIterator == null || !_processPendingIterator.hasNext())
            {
                _processPendingIterator = _consumersWithPendingWork.iterator();
            }

            if(_processPendingIterator.hasNext())
            {
                ConsumerTarget_0_8 target = _processPendingIterator.next();
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
    public void notifyWork(final ConsumerTarget_0_8 target)
    {
        if(_consumersWithPendingWork.add(target))
        {
            getAMQPConnection().notifyWork(this);
        }
    }

    @Override
    public void restoreCredit(final ConsumerTarget target, final int count, final long size)
    {
        boolean hasCredit = _creditManager.hasCredit();
        _creditManager.restoreCredit(count, size);
        if(_creditManager.hasCredit() != hasCredit)
        {
            if (hasCredit || !_creditManager.isNotBytesLimitedAndHighPrefetch())
            {
                updateAllConsumerNotifyWorkDesired();
            }
        }
        else if (hasCredit)
        {
            if (_creditManager.isNotBytesLimitedAndHighPrefetch())
            {
                if (_creditManager.isCreditOverBatchLimit())
                {
                    updateAllConsumerNotifyWorkDesired();
                }
            }
            else if(_creditManager.isBytesLimited())
            {
                target.notifyWork();
            }
        }
    }

    private Collection<ConsumerTarget_0_8> getConsumerTargets()
    {
        return _tag2SubscriptionTargetMap.values();
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

    public void dispose()
    {
        _txCommitOkFrame.dispose();
    }
}
