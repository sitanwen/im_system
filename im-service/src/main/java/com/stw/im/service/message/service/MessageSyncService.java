package com.stw.im.service.message.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.stw.im.codec.pack.message.MessageReadedPack;
import com.stw.im.codec.pack.message.RecallMessageNotifyPack;
import com.stw.im.common.ResponseVO;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.ConversationTypeEnum;
import com.stw.im.common.enums.DelFlagEnum;
import com.stw.im.common.enums.MessageErrorCode;
import com.stw.im.common.enums.command.Command;
import com.stw.im.common.enums.command.GroupEventCommand;
import com.stw.im.common.enums.command.MessageCommand;
import com.stw.im.common.model.ClientInfo;
import com.stw.im.common.model.SyncReq;
import com.stw.im.common.model.SyncResp;
import com.stw.im.common.model.message.MessageReadedContent;
import com.stw.im.common.model.message.MessageReciveAckContent;
import com.stw.im.common.model.message.OfflineMessageContent;
import com.stw.im.common.model.message.RecallMessageContent;
import com.stw.im.service.conversation.service.ConversationService;
import com.stw.im.service.group.service.ImGroupMemberService;
import com.stw.im.service.message.dao.ImMessageBodyEntity;
import com.stw.im.service.message.dao.mapper.ImMessageBodyMapper;
import com.stw.im.service.seq.RedisSeq;
import com.stw.im.service.utils.ConversationIdGenerate;
import com.stw.im.service.utils.GroupMessageProducer;
import com.stw.im.service.utils.MessageProducer;
import com.stw.im.service.utils.SnowflakeIdWorker;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 消息同步服务类
 * 负责处理消息相关的同步操作，包括：
 * 1. 消息接收确认
 * 2. 消息已读标记及通知
 * 3. 离线消息同步
 * 4. 消息撤回处理
 */
@Service
public class MessageSyncService {

    @Autowired
    private MessageProducer messageProducer;  // 单聊消息生产者

    @Autowired
    private ConversationService conversationService;  // 会话服务，用于更新会话状态

    @Autowired
    private RedisTemplate redisTemplate;  // Redis操作模板，用于处理离线消息

    @Autowired
    private ImMessageBodyMapper imMessageBodyMapper;  // 消息体数据库操作

    @Autowired
    private RedisSeq redisSeq;  // Redis序列生成器，用于生成消息序号

    @Autowired
    private SnowflakeIdWorker snowflakeIdWorker;  // 雪花算法生成器，用于生成唯一ID

    @Autowired
    private ImGroupMemberService imGroupMemberService;  // 群成员服务，用于获取群成员信息

    @Autowired
    private GroupMessageProducer groupMessageProducer;  // 群聊消息生产者


    /**
     * 处理消息接收确认
     * 向消息发送方发送接收确认通知
     * @param messageReciveAckContent 消息接收确认内容，包含发送方、接收方等信息
     */
    public void receiveMark(MessageReciveAckContent messageReciveAckContent) {
        // 发送接收确认指令给消息接收方
        messageProducer.sendToUser(
                messageReciveAckContent.getToId(),
                MessageCommand.MSG_RECIVE_ACK,
                messageReciveAckContent,
                messageReciveAckContent.getAppId()
        );
    }

    /**
     * 处理单聊消息已读标记
     * 1. 更新会话的已读序列
     * 2. 通知当前用户的其他在线端已读状态
     * 3. 向消息发送方发送已读回执
     * @param messageContent 消息已读内容，包含会话类型、消息序号等信息
     */
    public void readMark(MessageReadedContent messageContent) {
        // 更新会话的已读状态
        conversationService.messageMarkRead(messageContent);

        // 转换为已读通知包
        MessageReadedPack messageReadedPack = new MessageReadedPack();
        BeanUtils.copyProperties(messageContent, messageReadedPack);

        // 同步已读状态到当前用户的其他端
        syncToSender(messageReadedPack, messageContent, MessageCommand.MSG_READED_NOTIFY);

        // 向消息发送方发送已读回执
        messageProducer.sendToUser(
                messageContent.getToId(),
                MessageCommand.MSG_READED_RECEIPT,
                messageReadedPack,
                messageContent.getAppId()
        );
    }

    /**
     * 同步消息状态到发送方的其他客户端
     * @param pack 要发送的数据包
     * @param content 消息内容（包含客户端信息）
     * @param command 指令类型（单聊/群聊）
     */
    private void syncToSender(MessageReadedPack pack, MessageReadedContent content, Command command) {
        // 发送给当前用户的其他在线端（排除当前操作的客户端）
        messageProducer.sendToUserExceptClient(
                pack.getFromId(),
                command,
                pack,
                content
        );
    }

    /**
     * 处理群聊消息已读标记
     * 1. 更新群会话的已读序列
     * 2. 通知当前用户的其他在线端已读状态
     * 3. 向群主/消息发送方发送已读回执（非自己发送的消息时）
     * @param messageReaded 群聊消息已读内容
     */
    public void groupReadMark(MessageReadedContent messageReaded) {
        // 更新群会话的已读状态
        conversationService.messageMarkRead(messageReaded);

        // 转换为已读通知包
        MessageReadedPack messageReadedPack = new MessageReadedPack();
        BeanUtils.copyProperties(messageReaded, messageReadedPack);

        // 同步已读状态到当前用户的其他端（使用群聊指令）
        syncToSender(messageReadedPack, messageReaded, GroupEventCommand.MSG_GROUP_READED_NOTIFY);

        // 非自己发送的消息，向消息发送方发送已读回执
        if (!messageReaded.getFromId().equals(messageReaded.getToId())) {
            messageProducer.sendToUser(
                    messageReadedPack.getToId(),
                    GroupEventCommand.MSG_GROUP_READED_RECEIPT,
                    messageReaded,
                    messageReaded.getAppId()
            );
        }
    }

    /**
     * 同步离线消息
     * 从Redis中查询用户的离线消息，支持分页获取
     * @param req 同步请求参数，包含用户ID、最后同步的序号、最大条数等
     * @return 离线消息同步结果，包含消息列表、最大序号、是否同步完成
     */
    public ResponseVO syncOfflineMessage(SyncReq req) {
        SyncResp<OfflineMessageContent> resp = new SyncResp<>();
        // 构建Redis中离线消息的key（格式：appId:OfflineMessage:userId）
        String key = req.getAppId() + ":" + Constants.RedisConstants.OfflineMessage + ":" + req.getOperater();

        ZSetOperations zSetOperations = redisTemplate.opsForZSet();
        // 获取离线消息中的最大序号（最新消息的score）
        Long maxSeq = 0L;
        Set<?> latestMsgSet = zSetOperations.reverseRangeWithScores(key, 0, 0);
        if (!CollectionUtils.isEmpty(latestMsgSet)) {
            DefaultTypedTuple<?> latestMsg = (DefaultTypedTuple<?>) new ArrayList<>(latestMsgSet).get(0);
            maxSeq = latestMsg.getScore().longValue();
        }
        resp.setMaxSequence(maxSeq);

        // 查询指定范围的离线消息（lastSequence ~ maxSeq，最多maxLimit条）
        Set<ZSetOperations.TypedTuple<String>> querySet = zSetOperations.rangeByScoreWithScores(
                key,
                req.getLastSequence(),
                maxSeq,
                0,
                req.getMaxLimit()
        );

        // 解析消息并封装结果
        List<OfflineMessageContent> respList = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> typedTuple : querySet) {
            String msgJson = typedTuple.getValue();
            OfflineMessageContent offlineMsg = JSONObject.parseObject(msgJson, OfflineMessageContent.class);
            respList.add(offlineMsg);
        }
        resp.setDataList(respList);

        // 判断是否已同步完成（最后一条消息的序号是否达到最大序号）
        if (!CollectionUtils.isEmpty(respList)) {
            OfflineMessageContent lastMsg = respList.get(respList.size() - 1);
            resp.setCompleted(maxSeq <= lastMsg.getMessageKey());
        }

        return ResponseVO.successResponse(resp);
    }

    /**
     * 处理消息撤回
     * 1. 校验撤回时效（2分钟内可撤回）
     * 2. 校验消息存在性及状态（未被撤回）
     * 3. 更新消息体为已删除状态
     * 4. 处理单聊/群聊的离线消息标记
     * 5. 发送撤回通知给相关用户
     * @param content 消息撤回内容，包含消息ID、会话类型等信息
     */
    public void recallMessage(RecallMessageContent content) {
        Long messageTime = content.getMessageTime();
        Long now = System.currentTimeMillis();
        RecallMessageNotifyPack notifyPack = new RecallMessageNotifyPack();
        BeanUtils.copyProperties(content, notifyPack);

        // 校验：消息发送超过2分钟（120000ms）不可撤回
        if (now - messageTime > 120000L) {
            recallAck(notifyPack, ResponseVO.errorResponse(MessageErrorCode.MESSAGE_RECALL_TIME_OUT), content);
            return;
        }

        // 查询消息体是否存在
        QueryWrapper<ImMessageBodyEntity> query = new QueryWrapper<>();
        query.eq("app_id", content.getAppId())
                .eq("message_key", content.getMessageKey());
        ImMessageBodyEntity msgBody = imMessageBodyMapper.selectOne(query);

        // 消息体不存在，返回错误
        if (msgBody == null) {
            recallAck(notifyPack, ResponseVO.errorResponse(MessageErrorCode.MESSAGEBODY_IS_NOT_EXIST), content);
            return;
        }

        // 消息已被撤回，返回错误
        if (msgBody.getDelFlag() == DelFlagEnum.DELETE.getCode()) {
            recallAck(notifyPack, ResponseVO.errorResponse(MessageErrorCode.MESSAGE_IS_RECALLED), content);
            return;
        }

        // 更新消息体为已删除状态
        msgBody.setDelFlag(DelFlagEnum.DELETE.getCode());
        imMessageBodyMapper.update(msgBody, query);

        // 处理单聊消息撤回
        if (content.getConversationType() == ConversationTypeEnum.P2P.getCode()) {
            handleP2PRecall(content, msgBody, notifyPack);
        }
        // 处理群聊消息撤回
        else {
            handleGroupRecall(content, msgBody, notifyPack);
        }
    }

    /**
     * 处理单聊消息撤回的具体逻辑
     * @param content 撤回内容
     * @param msgBody 消息体
     * @param notifyPack 撤回通知包
     */
    private void handleP2PRecall(RecallMessageContent content, ImMessageBodyEntity msgBody, RecallMessageNotifyPack notifyPack) {
        // 构建发送方和接收方的离线消息Redis键
        String fromKey = content.getAppId() + ":" + Constants.RedisConstants.OfflineMessage + ":" + content.getFromId();
        String toKey = content.getAppId() + ":" + Constants.RedisConstants.OfflineMessage + ":" + content.getToId();

        // 构建离线消息撤回标记
        OfflineMessageContent offlineMsg = new OfflineMessageContent();
        BeanUtils.copyProperties(content, offlineMsg);
        offlineMsg.setDelFlag(DelFlagEnum.DELETE.getCode());
        offlineMsg.setMessageKey(content.getMessageKey());
        offlineMsg.setConversationType(ConversationTypeEnum.P2P.getCode());
        offlineMsg.setConversationId(conversationService.convertConversationId(
                ConversationTypeEnum.P2P.getCode(),
                content.getFromId(),
                content.getToId()
        ));
        offlineMsg.setMessageBody(msgBody.getMessageBody());

        // 生成消息序号并设置
        long seq = redisSeq.doGetSeq(
                content.getAppId() + ":" + Constants.SeqConstants.Message + ":" +
                        ConversationIdGenerate.generateP2PId(content.getFromId(), content.getToId())
        );
        offlineMsg.setMessageSequence(seq);

        // 生成消息唯一标识并写入Redis（标记为已撤回）
        long messageKey = SnowflakeIdWorker.nextId();
        redisTemplate.opsForZSet().add(fromKey, JSONObject.toJSONString(offlineMsg), messageKey);
        redisTemplate.opsForZSet().add(toKey, JSONObject.toJSONString(offlineMsg), messageKey);

        // 发送撤回成功ACK
        recallAck(notifyPack, ResponseVO.successResponse(), content);
        // 通知发送方的其他在线端
        messageProducer.sendToUserExceptClient(
                content.getFromId(),
                MessageCommand.MSG_RECALL_NOTIFY,
                notifyPack,
                content
        );
        // 通知接收方
        messageProducer.sendToUser(
                content.getToId(),
                MessageCommand.MSG_RECALL_NOTIFY,
                notifyPack,
                content.getAppId()
        );
    }

    /**
     * 处理群聊消息撤回的具体逻辑
     * @param content 撤回内容
     * @param msgBody 消息体
     * @param notifyPack 撤回通知包
     */
    private void handleGroupRecall(RecallMessageContent content, ImMessageBodyEntity msgBody, RecallMessageNotifyPack notifyPack) {
        // 获取群内所有成员ID
        List<String> groupMemberIds = imGroupMemberService.getGroupMemberId(content.getToId(), content.getAppId());

        // 生成消息序号
        long seq = redisSeq.doGetSeq(
                content.getAppId() + ":" + Constants.SeqConstants.Message + ":" +
                        ConversationIdGenerate.generateP2PId(content.getFromId(), content.getToId())
        );

        // 发送撤回成功ACK
        recallAck(notifyPack, ResponseVO.successResponse(), content);
        // 通知发送方的其他在线端
        messageProducer.sendToUserExceptClient(
                content.getFromId(),
                MessageCommand.MSG_RECALL_NOTIFY,
                notifyPack,
                content
        );

        // 向所有群成员的离线消息队列添加撤回标记，并发送通知
        for (String memberId : groupMemberIds) {
            String toKey = content.getAppId() + ":" + Constants.SeqConstants.Message + ":" + memberId;

            OfflineMessageContent offlineMsg = new OfflineMessageContent();
            offlineMsg.setDelFlag(DelFlagEnum.DELETE.getCode());
            BeanUtils.copyProperties(content, offlineMsg);
            offlineMsg.setConversationType(ConversationTypeEnum.GROUP.getCode());
            offlineMsg.setConversationId(conversationService.convertConversationId(
                    ConversationTypeEnum.GROUP.getCode(),
                    content.getFromId(),
                    content.getToId()
            ));
            offlineMsg.setMessageBody(msgBody.getMessageBody());
            offlineMsg.setMessageSequence(seq);

            // 写入Redis标记撤回
            redisTemplate.opsForZSet().add(toKey, JSONObject.toJSONString(offlineMsg), seq);
            // 发送撤回通知给群成员
            groupMessageProducer.producer(content.getFromId(), MessageCommand.MSG_RECALL_NOTIFY, notifyPack, content);
        }
    }

    /**
     * 发送消息撤回的ACK响应
     * @param recallPack 撤回通知包
     * @param response 响应内容（成功/失败）
     * @param clientInfo 客户端信息
     */
    private void recallAck(RecallMessageNotifyPack recallPack, ResponseVO<Object> response, ClientInfo clientInfo) {
        messageProducer.sendToUser(
                recallPack.getFromId(),
                MessageCommand.MSG_RECALL_ACK,
                response,
                clientInfo
        );
    }

}