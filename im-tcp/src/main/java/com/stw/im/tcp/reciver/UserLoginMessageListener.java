package com.stw.im.tcp.reciver;

import com.alibaba.fastjson.JSONObject;
import com.stw.im.codec.proto.MessagePack;
import com.stw.im.common.ClientType;
import com.stw.im.common.constant.Constants;
import com.stw.im.common.enums.DeviceMultiLoginEnum;
import com.stw.im.common.enums.command.SystemCommand;
import com.stw.im.common.model.UserClientDto;
import com.stw.im.tcp.redis.RedisManager;
import com.stw.im.tcp.utils.SessionSocketHolder;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.redisson.api.RTopic;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @description: 用户登录消息监听器
 * 负责处理多端登录冲突逻辑，根据配置的登录模式（单端/双端/三端）管理用户在线设备：
 * 1. 单端登录：仅允许一个设备在线，新登录会踢掉其他所有设备
 * 2. 双端登录：允许PC/手机其中一端 + Web端在线，新登录会踢掉同类型非Web设备的旧连接
 * 3. 三端登录：允许手机+PC+Web同时在线，新登录会踢掉同类型（如手机与手机）的旧设备（Web除外）
 * 4. 不做处理：允许所有设备同时在线
 * @author: stw
 * @version: 1.0
 */
public class UserLoginMessageListener {

    // 日志记录器，用于输出监听过程中的关键信息
    private final static Logger logger = LoggerFactory.getLogger(UserLoginMessageListener.class);

    // 登录模式：由配置文件指定，对应DeviceMultiLoginEnum的枚举值
    private Integer loginModel;

    /**
     * 构造方法：初始化登录模式
     * @param loginModel 登录模式（1-单端，2-双端，3-三端，4-不限制）
     */
    public UserLoginMessageListener(Integer loginModel) {
        this.loginModel = loginModel;
    }

    /**
     * 启动登录事件监听
     * 订阅Redis的用户登录频道，接收所有节点的登录通知并处理冲突
     */
    public void listenerUserLogin(){
        // 获取Redis的发布订阅频道（用户登录事件专用）
        RTopic topic = RedisManager.getRedissonClient().getTopic(Constants.RedisConstants.UserLoginChannel);

        // 为频道添加消息监听器，处理接收到的登录事件
        topic.addListener(String.class, new MessageListener<String>() {
            @Override
            public void onMessage(CharSequence charSequence, String msg) {
                logger.info("收到用户上线通知：" + msg);
                // 将消息内容解析为用户客户端信息对象（包含appId、userId、设备类型、imei等）
                UserClientDto dto = JSONObject.parseObject(msg, UserClientDto.class);

                // 从本地缓存获取当前节点上该用户的所有在线连接
                List<NioSocketChannel> nioSocketChannels = SessionSocketHolder.get(dto.getAppId(), dto.getUserId());

                // 遍历当前节点上的所有连接，根据登录模式处理冲突
                for (NioSocketChannel nioSocketChannel : nioSocketChannels) {
                    // 单端登录模式处理
                    if(loginModel == DeviceMultiLoginEnum.ONE.getLoginMode()){
                        // 获取当前连接的设备类型（如手机、PC等）
                        Integer clientType = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.ClientType)).get();
                        // 获取当前连接的设备唯一标识（imei）
                        String imei = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.Imei)).get();

                        // 对比当前连接的设备标识与新登录的设备标识
                        // 如果不相同，说明是旧设备，需要踢下线
                        if(!(clientType + ":" + imei).equals(dto.getClientType()+":"+dto.getImei())){
                            // 构建"互踢"指令消息包
                            MessagePack<Object> pack = new MessagePack<>();
                            pack.setToId((String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get());
                            pack.setUserId((String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get());
                            pack.setCommand(SystemCommand.MUTUALLOGIN.getCommand()); // 互踢指令
                            // 发送踢下线指令给旧设备
                            nioSocketChannel.writeAndFlush(pack);
                        }

                        // 双端登录模式处理
                    }else if(loginModel == DeviceMultiLoginEnum.TWO.getLoginMode()){
                        // 如果新登录的是Web端，不处理（双端模式允许Web端共存）
                        if(dto.getClientType() == ClientType.WEB.getCode()){
                            continue;
                        }
                        // 获取当前连接的设备类型
                        Integer clientType = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.ClientType)).get();

                        // 如果当前连接是Web端，不处理（双端模式允许Web端共存）
                        if (clientType == ClientType.WEB.getCode()){
                            continue;
                        }
                        // 获取当前连接的设备唯一标识
                        String imei = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.Imei)).get();

                        // 对比设备标识，不同则踢掉旧设备（非Web端）
                        if(!(clientType + ":" + imei).equals(dto.getClientType()+":"+dto.getImei())){
                            MessagePack<Object> pack = new MessagePack<>();
                            pack.setToId((String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get());
                            pack.setUserId((String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get());
                            pack.setCommand(SystemCommand.MUTUALLOGIN.getCommand());
                            nioSocketChannel.writeAndFlush(pack);
                        }

                        // 三端登录模式处理
                    }else if(loginModel == DeviceMultiLoginEnum.THREE.getLoginMode()){
                        // 获取当前连接的设备类型和imei
                        Integer clientType = (Integer) nioSocketChannel.attr(AttributeKey.valueOf(Constants.ClientType)).get();
                        String imei = (String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.Imei)).get();

                        // 如果新登录的是Web端，不处理（三端模式允许Web端共存）
                        if(dto.getClientType() == ClientType.WEB.getCode()){
                            continue;
                        }

                        // 判断当前连接的设备与新登录设备是否为同类型（如手机与手机、PC与PC）
                        Boolean isSameClient = false;
                        // 手机类型（iOS/Android）互斥
                        if((clientType == ClientType.IOS.getCode() ||
                                clientType == ClientType.ANDROID.getCode()) &&
                                (dto.getClientType() == ClientType.IOS.getCode() ||
                                        dto.getClientType() == ClientType.ANDROID.getCode())){
                            isSameClient = true;
                        }
                        // PC类型（Mac/Windows）互斥
                        if((clientType == ClientType.MAC.getCode() ||
                                clientType == ClientType.WINDOWS.getCode()) &&
                                (dto.getClientType() == ClientType.MAC.getCode() ||
                                        dto.getClientType() == ClientType.WINDOWS.getCode())){
                            isSameClient = true;
                        }

                        // 同类型设备且标识不同，则踢掉旧设备
                        if(isSameClient && !(clientType + ":" + imei).equals(dto.getClientType()+":"+dto.getImei())){
                            MessagePack<Object> pack = new MessagePack<>();
                            pack.setToId((String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get());
                            pack.setUserId((String) nioSocketChannel.attr(AttributeKey.valueOf(Constants.UserId)).get());
                            pack.setCommand(SystemCommand.MUTUALLOGIN.getCommand());
                            nioSocketChannel.writeAndFlush(pack);
                        }
                    }
                }
            }
        });
    }
}