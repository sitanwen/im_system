package com.stw.im.tcp.server;

import com.stw.im.codec.MessageDecoder;
import com.stw.im.codec.MessageEncoder;
import com.stw.im.codec.config.BootstrapConfig;
import com.stw.im.tcp.handler.HeartBeatHandler;
import com.stw.im.tcp.handler.NettyServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP服务器核心类，基于Netty框架实现
 * 负责初始化TCP服务端配置、设置网络参数、构建ChannelPipeline处理链
 * 提供TCP连接的建立、消息编解码、心跳检测等核心功能
 *
 * @author:
 * @version: 1.0
 */
public class LimServer {

    private final static Logger logger = LoggerFactory.getLogger(LimServer.class);

    // TCP服务器配置信息（端口、线程数、心跳时间等）
    private final BootstrapConfig.TcpConfig config;

    // 主线程组（负责处理客户端连接请求）
    private EventLoopGroup mainGroup;

    // 从线程组（负责处理已建立连接的IO操作）
    private EventLoopGroup subGroup;

    // Netty服务端启动器
    private ServerBootstrap server;

    /**
     * 构造函数：初始化TCP服务器配置
     * @param config TCP服务器配置对象
     */
    public LimServer(BootstrapConfig.TcpConfig config) {
        this.config = config;
        // 初始化线程组（boss线程数和work线程数从配置获取）
        mainGroup = new NioEventLoopGroup(config.getBossThreadSize());
        subGroup = new NioEventLoopGroup(config.getWorkThreadSize());
        server = new ServerBootstrap();

        // 配置Netty服务端参数
        server.group(mainGroup, subGroup)
                // 使用NIO Socket通道
                .channel(NioServerSocketChannel.class)
                // 服务端连接队列大小（未完成三次握手的连接队列）
                .option(ChannelOption.SO_BACKLOG, 10240)
                // 允许重复使用本地地址和端口（服务重启时快速占用端口）
                .option(ChannelOption.SO_REUSEADDR, true)
                // 禁用Nagle算法（减少TCP包延迟，保证消息实时性）
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 启用TCP保活机制（2小时无数据自动发送心跳检测）
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // 配置Channel处理 pipeline（责任链模式）
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // 1. 消息解码器（将二进制字节流转换为Java对象）
                        ch.pipeline().addLast(new MessageDecoder());
                        // 2. 消息编码器（将Java对象转换为二进制字节流）
                        ch.pipeline().addLast(new MessageEncoder());
                        // 注释：IdleStateHandler用于检测连接空闲状态（当前使用自定义HeartBeatHandler处理）
//                        ch.pipeline().addLast(new IdleStateHandler(
//                                0, 0,
//                                10));
                        // 3. 心跳处理器（检测客户端心跳超时，处理离线逻辑）
                        ch.pipeline().addLast(new HeartBeatHandler(config.getHeartBeatTime()));
                        // 4. 核心业务处理器（处理登录、消息收发等业务逻辑）
                        ch.pipeline().addLast(new NettyServerHandler(config.getBrokerId(), config.getLogicUrl()));
                    }
                });
    }

    /**
     * 启动TCP服务器，绑定配置的TCP端口
     */
    public void start() {
        try {
            // 绑定端口并同步等待（此处可添加ChannelFuture监听处理启动结果）
            this.server.bind(this.config.getTcpPort()).sync(); // 绑定端口，启动服务器，开始监听连接
            logger.info("TCP服务器启动成功，绑定端口：{}", config.getTcpPort());
        } catch (InterruptedException e) {
            logger.error("TCP服务器启动失败", e);
            // 中断异常时恢复中断状态
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 优雅关闭服务器（释放线程资源）
     */
    public void shutdown() {
        if (mainGroup != null) {
            mainGroup.shutdownGracefully();
        }
        if (subGroup != null) {
            subGroup.shutdownGracefully();
        }
        logger.info("TCP服务器已关闭");
    }
}