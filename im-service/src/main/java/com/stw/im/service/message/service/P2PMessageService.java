package com.stw.im.service.message.service;

import com.alibaba.fastjson.JSONObject;
import com.stw.im.codec.pack.message.ChatMessageAck;
import com.stw.im.codec.pack.message.MessageReciveServerAckPack;
import com.stw.im.common.ResponseVO;
import com.stw.im.common.config.AppConfig;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.ConversationTypeEnum;
import com.stw.im.common.enums.ImConnectStatusEnum;
import com.stw.im.common.enums.command.MessageCommand;
import com.stw.im.common.model.ClientInfo;
import com.stw.im.common.model.UserSession;
import com.stw.im.common.model.message.MessageContent;
import com.stw.im.common.model.message.OfflineMessageContent;
import com.stw.im.service.message.model.req.SendMessageReq;
import com.stw.im.service.message.model.resp.SendMessageResp;
import com.stw.im.service.seq.RedisSeq;
import com.stw.im.service.utils.CallbackService;
import com.stw.im.service.utils.ConversationIdGenerate;
import com.stw.im.service.utils.MessageProducer;
import com.stw.im.tcp.utils.SessionSocketHolder;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @description: 点对点消息服务类，负责处理个人之间的消息发送、存储、同步等核心业务逻辑
 * @author: stw
 * @version: 1.0
 */
@Service
public class P2PMessageService {

    private static final Logger logger = LoggerFactory.getLogger(P2PMessageService.class);

    // 本地锁容器：key=messageId，value=锁对象（确保同一messageId串行处理）
    private final ConcurrentHashMap<String, ReentrantLock> groupMessageLocks = new ConcurrentHashMap<>();

    /** 消息发送前置校验服务 */
    @Autowired
    private CheckSendMessageService checkSendMessageService;

    /** 消息生产者，用于向指定用户发送消息 */
    @Autowired
    private MessageProducer messageProducer;

    /** 消息存储服务，负责消息的持久化和缓存操作 */
    @Autowired
    private DbMessageStoreService messageStoreService;

    /** Redis序列生成器，用于生成消息序列号 */
    @Autowired
    private RedisSeq redisSeq;

    /** 应用配置类，包含系统级配置参数 */
    @Autowired
    private AppConfig appConfig;

    /** 回调服务，用于消息发送前后的回调通知 */
    @Autowired
    private CallbackService callbackService;

    /** 线程池，用于异步处理消息相关操作，避免阻塞主线程 */
    private final ThreadPoolExecutor threadPoolExecutor;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    /**
     * 静态代码块初始化线程池
     * 核心线程数8，最大线程数8，空闲时间60秒，使用有界队列(容量1000)
     * 线程命名规则：message-process-thread-自增序号，设置为守护线程
     * 增加拒绝策略：当队列满时让提交任务的线程执行，避免任务丢失
     */
    {
        final AtomicInteger num = new AtomicInteger(0);
        threadPoolExecutor = new ThreadPoolExecutor(
                8, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingDeque<>(1000),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setDaemon(true); // 守护线程，随主线程退出
                        thread.setName("message-process-thread-" + num.getAndIncrement());
                        return thread;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者线程执行
        );
    }

    /**
     * 处理点对点消息的核心方法
     * 包含消息重复校验、前置回调、序列号生成、消息存储、同步分发等流程
     * @param messageContent 消息内容对象，包含发送者、接收者、消息体等信息
     */
    public void process(MessageContent messageContent) {
        String messageId = messageContent.getMessageId();
        logger.info("消息开始处理：{}", messageId);
        String fromId = messageContent.getFromId();
        String toId = messageContent.getToId();
        Integer appId = messageContent.getAppId();
        ReentrantLock lock = null;

        String statusKey = Constants.MSG_STATUS_KEY_PREFIX + messageId;

        try {
            // 1. 获取本地锁（确保同一消息串行处理）
            lock = groupMessageLocks.computeIfAbsent(messageId, k -> new ReentrantLock());
            lock.lock();

            // 2. 检查消息是否已处理过（Redis状态+本地缓存双重校验）
            String status = stringRedisTemplate.opsForValue().get(statusKey);
            MessageContent cachedMessage = messageStoreService.getMessageFromMessageIdCache(
                    appId, messageId, MessageContent.class);

            if (Constants.MsgStatus.SUCCESS.getStatus().equals(status) && cachedMessage != null) {
                logger.info("消息已处理，直接分发：{}", messageId);
                // 已处理过的消息，异步执行分发流程
                threadPoolExecutor.execute(() -> handleProcessedMessage(cachedMessage));
                return;
            }

            // 3. 消息发送前置回调（若配置开启）
            ResponseVO callbackResp = ResponseVO.successResponse();
            if (appConfig.isSendMessageAfterCallback()) {
                callbackResp = callbackService.beforeCallback(
                        appId,
                        Constants.CallbackCommand.SendMessageBefore,
                        JSONObject.toJSONString(messageContent)
                );
            }

            // 4. 前置回调失败则回复错误ACK
            if (!callbackResp.isOk()) {
                logger.warn("消息前置回调失败：{}，msgId：{}", callbackResp.getMsg(), messageId);
                ack(messageContent, callbackResp);
                return;
            }

            // 5. 生成消息序列号（用于消息排序和同步）
            long seq = redisSeq.doGetSeq(
                    appId + ":" + Constants.SeqConstants.Message + ":" +
                            ConversationIdGenerate.generateP2PId(fromId, toId)
            );
            messageContent.setMessageSequence(seq);
            logger.info("消息生成序列号：{}，msgId：{}", seq, messageId);

            // 6. 异步执行消息存储和分发逻辑
            threadPoolExecutor.execute(() -> asyncProcess(messageContent, statusKey));

        } catch (Exception e) {
            logger.error("消息处理主流程异常，msgId：{}", messageId, e);
            ack(messageContent, ResponseVO.errorResponse("消息处理失败"));
        } finally {
            // 确保锁释放，避免死锁
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
                logger.info("消息处理锁释放：{}", messageId);
            }
        }
    }

    /**
     * 处理已完成的消息（分发和同步）
     */
    private void handleProcessedMessage(MessageContent messageContent) {
        try {
            ack(messageContent, ResponseVO.successResponse());
            syncToSender(messageContent);
            List<ClientInfo> clientInfos = dispatchMessage(messageContent);
            if (clientInfos.isEmpty()) {
                reciverAck(messageContent);
            }
        } catch (Exception e) {
            logger.error("处理已完成消息异常，msgId：{}", messageContent.getMessageId(), e);
        }
    }

    /**
     * 异步处理消息存储和分发
     */
    private void asyncProcess(MessageContent messageContent, String statusKey) {
        try {
            // 5.1 存储点对点消息到数据库
            messageStoreService.storeP2PMessage(messageContent);

            // 5.2 构建离线消息并存储到Redis
            // 5.2 构建离线消息，仅在接收方离线时存储到Redis
            OfflineMessageContent offlineMsg = new OfflineMessageContent();
            BeanUtils.copyProperties(messageContent, offlineMsg);
            offlineMsg.setConversationType(ConversationTypeEnum.P2P.getCode());

        // 判断接收方是否在线（需补充在线状态查询逻辑）
            boolean isReceiverOnline = isUserOnline(messageContent.getAppId(), messageContent.getToId());
            if (!isReceiverOnline) {
                // 接收方离线，存储离线消息
                messageStoreService.storeOfflineMessage(offlineMsg);
            }


            // 5.3 回复发送者ACK
            ack(messageContent, ResponseVO.successResponse());

            // 5.4 同步到发送者其他在线端
            syncToSender(messageContent);

            // 5.5 分发消息到接收者
            List<ClientInfo> clientInfos = dispatchMessage(messageContent);

            // 5.6 缓存消息到Redis（去重校验）
            messageStoreService.setMessageFromMessageIdCache(
                    messageContent.getAppId(),
                    messageContent.getMessageId(),
                    messageContent
            );

            // 5.7 接收者离线时发送服务端确认
            if (clientInfos.isEmpty()) {
                reciverAck(messageContent);
            }

            // 5.8 后置回调
            if (appConfig.isSendMessageAfterCallback()) {
                callbackService.callback(
                        messageContent.getAppId(),
                        Constants.CallbackCommand.SendMessageAfter,
                        JSONObject.toJSONString(messageContent)
                );
            }

            // 5.9 标记消息处理成功
            stringRedisTemplate.opsForValue().set(statusKey, Constants.MsgStatus.SUCCESS.getStatus(),
                    24, TimeUnit.HOURS); // 缓存24小时

            logger.info("消息处理完成：{}", messageContent.getMessageId());

        } catch (Exception e) {
            logger.error("消息异步处理异常，msgId：{}", messageContent.getMessageId(), e);
            // 标记消息处理失败
            stringRedisTemplate.opsForValue().set(statusKey, Constants.MsgStatus.FAILED.getStatus(),
                    1, TimeUnit.HOURS);
        }
    }

    /**
     * 将消息分发到接收者的在线客户端
     * @param messageContent 消息内容
     * @return 成功接收消息的客户端信息列表
     */
    private List<ClientInfo> dispatchMessage(MessageContent messageContent) {
        try {
            return messageProducer.sendToUser(
                    messageContent.getToId(),
                    MessageCommand.MSG_P2P,
                    messageContent,
                    messageContent.getAppId()
            );
        } catch (Exception e) {
            logger.error("消息分发异常，msgId：{}", messageContent.getMessageId(), e);
            return Arrays.asList();
        }
    }

    /**
     * 向发送者回复消息处理结果的ACK
     */
    private void ack(MessageContent messageContent, ResponseVO responseVO) {
        try {
            ChatMessageAck ack = new ChatMessageAck(
                    messageContent.getMessageId(),
                    messageContent.getMessageSequence()
            );
            responseVO.setData(ack);

            messageProducer.sendToUser(
                    messageContent.getFromId(),
                    MessageCommand.MSG_ACK,
                    responseVO,
                    messageContent
            );
            logger.info("消息ACK回复完成，msgId：{}", messageContent.getMessageId());
        } catch (Exception e) {
            logger.error("消息ACK回复异常，msgId：{}", messageContent.getMessageId(), e);
        }
    }

    /**
     * 接收者离线时，向发送者发送服务端接收确认
     */
    public void reciverAck(MessageContent messageContent) {
        try {
            MessageReciveServerAckPack pack = new MessageReciveServerAckPack();
            pack.setFromId(messageContent.getToId());
            pack.setToId(messageContent.getFromId());
            pack.setMessageKey(messageContent.getMessageKey());
            pack.setMessageSequence(messageContent.getMessageSequence());
            pack.setServerSend(true);

            messageProducer.sendToUser(
                    messageContent.getFromId(),
                    MessageCommand.MSG_RECIVE_ACK,
                    pack,
                    new ClientInfo(
                            messageContent.getAppId(),
                            messageContent.getClientType(),
                            messageContent.getImei()
                    )
            );
            logger.info("服务端接收确认发送完成，msgId：{}", messageContent.getMessageId());
        } catch (Exception e) {
            logger.error("服务端接收确认发送异常，msgId：{}", messageContent.getMessageId(), e);
        }
    }

    /**
     * 将消息同步到发送者的其他在线客户端（排除发送端）
     */
    private void syncToSender(MessageContent messageContent) {
        try {
            ClientInfo excludeClient = new ClientInfo(
                    messageContent.getAppId(),
                    messageContent.getClientType(),
                    messageContent.getImei()
            );
            messageProducer.sendToUserExceptClient(
                    messageContent.getFromId(),
                    MessageCommand.MSG_P2P,
                    messageContent,
                    excludeClient
            );
            logger.info("消息同步到发送者其他端完成，msgId：{}", messageContent.getMessageId());
        } catch (Exception e) {
            logger.error("消息同步到发送者其他端异常，msgId：{}", messageContent.getMessageId(), e);
        }
    }

    /**
     * 消息发送权限校验
     */
    public ResponseVO imServerPermissionCheck(String fromId, String toId, Integer appId) {
        try {
            // 校验发送者是否被禁言或禁用
            ResponseVO resp = checkSendMessageService.checkSenderForvidAndMute(fromId, appId);
            if (!resp.isOk()) {
                return resp;
            }
            // 校验发送者与接收者是否为好友关系
            return checkSendMessageService.checkFriendShip(fromId, toId, appId);
        } catch (Exception e) {
            logger.error("消息权限校验异常，fromId：{}，toId：{}", fromId, toId, e);
            return ResponseVO.errorResponse("权限校验失败");
        }
    }

    /**
     * 处理发送消息的请求（对外接口）
     */
    public SendMessageResp send(SendMessageReq req) {
        SendMessageResp resp = new SendMessageResp();
        String messageId = req.getMessageId();
        try {
            // 重复发送校验
            String statusKey = Constants.MSG_STATUS_KEY_PREFIX + messageId;
            String status = stringRedisTemplate.opsForValue().get(statusKey);
            if (Constants.MsgStatus.SUCCESS.getStatus().equals(status)) {
                MessageContent cachedMsg = messageStoreService.getMessageFromMessageIdCache(
                        req.getAppId(), messageId, MessageContent.class);
                if (cachedMsg != null) {
                    resp.setMessageKey(cachedMsg.getMessageKey());
                    resp.setMessageTime(cachedMsg.getMessageTime());
                    return resp;
                }
            }

            // 转换请求为消息内容
            MessageContent message = new MessageContent();
            BeanUtils.copyProperties(req, message);
            message.setMessageTime(System.currentTimeMillis());

            // 存储消息
            messageStoreService.storeP2PMessage(message);

            // 设置返回结果
            resp.setMessageKey(message.getMessageKey());
            resp.setMessageTime(message.getMessageTime());

            // 同步和分发
            syncToSender(message);
            dispatchMessage(message);

            // 标记发送成功
            stringRedisTemplate.opsForValue().set(statusKey, Constants.MsgStatus.SUCCESS.getStatus(),
                    24, TimeUnit.HOURS);

        } catch (Exception e) {
            logger.error("消息发送接口异常，msgId：{}", messageId, e);
            throw new RuntimeException("消息发送失败", e);
        }
        return resp;
    }


    /**
     * 判断用户是否在线
     * @param appId 应用ID
     * @param userId 用户ID
     * @return true=在线，false=离线
     */
    private boolean isUserOnline(Integer appId, String userId) {
        // 1. 先查本地缓存（当前节点的在线连接）
        List<NioSocketChannel> channels = SessionSocketHolder.get(appId, userId);
        if (channels != null && !channels.isEmpty()) {
            return true;
        }

        // 2. 再查Redis中的用户会话（跨节点在线状态） 多端登录
        String userSessionKey = appId + ":" + Constants.RedisConstants.USER_SESSION + ":" + userId;
        HashOperations<String, String, String> hashOps = stringRedisTemplate.opsForHash();
        Map<String, String> sessionMap = hashOps.entries(userSessionKey);

        // 检查是否有在线状态的会话（connectState=1）
        for (String sessionInfo : sessionMap.values()) {
            UserSession session = JSONObject.parseObject(sessionInfo, UserSession.class);
            if (session != null && ImConnectStatusEnum.ONLINE_STATUS.getCode() == session.getConnectState()) {
                return true;
            }
        }

        return false;
    }
}