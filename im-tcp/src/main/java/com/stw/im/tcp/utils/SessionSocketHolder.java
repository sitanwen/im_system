package com.stw.im.tcp.utils;

import com.alibaba.fastjson.JSONObject;
import com.stw.im.codec.pack.user.UserStatusChangeNotifyPack;
import com.stw.im.codec.proto.MessageHeader;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.ImConnectStatusEnum;
import com.stw.im.common.enums.command.UserEventCommand;
import com.stw.im.common.model.UserClientDto;
import com.stw.im.common.model.UserSession;
import com.stw.im.tcp.publish.MqMessageProducer;
import com.stw.im.tcp.redis.RedisManager;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话连接管理器
 * 核心功能：维护用户与Netty Channel的映射关系，管理在线状态与会话生命周期
 * 包含本地缓存(CHANNELS)与Redis分布式存储的同步逻辑
 */
public class SessionSocketHolder {

    /**
     * 本地缓存：用户客户端标识 -> 对应的Netty Channel连接
     * 键：UserClientDto（包含appId、userId、clientType、imei，唯一标识一个客户端）
     * 值：NioSocketChannel（客户端与服务端的TCP连接通道）
     * 采用ConcurrentHashMap保证多线程环境下的操作安全
     */
    private static final Map<UserClientDto, NioSocketChannel> CHANNELS = new ConcurrentHashMap<>();

    /**
     * 存储用户客户端与Channel的映射关系
     * @param appId 应用ID（多租户隔离）
     * @param userId 用户ID
     * @param clientType 客户端类型（如Android、iOS、Web等）
     * @param imei 设备唯一标识
     * @param channel 对应的Netty Channel
     */
    public static void put(Integer appId, String userId, Integer clientType,
                           String imei, NioSocketChannel channel) {
        UserClientDto dto = new UserClientDto();
        dto.setImei(imei);
        dto.setAppId(appId);
        dto.setClientType(clientType);
        dto.setUserId(userId);
        CHANNELS.put(dto, channel);
    }

    /**
     * 根据用户客户端信息获取对应的Channel
     * @param appId 应用ID
     * @param userId 用户ID
     * @param clientType 客户端类型
     * @param imei 设备唯一标识
     * @return 对应的NioSocketChannel，不存在则返回null
     */
    public static NioSocketChannel get(Integer appId, String userId,
                                       Integer clientType, String imei) {
        UserClientDto dto = new UserClientDto();
        dto.setImei(imei);
        dto.setAppId(appId);
        dto.setClientType(clientType);
        dto.setUserId(userId);
        return CHANNELS.get(dto);
    }

    /**
     * 获取用户在当前服务节点上的所有在线连接
     * @param appId 应用ID
     * @param userId 用户ID
     * @return 该用户在当前节点的所有Channel列表
     */
    public static List<NioSocketChannel> get(Integer appId, String userId) {
        Set<UserClientDto> channelInfos = CHANNELS.keySet();
        List<NioSocketChannel> channels = new ArrayList<>();

        channelInfos.forEach(channel -> {
            if (channel.getAppId().equals(appId) && userId.equals(channel.getUserId())) {
                channels.add(CHANNELS.get(channel));
            }
        });

        return channels;
    }

    /**
     * 从本地缓存中移除指定用户客户端的Channel映射
     * @param appId 应用ID
     * @param userId 用户ID
     * @param clientType 客户端类型
     * @param imei 设备唯一标识
     */
    public static void remove(Integer appId, String userId, Integer clientType, String imei) {
        UserClientDto dto = new UserClientDto();
        dto.setAppId(appId);
        dto.setImei(imei);
        dto.setClientType(clientType);
        dto.setUserId(userId);
        CHANNELS.remove(dto);
    }

    /**
     * 根据Channel从本地缓存中移除对应的用户客户端映射
     * @param channel 要移除的Channel
     */
    public static void remove(NioSocketChannel channel) {
        CHANNELS.entrySet().stream()
                .filter(entity -> entity.getValue() == channel)
                .forEach(entry -> CHANNELS.remove(entry.getKey()));
    }

    /**
     * 彻底移除用户会话（用户主动登出场景）
     * 1. 移除本地缓存映射
     * 2. 删除Redis中的会话记录
     * 3. 发布用户离线状态通知
     * 4. 关闭Channel连接
     * @param nioSocketChannel 要移除的Channel
     */
    public static void removeUserSession(NioSocketChannel nioSocketChannel) {
        // 从Channel属性中获取用户信息
        String userId = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get();
        Integer appId = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.AppId)).get();
        Integer clientType = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.ClientType)).get();
        String imei = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.Imei)).get();

        // 移除本地缓存
        SessionSocketHolder.remove(appId, userId, clientType, imei);

        // 删除Redis中的会话记录（彻底登出，不再保留离线状态）
        RedissonClient redissonClient = RedisManager.getRedissonClient();
        RMap<Object, Object> map = redissonClient.getMap(appId +
                Constants.RedisConstants.UserSessionConstants + userId);
        map.remove(clientType + ":" + imei);

        // 构建离线状态通知并发送到消息队列
        MessageHeader messageHeader = new MessageHeader();
        messageHeader.setAppId(appId);
        messageHeader.setImei(imei);
        messageHeader.setClientType(clientType);

        UserStatusChangeNotifyPack userStatusChangeNotifyPack = new UserStatusChangeNotifyPack();
        userStatusChangeNotifyPack.setAppId(appId);
        userStatusChangeNotifyPack.setUserId(userId);
        userStatusChangeNotifyPack.setStatus(ImConnectStatusEnum.OFFLINE_STATUS.getCode());
        MqMessageProducer.sendMessage(userStatusChangeNotifyPack, messageHeader,
                UserEventCommand.USER_ONLINE_STATUS_CHANGE.getCommand());

        // 关闭连接
        nioSocketChannel.close();
    }

    /**
     * 标记用户会话为离线（心跳超时等被动离线场景）
     * 1. 移除本地缓存映射
     * 2. 更新Redis中会话的连接状态为离线（保留会话记录）
     * 3. 发布用户离线状态通知
     * 4. 关闭Channel连接
     * @param nioSocketChannel 要离线的Channel
     */
    public static void offlineUserSession(NioSocketChannel nioSocketChannel) {
        // 从Channel属性中获取用户信息
        String userId = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get();
        Integer appId = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.AppId)).get();
        Integer clientType = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.ClientType)).get();
        String imei = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.Imei)).get();

        // 移除本地缓存
        SessionSocketHolder.remove(appId, userId, clientType, imei);

        // 更新Redis中会话的连接状态为离线（保留会话信息，便于后续重连）
        RedissonClient redissonClient = RedisManager.getRedissonClient();
        RMap<String, String> map = redissonClient.getMap(appId +
                Constants.RedisConstants.UserSessionConstants + userId);
        String sessionStr = map.get(clientType.toString() + ":" + imei);

        if (!StringUtils.isBlank(sessionStr)) {
            UserSession userSession = JSONObject.parseObject(sessionStr, UserSession.class);
            userSession.setConnectState(ImConnectStatusEnum.OFFLINE_STATUS.getCode());
            map.put(clientType.toString() + ":" + imei, JSONObject.toJSONString(userSession));
        }

        // 构建离线状态通知并发送到消息队列
        MessageHeader messageHeader = new MessageHeader();
        messageHeader.setAppId(appId);
        messageHeader.setImei(imei);
        messageHeader.setClientType(clientType);

        UserStatusChangeNotifyPack userStatusChangeNotifyPack = new UserStatusChangeNotifyPack();
        userStatusChangeNotifyPack.setAppId(appId);
        userStatusChangeNotifyPack.setUserId(userId);
        userStatusChangeNotifyPack.setStatus(ImConnectStatusEnum.OFFLINE_STATUS.getCode());
        MqMessageProducer.sendMessage(userStatusChangeNotifyPack, messageHeader,
                UserEventCommand.USER_ONLINE_STATUS_CHANGE.getCommand());

        // 关闭连接
        nioSocketChannel.close();
    }
}