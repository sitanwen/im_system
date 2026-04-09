package com.stw.im.tcp.publish;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stw.im.codec.proto.Message;
import com.stw.im.codec.proto.MessageHeader;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.command.CommandType;
import com.stw.im.tcp.utils.MqFactory;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;

/**
 * @description: 生产者1号：RabbitMQ消息生产者，负责将TCP层接收的消息按类型路由到不同业务队列
 * 核心功能：根据消息指令（command）的类型，将消息分发到对应的RabbitMQ交换机/队列，
 * 支撑单聊、群聊、好友关系、用户状态等业务模块的解耦通信
 * @author: stw
 * @version: 1.0
 */
@Slf4j
public class MqMessageProducer {

    /**
     * 发送消息到RabbitMQ（适用于包含完整Message对象的场景）
     * 主要用于TCP层接收客户端原始消息后转发到业务服务
     * @param message 消息对象（包含消息头和消息体）
     * @param command 消息指令（用于确定消息类型和路由目标）
     */
    public static void sendMessage(Message message, Integer command) {
        Channel channel = null;
        try {
            // 1. 解析指令类型：取指令第一位字符判断业务类型（如消息、群组、好友、用户）
            String commandStr = command.toString();
            String commandPrefix = commandStr.substring(0, 1);
            CommandType commandType = CommandType.getCommandType(commandPrefix);

            // 2. 根据业务类型确定目标队列名称
            String channelName = getChannelNameByCommandType(commandType);
            if (channelName.isEmpty()) {
                log.warn("未找到匹配的队列，指令: {}", command);
                return;
            }

            // 3. 获取RabbitMQ通道（由MqFactory管理连接池）
            channel = MqFactory.getChannel(channelName);

            // 4. 构建消息体：将业务数据转换为JSON，并附加必要的元信息
            JSONObject messageJson = (JSONObject) JSON.toJSON(message.getMessagePack());
            addBaseMessageInfo(messageJson, command, message.getMessageHeader());

            // 5. 发送消息到指定队列
            channel.basicPublish(
                    channelName,  // 交换机名称（与队列同名，简化绑定）
                    "",           // 路由键（此处无需特殊路由，使用默认）
                    null,         // 消息属性（默认）
                    messageJson.toJSONString().getBytes()  // 消息体字节数组
            );

        } catch (Exception e) {
            log.error("发送消息异常，指令: {}，异常信息: {}", command, e.getMessage());
        } finally {
            // 注意：此处不关闭channel，由MqFactory统一管理通道生命周期
        }
    }

    /**
     * 发送消息到RabbitMQ（适用于自定义消息体+消息头的场景）
     * 主要用于业务事件通知（如用户状态变更、好友关系变动等）
     * @param message 自定义业务消息体
     * @param header 消息头（包含appId、客户端类型等元信息）
     * @param command 消息指令（用于确定消息类型和路由目标）
     */
    public static void sendMessage(Object message, MessageHeader header, Integer command) {
        Channel channel = null;
        try {
            // 1. 解析指令类型：同sendMessage(Message, Integer)逻辑
            String commandStr = command.toString();
            String commandPrefix = commandStr.substring(0, 1);
            CommandType commandType = CommandType.getCommandType(commandPrefix);

            // 2. 确定目标队列名称
            String channelName = getChannelNameByCommandType(commandType);
            if (channelName.isEmpty()) {
                log.warn("未找到匹配的队列，指令: {}", command);
                return;
            }

            // 3. 获取RabbitMQ通道
            channel = MqFactory.getChannel(channelName);

            // 4. 构建消息体：转换业务对象为JSON，附加元信息
            JSONObject messageJson = (JSONObject) JSON.toJSON(message);
            addBaseMessageInfo(messageJson, command, header);

            // 5. 发送消息
            channel.basicPublish(
                    channelName,
                    "",
                    null,
                    messageJson.toJSONString().getBytes()
            );

        } catch (Exception e) {
            log.error("发送消息异常，指令: {}，异常信息: {}", command, e.getMessage());
        }
    }

    /**
     * 根据指令类型获取对应的RabbitMQ队列名称
     * @param commandType 业务指令类型（消息、群组、好友、用户）
     * @return 队列名称，若未匹配则返回空字符串
     */
    private static String getChannelNameByCommandType(CommandType commandType) {
        if (commandType == null) {
            return "";
        }
        switch (commandType) {
            case MESSAGE:
                return Constants.RabbitConstants.Im2MessageService;  // 单聊消息队列
            case GROUP:
                return Constants.RabbitConstants.Im2GroupService;    // 群聊消息队列
            case FRIEND:
                return Constants.RabbitConstants.Im2FriendshipService;  // 好友关系队列
            case USER:
                return Constants.RabbitConstants.Im2UserService;     // 用户状态队列
            default:
                return "";
        }
    }

    /**
     * 向消息JSON中添加基础元信息（指令、客户端类型、设备标识、应用ID）
     * 这些信息是所有业务队列消费时都需要的通用信息
     * @param messageJson 消息JSON对象
     * @param command 消息指令
     * @param header 消息头（包含客户端元信息）
     */
    private static void addBaseMessageInfo(JSONObject messageJson, Integer command, MessageHeader header) {
        messageJson.put("command", command);           // 消息指令（用于消费端识别消息类型）
        messageJson.put("clientType", header.getClientType());  // 客户端类型（如Android、iOS、Web）
        messageJson.put("imei", header.getImei());     // 设备唯一标识（用于多端登录管理）
        messageJson.put("appId", header.getAppId());   // 应用ID（支持多应用隔离）
    }
}