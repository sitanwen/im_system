package com.stw.im.common.utils;

import com.stw.im.common.constant.Constants;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * 上下文工具：从Netty Channel或ThreadLocal获取当前上下文信息
 */
public class UserContextHolder {
    // 用于非Netty线程（如Service层）存储上下文
    private static final ThreadLocal<Integer> APP_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> OPERATOR_ID = new ThreadLocal<>();

    // 从Netty Channel获取上下文（登录时已设置，见NettyServerHandler.handleLogin）
    public static Integer getCurrentAppId(Channel channel) {
        return (Integer) channel.attr(AttributeKey.valueOf(Constants.AppId)).get();
    }

    public static String getOperatorId(Channel channel) {
        return (String) channel.attr(AttributeKey.valueOf(Constants.UserId)).get();
    }

    // 用于Service层获取上下文（在Netty处理器中提前设置）
    public static void setCurrentAppId(Integer appId) {
        APP_ID.set(appId);
    }

    public static Integer getCurrentAppId() {
        return APP_ID.get();
    }

    public static void setOperatorId(String operatorId) {
        OPERATOR_ID.set(operatorId);
    }

    public static String getOperatorId() {
        return OPERATOR_ID.get();
    }

    // 清除上下文（请求结束时调用）
    public static void clear() {
        APP_ID.remove();
        OPERATOR_ID.remove();
    }
}