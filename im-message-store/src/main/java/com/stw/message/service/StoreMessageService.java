package com.stw.message.service;

import com.stw.im.common.annotation.AutoFill;
import com.stw.im.common.model.message.GroupChatMessageContent;
import com.stw.im.common.model.message.MessageContent;
import com.stw.message.dao.ImGroupMessageHistoryEntity;
import com.stw.message.dao.ImMessageBodyEntity;
import com.stw.message.dao.ImMessageHistoryEntity;
import com.stw.message.dao.mapper.ImGroupMessageHistoryMapper;
import com.stw.message.dao.mapper.ImMessageBodyMapper;
import com.stw.message.dao.mapper.ImMessageHistoryMapper;
import com.stw.message.model.DoStoreGroupMessageDto;
import com.stw.message.model.DoStoreP2PMessageDto;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息存储服务类
 * 负责处理单聊、群聊消息的持久化存储逻辑，包括消息体和消息历史记录的存储
 * 采用写扩散机制优化消息读取性能，保证数据一致性
 */
@Service
public class StoreMessageService {

    @Autowired
    private ImMessageHistoryMapper imMessageHistoryMapper; // 单聊消息历史Mapper

    @Autowired
    private ImMessageBodyMapper imMessageBodyMapper; // 消息体Mapper

    @Autowired
    private ImGroupMessageHistoryMapper imGroupMessageHistoryMapper; // 群聊消息历史Mapper


    /**
     * 处理单聊消息的持久化存储
     * 采用事务保证消息体和消息历史的原子性存储
     * @param dto 单聊消息存储DTO，包含消息内容和消息体
     */
    @Transactional(rollbackFor = Exception.class)
    @AutoFill(AutoFill.Operation.INSERT) // 新增操作：填充createTime、appId等
    public void doStoreP2PMessage(DoStoreP2PMessageDto dto) {
        // 1. 存储消息体（此时messageBody的公共字段已被AOP填充）
        imMessageBodyMapper.insert(dto.getImMessageBodyEntity());

        // 2. 生成并存储消息历史（同样需要填充公共字段）
        List<ImMessageHistoryEntity> historyList = extractToP2PMessageHistory(
                dto.getMessageContent(), dto.getImMessageBodyEntity()
        );
        imMessageHistoryMapper.insertBatchSomeColumn(historyList);
    }


    /**
     * 生成单聊消息的双向历史记录
     * 实现写扩散机制：一条消息同时写入发送方和接收方的历史记录中
     * @param messageContent 单聊消息内容（包含发送方、接收方等元信息）
     * @param messageBodyEntity 消息体实体（包含消息实际内容）
     * @return 包含发送方和接收方记录的消息历史列表
     */
    public List<ImMessageHistoryEntity> extractToP2PMessageHistory(
            MessageContent messageContent,
            ImMessageBodyEntity messageBodyEntity) {

        List<ImMessageHistoryEntity> historyList = new ArrayList<>(2); // 固定容量为2，优化性能

        // 1. 生成发送方的消息历史记录
        ImMessageHistoryEntity fromHistory = new ImMessageHistoryEntity();
        // 复制消息基本信息（appId、fromId、toId、messageTime等）
        BeanUtils.copyProperties(messageContent, fromHistory);
        fromHistory.setOwnerId(messageContent.getFromId()); // 归属者为发送方
        fromHistory.setMessageKey(messageBodyEntity.getMessageKey()); // 关联消息体
        fromHistory.setCreateTime(System.currentTimeMillis()); // 存储时间戳
        fromHistory.setSequence(messageContent.getMessageSequence()); // 消息序列号（用于排序）

        // 2. 生成接收方的消息历史记录
        ImMessageHistoryEntity toHistory = new ImMessageHistoryEntity();
        BeanUtils.copyProperties(messageContent, toHistory);
        toHistory.setOwnerId(messageContent.getToId()); // 归属者为接收方
        toHistory.setMessageKey(messageBodyEntity.getMessageKey()); // 关联同一消息体
        toHistory.setCreateTime(System.currentTimeMillis());
        toHistory.setSequence(messageContent.getMessageSequence());

        historyList.add(fromHistory);
        historyList.add(toHistory);
        return historyList;
    }

    /**
     * 处理群聊消息的持久化存储
     * 群聊采用写扩散的变种：消息体和群聊历史仅存储一次，成员读取时通过群ID关联
     * @param doStoreGroupMessageDto 群聊消息存储DTO，包含群聊消息内容和消息体
     */
    @Transactional(rollbackFor = Exception.class)
    public void doStoreGroupMessage(DoStoreGroupMessageDto doStoreGroupMessageDto) {
        // 1. 存储消息体（与单聊共用同一张表，全局唯一）
        imMessageBodyMapper.insert(doStoreGroupMessageDto.getImMessageBodyEntity());

        // 2. 生成群聊消息历史记录（仅存储一条，关联群ID）
        ImGroupMessageHistoryEntity groupHistory = extractToGroupMessageHistory(
                doStoreGroupMessageDto.getGroupChatMessageContent(),
                doStoreGroupMessageDto.getImMessageBodyEntity()
        );

        // 3. 插入群聊消息历史记录
        imGroupMessageHistoryMapper.insert(groupHistory);
    }

    /**
     * 生成群聊消息的历史记录
     * 群聊消息历史关联群ID，而非单个成员，减少存储冗余
     * @param messageContent 群聊消息内容（包含群ID、发送方等信息）
     * @param messageBodyEntity 消息体实体
     * @return 群聊消息历史实体
     */
    private ImGroupMessageHistoryEntity extractToGroupMessageHistory(
            GroupChatMessageContent messageContent,
            ImMessageBodyEntity messageBodyEntity) {

        ImGroupMessageHistoryEntity groupHistory = new ImGroupMessageHistoryEntity();
        // 复制群聊消息基本信息（appId、fromId、messageTime等）
        BeanUtils.copyProperties(messageContent, groupHistory);
        groupHistory.setGroupId(messageContent.getGroupId()); // 关联群ID
        groupHistory.setMessageKey(messageBodyEntity.getMessageKey()); // 关联消息体
        groupHistory.setCreateTime(System.currentTimeMillis()); // 存储时间戳
        return groupHistory;
    }
}