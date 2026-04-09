package com.stw.im.tcp;

import com.stw.im.codec.config.BootstrapConfig;
import com.stw.im.tcp.reciver.MessageReciver;
import com.stw.im.tcp.redis.RedisManager;
import com.stw.im.tcp.register.RegistryZK;
import com.stw.im.tcp.register.ZKit;
import com.stw.im.tcp.server.LimServer;
import com.stw.im.tcp.server.LimWebSocketServer;
import com.stw.im.tcp.utils.MqFactory;
import org.I0Itec.zkclient.ZkClient;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IM服务总启动器（程序入口类）
 * 负责统筹整个IM-TCP服务的启动流程：
 * 1. 加载配置文件（端口、中间件地址等）
 * 2. 启动TCP服务器和WebSocket服务器
 * 3. 初始化Redis、RabbitMQ等中间件
 * 4. 将服务注册到ZooKeeper（用于服务发现）
 * 是整个IM服务启动的"总开关"
 *
 * @author: stw
 * @version: 1.0
 */
public class Starter {


    // 以下为协议设计相关注释（辅助理解）
    // HTTP方法与版本：GET POST PUT DELETE 1.0 1.1 2.0
    // 支持的客户端：IOS 安卓 pc(windows mac) web（兼容json和protobuf）
    // 协议设计核心要素：
    // - appId：区分不同应用的消息来源
    // - imei：设备唯一标识（用于多设备登录区分）
    // - 请求结构：请求头（指令、版本、客户端类型、消息解析类型、imei长度、appId、body长度）+ imei号 + 请求体
    // - 传输格式：len（长度）+ body（内容）


    /**
     * 程序入口方法
     * 接收启动参数（配置文件路径），触发服务启动流程
     * @param args 启动参数，第一个参数为配置文件路径
     */
    public static void main(String[] args)  {
        // 从启动参数中获取配置文件路径，调用start方法启动服务
        if(args.length > 0){
            start(args[0]);
        }
    }

    /**
     * 核心启动方法
     * 加载配置 → 启动服务器 → 初始化中间件 → 注册服务到ZK
     * @param path 配置文件路径（如config.yml）
     */
    private static void start(String path){
        try {
            // 1. 加载YAML配置文件，解析为BootstrapConfig对象
            Yaml yaml = new Yaml();
            InputStream inputStream = new FileInputStream(path);
            BootstrapConfig bootstrapConfig = yaml.loadAs(inputStream, BootstrapConfig.class);

            // 2. 启动TCP服务器（基于Netty的LimServer）和WebSocket服务器
            new LimServer(bootstrapConfig.getLim()).start();
            new LimWebSocketServer(bootstrapConfig.getLim()).start();

            // 3. 初始化Redis连接（用于会话存储、在线状态等）
            RedisManager.init(bootstrapConfig);
            // 4. 初始化RabbitMQ连接（用于消息队列通信）
            MqFactory.init(bootstrapConfig.getLim().getRabbitmq());
            // 5. 初始化消息接收器（监听MQ队列，处理消息分发）
            MessageReciver.init(bootstrapConfig.getLim().getBrokerId()+"");
            // 6. 将服务注册到ZooKeeper（供客户端发现服务地址）
            registerZK(bootstrapConfig);

        }catch (Exception e){
            // 启动失败时打印异常并退出程序
            e.printStackTrace();
            System.exit(500);
        }
    }

    /**
     * 注册服务到ZooKeeper
     * 存储当前服务的IP和端口（TCP/WebSocket）到ZK节点，用于服务发现
     * @param config 全局配置对象
     */
    public static void registerZK(BootstrapConfig config) throws UnknownHostException {
        // 获取当前服务器IP地址
        String hostAddress = InetAddress.getLocalHost().getHostAddress();
        // 创建ZK客户端
        ZkClient zkClient = new ZkClient(config.getLim().getZkConfig().getZkAddr(),
                config.getLim().getZkConfig().getZkConnectTimeOut());
        ZKit zKit = new ZKit(zkClient);
        // 构建注册器并启动线程执行注册（避免阻塞主线程）
        RegistryZK registryZK = new RegistryZK(zKit, hostAddress, config.getLim());
        Thread thread = new Thread(registryZK);
        thread.start();
    }
}