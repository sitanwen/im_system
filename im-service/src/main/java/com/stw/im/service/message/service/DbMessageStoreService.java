package com.stw.im.service.message.service;

import com.alibaba.fastjson.JSONObject;
import com.stw.im.common.config.AppConfig;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.ConversationTypeEnum;
import com.stw.im.common.enums.DelFlagEnum;
import com.stw.im.common.model.message.*;
import com.stw.im.service.conversation.service.ConversationService;
import com.stw.im.service.group.dao.ImGroupMessageHistoryEntity;
import com.stw.im.service.group.dao.mapper.ImGroupMessageHistoryMapper;
import com.stw.im.service.message.dao.ImMessageBodyEntity;
import com.stw.im.service.message.dao.ImMessageHistoryEntity;
import com.stw.im.service.message.dao.mapper.ImMessageBodyMapper;
import com.stw.im.service.message.dao.mapper.ImMessageHistoryMapper;
import com.stw.im.service.utils.SnowflakeIdWorker;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @description: 消息存储核心服务类
 * 负责处理单聊/群聊消息的持久化、离线消息存储、消息缓存等核心逻辑
 * @author: stw
 * @version: 1.0
 */
@Service
public class DbMessageStoreService {

    @Autowired
    ImMessageHistoryMapper imMessageHistoryMapper; // 单聊消息历史Mapper

    @Autowired
    ImMessageBodyMapper imMessageBodyMapper; // 消息体Mapper

    @Autowired
    SnowflakeIdWorker snowflakeIdWorker; // 分布式ID生成器

    @Autowired
    ImGroupMessageHistoryMapper imGroupMessageHistoryMapper; // 群聊消息历史Mapper

    @Autowired
    RabbitTemplate rabbitTemplate; // RabbitMQ消息模板，用于异步消息投递

    @Autowired
    StringRedisTemplate stringRedisTemplate; // Redis操作模板，用于离线消息和缓存

    @Autowired
    ConversationService conversationService; // 会话服务，用于生成会话ID

    @Autowired
    AppConfig appConfig; // 应用配置，包含离线消息数量限制等

    /**
     * 存储单聊消息（异步方式）
     * 核心逻辑：生成消息体 -> 封装DTO -> 发送到RabbitMQ队列，由消息存储服务异步处理持久化
     */
    @Transactional
    public void storeP2PMessage(MessageContent messageContent){
        // 1. 生成消息体实体（包含消息内容、时间等核心信息）
        ImMessageBody imMessageBodyEntity = extractMessageBody(messageContent);
        // 2. 封装存储DTO，包含消息内容和消息体
        DoStoreP2PMessageDto dto = new DoStoreP2PMessageDto();
        dto.setMessageContent(messageContent);
        dto.setMessageBody(imMessageBodyEntity);
        // 3. 将消息唯一标识设置到原消息中，便于后续追踪
        messageContent.setMessageKey(imMessageBodyEntity.getMessageKey());
        // 4. 发送消息到RabbitMQ，触发异步存储（解耦主流程，提高响应速度） 就是发送到store应用服务模块,消息接口,都不存在直接调用
        // 消息直接进行绑定和订阅  StoreP2PMessageReceiver
        rabbitTemplate.convertAndSend(Constants.RabbitConstants.StoreP2PMessage,"",
                JSONObject.toJSONString(dto));
    }

    /**
     * 提取消息体实体
     * 将消息内容转换为可持久化的消息体对象，生成唯一messageKey
     */
    public ImMessageBody extractMessageBody(MessageContent messageContent){
        ImMessageBody messageBody = new ImMessageBody();
        messageBody.setAppId(messageContent.getAppId()); // 应用ID
        messageBody.setMessageKey(snowflakeIdWorker.nextId()); // 消息唯一标识（分布式ID）
        messageBody.setCreateTime(System.currentTimeMillis()); // 创建时间
        messageBody.setSecurityKey(""); // 安全密钥（预留）
        messageBody.setExtra(messageContent.getExtra()); // 额外信息
        messageBody.setDelFlag(DelFlagEnum.NORMAL.getCode()); // 删除标识（默认正常）
        messageBody.setMessageTime(messageContent.getMessageTime()); // 消息发送时间
        messageBody.setMessageBody(messageContent.getMessageBody()); // 消息内容
        return messageBody;
    }

    /**
     * 转换为单聊消息历史实体列表
     * 单聊消息需要为发送方和接收方各存储一条记录（便于查询各自的消息历史）写扩散的原理
     */
    public List<ImMessageHistoryEntity> extractToP2PMessageHistory(MessageContent messageContent,
                                                                   ImMessageBodyEntity imMessageBodyEntity){
        List<ImMessageHistoryEntity> list = new ArrayList<>();
        // 发送方的消息历史记录
        ImMessageHistoryEntity fromHistory = new ImMessageHistoryEntity();
        BeanUtils.copyProperties(messageContent,fromHistory);
        fromHistory.setOwnerId(messageContent.getFromId()); // 所有者为发送方
        fromHistory.setMessageKey(imMessageBodyEntity.getMessageKey()); // 关联消息体
        fromHistory.setCreateTime(System.currentTimeMillis()); // 记录创建时间

        // 接收方的消息历史记录
        ImMessageHistoryEntity toHistory = new ImMessageHistoryEntity();
        BeanUtils.copyProperties(messageContent,toHistory);
        toHistory.setOwnerId(messageContent.getToId()); // 所有者为接收方
        toHistory.setMessageKey(imMessageBodyEntity.getMessageKey()); // 关联消息体
        toHistory.setCreateTime(System.currentTimeMillis()); // 记录创建时间

        list.add(fromHistory);
        list.add(toHistory);
        return list;
    }

    /**
     * 存储群聊消息（异步方式）
     * 逻辑类似单聊，生成消息体后发送到群聊消息存储队列
     */
    @Transactional
    public void storeGroupMessage(GroupChatMessageContent messageContent){
        ImMessageBody imMessageBody = extractMessageBody(messageContent);
        DoStoreGroupMessageDto dto = new DoStoreGroupMessageDto();
        dto.setMessageBody(imMessageBody);
        dto.setGroupChatMessageContent(messageContent);
        // 发送到群聊消息存储队列
        rabbitTemplate.convertAndSend(Constants.RabbitConstants.StoreGroupMessage,
                "",
                JSONObject.toJSONString(dto));
        messageContent.setMessageKey(imMessageBody.getMessageKey());
    }

    /**
     * 转换为群聊消息历史实体
     * 群聊消息只需存储一条记录，关联群ID和发送方
     */
    private ImGroupMessageHistoryEntity extractToGroupMessageHistory(GroupChatMessageContent
                                                                             messageContent ,ImMessageBodyEntity messageBodyEntity){
        ImGroupMessageHistoryEntity result = new ImGroupMessageHistoryEntity();
        BeanUtils.copyProperties(messageContent,result);
        result.setGroupId(messageContent.getGroupId()); // 群ID
        result.setMessageKey(messageBodyEntity.getMessageKey()); // 关联消息体
        result.setCreateTime(System.currentTimeMillis()); // 记录创建时间
        return result;
    }

    /**
     * 缓存消息到Redis（按messageId）
     * 用于消息去重和快速查询（未实现具体缓存逻辑）
     */
    public void setMessageFromMessageIdCache(Integer appId,String messageId,Object messageContent){
        // 缓存键格式：appid:cache:message:messageId
        String key =appId + ":" + Constants.RedisConstants.cacheMessage + ":" + messageId;
        // TODO: 实际项目中需添加缓存逻辑，如stringRedisTemplate.opsForValue().set(key, JSONObject.toJSONString(messageContent));
    }

    /**
     * 从Redis缓存获取消息（按messageId）
     * 用于消息去重校验（避免重复处理）
     */
    public <T> T getMessageFromMessageIdCache(Integer appId,
                                              String messageId,Class<T> clazz){
        String key = appId + ":" + Constants.RedisConstants.cacheMessage + ":" + messageId;
        String msg = stringRedisTemplate.opsForValue().get(key);
        if(StringUtils.isBlank(msg)){
            return null;
        }
        return JSONObject.parseObject(msg, clazz); // 反序列化为指定类型
    }

    /**
     * 存储单聊离线消息到Redis
     * 当接收方离线时，将消息存入ZSet（按messageKey排序，便于同步）
     */
    public void storeOfflineMessage(OfflineMessageContent offlineMessage){

        // 发送方的离线消息队列键
        String fromKey = offlineMessage.getAppId() + ":" + Constants.RedisConstants.OfflineMessage + ":" + offlineMessage.getFromId();
        // 接收方的离线消息队列键
        String toKey = offlineMessage.getAppId() + ":" + Constants.RedisConstants.OfflineMessage + ":" + offlineMessage.getToId();

        ZSetOperations<String, String> operations = stringRedisTemplate.opsForZSet();
        // 若队列长度超过限制，移除最早的消息（保留最新的N条）
        if(operations.zCard(fromKey) > appConfig.getOfflineMessageCount()){
            operations.removeRange(fromKey,0,0); // 移除排名最小的元素
        }
        // 设置会话ID（用于区分不同会话的离线消息）
        offlineMessage.setConversationId(conversationService.convertConversationId(
                ConversationTypeEnum.P2P.getCode(),offlineMessage.getFromId(),offlineMessage.getToId()
        ));
        // 存入ZSet，以messageKey为分值（保证有序性）
        operations.add(fromKey,JSONObject.toJSONString(offlineMessage),
                offlineMessage.getMessageKey());

        // 处理接收方的离线消息队列（逻辑同上）
        if(operations.zCard(toKey) > appConfig.getOfflineMessageCount()){
            operations.removeRange(toKey,0,0);
        }
        offlineMessage.setConversationId(conversationService.convertConversationId(
                ConversationTypeEnum.P2P.getCode(),offlineMessage.getToId(),offlineMessage.getFromId()
        ));
        operations.add(toKey,JSONObject.toJSONString(offlineMessage),
                offlineMessage.getMessageKey());
    }


    /**
     * 存储群聊离线消息到Redis
     * 为群内每个成员（除发送方）存储离线消息
     */
    public void storeGroupOfflineMessage(OfflineMessageContent offlineMessage
            ,List<String> memberIds){

        ZSetOperations<String, String> operations = stringRedisTemplate.opsForZSet();
        offlineMessage.setConversationType(ConversationTypeEnum.GROUP.getCode()); // 标记为群聊消息

        for (String memberId : memberIds) {
            // 每个群成员的离线消息队列键
            String toKey = offlineMessage.getAppId() + ":" +
                    Constants.RedisConstants.OfflineMessage + ":" +
                    memberId;
            // 设置会话ID（群聊会话ID格式）
            offlineMessage.setConversationId(conversationService.convertConversationId(
                    ConversationTypeEnum.GROUP.getCode(),memberId,offlineMessage.getToId()
            ));
            // 超过限制时移除最早消息
            if(operations.zCard(toKey) > appConfig.getOfflineMessageCount()){
                operations.removeRange(toKey,0,0);
            }
            // 存入ZSet
            operations.add(toKey,JSONObject.toJSONString(offlineMessage),
                    offlineMessage.getMessageKey());
        }
    }
}