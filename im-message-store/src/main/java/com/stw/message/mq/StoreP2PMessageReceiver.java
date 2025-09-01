package com.stw.message.mq;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.model.message.MessageContent;
import com.stw.im.common.utils.UserContextHolder;
import com.stw.message.dao.ImMessageBodyEntity;
import com.stw.message.model.DoStoreP2PMessageDto;
import com.stw.message.service.StoreMessageService;
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
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * @description: 单聊消息存储的RabbitMQ消息消费者
 * 负责监听单聊消息存储队列，接收消息并调用服务层完成消息持久化
 * @author: stw
 * @version: 1.0
 */
@Service
public class StoreP2PMessageReceiver {
    // 日志记录器，用于记录消费过程中的关键信息和异常
    private static Logger logger = LoggerFactory.getLogger(StoreP2PMessageReceiver.class);

    // 注入消息存储服务，用于处理单聊消息的持久化逻辑
    @Autowired
    StoreMessageService storeMessageService;

    /**
     * RabbitMQ监听注解，配置队列与交换机的绑定关系
     * bindings：队列绑定配置
     * @Queue：定义队列，value为队列名称（从常量类获取），durable=true表示队列持久化
     * @Exchange：定义交换机，value为交换机名称（与队列同名），durable=true表示交换机持久化
     * concurrency：消费者并发数，这里设置为1表示单线程处理
     */
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = Constants.RabbitConstants.StoreP2PMessage,durable = "true"),
                    exchange = @Exchange(value = Constants.RabbitConstants.StoreP2PMessage,durable = "true")
            ),concurrency = "1"
    )
    /**
     * 消息消费处理方法
     * @param message：RabbitMQ消息对象，包含消息体等信息
     * @param headers：消息头信息，包含投递标签等元数据
     * @param channel：RabbitMQ通信通道，用于消息确认
     * @throws Exception：处理过程中可能抛出的异常
     */
    public void onChatMessage(@Payload Message message,
                              @Headers Map<String,Object> headers,
                              Channel channel) throws Exception {
        // 将消息体字节数组转换为UTF-8字符串
        String msg = new String(message.getBody(),"utf-8");
        // 记录接收到的消息内容，便于调试和问题排查
        logger.info("CHAT MSG FORM QUEUE ::: {}", msg);
        // 从消息头中获取投递标签（deliveryTag），用于消息确认
        Long deliveryTag = (Long) headers.get(AmqpHeaders.DELIVERY_TAG);
        try {
            // 将JSON字符串解析为JSONObject
            JSONObject jsonObject = JSON.parseObject(msg);
            // 将JSON对象转换为单聊消息存储DTO对象
            DoStoreP2PMessageDto doStoreP2PMessageDto = jsonObject.toJavaObject(DoStoreP2PMessageDto.class);
            // 从JSON中提取消息体实体并设置到DTO中
            ImMessageBodyEntity messageBody = jsonObject.getObject("messageBody", ImMessageBodyEntity.class);
            doStoreP2PMessageDto.setImMessageBodyEntity(messageBody);

            // 从消息内容中提取上下文信息并设置
            MessageContent content = doStoreP2PMessageDto.getMessageContent();
            UserContextHolder.setCurrentAppId(content.getAppId()); // 设置appId
            UserContextHolder.setOperatorId(content.getFromId()); // 设置操作人（发送者ID）


            // 调用服务层方法完成单聊消息的持久化（存储到数据库）
            storeMessageService.doStoreP2PMessage(doStoreP2PMessageDto);

            // 消息处理成功，手动确认消息（basicAck）
            // deliveryTag：当前消息的投递标签
            // false：表示不批量确认
            channel.basicAck(deliveryTag, false);
        }catch (Exception e){
            // 记录异常信息，包括异常消息、堆栈和失败的消息内容
            logger.error("处理消息出现异常：{}", e.getMessage());
            logger.error("RMQ_CHAT_TRAN_ERROR", e);
            logger.error("NACK_MSG:{}", msg);

            // 消息处理失败，拒绝消息（basicNack）
            // 第一个false：不批量拒绝
            // 第二个false：消息不重回队列（避免死循环）
            channel.basicNack(deliveryTag, false, false);
        }

    }
}