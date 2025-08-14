package com.stw.im.service.message.mq;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.command.MessageCommand;
import com.stw.im.common.model.message.*;
import com.stw.im.service.message.service.MessageSyncService;
import com.stw.im.service.message.service.P2PMessageService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @description: 聊天消息操作接收器
 * 负责消费来自Im2MessageService队列的消息，处理单聊消息、消息接收确认、消息已读、消息撤回等事件
 * @author: stw
 * @version: 1.0
 */
@Component
public class ChatOperateReceiver {

    private static final Logger logger = LoggerFactory.getLogger(ChatOperateReceiver.class);

    @Autowired
    private P2PMessageService p2PMessageService; // 单聊消息处理服务

    @Autowired
    private MessageSyncService messageSyncService; // 消息同步服务（处理确认、已读、撤回等）


    /**
     * 监听Im2MessageService队列的消息
     * 队列与交换机绑定，采用持久化配置，并发数为1
     */
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = Constants.RabbitConstants.Im2MessageService, durable = "true"),
                    exchange = @Exchange(value = Constants.RabbitConstants.Im2MessageService, durable = "true")
            ), concurrency = "1"
    )
    public void onChatMessage(
            @Payload Message message,
            @Headers Map<String, Object> headers,
            Channel channel) throws Exception {

        String msg = new String(message.getBody(), "utf-8");
        logger.info("从队列接收聊天消息 ::: {}", msg);

        // 获取消息投递标签，用于消息确认
        Long deliveryTag = (Long) headers.get(AmqpHeaders.DELIVERY_TAG);

        try {
            JSONObject jsonObject = JSON.parseObject(msg);
            Integer command = jsonObject.getInteger("command"); // 消息指令，用于区分消息类型

            // 根据不同指令处理消息（使用==比较基本数据类型，避免equals调用导致的异常）
            if (command != null && command == MessageCommand.MSG_P2P.getCommand()) {
                // 处理单聊消息
                MessageContent messageContent = jsonObject.toJavaObject(MessageContent.class);
                p2PMessageService.process(messageContent);

            } else if (command != null && command == MessageCommand.MSG_RECIVE_ACK.getCommand()) {
                // 处理消息接收确认
                MessageReciveAckContent ackContent = jsonObject.toJavaObject(MessageReciveAckContent.class);
                messageSyncService.receiveMark(ackContent);

            } else if (command != null && command == MessageCommand.MSG_READED.getCommand()) {
                // 处理消息已读通知
                MessageReadedContent readedContent = jsonObject.toJavaObject(MessageReadedContent.class);
                messageSyncService.readMark(readedContent);

            } else if (command != null && command == MessageCommand.MSG_RECALL.getCommand()) {
                // 处理消息撤回
                RecallMessageContent recallContent = JSON.parseObject(msg, new TypeReference<RecallMessageContent>() {});
                messageSyncService.recallMessage(recallContent);
            }

            // 消息处理成功，手动确认
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            logger.error("消息处理异常: {}", e.getMessage());
            logger.error("异常堆栈:", e);
            logger.error("处理失败的消息: {}", msg);

            // 消息处理失败，拒绝消息（不批量拒绝，不重回队列）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}