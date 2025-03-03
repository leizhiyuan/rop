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

package org.streamnative.pulsar.handlers.rocketmq.inner.proxy;

import static org.apache.bookkeeper.util.ZkUtils.createFullPathOptimistic;
import static org.apache.bookkeeper.util.ZkUtils.deleteFullPathOptimistic;
import static org.apache.pulsar.broker.web.PulsarWebResource.joinPath;
import static org.apache.rocketmq.common.protocol.RequestCode.CONSUMER_SEND_MSG_BACK;
import static org.apache.rocketmq.common.protocol.RequestCode.GET_MAX_OFFSET;
import static org.apache.rocketmq.common.protocol.RequestCode.GET_MIN_OFFSET;
import static org.apache.rocketmq.common.protocol.RequestCode.PULL_MESSAGE;
import static org.apache.rocketmq.common.protocol.RequestCode.QUERY_MESSAGE;
import static org.apache.rocketmq.common.protocol.RequestCode.SEARCH_OFFSET_BY_TIMESTAMP;
import static org.apache.rocketmq.common.protocol.RequestCode.SEND_BATCH_MESSAGE;
import static org.apache.rocketmq.common.protocol.RequestCode.SEND_MESSAGE;
import static org.apache.rocketmq.common.protocol.RequestCode.SEND_MESSAGE_V2;
import static org.streamnative.pulsar.handlers.rocketmq.inner.zookeeper.RopZkUtils.BROKER_CLUSTER_PATH;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.COLO_CHAR;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.PULSAR_REAL_PARTITION_ID_TAG;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_CACHE_EXPIRE_TIME_MS;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_CACHE_INITIAL_SIZE;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_CACHE_MAX_SIZE;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_INNER_CLIENT_ADDRESS;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_INNER_MESSAGE_ID;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_INNER_REMOTE_CLIENT_TAG;
import static org.streamnative.pulsar.handlers.rocketmq.utils.CommonUtils.ROP_TRACE_START_TIME;
import static org.streamnative.pulsar.handlers.rocketmq.utils.PulsarUtil.autoExpanseBrokerGroupData;
import static org.streamnative.pulsar.handlers.rocketmq.utils.PulsarUtil.genBrokerGroupData;

import com.alibaba.fastjson.JSON;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.common.util.OrderedExecutor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.util.Strings;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerImpl;
import org.apache.pulsar.broker.loadbalance.impl.ModularLoadManagerWrapper;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.rocketmq.broker.mqtrace.ConsumeMessageHook;
import org.apache.rocketmq.broker.mqtrace.SendMessageHook;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.filter.FilterAPI;
import org.apache.rocketmq.common.help.FAQUrl;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.ConsumerSendMsgBackRequestHeader;
import org.apache.rocketmq.common.protocol.header.GetMaxOffsetRequestHeader;
import org.apache.rocketmq.common.protocol.header.GetMaxOffsetResponseHeader;
import org.apache.rocketmq.common.protocol.header.GetMinOffsetRequestHeader;
import org.apache.rocketmq.common.protocol.header.GetMinOffsetResponseHeader;
import org.apache.rocketmq.common.protocol.header.PullMessageRequestHeader;
import org.apache.rocketmq.common.protocol.header.PullMessageResponseHeader;
import org.apache.rocketmq.common.protocol.header.SearchOffsetRequestHeader;
import org.apache.rocketmq.common.protocol.header.SearchOffsetResponseHeader;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeader;
import org.apache.rocketmq.common.protocol.header.SendMessageResponseHeader;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.common.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.common.sysflag.PullSysFlag;
import org.apache.rocketmq.remoting.ChannelEventListener;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingCommandException;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.RemotingSysResponseCode;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.streamnative.pulsar.handlers.rocketmq.RocketMQServiceConfiguration;
import org.streamnative.pulsar.handlers.rocketmq.inner.PutMessageResult;
import org.streamnative.pulsar.handlers.rocketmq.inner.RocketMQBrokerController;
import org.streamnative.pulsar.handlers.rocketmq.inner.RocketMQRemoteServer;
import org.streamnative.pulsar.handlers.rocketmq.inner.consumer.CommitLogOffset;
import org.streamnative.pulsar.handlers.rocketmq.inner.consumer.ConsumerGroupInfo;
import org.streamnative.pulsar.handlers.rocketmq.inner.coordinator.RopCoordinator;
import org.streamnative.pulsar.handlers.rocketmq.inner.namesvr.MQTopicManager;
import org.streamnative.pulsar.handlers.rocketmq.inner.namesvr.NameserverProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.AdminBrokerProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.ClientManageProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.ConsumerManageProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.EndTransactionProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.PullMessageProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.QueryMessageProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.processor.SendMessageProcessor;
import org.streamnative.pulsar.handlers.rocketmq.inner.producer.ClientTopicName;
import org.streamnative.pulsar.handlers.rocketmq.inner.trace.TraceContext;
import org.streamnative.pulsar.handlers.rocketmq.inner.trace.TraceManager;
import org.streamnative.pulsar.handlers.rocketmq.inner.zookeeper.RopClusterContent;
import org.streamnative.pulsar.handlers.rocketmq.inner.zookeeper.RopZkUtils;

/**
 * Rop broker proxy is a rocketmq request simulator
 * find the real broker that topicPartition is stored on
 * and transfer the request to the owner broke.
 */
@Slf4j
public class RopBrokerProxy extends RocketMQRemoteServer implements AutoCloseable {

    public static final Map<Integer, AtomicLong> REQUEST_COUNT_TABLE = Maps.newConcurrentMap();
    private static final int ROP_SERVICE_PORT = 9876;
    public static final String INNER_CLIENT_NAME_PREFIX = "rop_broker_proxy_";

    private final int internalRedirectTimeoutMs;
    private final int internalRedirectPullMsgTimeoutMs;
    private final RocketMQBrokerController brokerController;
    private final List<SendMessageHook> sendMessageHookList = new ArrayList<>();
    private final List<ConsumeMessageHook> consumeMessageHookList = new ArrayList<>();
    @Getter
    private RopZookeeperCacheService zkService;
    private RopCoordinator coordinator;
    private PulsarService pulsarService;
    private final OrderedExecutor orderedExecutor;
    private final List<ProcessorProxyRegister> processorProxyRegisters = new ArrayList<>();
    @Getter
    private final BrokerNetworkAPI brokerNetworkClients;
    @Getter
    private final BrokerNetworkAPI sendBrokerNetworkClients;
    @Getter
    private volatile String brokerTag = Strings.EMPTY;
    private final String clusterName;
    @Getter
    private final MQTopicManager mqTopicManager;
    private final ThreadLocal<RemotingCommand> sendResponseThreadLocal = ThreadLocal
            .withInitial(() -> RemotingCommand.createResponseCommand(SendMessageResponseHeader.class));
    private final ThreadLocal<PulsarClientImpl> pulsarClientThreadLocal = new ThreadLocal<>();
    private final Cache<TopicName, String> ownedBrokerCache = CacheBuilder.newBuilder()
            .initialCapacity(ROP_CACHE_INITIAL_SIZE).maximumSize(ROP_CACHE_MAX_SIZE)
            .expireAfterAccess(ROP_CACHE_EXPIRE_TIME_MS, TimeUnit.MILLISECONDS).build();
    @Getter
    private PullMessageProcessor pullMessageProcessor;

    public RopBrokerProxy(final RocketMQServiceConfiguration config, RocketMQBrokerController brokerController,
            final ChannelEventListener channelEventListener) {
        super(config, channelEventListener);
        this.clusterName = config.getClusterName();
        this.brokerController = brokerController;
        this.orderedExecutor = OrderedExecutor.newBuilder().numThreads(4).name("rop-ordered-executor").build();
        this.mqTopicManager = new MQTopicManager(brokerController);
        this.brokerNetworkClients = new BrokerNetworkAPI(this, config.getRopRemotingClientPoolSize());
        this.sendBrokerNetworkClients = new BrokerNetworkAPI(this, config.getRopRemotingClientPoolSize());

        this.internalRedirectTimeoutMs = brokerController.getServerConfig().getRopInternalRedirectTimeoutMs();
        this.internalRedirectPullMsgTimeoutMs = brokerController.getServerConfig()
                .getRopInternalRedirectPullMsgTimeoutMs();
    }

    private boolean checkTopicOwnerBroker(RemotingCommand cmd, TopicName pulsarTopicName, int queueId) {
        if (cmd.getExtFields().containsKey(PULSAR_REAL_PARTITION_ID_TAG)) {
            return true;
        }
        int pulsarTopicPartitionId = getPulsarTopicPartitionId(pulsarTopicName, queueId);
        boolean isOwner = mqTopicManager.isPartitionTopicOwner(pulsarTopicName, pulsarTopicPartitionId);
        cmd.addExtField(PULSAR_REAL_PARTITION_ID_TAG, String.valueOf(pulsarTopicPartitionId));
        return isOwner;
    }

    public int getPulsarTopicPartitionId(TopicName pulsarTopicName, int queueId) {
        try {
            Map<String, List<Integer>> pulsarTopicRoute = mqTopicManager
                    .getPulsarTopicRoute(pulsarTopicName, Strings.EMPTY);
            Preconditions.checkArgument(pulsarTopicRoute != null && !pulsarTopicRoute.isEmpty());
            List<Integer> pulsarPartitionIdList = pulsarTopicRoute.get(this.brokerTag);
            Preconditions.checkArgument(!pulsarPartitionIdList.isEmpty() && queueId < pulsarPartitionIdList.size());
            return pulsarPartitionIdList.get(queueId);
        } catch (RuntimeException e) {
            log.error("Rop [{}] [{}] getPulsarTopicPartitionId error.", pulsarTopicName, queueId, e);
            throw e;
        }
    }

    public List<Integer> getPulsarTopicPartitionIdList(TopicName pulsarTopicName) {
        try {
            Map<String, List<Integer>> pulsarTopicRoute = mqTopicManager
                    .getPulsarTopicRoute(pulsarTopicName, Strings.EMPTY);
            Preconditions.checkArgument(pulsarTopicRoute != null && !pulsarTopicRoute.isEmpty());
            return pulsarTopicRoute.get(this.brokerTag);
        } catch (RuntimeException e) {
            log.error("Rop [{}] [{}] getPulsarTopicPartitionIdList error.", pulsarTopicName, e);
            throw e;
        }
    }

    @Override
    public void processRequestCommand(ChannelHandlerContext ctx, RemotingCommand cmd) throws RemotingCommandException {
        try {
            REQUEST_COUNT_TABLE
                    .computeIfAbsent(cmd.getCode(), (Function<Integer, AtomicLong>) input -> new AtomicLong(0))
                    .incrementAndGet();

            switch (cmd.getCode()) {
                case PULL_MESSAGE:
                    PullMessageRequestHeader pullMsgHeader =
                            (PullMessageRequestHeader) cmd.decodeCommandCustomHeader(PullMessageRequestHeader.class);
                    ClientTopicName rmqTopic = new ClientTopicName(pullMsgHeader.getTopic());
                    TopicName pulsarTopicName = rmqTopic.toPulsarTopicName();
                    boolean isOwnedBroker = checkTopicOwnerBroker(cmd, pulsarTopicName,
                            pullMsgHeader.getQueueId());

                    if (isOwnedBroker) {
                        log.trace("process owned broker pull request[{}].", cmd);
                        super.processRequestCommand(ctx, cmd);
                    } else {
                        log.trace("process unowned broker pull request[{}].", cmd);
                        SubscriptionData subscriptionData = this.brokerController.getConsumerManager()
                                .findSubscriptionData(pullMsgHeader.getConsumerGroup(), pullMsgHeader.getTopic());
                        int sysFlag = pullMsgHeader.getSysFlag();
                        if (subscriptionData == null) {
                            if (!PullSysFlag.hasSubscriptionFlag(sysFlag)) {
                                RemotingCommand consumeResponse = RemotingCommand
                                        .createResponseCommand(PullMessageResponseHeader.class);
                                consumeResponse.setOpaque(cmd.getOpaque());
                                consumeResponse.setCode(ResponseCode.SUBSCRIPTION_NOT_EXIST);
                                consumeResponse.setRemark(
                                        "[proxy]the consumer's group info not exist" + FAQUrl
                                                .suggestTodo(FAQUrl.SAME_GROUP_DIFFERENT_TOPIC));
                                ctx.writeAndFlush(consumeResponse);
                                return;
                            }
                            subscriptionData = FilterAPI.build(
                                    pullMsgHeader.getTopic(), pullMsgHeader.getSubscription(),
                                    pullMsgHeader.getExpressionType()
                            );
                        }

                        ConsumerGroupInfo consumerGroupInfo = this.brokerController.getConsumerManager()
                                .getConsumerGroupInfo(pullMsgHeader.getConsumerGroup());
                        if (!PullSysFlag.hasSubscriptionFlag(sysFlag)) {
                            if (consumerGroupInfo == null) {
                                RemotingCommand consumeResponse = RemotingCommand
                                        .createResponseCommand(PullMessageResponseHeader.class);
                                log.warn("[proxy]the consumer's group info not exist, group: {}",
                                        pullMsgHeader.getConsumerGroup());
                                consumeResponse.setOpaque(cmd.getOpaque());
                                consumeResponse.setCode(ResponseCode.SUBSCRIPTION_NOT_EXIST);
                                consumeResponse.setRemark(
                                        "[proxy]the consumer's group info not exist" + FAQUrl
                                                .suggestTodo(FAQUrl.SAME_GROUP_DIFFERENT_TOPIC));
                                ctx.writeAndFlush(consumeResponse);
                                return;
                            }
                        }

                        SubscriptionGroupConfig subscriptionGroupConfig = this.brokerController
                                .getSubscriptionGroupManager()
                                .findSubscriptionGroupConfig(pullMsgHeader.getConsumerGroup());
                        if (null == subscriptionGroupConfig) {
                            RemotingCommand consumeResponse = RemotingCommand
                                    .createResponseCommand(PullMessageResponseHeader.class);
                            consumeResponse.setCode(ResponseCode.SUBSCRIPTION_GROUP_NOT_EXIST);
                            consumeResponse.setRemark(
                                    String.format("[proxy]subscription group [%s] does not exist, %s",
                                            pullMsgHeader.getConsumerGroup(),
                                            FAQUrl.suggestTodo(FAQUrl.SUBSCRIPTION_GROUP_NOT_EXIST)));
                            ctx.writeAndFlush(consumeResponse);
                            return;
                        }

                        if (!subscriptionGroupConfig.isConsumeBroadcastEnable()
                                && consumerGroupInfo.getMessageModel() == MessageModel.BROADCASTING) {
                            RemotingCommand consumeResponse = RemotingCommand
                                    .createResponseCommand(PullMessageResponseHeader.class);
                            consumeResponse.setOpaque(cmd.getOpaque());
                            consumeResponse.setCode(ResponseCode.NO_PERMISSION);
                            consumeResponse
                                    .setRemark("[proxy]the consumer group[" + pullMsgHeader.getConsumerGroup()
                                            + "] can not consume by broadcast way");
                            ctx.writeAndFlush(consumeResponse);
                            return;
                        }

                        pullMsgHeader.setExpressionType(subscriptionData.getExpressionType());
                        pullMsgHeader.setSubscription(subscriptionData.getSubString());
                        boolean hasSuspendFlag = PullSysFlag.hasSuspendFlag(sysFlag);
                        boolean hasCommitOffsetFlag = PullSysFlag.hasCommitOffsetFlag(sysFlag);
                        sysFlag = PullSysFlag
                                .buildSysFlag(hasCommitOffsetFlag, hasSuspendFlag, true, false);
                        pullMsgHeader.setSysFlag(sysFlag);
                        processNonOwnedBrokerPullRequest(ctx, cmd, pullMsgHeader, pulsarTopicName,
                                internalRedirectPullMsgTimeoutMs);
                    }
                    break;
                case SEND_MESSAGE:
                case SEND_MESSAGE_V2:
                case SEND_BATCH_MESSAGE:
                    // mark trace start time and proxy info
                    cmd.getExtFields().put(ROP_TRACE_START_TIME, String.valueOf(System.currentTimeMillis()));

                    RemotingCommand sendResponse = sendResponseThreadLocal.get();
                    SendMessageRequestHeader sendHeader = SendMessageProcessor.parseRequestHeader(cmd);
                    sendResponse.setCode(-1);
                    SendMessageProcessor
                            .msgCheck(brokerController.getServerConfig(), mqTopicManager, ctx, sendHeader,
                                    sendResponse);
                    if (sendResponse.getCode() != -1) {
                        //fail to msgCheck and flush error at once;
                        ctx.writeAndFlush(sendResponse);
                        break;
                    }
                    String topic = sendHeader.getTopic();
                    int queueId = sendHeader.getQueueId();
                    rmqTopic = new ClientTopicName(topic);
                    pulsarTopicName = rmqTopic.toPulsarTopicName();
                    isOwnedBroker = checkTopicOwnerBroker(cmd, pulsarTopicName, queueId);
                    if (isOwnedBroker) {
                        log.trace("process owned broker send request[{}].", cmd);
                        super.processRequestCommand(ctx, cmd);
                    } else {
                        log.trace("process unowned broker send request[{}].", cmd);
                        processNonOwnedBrokerSendRequest(ctx, cmd, sendHeader, pulsarTopicName,
                                internalRedirectTimeoutMs);
                    }
                    break;
                case CONSUMER_SEND_MSG_BACK:
                    final ConsumerSendMsgBackRequestHeader requestHeader =
                            (ConsumerSendMsgBackRequestHeader) cmd
                                    .decodeCommandCustomHeader(ConsumerSendMsgBackRequestHeader.class);
                    CommitLogOffset commitLogOffset = new CommitLogOffset(requestHeader.getOffset());
                    topic = commitLogOffset.isRetryTopic() ? MixAll.getRetryTopic(requestHeader.getGroup())
                            : requestHeader.getOriginTopic();
                    int pulsarPartitionId = commitLogOffset.getPartitionId();
                    rmqTopic = new ClientTopicName(topic);
                    pulsarTopicName = rmqTopic.toPulsarTopicName();
                    isOwnedBroker = mqTopicManager.isPartitionTopicOwner(pulsarTopicName, pulsarPartitionId);
                    if (isOwnedBroker) {
                        super.processRequestCommand(ctx, cmd);
                    } else {
                        processNonOwnedBrokerConsumerSendBackRequest(ctx, cmd, requestHeader, pulsarTopicName,
                                pulsarPartitionId, internalRedirectTimeoutMs);
                    }
                    break;
                case QUERY_MESSAGE:
                    // TODO: not to support in the version
                    break;
                case GET_MAX_OFFSET:
                    final GetMinOffsetRequestHeader getMaxOffsetHeader =
                            (GetMinOffsetRequestHeader) cmd.decodeCommandCustomHeader(GetMinOffsetRequestHeader.class);

                    topic = getMaxOffsetHeader.getTopic();
                    queueId = getMaxOffsetHeader.getQueueId();
                    rmqTopic = new ClientTopicName(topic);
                    pulsarTopicName = rmqTopic.toPulsarTopicName();
                    isOwnedBroker = checkTopicOwnerBroker(cmd, pulsarTopicName, queueId);
                    if (isOwnedBroker) {
                        log.trace("process owned broker getMaxOffset request[{}].", cmd);
                        super.processRequestCommand(ctx, cmd);
                    } else {
                        log.trace("process unowned broker getMaxOffset request[{}].", cmd);
                        processGetMaxOffsetRequest(ctx, cmd, pulsarTopicName, internalRedirectTimeoutMs);
                    }
                    break;
                case GET_MIN_OFFSET:
                    final GetMinOffsetRequestHeader getMinOffsetHeader =
                            (GetMinOffsetRequestHeader) cmd.decodeCommandCustomHeader(GetMinOffsetRequestHeader.class);

                    topic = getMinOffsetHeader.getTopic();
                    queueId = getMinOffsetHeader.getQueueId();
                    rmqTopic = new ClientTopicName(topic);
                    pulsarTopicName = rmqTopic.toPulsarTopicName();
                    isOwnedBroker = checkTopicOwnerBroker(cmd, pulsarTopicName, queueId);
                    if (isOwnedBroker) {
                        log.trace("process owned broker getMinOffset request[{}].", cmd);
                        super.processRequestCommand(ctx, cmd);
                    } else {
                        log.trace("process unowned broker getMinOffset request[{}].", cmd);
                        processGetMinOffsetRequest(ctx, cmd, pulsarTopicName, internalRedirectTimeoutMs);
                    }
                    break;
                case SEARCH_OFFSET_BY_TIMESTAMP:
                    final SearchOffsetRequestHeader searchOffsetByTimestampHeader =
                            (SearchOffsetRequestHeader) cmd.decodeCommandCustomHeader(SearchOffsetRequestHeader.class);

                    topic = searchOffsetByTimestampHeader.getTopic();
                    queueId = searchOffsetByTimestampHeader.getQueueId();
                    rmqTopic = new ClientTopicName(topic);
                    pulsarTopicName = rmqTopic.toPulsarTopicName();
                    isOwnedBroker = checkTopicOwnerBroker(cmd, pulsarTopicName, queueId);
                    if (isOwnedBroker) {
                        log.trace("process owned broker searchOffsetByTimestamp request[{}].", cmd);
                        super.processRequestCommand(ctx, cmd);
                    } else {
                        log.trace("process unowned broker searchOffsetByTimestamp request[{}].", cmd);
                        processSearchOffsetByTimestampRequest(ctx, cmd, pulsarTopicName, internalRedirectTimeoutMs);
                    }
                    break;
                default:
                    super.processRequestCommand(ctx, cmd);
                    break;
            }
        } catch (Exception e) {
            log.warn("Rop processRequestCommand error, RemotingCommand: [{}]", cmd, e);
            final RemotingCommand response = RemotingCommand
                    .createResponseCommand(RemotingSysResponseCode.SYSTEM_ERROR, "Wrong routing info.");
            response.setOpaque(cmd.getOpaque());
            ctx.writeAndFlush(response);
        }

    }

    public long searchOffsetByTimestamp(ClientTopicName clientTopicName, int queueId, int pulsarPartitionId,
            long timestamp) throws Exception {
        SearchOffsetRequestHeader requestHeader = new SearchOffsetRequestHeader();
        requestHeader.setTopic(clientTopicName.getRmqTopicName());
        requestHeader.setQueueId(queueId);
        requestHeader.setTimestamp(timestamp);
        RemotingCommand request = RemotingCommand.createRequestCommand(SEARCH_OFFSET_BY_TIMESTAMP, requestHeader);
        request.addExtField(PULSAR_REAL_PARTITION_ID_TAG, String.valueOf(pulsarPartitionId));

        TopicName pulsarTopicName = TopicName.get(clientTopicName.getPulsarTopicName());
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);

        RemotingCommand response = brokerNetworkClients.invokeSync(address, request, 5000);
        assert response != null;
        if (response.getCode() == ResponseCode.SUCCESS) {
            SearchOffsetResponseHeader responseHeader =
                    (SearchOffsetResponseHeader) response.decodeCommandCustomHeader(SearchOffsetResponseHeader.class);
            return responseHeader.getOffset();
        }

        throw new MQBrokerException(response.getCode(), response.getRemark());
    }

    public long searchMaxOffset(ClientTopicName clientTopicName, int queueId, int pulsarPartitionId)
            throws Exception {
        GetMaxOffsetRequestHeader requestHeader = new GetMaxOffsetRequestHeader();
        requestHeader.setTopic(clientTopicName.getRmqTopicName());
        requestHeader.setQueueId(queueId);
        RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.GET_MAX_OFFSET, requestHeader);
        request.addExtField(PULSAR_REAL_PARTITION_ID_TAG, String.valueOf(pulsarPartitionId));

        TopicName pulsarTopicName = TopicName.get(clientTopicName.getPulsarTopicName());
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);

        RemotingCommand response = brokerNetworkClients.invokeSync(address, request, 5000);
        assert response != null;
        if (response.getCode() == ResponseCode.SUCCESS) {
            GetMaxOffsetResponseHeader responseHeader =
                    (GetMaxOffsetResponseHeader) response.decodeCommandCustomHeader(GetMaxOffsetResponseHeader.class);

            return responseHeader.getOffset();
        }

        throw new MQBrokerException(response.getCode(), response.getRemark());
    }

    private void processNonOwnedBrokerConsumerSendBackRequest(ChannelHandlerContext ctx, RemotingCommand cmd,
            ConsumerSendMsgBackRequestHeader requestHeader,
            TopicName pulsarTopicName, int pulsarPartitionId, long timeout) {
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);
        final int opaque = cmd.getOpaque();
        try {
            cmd.addExtField(ROP_INNER_REMOTE_CLIENT_TAG, INNER_CLIENT_NAME_PREFIX + requestHeader.getGroup());
            cmd.addExtField(ROP_INNER_CLIENT_ADDRESS, RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
            brokerNetworkClients.invokeAsync(address, cmd, timeout, (responseFuture) -> {
                RemotingCommand response = responseFuture.getResponseCommand();
                if (response != null) {
                    response.setOpaque(opaque);
                    response.markResponseType();
                    ctx.writeAndFlush(response);
                } else {
                    log.warn("processNonOwnedBrokerConsumerSendBackRequest[sendMsgBack] return null");
                    ownedBrokerCache.invalidate(partitionedTopicName);
                    response = RemotingCommand.createResponseCommand(null);
                    response.setOpaque(opaque);
                    response.setCode(ResponseCode.SYSTEM_ERROR);
                    response.setRemark("processNonOwnedBrokerConsumerSendBackRequest[sendMsgBack] return null");
                    ctx.writeAndFlush(response);
                }
            });
        } catch (Exception e) {
            log.warn("BrokerNetworkAPI invokeAsync[sendMsgBack] error.", e);
            ownedBrokerCache.invalidate(partitionedTopicName);
            RemotingCommand response = RemotingCommand.createResponseCommand(null);
            response.setOpaque(opaque);
            response.setCode(ResponseCode.SYSTEM_ERROR);
            response.setRemark("BrokerNetworkAPI invokeAsync[sendMsgBack] error");
            ctx.writeAndFlush(response);
        }
    }

    private void processNonOwnedBrokerSendRequest(ChannelHandlerContext ctx, RemotingCommand cmd,
            SendMessageRequestHeader sendHeader,
            TopicName pulsarTopicName, long timeout) {
        int pulsarPartitionId = Integer.parseInt(cmd.getExtFields().get(PULSAR_REAL_PARTITION_ID_TAG));
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);
        log.debug("processNonOwnedBrokerSendRequest [topic={},pid={}] and address=[{}].", pulsarTopicName,
                pulsarPartitionId, address);
        final int opaque = cmd.getOpaque();
        long startTime = System.currentTimeMillis();
        try {
            cmd.addExtField(ROP_INNER_REMOTE_CLIENT_TAG, INNER_CLIENT_NAME_PREFIX + sendHeader.getProducerGroup());
            cmd.addExtField(ROP_INNER_CLIENT_ADDRESS, RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
            if (cmd.isOnewayRPC()) {
                brokerNetworkClients.invokeOneway(address, cmd, timeout);
                return;
            }
            TraceContext traceContext =
                    this.brokerController.isRopTraceEnable() ? TraceContext.buildMsgContext(ctx, sendHeader) : null;
            sendBrokerNetworkClients.invokeAsync(address, cmd, timeout, (responseFuture) -> {
                RemotingCommand sendResponse = responseFuture.getResponseCommand();

                if (System.currentTimeMillis() - startTime > 3000) {
                    log.warn("RoP proxy timeout [request={}], [response={}], address=[{}]. Cost = [{}ms]",
                            cmd, sendResponse, address, System.currentTimeMillis() - startTime);
                }

                if (sendResponse != null) {
                    if (sendResponse.getCode() == ResponseCode.SYSTEM_ERROR && "NotFoundTopic"
                            .equals(sendResponse.getRemark())) {
                        ownedBrokerCache.invalidate(partitionedTopicName);
                    }
                    sendResponse.setOpaque(opaque);
                    sendResponse.markResponseType();
                    ctx.writeAndFlush(sendResponse);
                } else {
                    log.warn("processNonOwnedBrokerSendRequest invokeAsync error[request={}]. Cost = [{}ms]",
                            cmd, System.currentTimeMillis() - startTime);
                    ownedBrokerCache.invalidate(partitionedTopicName);
                    sendResponse = sendResponseThreadLocal.get();
                    sendResponse.setOpaque(opaque);
                    sendResponse.markResponseType();
                    sendResponse.setCode(ResponseCode.SYSTEM_ERROR);
                    sendResponse.setRemark("processNonOwnedBrokerSendRequest invokeAsync error");
                    ctx.writeAndFlush(sendResponse);
                }

                if (traceContext != null) {
                    try {
                        if (sendResponse.getExtFields() != null && sendResponse.getExtFields()
                                .containsKey(ROP_INNER_MESSAGE_ID)) {
                            List<PutMessageResult> putMessageResults = JSON
                                    .parseArray(sendResponse.getExtFields().get(ROP_INNER_MESSAGE_ID),
                                            PutMessageResult.class);
                            for (PutMessageResult result : putMessageResults) {
                                traceContext.setOffset(result.getOffset());
                                traceContext.setMsgId(result.getMsgId());
                                traceContext.setMsgKey(result.getMsgKey());
                                traceContext.setTags(result.getMsgTag());
                                traceContext.setOffsetMsgId(result.getOffsetMsgId());
                                traceContext.setPulsarMsgId(result.getPulsarMsgId());
                                traceContext.setPartitionId(pulsarPartitionId);
                                traceContext.setPutStartTime(NumberUtils
                                        .toLong(cmd.getExtFields().get(ROP_TRACE_START_TIME),
                                                System.currentTimeMillis()));
                                traceContext.setEndTime(System.currentTimeMillis());
                                traceContext.setDuration(System.currentTimeMillis() - traceContext.getPutStartTime());
                                traceContext.setCode(sendResponse.getCode());
                                TraceManager.get().tracePut(traceContext);
                            }
                        }
                    } catch (Exception e) {
                        log.info("RoP trace put on proxy failed", e);
                    }
                }
            });

            if (System.currentTimeMillis() - startTime > 3000) {
                log.warn("RoP proxy invokeAsync timeout [request={}], address=[{}]. Cost = [{}ms]",
                        cmd, address, System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            log.warn("BrokerNetworkAPI proxy invokeAsyncSend request error[request={}]. Cost = [{}ms]",
                    cmd, System.currentTimeMillis() - startTime, e);
            ownedBrokerCache.invalidate(partitionedTopicName);
            RemotingCommand sendResponse = sendResponseThreadLocal.get();
            sendResponse.setOpaque(opaque);
            sendResponse.markResponseType();
            sendResponse.setCode(ResponseCode.SYSTEM_ERROR);
            sendResponse.setRemark("BrokerNetworkAPI invokeAsync[send] error");
            ctx.writeAndFlush(sendResponse);
        }
    }


    private void processGetMaxOffsetRequest(ChannelHandlerContext ctx, RemotingCommand cmd,
            TopicName pulsarTopicName, long timeout) {
        int pulsarPartitionId = Integer.parseInt(cmd.getExtFields().get(PULSAR_REAL_PARTITION_ID_TAG));
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);
        log.debug("processGetMaxOffsetRequest [topic={},pid={}] and address=[{}].", pulsarTopicName,
                pulsarPartitionId, address);
        final int opaque = cmd.getOpaque();
        try {
            brokerNetworkClients.invokeAsync(address, cmd, timeout, (responseFuture) -> {
                RemotingCommand getMaxOffsetResponse = responseFuture.getResponseCommand();
                if (getMaxOffsetResponse != null) {
//                    if (getMaxOffsetResponse.getCode() != ResponseCode.SUCCESS) {
//                        ownedBrokerCache.invalidate(partitionedTopicName);
//                    }
                    getMaxOffsetResponse.setOpaque(opaque);
                    getMaxOffsetResponse.markResponseType();
                    ctx.writeAndFlush(getMaxOffsetResponse);
                } else {
                    log.trace("processGetMaxOffsetRequest invokeAsync error[request={}].", cmd);
                    ownedBrokerCache.invalidate(partitionedTopicName);
                    getMaxOffsetResponse = RemotingCommand
                            .createResponseCommand(GetMaxOffsetResponseHeader.class);
                    getMaxOffsetResponse.setOpaque(opaque);
                    getMaxOffsetResponse.setCode(ResponseCode.SYSTEM_ERROR);
                    getMaxOffsetResponse.setRemark("processGetMaxOffsetRequest invokeAsync error");
                    ctx.writeAndFlush(getMaxOffsetResponse);
                }
            });
        } catch (Exception e) {
            log.warn("BrokerNetworkAPI processGetMaxOffsetRequest error.", e);
            ownedBrokerCache.invalidate(partitionedTopicName);
            RemotingCommand getMaxOffsetResponse = RemotingCommand
                    .createResponseCommand(GetMaxOffsetResponseHeader.class);
            getMaxOffsetResponse.setOpaque(opaque);
            getMaxOffsetResponse.setCode(ResponseCode.SYSTEM_ERROR);
            getMaxOffsetResponse.setRemark("BrokerNetworkAPI processGetMaxOffsetRequest error");
            ctx.writeAndFlush(getMaxOffsetResponse);
        }
    }

    private void processGetMinOffsetRequest(ChannelHandlerContext ctx, RemotingCommand cmd,
            TopicName pulsarTopicName, long timeout) {
        int pulsarPartitionId = Integer.parseInt(cmd.getExtFields().get(PULSAR_REAL_PARTITION_ID_TAG));
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);
        log.debug("processGetMinOffsetRequest [topic={},pid={}] and address=[{}].", pulsarTopicName,
                pulsarPartitionId, address);
        final int opaque = cmd.getOpaque();
        try {
            brokerNetworkClients.invokeAsync(address, cmd, timeout, (responseFuture) -> {
                RemotingCommand getMinOffsetResponse = responseFuture.getResponseCommand();
                if (getMinOffsetResponse != null) {
//                    if (getMinOffsetResponse.getCode() != ResponseCode.SUCCESS) {
//                        ownedBrokerCache.invalidate(partitionedTopicName);
//                    }
                    getMinOffsetResponse.setOpaque(opaque);
                    getMinOffsetResponse.markResponseType();
                    ctx.writeAndFlush(getMinOffsetResponse);
                } else {
                    log.trace("processGetMinOffsetRequest invokeAsync error[request={}].", cmd);
                    ownedBrokerCache.invalidate(partitionedTopicName);
                    getMinOffsetResponse = RemotingCommand.createResponseCommand(GetMinOffsetResponseHeader.class);
                    getMinOffsetResponse.setOpaque(opaque);
                    getMinOffsetResponse.setCode(ResponseCode.SYSTEM_ERROR);
                    getMinOffsetResponse.setRemark("processGetMinOffsetRequest invokeAsync error");
                    ctx.writeAndFlush(getMinOffsetResponse);
                }
            });
        } catch (Exception e) {
            log.warn("BrokerNetworkAPI processGetMinOffsetRequest error.", e);
            ownedBrokerCache.invalidate(partitionedTopicName);
            RemotingCommand getMinOffsetResponse = RemotingCommand
                    .createResponseCommand(GetMinOffsetResponseHeader.class);
            getMinOffsetResponse.setOpaque(opaque);
            getMinOffsetResponse.setCode(ResponseCode.SYSTEM_ERROR);
            getMinOffsetResponse.setRemark("BrokerNetworkAPI processGetMinOffsetRequest error");
            ctx.writeAndFlush(getMinOffsetResponse);
        }
    }

    private void processSearchOffsetByTimestampRequest(ChannelHandlerContext ctx, RemotingCommand cmd,
            TopicName pulsarTopicName, long timeout) {
        int pulsarPartitionId = Integer.parseInt(cmd.getExtFields().get(PULSAR_REAL_PARTITION_ID_TAG));
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);
        log.debug("processSearchOffsetByTimestampRequest [topic={},pid={}] and address=[{}].", pulsarTopicName,
                pulsarPartitionId, address);
        final int opaque = cmd.getOpaque();
        try {
            brokerNetworkClients.invokeAsync(address, cmd, timeout, (responseFuture) -> {
                RemotingCommand searchOffsetByTimestampResponse = responseFuture.getResponseCommand();
                if (searchOffsetByTimestampResponse != null) {
//                    if (searchOffsetByTimestampResponse.getCode() != ResponseCode.SUCCESS) {
//                        ownedBrokerCache.invalidate(partitionedTopicName);
//                    }
                    searchOffsetByTimestampResponse.setOpaque(opaque);
                    searchOffsetByTimestampResponse.markResponseType();
                    ctx.writeAndFlush(searchOffsetByTimestampResponse);
                } else {
                    log.trace("processSearchOffsetByTimestampRequest invokeAsync error[request={}].", cmd);
                    ownedBrokerCache.invalidate(partitionedTopicName);
                    searchOffsetByTimestampResponse = RemotingCommand
                            .createResponseCommand(SearchOffsetResponseHeader.class);
                    searchOffsetByTimestampResponse.setOpaque(opaque);
                    searchOffsetByTimestampResponse.setCode(ResponseCode.SYSTEM_ERROR);
                    searchOffsetByTimestampResponse
                            .setRemark("processSearchOffsetByTimestampRequest invokeAsync error");
                    ctx.writeAndFlush(searchOffsetByTimestampResponse);
                }
            });
        } catch (Exception e) {
            log.warn("BrokerNetworkAPI processSearchOffsetByTimestampRequest error.", e);
            ownedBrokerCache.invalidate(partitionedTopicName);
            RemotingCommand searchOffsetByTimestampResponse = RemotingCommand
                    .createResponseCommand(SearchOffsetResponseHeader.class);
            searchOffsetByTimestampResponse.setOpaque(opaque);
            searchOffsetByTimestampResponse.setCode(ResponseCode.SYSTEM_ERROR);
            searchOffsetByTimestampResponse.setRemark("BrokerNetworkAPI processSearchOffsetByTimestampRequest error");
            ctx.writeAndFlush(searchOffsetByTimestampResponse);
        }
    }

    private void processNonOwnedBrokerPullRequest(ChannelHandlerContext ctx, RemotingCommand cmd,
            PullMessageRequestHeader pullMsgHeader,
            TopicName pulsarTopicName, long timeout) {
        int pulsarPartitionId = Integer.parseInt(cmd.getExtFields().get(PULSAR_REAL_PARTITION_ID_TAG));
        TopicName partitionedTopicName = pulsarTopicName.getPartition(pulsarPartitionId);
        String address = lookupPulsarTopicBroker(partitionedTopicName);
        final int opaque = cmd.getOpaque();
        try {
            RemotingCommand newCmd = RemotingCommand.createRequestCommand(RequestCode.PULL_MESSAGE, pullMsgHeader);
            newCmd.addExtField(ROP_INNER_REMOTE_CLIENT_TAG,
                    INNER_CLIENT_NAME_PREFIX + pullMsgHeader.getConsumerGroup() + "_" + RemotingHelper
                            .parseChannelRemoteAddr(ctx.channel()));
            // The change fo rop acl, for new cmd, the access key is null.
            newCmd.getExtFields().putAll(cmd.getExtFields());
            newCmd.addExtField(PULSAR_REAL_PARTITION_ID_TAG, String.valueOf(pulsarPartitionId));
            newCmd.addExtField(ROP_INNER_CLIENT_ADDRESS, RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
            brokerNetworkClients.invokeAsync(address, newCmd, timeout, (responseFuture) -> {
                RemotingCommand pullResponse = responseFuture.getResponseCommand();
                if (pullResponse != null) {
                    pullResponse.setOpaque(opaque);
                    pullResponse.markResponseType();
                    ctx.writeAndFlush(pullResponse);
                } else {
                    log.error("Rop processNonOwnedBrokerPullRequest[request={}] pullResponse is null.", newCmd);
                }
            });
        } catch (Exception e) {
            log.warn("BrokerNetworkAPI invokeAsync error.", e);
            ownedBrokerCache.invalidate(partitionedTopicName);
            RemotingCommand consumeResponse = RemotingCommand.createResponseCommand(PullMessageResponseHeader.class);
            consumeResponse.setOpaque(opaque);
            consumeResponse.setCode(ResponseCode.SYSTEM_ERROR);
            consumeResponse.setRemark("BrokerNetworkAPI invokeAsync error");
            ctx.writeAndFlush(consumeResponse);
        }
    }

    @Override
    public void start() {
        super.start();
        try {
            this.pulsarService = brokerController.getBrokerService().pulsar();
            ServiceConfiguration config = this.pulsarService.getConfig();
            RopZookeeperCache ropZkCache = new RopZookeeperCache(pulsarService.getZkClientFactory(),
                    (int) config.getZooKeeperSessionTimeoutMillis(),
                    config.getZooKeeperOperationTimeoutSeconds(), config.getZookeeperServers(), orderedExecutor,
                    brokerController.getScheduledExecutorService(), config.getZooKeeperCacheExpirySeconds());
            this.zkService = new RopZookeeperCacheService(ropZkCache);
            this.zkService.start();

            initClusterMeta();
            setBrokerTagListener();

            this.mqTopicManager.start(zkService);
        } catch (Exception e) {
            log.error("RopBrokerProxy fail to start.", e);
            throw new RuntimeException("RopBrokerProxy not running.");
        }
    }

    private void initClusterMeta() throws Exception {
        RopClusterContent clusterContent = zkService.getClusterContent();
        List<String> activeBrokers = getActiveBrokers();
        int ropBrokerReplicationNum = getConfig().getRopBrokerReplicationNum();
        Preconditions.checkArgument(ropBrokerReplicationNum > 0);
        if (clusterContent == null) {
            //initialize RoP cluster metadata
            RopClusterContent defaultClusterContent = new RopClusterContent();
            defaultClusterContent.setClusterName(clusterName);
            log.info("RoP cluster[{}] broker list: {}.", defaultClusterContent.getClusterName(),
                    activeBrokers.toString());
            defaultClusterContent
                    .setBrokerCluster(
                            genBrokerGroupData(activeBrokers, ropBrokerReplicationNum));
            zkService.setJsonObjectForPath(BROKER_CLUSTER_PATH, defaultClusterContent);
            zkService.getClusterDataCache().reloadCache(BROKER_CLUSTER_PATH);
        } else if (getConfig().isAutoCreateRopClusterMeta()) {
            if (autoExpanseBrokerGroupData(clusterContent, activeBrokers, ropBrokerReplicationNum)) {
                zkService.setJsonObjectForPath(BROKER_CLUSTER_PATH, clusterContent);
                zkService.getClusterDataCache().reloadCache(BROKER_CLUSTER_PATH);
            }
        }
        log.info("RoP cluster metadata is: [{}].", zkService.getClusterContent());
    }

    public String getActiveBrokerByBrokerTag(String brokerTag) {
        try {
            RopClusterContent clusterContent = zkService.getClusterContent();
            List<String> routingBrokers = clusterContent.getBrokerCluster().get(brokerTag);
            List<String> activeBrokers = getActiveBrokers();
            routingBrokers.retainAll(activeBrokers);
            return routingBrokers.get(0);
        } catch (Exception e) {
            log.error("RoP cluster metadata is missing, service can't run correctly.");
        }
        return Strings.EMPTY;
    }

    private String setBrokerTagListener() {
        String brokerHost = brokerController.getBrokerHost();
        RopClusterContent clusterContent = zkService.getClusterContent();
        for (Entry<String, List<String>> entry : clusterContent.getBrokerCluster().entrySet()) {
            if (entry.getValue().contains(brokerHost)) {
                this.brokerTag = entry.getKey();
            }
        }
        zkService.getClusterDataCache().registerListener((path, data, stat) -> {
            if (BROKER_CLUSTER_PATH.equals(path)) {
                log.info("the cluster[{}] configure have changed, new configure: [{}].",
                        clusterContent.getClusterName(), data);
                String host = brokerController.getBrokerHost();
                for (Entry<String, List<String>> entry : data.getBrokerCluster().entrySet()) {
                    if (entry.getValue().contains(host)) {
                        brokerTag = entry.getKey();
                        log.info("current broker tag = {}.", brokerTag);
                    }
                }
            }
        });
        if (this.brokerTag.equals(Strings.EMPTY)) {
            log.warn("host[{}] isn't belong to current cluster[{}].", brokerHost, getConfig().getClusterName());
        }
        log.info("current broker tag = {}.", brokerTag);
        return brokerTag;
    }

    @Override
    public void close() throws Exception {
        this.coordinator.close();
        this.zkService.close();
        this.brokerNetworkClients.close();
        this.mqTopicManager.shutdown();
    }

    // such as: /rop/brokers/{xxx,}
    private void registerBrokerZNode() {
        String hostName = this.brokerController.getBrokerHost();
        String brokerPathRoot = RopZkUtils.BROKERS_PATH;
        String localAddressPath = joinPath(brokerPathRoot, hostName);
        this.zkService.getBrokerCache()
                .getAsync(localAddressPath)
                .thenApply(brokerInfo -> {
                    try {
                        if (brokerInfo.isPresent()) {
                            log.info("broker[{}] is already exists, delete it first.",
                                    hostName);
                            deleteFullPathOptimistic(zkService.getZookeeperCache().getZooKeeper(), localAddressPath,
                                    -1);
                        }

                        createFullPathOptimistic(zkService.getZookeeperCache().getZooKeeper(),
                                localAddressPath,
                                hostName.getBytes(StandardCharsets.UTF_8),
                                Ids.OPEN_ACL_UNSAFE,
                                CreateMode.EPHEMERAL);
                        zkService.getBrokerCache().reloadCache(localAddressPath);
                        log.info("broker address ===========>[{}].",
                                zkService.getBrokerCache().getDataIfPresent(localAddressPath));
                    } catch (KeeperException | InterruptedException e) {
                        log.warn("broker[{}] is already exists.", hostName, e);
                    }
                    return null;
                });
    }

    public void registerProcessor() {
        // SendMessageProcessor
        processorProxyRegisters.add(new SendMessageProcessorProxy(brokerController.getSendMessageExecutor()));

        // PullMessageProcessor
        processorProxyRegisters.add(new PullMessageProcessorProxy(brokerController.getPullMessageExecutor()));

        // QueryMessageProcessor
        processorProxyRegisters.add(new QueryMessageProcessorProxy(brokerController.getQueryMessageExecutor()));

        // ClientManageProcessor
        processorProxyRegisters.add(new ClientManageProcessorProxy(brokerController.getHeartbeatExecutor()));

        // ConsumerManageProcessor
        processorProxyRegisters
                .add(new ConsumerManageProcessorProxy(brokerController.getConsumerManageExecutor()));

        // EndTransactionProcessor
        processorProxyRegisters
                .add(new EndTransactionProcessorProxy(brokerController.getEndTransactionExecutor()));

        // NameserverProcessor
        processorProxyRegisters.add(new NameserverProcessorProxy(brokerController.getAdminBrokerExecutor()));

        // Default
        processorProxyRegisters.add(new AdminBrokerProcessorProxy(brokerController.getAdminBrokerExecutor()));

        //register all processors to remoteServer
        processorProxyRegisters.forEach(ProcessorProxyRegister::registerProxyProcessor);
    }

    /**
     * Processor Proxy Register.
     */
    protected interface ProcessorProxyRegister {

        /**
         * register Proxy Processor.
         *
         * @return boolean
         */
        boolean registerProxyProcessor();
    }

    /**
     * Admin Broker Processor Proxy.
     */
    protected class AdminBrokerProcessorProxy extends AdminBrokerProcessor implements ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public AdminBrokerProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerDefaultProcessor(this, processorExecutor);
            return true;
        }

        /**
         * process Request.
         */
        @Override
        public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
                throws RemotingCommandException {
            //TODO
            return super.processRequest(ctx, request);
        }
    }

    /**
     * Nameserver Processor Proxy.
     */
    protected class NameserverProcessorProxy extends NameserverProcessor implements ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public NameserverProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerProcessor(RequestCode.PUT_KV_CONFIG, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_KV_CONFIG, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.DELETE_KV_CONFIG, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.QUERY_DATA_VERSION, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.REGISTER_BROKER, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.UNREGISTER_BROKER, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_ROUTEINTO_BY_TOPIC, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_BROKER_CLUSTER_INFO, this, processorExecutor);
            RopBrokerProxy.this
                    .registerProcessor(RequestCode.WIPE_WRITE_PERM_OF_BROKER, this, processorExecutor);
            RopBrokerProxy.this
                    .registerProcessor(RequestCode.GET_ALL_TOPIC_LIST_FROM_NAMESERVER, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.DELETE_TOPIC_IN_NAMESRV, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_KVLIST_BY_NAMESPACE, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_TOPICS_BY_CLUSTER, this, processorExecutor);
            RopBrokerProxy.this
                    .registerProcessor(RequestCode.GET_SYSTEM_TOPIC_LIST_FROM_NS, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_UNIT_TOPIC_LIST, this, processorExecutor);
            RopBrokerProxy.this
                    .registerProcessor(RequestCode.GET_HAS_UNIT_SUB_TOPIC_LIST, this, processorExecutor);
            RopBrokerProxy.this
                    .registerProcessor(RequestCode.GET_HAS_UNIT_SUB_UNUNIT_TOPIC_LIST, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.UPDATE_NAMESRV_CONFIG, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.GET_NAMESRV_CONFIG, this, processorExecutor);
            return true;
        }
    }

    /**
     * Consumer Manage Processor Proxy.
     */
    protected class ConsumerManageProcessorProxy extends ConsumerManageProcessor implements
            ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public ConsumerManageProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this
                    .registerProcessor(RequestCode.GET_CONSUMER_LIST_BY_GROUP, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.UPDATE_CONSUMER_OFFSET, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.QUERY_CONSUMER_OFFSET, this, processorExecutor);
            return true;
        }
    }

    /**
     * Client Manage Processor Proxy.
     */
    protected class ClientManageProcessorProxy extends ClientManageProcessor implements ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public ClientManageProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerProcessor(RequestCode.HEART_BEAT, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.UNREGISTER_CLIENT, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.CHECK_CLIENT_CONFIG, this, processorExecutor);
            return true;
        }
    }

    /**
     * Query Message Processor Proxy.
     */
    protected class QueryMessageProcessorProxy extends QueryMessageProcessor implements ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public QueryMessageProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerProcessor(RequestCode.QUERY_MESSAGE, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(RequestCode.VIEW_MESSAGE_BY_ID, this, processorExecutor);
            return true;
        }
    }

    /**
     * Pull Message Processor Proxy.
     */
    protected class PullMessageProcessorProxy extends PullMessageProcessor implements ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public PullMessageProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
                throws RemotingCommandException {
            return super.processRequest(ctx, request);
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerProcessor(PULL_MESSAGE, this, processorExecutor);
            RopBrokerProxy.this.pullMessageProcessor = this;
            return true;
        }
    }

    /**
     * Send Message Processor Proxy.
     */
    protected class SendMessageProcessorProxy extends SendMessageProcessor implements ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public SendMessageProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
            registerSendMessageHook(sendMessageHookList);
            registerConsumeMessageHook(consumeMessageHookList);
        }

        @Override
        public RemotingCommand processRequest(ChannelHandlerContext ctx, RemotingCommand request)
                throws RemotingCommandException {
            return super.processRequest(ctx, request);
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerProcessor(SEND_MESSAGE, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(SEND_MESSAGE_V2, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(SEND_BATCH_MESSAGE, this, processorExecutor);
            RopBrokerProxy.this.registerProcessor(CONSUMER_SEND_MSG_BACK, this, processorExecutor);
            return true;
        }
    }

    /**
     * End Transaction Processor Proxy.
     */
    protected class EndTransactionProcessorProxy extends EndTransactionProcessor implements
            ProcessorProxyRegister {

        private final ExecutorService processorExecutor;

        public EndTransactionProcessorProxy(ExecutorService processorExecutor) {
            super(RopBrokerProxy.this.brokerController);
            this.processorExecutor = processorExecutor;
        }

        @Override
        public boolean registerProxyProcessor() {
            RopBrokerProxy.this.registerProcessor(RequestCode.END_TRANSACTION, this, processorExecutor);
            return true;
        }
    }

    public List<String> getActiveBrokers() {
        ModularLoadManagerImpl loadManager = (ModularLoadManagerImpl) ((ModularLoadManagerWrapper) pulsarService
                .getLoadManager().get()).getLoadManager();
        return loadManager.getAvailableBrokers().stream()
                .map(broker -> Splitter.on(COLO_CHAR).splitToList(broker).get(0)).collect(
                        Collectors.toList());
    }

    private PulsarClientImpl initPulsarClient(String listenerName) {
        try {
            ClientBuilder builder =
                    PulsarClient.builder().serviceUrl(pulsarService.getBrokerServiceUrl());
            if (StringUtils.isNotBlank(getConfig().getBrokerClientAuthenticationPlugin())) {
                builder.authentication(
                        getConfig().getBrokerClientAuthenticationPlugin(),
                        getConfig().getBrokerClientAuthenticationParameters()
                );
            }
            if (StringUtils.isNotBlank(listenerName)) {
                builder.listenerName(listenerName);
            }
            return (PulsarClientImpl) builder.build();
        } catch (Exception e) {
            log.error("listenerName [{}] getClient error", listenerName, e);
            return null;
        }
    }

    public PulsarClientImpl getPulsarClient() {
        PulsarClientImpl pulsarClient = pulsarClientThreadLocal.get();
        if (pulsarClient == null || pulsarClient.isClosed()) {
            pulsarClientThreadLocal.remove();
            pulsarClient = initPulsarClient(null);
            pulsarClientThreadLocal.set(pulsarClient);
        }
        return pulsarClient;
    }

    public String lookupPulsarTopicBroker(TopicName pulsarTopicName) {
        try {
            return ownedBrokerCache.get(pulsarTopicName, () -> {
                InetSocketAddress pulsarBrokerAddr = getPulsarClient().getLookup()
                        .getBroker(pulsarTopicName)
                        .get(5, TimeUnit.SECONDS)
                        .getLeft();
                return Joiner.on(COLO_CHAR).join(pulsarBrokerAddr.getHostName(), ROP_SERVICE_PORT);
            });
        } catch (Exception e) {
            log.error("LookupTopics pulsar topic=[{}] error.", pulsarTopicName, e);
        }
        return null;
    }

}
