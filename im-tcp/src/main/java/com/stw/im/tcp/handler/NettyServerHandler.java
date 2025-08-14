package com.stw.im.tcp.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.stw.im.codec.pack.LoginPack;
import com.stw.im.codec.pack.message.ChatMessageAck;
import com.stw.im.codec.pack.user.LoginAckPack;
import com.stw.im.codec.pack.user.UserStatusChangeNotifyPack;
import com.stw.im.codec.proto.Message;
import com.stw.im.codec.proto.MessagePack;
import com.stw.im.common.ResponseVO;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.ImConnectStatusEnum;
import com.stw.im.common.enums.command.GroupEventCommand;
import com.stw.im.common.enums.command.MessageCommand;
import com.stw.im.common.enums.command.SystemCommand;
import com.stw.im.common.enums.command.UserEventCommand;
import com.stw.im.common.model.UserClientDto;
import com.stw.im.common.model.UserSession;
import com.stw.im.common.model.message.CheckSendMessageReq;
import com.stw.im.tcp.feign.FeignMessageService;
import com.stw.im.tcp.publish.MqMessageProducer;
import com.stw.im.tcp.redis.RedisManager;
import com.stw.im.tcp.utils.SessionSocketHolder;
import feign.Feign;
import feign.Request;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;

/**
 * Netty 服务端核心处理器
 * 负责处理客户端发送的所有消息，包括登录、登出、心跳、消息收发等事件
 * 继承 SimpleChannelInboundHandler<Message>，专注于处理 Message 类型的消息
 */
public class NettyServerHandler extends SimpleChannelInboundHandler<Message> {

    private static final Logger logger = LoggerFactory.getLogger(NettyServerHandler.class);

    /** 当前 broker 服务的唯一标识 */
    private Integer brokerId;

    /** 逻辑服务（如消息校验服务）的请求地址 */
    private String logicUrl;

    /** 用于调用逻辑服务的 Feign 客户端 */
    private FeignMessageService feignMessageService;

    /**
     * 构造方法：初始化处理器
     * @param brokerId 当前 broker 服务ID
     * @param logicUrl 逻辑服务地址
     */
    public NettyServerHandler(Integer brokerId, String logicUrl) {
        this.brokerId = brokerId;
        // 初始化 Feign 客户端，用于调用消息校验等逻辑服务
        feignMessageService = Feign.builder()
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .options(new Request.Options(1000, 3500)) // 连接超时1秒，读取超时3.5秒
                .target(FeignMessageService.class, logicUrl);
    }

    /**
     * 核心方法：处理客户端发送的消息
     * 根据消息中的指令（command）分发到不同的处理逻辑
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        Integer command = msg.getMessageHeader().getCommand();

        // 处理登录事件
        if (command == SystemCommand.LOGIN.getCommand()) {
            handleLogin(ctx, msg);
        }
        // 处理登出事件
        else if (command == SystemCommand.LOGOUT.getCommand()) {
            handleLogout(ctx);
        }
        // 处理心跳事件（客户端发送的PING）
        else if (command == SystemCommand.PING.getCommand()) {
            handlePing(ctx);
        }
        // 处理单聊或群聊消息
        else if (command == MessageCommand.MSG_P2P.getCommand()
                || command == GroupEventCommand.MSG_GROUP.getCommand()) {
            handleChatMessage(ctx, msg, command);
        }
        // 其他指令：直接转发到消息队列
        else {
            MqMessageProducer.sendMessage(msg, command);
        }
    }

    /**
     * 处理登录逻辑
     * 1. 解析登录信息，存储用户会话
     * 2. 更新Redis中的在线状态
     * 3. 广播登录事件（多端登录冲突处理）
     * 4. 发送登录成功响应
     */
    private void handleLogin(ChannelHandlerContext ctx, Message msg) {
        // 解析登录数据包
        LoginPack loginPack = JSON.parseObject(
                JSONObject.toJSONString(msg.getMessagePack()),
                new TypeReference<LoginPack>() {}.getType()
        );
        String userId = loginPack.getUserId();
        Integer appId = msg.getMessageHeader().getAppId();
        Integer clientType = msg.getMessageHeader().getClientType();
        String imei = msg.getMessageHeader().getImei();

        // 存储用户信息到Channel属性（便于后续获取）
        ctx.channel().attr(AttributeKey.valueOf(Constants.UserId)).set(userId);
        ctx.channel().attr(AttributeKey.valueOf(Constants.ClientImei)).set(clientType + ":" + imei);
        ctx.channel().attr(AttributeKey.valueOf(Constants.AppId)).set(appId);
        ctx.channel().attr(AttributeKey.valueOf(Constants.ClientType)).set(clientType);
        ctx.channel().attr(AttributeKey.valueOf(Constants.Imei)).set(imei);

        // 构建用户会话信息
        UserSession userSession = new UserSession();
        userSession.setAppId(appId);
        userSession.setClientType(clientType);
        userSession.setUserId(userId);
        userSession.setConnectState(ImConnectStatusEnum.ONLINE_STATUS.getCode()); // 标记在线
        userSession.setBrokerId(brokerId);
        userSession.setImei(imei);
        try {
            userSession.setBrokerHost(InetAddress.getLocalHost().getHostAddress()); // 当前服务IP
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 存储会话到Redis（用户-客户端映射）
        RedissonClient redissonClient = RedisManager.getRedissonClient();
        RMap<String, String> sessionMap = redissonClient.getMap(
                appId + Constants.RedisConstants.UserSessionConstants + userId
        );
        sessionMap.put(clientType + ":" + imei, JSONObject.toJSONString(userSession));

        // 存储会话到本地缓存（Channel映射，用于实时推送）
        SessionSocketHolder.put(appId, userId, clientType, imei, (NioSocketChannel) ctx.channel());

        // 发布登录事件到Redis频道（用于多端登录冲突处理）
        UserClientDto loginDto = new UserClientDto();
        loginDto.setImei(imei);
        loginDto.setUserId(userId);
        loginDto.setClientType(clientType);
        loginDto.setAppId(appId);
        RTopic loginTopic = redissonClient.getTopic(Constants.RedisConstants.UserLoginChannel);
        loginTopic.publish(JSONObject.toJSONString(loginDto));

        // 发送用户上线通知到消息队列（同步给其他服务）
        UserStatusChangeNotifyPack statusNotify = new UserStatusChangeNotifyPack();
        statusNotify.setAppId(appId);
        statusNotify.setUserId(userId);
        statusNotify.setStatus(ImConnectStatusEnum.ONLINE_STATUS.getCode());
        MqMessageProducer.sendMessage(statusNotify, msg.getMessageHeader(), UserEventCommand.USER_ONLINE_STATUS_CHANGE.getCommand());

        // 向客户端发送登录成功响应
        MessagePack<LoginAckPack> loginAck = new MessagePack<>();
        LoginAckPack ackData = new LoginAckPack();
        ackData.setUserId(userId);
        loginAck.setCommand(SystemCommand.LOGINACK.getCommand());
        loginAck.setData(ackData);
        loginAck.setImei(imei);
        loginAck.setAppId(appId);
        ctx.channel().writeAndFlush(loginAck);
    }

    /**
     * 处理登出逻辑
     * 移除本地缓存和Redis中的会话信息，清理连接
     */
    private void handleLogout(ChannelHandlerContext ctx) {
        SessionSocketHolder.removeUserSession((NioSocketChannel) ctx.channel());
    }

    /**
     * 处理心跳（PING）
     * 更新最后读取时间，用于心跳检测（避免被判定为空闲连接）
     */
    private void handlePing(ChannelHandlerContext ctx) {
        ctx.channel().attr(AttributeKey.valueOf(Constants.ReadTime)).set(System.currentTimeMillis());
    }

    /**
     * 处理单聊/群聊消息
     * 1. 调用逻辑服务校验消息合法性
     * 2. 校验通过：转发消息到消息队列
     * 3. 校验失败：返回错误响应
     */
    private void handleChatMessage(ChannelHandlerContext ctx, Message msg, Integer command) {
        try {
            CheckSendMessageReq checkReq = new CheckSendMessageReq();
            checkReq.setAppId(msg.getMessageHeader().getAppId());
            checkReq.setCommand(command);

            // 解析消息中的发送方和接收方
            JSONObject msgData = JSON.parseObject(JSONObject.toJSONString(msg.getMessagePack()));
            checkReq.setFromId(msgData.getString("fromId"));
            if (command == MessageCommand.MSG_P2P.getCommand()) {
                checkReq.setToId(msgData.getString("toId")); // 单聊：接收用户ID
            } else {
                checkReq.setToId(msgData.getString("groupId")); // 群聊：群组ID
            }

            // 调用逻辑服务校验消息（如权限、黑名单等）
            ResponseVO checkResult = feignMessageService.checkSendMessage(checkReq);
            if (checkResult.isOk()) {
                // 校验通过：转发消息到消息队列处理
                MqMessageProducer.sendMessage(msg, command);
            } else {
                // 校验失败：返回错误ACK
                Integer ackCommand = (command == MessageCommand.MSG_P2P.getCommand())
                        ? MessageCommand.MSG_ACK.getCommand()
                        : GroupEventCommand.GROUP_MSG_ACK.getCommand();

                ChatMessageAck ackData = new ChatMessageAck(msgData.getString("messageId"));
                checkResult.setData(ackData);

                MessagePack<ResponseVO> ack = new MessagePack<>();
                ack.setData(checkResult);
                ack.setCommand(ackCommand);
                ctx.channel().writeAndFlush(ack);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 连接断开时触发（当前注释掉，实际逻辑在SessionSocketHolder中处理）
     */
    // @Override
    // public void channelInactive(ChannelHandlerContext ctx) {
    //     SessionSocketHolder.offlineUserSession((NioSocketChannel) ctx.channel());
    //     ctx.close();
    // }

    /**
     * 处理用户事件（如空闲事件，当前未实现）
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 处理异常事件
     * 打印异常并传递给下一个处理器
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        super.exceptionCaught(ctx, cause);
    }
}