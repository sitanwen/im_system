package com.stw.im.service.message.service;

import com.alibaba.fastjson.JSONObject;
import com.stw.im.codec.pack.message.ChatMessageAck;
import com.stw.im.codec.pack.message.MessageReciveServerAckPack;
import com.stw.im.common.ResponseVO;
import com.stw.im.common.config.AppConfig;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.ConversationTypeEnum;
import com.stw.im.common.enums.command.MessageCommand;
import com.stw.im.common.model.ClientInfo;
import com.stw.im.common.model.message.MessageContent;
import com.stw.im.common.model.message.OfflineMessageContent;
import com.stw.im.service.message.model.req.SendMessageReq;
import com.stw.im.service.message.model.resp.SendMessageResp;
import com.stw.im.service.seq.RedisSeq;
import com.stw.im.service.utils.CallbackService;
import com.stw.im.service.utils.ConversationIdGenerate;
import com.stw.im.service.utils.MessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final ConcurrentHashMap<String, ReentrantLock> messageLocks = new ConcurrentHashMap<>();

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

    /**
     * 静态代码块初始化线程池
     * 核心线程数8，最大线程数8，空闲时间60秒，使用有界队列(容量1000)
     * 线程命名规则：message-process-thread-自增序号，设置为守护线程
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
                }
        );
    }

    /**
     * 处理点对点消息的核心方法
     * 包含消息重复校验、前置回调、序列号生成、消息存储、同步分发等流程
     * @param messageContent 消息内容对象，包含发送者、接收者、消息体等信息
     */
    public void process(MessageContent messageContent){
        logger.info("消息开始处理：{}", messageContent.getMessageId());
        String fromId = messageContent.getFromId(); // 发送者ID
        String toId = messageContent.getToId();     // 接收者ID
        Integer appId = messageContent.getAppId();  // 应用ID



        // 1. 检查消息是否已处理过（通过消息ID缓存），避免重复处理
        MessageContent messageFromMessageIdCache = messageStoreService.getMessageFromMessageIdCache(
                messageContent.getAppId(), messageContent.getMessageId(), MessageContent.class);
        if (messageFromMessageIdCache != null) {
            // 已处理过的消息，直接异步执行后续分发流程
            threadPoolExecutor.execute(() -> {
                ack(messageContent, ResponseVO.successResponse()); // 回复ACK确认
                syncToSender(messageFromMessageIdCache, messageFromMessageIdCache); // 同步给发送者其他在线端
                List<ClientInfo> clientInfos = dispatchMessage(messageFromMessageIdCache); // 分发消息给接收者
                if (clientInfos.isEmpty()) {
                    // 接收者无在线端，发送服务端接收确认
                    reciverAck(messageFromMessageIdCache);
                }
            });
            return;
        }

        // 2. 消息发送前置回调（若配置开启）
        ResponseVO responseVO = ResponseVO.successResponse();
        if (appConfig.isSendMessageAfterCallback()) {
            responseVO = callbackService.beforeCallback(
                    messageContent.getAppId(),
                    Constants.CallbackCommand.SendMessageBefore,
                    JSONObject.toJSONString(messageContent)
            );
        }

        // 3. 前置回调失败则直接回复错误ACK
        if (!responseVO.isOk()) {
            ack(messageContent, responseVO);
            return;
        }

        // 4. 生成消息序列号（用于消息排序和同步）
        long seq = redisSeq.doGetSeq(
                messageContent.getAppId() + ":"
                        + Constants.SeqConstants.Message + ":"
                        + ConversationIdGenerate.generateP2PId(messageContent.getFromId(), messageContent.getToId())
        );
        messageContent.setMessageSequence(seq);

        // 5. 异步执行消息存储和分发逻辑
        threadPoolExecutor.execute(() -> {
            // 5.1 存储点对点消息到数据库
            messageStoreService.storeP2PMessage(messageContent);

            // 5.2 构建离线消息对象并存储到Redis
            OfflineMessageContent offlineMessageContent = new OfflineMessageContent();
            BeanUtils.copyProperties(messageContent, offlineMessageContent);
            offlineMessageContent.setConversationType(ConversationTypeEnum.P2P.getCode()); // 标记为点对点消息
            messageStoreService.storeOfflineMessage(offlineMessageContent);

            // 5.3 回复发送者消息处理成功的ACK
            ack(messageContent, ResponseVO.successResponse());

            // 5.4 同步消息到发送者的其他在线客户端
            syncToSender(messageContent, messageContent);

            // 5.5 将消息分发到接收者的在线客户端
            List<ClientInfo> clientInfos = dispatchMessage(messageContent);

            // 5.6 将消息缓存到Redis（通过消息ID），用于重复校验
            messageStoreService.setMessageFromMessageIdCache(
                    messageContent.getAppId(),
                    messageContent.getMessageId(),
                    messageContent
            );

            // 5.7 若接收者无在线客户端，发送服务端接收确认
            if (clientInfos.isEmpty()) {
                reciverAck(messageContent);
            }

            // 5.8 消息发送后置回调（若配置开启）
            if (appConfig.isSendMessageAfterCallback()) {
                callbackService.callback(
                        messageContent.getAppId(),
                        Constants.CallbackCommand.SendMessageAfter,
                        JSONObject.toJSONString(messageContent)
                );
            }

            logger.info("消息处理完成：{}", messageContent.getMessageId());
        });
    }

    /**
     * 将消息分发到接收者的在线客户端
     * @param messageContent 消息内容
     * @return 成功接收消息的客户端信息列表
     */
    private List<ClientInfo> dispatchMessage(MessageContent messageContent) {
        // 通过消息生产者发送消息到接收者，使用点对点消息命令
        List<ClientInfo> clientInfos = messageProducer.sendToUser(
                messageContent.getToId(),
                MessageCommand.MSG_P2P,
                messageContent,
                messageContent.getAppId()
        );
        return clientInfos;
    }

    /**
     * 向发送者回复消息处理结果的ACK
     * @param messageContent 消息内容
     * @param responseVO 处理结果封装对象
     */
    private void ack(MessageContent messageContent, ResponseVO responseVO) {
        logger.info("消息ACK回复，msgId={}, 处理结果={}", messageContent.getMessageId(), responseVO.getCode());

        // 构建ACK消息体，包含消息ID和序列号
        ChatMessageAck chatMessageAck = new ChatMessageAck(
                messageContent.getMessageId(),
                messageContent.getMessageSequence()
        );
        responseVO.setData(chatMessageAck);

        // 发送ACK消息给发送者
        messageProducer.sendToUser(
                messageContent.getFromId(),
                MessageCommand.MSG_ACK,
                responseVO,
                messageContent
        );
    }

    /**
     * 当接收者离线时，向发送者发送服务端接收确认
     * @param messageContent 消息内容
     */
    public void reciverAck(MessageContent messageContent) {
        MessageReciveServerAckPack pack = new MessageReciveServerAckPack();
        pack.setFromId(messageContent.getToId()); // 接收者ID
        pack.setToId(messageContent.getFromId()); // 发送者ID
        pack.setMessageKey(messageContent.getMessageKey()); // 消息唯一标识
        pack.setMessageSequence(messageContent.getMessageSequence()); // 消息序列号
        pack.setServerSend(true); // 标记为服务端发送的确认

        // 发送确认消息给发送者
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
    }

    /**
     * 将消息同步到发送者的其他在线客户端（除了发送消息的客户端）
     * @param messageContent 消息内容
     * @param clientInfo 客户端信息（用于排除发送端）
     */
    private void syncToSender(MessageContent messageContent, ClientInfo clientInfo) {
        messageProducer.sendToUserExceptClient(
                messageContent.getFromId(),
                MessageCommand.MSG_P2P,
                messageContent,
                messageContent
        );
    }

    /**
     * 消息发送权限校验
     * 包括发送者是否被禁言、发送者与接收者是否为好友等校验
     * @param fromId 发送者ID
     * @param toId 接收者ID
     * @param appId 应用ID
     * @return 校验结果
     */
    public ResponseVO imServerPermissionCheck(String fromId, String toId, Integer appId) {
        // 校验发送者是否被禁言或禁用
        ResponseVO responseVO = checkSendMessageService.checkSenderForvidAndMute(fromId, appId);
        if (!responseVO.isOk()) {
            return responseVO;
        }
        // 校验发送者与接收者是否为好友关系
        responseVO = checkSendMessageService.checkFriendShip(fromId, toId, appId);
        return responseVO;
    }

    /**
     * 处理发送消息的请求（对外接口）
     * @param req 发送消息请求参数
     * @return 发送结果，包含消息标识和时间
     */
    public SendMessageResp send(SendMessageReq req) {
        SendMessageResp sendMessageResp = new SendMessageResp();
        // 转换请求参数为消息内容对象
        MessageContent message = new MessageContent();
        BeanUtils.copyProperties(req, message);

        // 存储消息
        messageStoreService.storeP2PMessage(message);

        // 设置返回结果
        sendMessageResp.setMessageKey(message.getMessageKey());
        sendMessageResp.setMessageTime(System.currentTimeMillis());

        // 同步消息到发送者其他在线端
        syncToSender(message, message);

        // 分发消息到接收者
        dispatchMessage(message);

        return sendMessageResp;
    }
}