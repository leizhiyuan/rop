/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.streamnative.pulsar.handlers.rocketmq.inner;

import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.SLASH_CHAR;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.bookkeeper.mledger.util.Futures;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.impl.ClientCnx;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.collections.ConcurrentLongHashMap;
import org.apache.rocketmq.common.SystemClock;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBatch;
import org.apache.rocketmq.common.protocol.header.PullMessageRequestHeader;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.store.AppendMessageResult;
import org.apache.rocketmq.store.AppendMessageStatus;
import org.apache.rocketmq.store.GetMessageStatus;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;
import org.streamnative.pulsar.handlers.rocketmq.RocketMQProtocolHandler;
import org.streamnative.pulsar.handlers.rocketmq.inner.consumer.RopGetMessageResult;
import org.streamnative.pulsar.handlers.rocketmq.inner.exception.RopEncodeException;
import org.streamnative.pulsar.handlers.rocketmq.inner.exception.RopPersistentTopicException;
import org.streamnative.pulsar.handlers.rocketmq.inner.format.RopEntryFormatter;
import org.streamnative.pulsar.handlers.rocketmq.inner.format.RopMessageFilter;
import org.streamnative.pulsar.handlers.rocketmq.inner.producer.ClientTopicName;
import org.streamnative.pulsar.handlers.rocketmq.inner.pulsar.PulsarMessageStore;
import org.streamnative.pulsar.handlers.rocketmq.inner.request.PullRequestFilterKey;
import org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils;
import org.streamnative.pulsar.handlers.rocketmq.utils.MessageIdUtils;
import org.streamnative.pulsar.handlers.rocketmq.utils.RocketMQTopic;

/**
 * Rop server cnx.
 */
@Slf4j
@Getter
public class RopServerCnx extends ChannelInboundHandlerAdapter implements PulsarMessageStore {

    private static final int sendTimeoutInSec = 500;
    private static final int maxBatchMessageNum = 20;
    private static final int fetchTimeoutInMs = 100;
    private static final String ropHandlerName = "RopServerCnxHandler";
    private final BrokerService service;
    private final ConcurrentLongHashMap<Producer<byte[]>> producers;
    private final ConcurrentHashMap<String, ManagedCursor> cursors;
    private final HashMap<Long, Reader<byte[]>> lookMsgReaders;
    private final RopEntryFormatter entryFormatter = new RopEntryFormatter();
    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock lookMsgLock = new ReentrantLock();
    private final SystemClock systemClock = new SystemClock();
    private RocketMQBrokerController brokerController;
    private ChannelHandlerContext ctx;
    private SocketAddress remoteAddress;
    private State state;
    private int localListenPort;
    private final Cache<PullRequestFilterKey, Object> requestFilterCache = CacheBuilder
            .newBuilder()
            .initialCapacity(1024)
            .maximumSize(4096)
            .build();
    private final Object pullRequestFilterValue = new Object();

    public RopServerCnx(RocketMQBrokerController brokerController, ChannelHandlerContext ctx) {
        this.brokerController = brokerController;
        this.localListenPort =
                RocketMQProtocolHandler.getListenerPort(brokerController.getServerConfig().getRocketmqListeners());
        this.service = brokerController.getBrokerService();
        this.ctx = ctx;
        this.remoteAddress = ctx.channel().remoteAddress();
        this.state = State.Connected;
        this.producers = new ConcurrentLongHashMap(2, 1);
        this.lookMsgReaders = new HashMap<>();
        this.cursors = new ConcurrentHashMap<>(4);
        synchronized (ctx) {
            if (ctx.pipeline().get(ropHandlerName) == null) {
                ctx.pipeline().addLast(ropHandlerName, this);
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("Closed connection from {}", remoteAddress);
        // Connection is gone, close the resources immediately
        producers.values().forEach(Producer::closeAsync);
        cursors.values().forEach(v -> v.asyncClose(new Futures.CloseFuture(), null));
        producers.clear();
        cursors.clear();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Channel writability has changed to: {}", ctx.channel().isWritable());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (this.state != State.Failed) {
            log.warn("[{}] Got exception {}", this.remoteAddress,
                    ClientCnx.isKnownException(cause) ? cause : ExceptionUtils
                            .getStackTrace(cause));
            this.state = State.Failed;
        } else if (log.isDebugEnabled()) {
            log.debug("[{}] Got exception: {}", this.remoteAddress, cause);
        }
        this.ctx.close();
    }

    @Override
    public PutMessageResult putMessage(MessageExtBrokerInner messageInner, String producerGroup) {
        Preconditions.checkNotNull(messageInner);
        Preconditions.checkNotNull(producerGroup);
        RocketMQTopic rmqTopic = new RocketMQTopic(messageInner.getTopic());
        int partitionId = messageInner.getQueueId();
        String pTopic = rmqTopic.getPartitionName(partitionId);

        final int tranType = MessageSysFlag.getTransactionValue(messageInner.getSysFlag());
        if (tranType == MessageSysFlag.TRANSACTION_NOT_TYPE
                || tranType == MessageSysFlag.TRANSACTION_COMMIT_TYPE) {
            // Delay Delivery
            if (messageInner.getDelayTimeLevel() > 0 && !rmqTopic.isDLQTopic()) {
                if (messageInner.getDelayTimeLevel() > this.brokerController.getServerConfig().getMaxDelayLevelNum()) {
                    messageInner.setDelayTimeLevel(this.brokerController.getServerConfig().getMaxDelayLevelNum());
                }

                int totalQueueNum = this.brokerController.getServerConfig().getRmqScheduleTopicPartitionNum();
                partitionId = partitionId % totalQueueNum;
                pTopic = this.brokerController.getDelayedMessageService()
                        .getDelayedTopicName(messageInner.getDelayTimeLevel(), partitionId);

                MessageAccessor.putProperty(messageInner, MessageConst.PROPERTY_REAL_TOPIC, messageInner.getTopic());
                MessageAccessor.putProperty(messageInner, MessageConst.PROPERTY_REAL_QUEUE_ID,
                        String.valueOf(messageInner.getQueueId()));
                messageInner.setPropertiesString(MessageDecoder.messageProperties2String(messageInner.getProperties()));
                messageInner.setTopic(pTopic);
                messageInner.setQueueId(partitionId);

            }
        }

        try {
            List<byte[]> body = this.entryFormatter.encode(messageInner, 1);
            long offset;

            /*
             * Optimize the production performance of publish messages.
             * If the broker is the owner of the current partitioned topic, directly use the PersistentTopic interface
             * for publish message.
             */
            if (this.brokerController.getTopicConfigManager()
                    .isPartitionTopicOwner(rmqTopic.getPulsarTopicName(), partitionId)) {
                try {
                    PersistentTopic persistentTopic = this.brokerController.getTopicConfigManager()
                            .getPulsarPersistentTopic(pTopic);
                    if (persistentTopic == null) {
                        return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE,
                                new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
                    }

                    CompletableFuture<Long> offsetFuture = publishMessage(body.get(0), persistentTopic, pTopic,
                            partitionId);
                    offset = offsetFuture.get(sendTimeoutInSec, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.error("Topic [{}] putMessage timeout.", pTopic, e);
                    return new PutMessageResult(PutMessageStatus.FLUSH_DISK_TIMEOUT,
                            new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
                } catch (Exception e) {
                    log.error("Topic [{}] putMessage error.", pTopic, e);
                    return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE,
                            new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
                }
            } else {
                long producerId = buildPulsarProducerId(producerGroup, pTopic,
                        ctx.channel().remoteAddress().toString());
                Producer<byte[]> producer = this.producers.get(producerId);
                if (producer == null) {
                    log.info("putMessage creating producer[id={}] and channl=[{}].", producerId, ctx.channel());
                    synchronized (this.producers) {
                        if (this.producers.get(producerId) == null) {
                            producer = this.service.pulsar().getClient()
                                    .newProducer()
                                    .topic(pTopic)
                                    .maxPendingMessages(500)
                                    .producerName(producerGroup + CommonUtils.UNDERSCORE_CHAR + producerId)
                                    .sendTimeout(sendTimeoutInSec, TimeUnit.MILLISECONDS)
                                    .enableBatching(false)
                                    .create();
                            Producer<byte[]> oldProducer = this.producers.put(producerId, producer);
                            if (oldProducer != null) {
                                oldProducer.closeAsync();
                            }
                        }
                    }
                }
                MessageIdImpl messageId = (MessageIdImpl) this.producers.get(producerId).send(body.get(0));
                offset = MessageIdUtils.getOffset(messageId.getLedgerId(), messageId.getEntryId(), partitionId);
            }

            AppendMessageResult appendMessageResult = new AppendMessageResult(AppendMessageStatus.PUT_OK);
            appendMessageResult.setMsgNum(1);
            appendMessageResult.setWroteBytes(body.get(0).length);
            appendMessageResult.setMsgId(
                    CommonUtils.createMessageId(this.ctx.channel().localAddress(), localListenPort, offset));
            appendMessageResult.setLogicsOffset(offset);
            appendMessageResult.setWroteOffset(offset);
            return new PutMessageResult(PutMessageStatus.PUT_OK, appendMessageResult);
        } catch (RopEncodeException e) {
            log.warn("PutMessage encode error.", e);
        } catch (PulsarClientException e) {
            log.warn("PutMessage send error.", e);
        } catch (Exception e) {
            log.warn("PutMessage error.", e);
        }
        PutMessageStatus status = PutMessageStatus.FLUSH_DISK_TIMEOUT;
        AppendMessageResult temp = new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
        return new PutMessageResult(status, temp);
    }

    @Override
    public PutMessageResult putMessages(MessageExtBatch batchMessage, String producerGroup) {
        RocketMQTopic rmqTopic = new RocketMQTopic(batchMessage.getTopic());
        int partitionId = batchMessage.getQueueId();
        String pTopic = rmqTopic.getPartitionName(partitionId);

        try {
            StringBuilder sb = new StringBuilder();
            int totalBytesSize = 0;
            int messageNum = 0;

            /*
             * Optimize the production performance of batch publish messages.
             * If the broker is the owner of the current partitioned topic, directly use the PersistentTopic interface
             * for publish message.
             */
            if (this.brokerController.getTopicConfigManager()
                    .isPartitionTopicOwner(rmqTopic.getPulsarTopicName(), partitionId)) {
                PersistentTopic persistentTopic = this.brokerController.getTopicConfigManager()
                        .getPulsarPersistentTopic(pTopic);
                if (persistentTopic == null) {
                    return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE,
                            new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
                }

                List<byte[]> bodies = this.entryFormatter.encode(batchMessage, 1);
                List<CompletableFuture<Long>> batchMessageFutures = new ArrayList<>(bodies.size());
                for (byte[] body : bodies) {
                    try {
                        CompletableFuture<Long> offsetFuture = publishMessage(body, persistentTopic, pTopic,
                                partitionId);
                        batchMessageFutures.add(offsetFuture);
                        messageNum++;
                        totalBytesSize += body.length;
                    } catch (Exception e) {
                        log.error("Topic [{}] putMessage error", pTopic, e);
                        return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE,
                                new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
                    }
                }
                try {
                    FutureUtil.waitForAll(batchMessageFutures).get(sendTimeoutInSec, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.error("Topic [{}] putMessages timeout.", pTopic, e);
                    return new PutMessageResult(PutMessageStatus.FLUSH_DISK_TIMEOUT,
                            new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR));
                }
                for (CompletableFuture<Long> f : batchMessageFutures) {
                    String msgId = CommonUtils
                            .createMessageId(this.ctx.channel().localAddress(), localListenPort, f.get());
                    sb.append(msgId).append(",");
                }
            } else {
                long producerId = buildPulsarProducerId(producerGroup, pTopic, this.remoteAddress.toString());
                Producer<byte[]> putMsgProducer = this.producers.get(producerId);
                if (putMsgProducer == null) {
                    synchronized (this.producers) {
                        if (this.producers.get(producerId) == null) {
                            log.info("putMessages creating producer[id={}].", producerId);
                            putMsgProducer = this.service.pulsar().getClient().newProducer()
                                    .topic(pTopic)
                                    .producerName(producerGroup + producerId)
                                    .batchingMaxPublishDelay(fetchTimeoutInMs, TimeUnit.MILLISECONDS)
                                    .sendTimeout(sendTimeoutInSec, TimeUnit.MILLISECONDS)
                                    .batchingMaxMessages(maxBatchMessageNum)
                                    .enableBatching(true)
                                    .create();
                            log.info("putMessages create producer[id={}] conf=[{}] successfully.", producerId,
                                    ((ProducerImpl) putMsgProducer).getConfiguration());
                            Producer<byte[]> oldProducer = this.producers.put(producerId, putMsgProducer);
                            if (oldProducer != null) {
                                oldProducer.closeAsync();
                            }
                        }
                    }
                }

                log.info("The producer [{}] putMessages begin to send message.", producerId);
                List<byte[]> bodies = this.entryFormatter.encode(batchMessage, 1);
                List<CompletableFuture<MessageId>> batchMessageFutures = new ArrayList<>(bodies.size());
                for (byte[] body : bodies) {
                    batchMessageFutures.add(this.producers.get(producerId).sendAsync(body));
                    messageNum++;
                    totalBytesSize += body.length;
                }
                FutureUtil.waitForAll(batchMessageFutures).get(sendTimeoutInSec, TimeUnit.MILLISECONDS);
                for (CompletableFuture<MessageId> f : batchMessageFutures) {
                    MessageIdImpl messageId = (MessageIdImpl) f.get();
                    long ledgerId = messageId.getLedgerId();
                    long entryId = messageId.getEntryId();
                    String msgId = CommonUtils.createMessageId(this.ctx.channel().localAddress(), localListenPort,
                            MessageIdUtils.getOffset(ledgerId, entryId, partitionId));
                    sb.append(msgId).append(",");
                }
            }

            AppendMessageResult appendMessageResult = new AppendMessageResult(AppendMessageStatus.PUT_OK);
            appendMessageResult.setMsgNum(messageNum);
            appendMessageResult.setWroteBytes(totalBytesSize);
            appendMessageResult.setMsgId(sb.toString());
            return new PutMessageResult(PutMessageStatus.PUT_OK, appendMessageResult);
        } catch (RopEncodeException e) {
            log.warn("putMessages batchMessage encode error.", e);
        } catch (PulsarServerException e) {
            log.warn("putMessages batchMessage send error.", e);
        } catch (Exception e) {
            log.warn("putMessages batchMessage error.", e);
        }
        PutMessageStatus status = PutMessageStatus.FLUSH_DISK_TIMEOUT;
        AppendMessageResult temp = new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
        return new PutMessageResult(status, temp);
    }

    private CompletableFuture<Long> publishMessage(byte[] body, PersistentTopic persistentTopic, String pTopic,
            long partitionId) {
        ByteBuf headersAndPayload = null;
        try {
            headersAndPayload = this.entryFormatter.encode(body);

            org.apache.pulsar.broker.service.Producer producer = this.brokerController.getTopicConfigManager()
                    .getReferenceProducer(pTopic, persistentTopic, this);
            if (producer != null) {
                producer.updateRates(1, headersAndPayload.readableBytes());
            }
            persistentTopic.incrementPublishCount(1, headersAndPayload.readableBytes());

            CompletableFuture<Long> offsetFuture = new CompletableFuture<>();
            persistentTopic.publishMessage(headersAndPayload, RopMessagePublishContext
                    .get(offsetFuture, persistentTopic, System.nanoTime(), partitionId));

            return offsetFuture;
        } finally {
            if (headersAndPayload != null) {
                headersAndPayload.release();
            }
        }
    }

    @Override
    public MessageExt lookMessageByMessageId(String partitionedTopic, String msgId) {
        return null;
    }

    @Override
    public MessageExt lookMessageByMessageId(String originTopic, long offset) {
        Preconditions.checkNotNull(originTopic, "topic mustn't be null");
        MessageIdImpl messageId = MessageIdUtils.getMessageId(offset);

        RocketMQTopic rocketMQTopic = new RocketMQTopic(originTopic);
        TopicName pTopic = rocketMQTopic.getPulsarTopicName().getPartition(messageId.getPartitionIndex());

        Message<byte[]> message = null;
        long readerId = pTopic.toString().hashCode();

        try {
            lookMsgLock.lock();
            Reader<byte[]> topicReader = this.lookMsgReaders.get(readerId);
            if (topicReader == null) {
                topicReader = service.pulsar().getClient()
                        .newReader()
                        .startMessageId(messageId)
                        .startMessageIdInclusive()
                        .topic(pTopic.toString())
                        .create();
                this.lookMsgReaders.put(readerId, topicReader);
            }

            message = topicReader.readNext(fetchTimeoutInMs, TimeUnit.MILLISECONDS);
            if (message != null && MessageIdUtils.isMessageEquals(messageId, message.getMessageId())) {
                return this.entryFormatter.decodePulsarMessage(Collections.singletonList(message), null).get(0);
            } else {
                topicReader.seek(messageId);
                message = topicReader.readNext(fetchTimeoutInMs, TimeUnit.MILLISECONDS);
                if (message != null && MessageIdUtils.isMessageEquals(messageId, message.getMessageId())) {
                    return this.entryFormatter.decodePulsarMessage(Collections.singletonList(message), null).get(0);
                }
            }
        } catch (Exception ex) {
            log.warn("lookMessageByMessageId message[topic={}, msgId={}] error.", originTopic, messageId);
        } finally {
            lookMsgLock.unlock();
        }
        return null;
    }

    @Override
    public MessageExt lookMessageByTimestamp(String partitionedTopic, long timestamp) {
        try {
            lookMsgLock.lock();
            Long readerKey = Long.valueOf(partitionedTopic.hashCode());
            Reader<byte[]> topicReader = this.lookMsgReaders.get(readerKey);
            if (topicReader == null) {
                topicReader = service.pulsar().getClient().newReader()
                        .topic(partitionedTopic)
                        .create();
                this.lookMsgReaders.put(readerKey, topicReader);
            }
            Preconditions.checkNotNull(topicReader);
            Message<byte[]> message = null;
            topicReader.seek(timestamp);
            message = topicReader.readNext();
            if (message != null) {
                return this.entryFormatter.decodePulsarMessage(Collections.singletonList(message), null).get(0);
            }
        } catch (Exception ex) {
            log.warn("lookMessageByMessageId message[topic={}, timestamp={}] error.", partitionedTopic, timestamp);
        } finally {
            lookMsgLock.unlock();
        }
        return null;
    }

    @Override
    public long now() {
        return systemClock.now();
    }

    @Override
    public RopGetMessageResult getMessage(RemotingCommand request, PullMessageRequestHeader requestHeader,
            RopMessageFilter messageFilter) {
        RopGetMessageResult getResult = new RopGetMessageResult();

        String consumerGroupName = requestHeader.getConsumerGroup();
        String topicName = requestHeader.getTopic();
        int queueId = requestHeader.getQueueId();

        // hang pull request if this broker not owner for the request queueId topicName
        RocketMQTopic rmqTopic = new RocketMQTopic(topicName);
        if (!this.brokerController.getTopicConfigManager()
                .isPartitionTopicOwner(rmqTopic.getPulsarTopicName(), queueId)) {
            getResult.setStatus(GetMessageStatus.OFFSET_FOUND_NULL);
            // set suspend flag
            requestHeader.setSysFlag(requestHeader.getSysFlag() | 2);
            return getResult;
        }

        if (requestFilterCache.getIfPresent(new PullRequestFilterKey(consumerGroupName, topicName, queueId)) != null) {
            getResult.setStatus(GetMessageStatus.OFFSET_FOUND_NULL);
            requestHeader.setSysFlag(requestHeader.getSysFlag() | 2);
            return getResult;
        }

        // queueOffset 是要拉取消息的起始位置
        long queueOffset = requestHeader.getQueueOffset();
        int maxMsgNums = requestHeader.getMaxMsgNums();
        if (maxMsgNums < 1) {
            getResult.setStatus(GetMessageStatus.NO_MATCHED_MESSAGE);
            getResult.setNextBeginOffset(queueOffset);
            return getResult;
        }

        long maxOffset;
        long minOffset;
        try {
            maxOffset = this.brokerController.getConsumerOffsetManager()
                    .getMaxOffsetInQueue(new ClientTopicName(topicName), queueId);
            minOffset = this.brokerController.getConsumerOffsetManager()
                    .getMinOffsetInQueue(new ClientTopicName(topicName), queueId);
        } catch (RopPersistentTopicException e) {
            requestFilterCache
                    .put(new PullRequestFilterKey(consumerGroupName, topicName, queueId), pullRequestFilterValue);
            getResult.setStatus(GetMessageStatus.NO_MATCHED_LOGIC_QUEUE);
            getResult.setNextBeginOffset(0L);
            return getResult;
        }

        MessageIdImpl startOffset;
        GetMessageStatus status;
        if (queueOffset <= MessageIdUtils.MIN_ROP_OFFSET) {
            startOffset = (MessageIdImpl) MessageId.earliest;
        } else if (queueOffset >= MessageIdUtils.MAX_ROP_OFFSET) {
            startOffset = (MessageIdImpl) MessageId.latest;
        } else {
            startOffset = MessageIdUtils.getMessageId(queueOffset);
        }
        long nextBeginOffset = queueOffset;
        String pTopic = rmqTopic.getPartitionName(queueId);
        long readerId = buildPulsarReaderId(consumerGroupName, pTopic, this.ctx.channel().id().asLongText());

        List<ByteBuffer> messagesBufferList = Lists.newArrayList();
        final PositionImpl startPosition = MessageIdUtils.getPosition(MessageIdUtils.getOffset(startOffset));
        ManagedCursor managedCursor = cursors.computeIfAbsent(pTopic, (Function<String, ManagedCursor>) s -> {
            try {
                PersistentTopic persistentTopic = brokerController.getConsumerOffsetManager()
                        .getPulsarPersistentTopic(new ClientTopicName(rmqTopic.getPulsarTopicName()), queueId);
                ManagedLedgerImpl managedLedger = (ManagedLedgerImpl) persistentTopic.getManagedLedger();
//                PositionImpl previousPosition = managedLedger.getPreviousPosition(startPosition);
                return managedLedger.newNonDurableCursor(startPosition, "Rop-cursor-" + readerId);
            } catch (Exception e) {
                log.warn("Topic [{}] create managedLedger failed", pTopic, e);
            }
            return null;
        });

        if (managedCursor != null) {
            Position position = startPosition;
            try {
                List<Entry> entries = managedCursor.readEntries(maxMsgNums);
                for (Entry entry : entries) {
                    try {
                        nextBeginOffset = MessageIdUtils.getOffset(entry.getLedgerId(), entry.getEntryId(), queueId);
                        ByteBuffer byteBuffer = this.entryFormatter
                                .decodePulsarMessage(entry.getDataBuffer(), nextBeginOffset, messageFilter);
                        if (byteBuffer != null) {
                            messagesBufferList.add(byteBuffer);
                        }
                        position = entry.getPosition();
                    } finally {
                        entry.release();
                    }
                }
            } catch (ManagedLedgerException | InterruptedException e) {
                log.warn("Fetch message failed, seek to startPosition [{}]", startPosition, e);
                managedCursor.seek(position);
            } catch (Exception e) {
                log.warn("Fetch message error, seek to startPosition [{}]", startPosition, e);
                managedCursor.seek(position);
            }
        }

        if (!messagesBufferList.isEmpty()) {
            status = GetMessageStatus.FOUND;
            getResult.setMessageBufferList(messagesBufferList);
        } else {
            status = GetMessageStatus.OFFSET_FOUND_NULL;
        }
        getResult.setMaxOffset(maxOffset);
        getResult.setMinOffset(minOffset);
        getResult.setStatus(status);
        getResult.setNextBeginOffset(nextBeginOffset);
        return getResult;
    }

    private long buildPulsarReaderId(String... tags) {
        return (Joiner.on(SLASH_CHAR).join(tags)).hashCode();
    }

    private long buildPulsarProducerId(String... tags) {
        return (Joiner.on(SLASH_CHAR).join(tags)).hashCode();
    }

    enum State {
        Start,
        Connected,
        Failed,
        Connecting;
    }
}
