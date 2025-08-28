L-IM: 轻量级即时通讯系统
一个基于 Java 的分布式即时通讯（IM）系统，支持单聊、群聊、消息时序保证、好友关系管理、群组管理等核心功能，适用于构建各类实时通讯场景。
项目简介
L-IM 是一个轻量级、可扩展的即时通讯解决方案，专注于解决分布式环境下的消息时序性、幂等性、离线消息同步等核心问题。系统采用分层设计，包含用户服务、好友关系服务、群组服务、消息服务等模块，支持多端接入（TCP/WebSocket），可快速集成到各类业务系统中。
核心功能
实时通讯：支持单聊、群聊，基于 TCP 和 WebSocket 协议实现实时消息收发。
消息可靠性：
时序保证：通过会话级单调递增序列号（messageSequence）确保消息按发送顺序接收。
幂等处理：基于messageId去重，避免重复消息。
离线消息：支持离线消息存储与同步，通过 Redis ZSet 有序存储。
关系链管理：好友添加、删除、黑名单、好友分组等功能。
群组管理：创建群组、加入 / 退出群组、群成员权限（禁言、角色）、群公告等。
多端适配：支持多客户端（Web / 移动端）接入，维护会话状态一致性。
技术栈
后端框架：Spring Boot、MyBatis-Plus
通讯协议：TCP、WebSocket
中间件：
Redis：用于序列号生成、离线消息存储、分布式锁。
ZooKeeper：服务注册与发现、配置管理。
RabbitMQ：消息队列（可选，用于异步消息处理）。
数据库：MySQL（存储用户、好友、群组、消息历史等数据）。
序列化：JSON（消息编解码）。
分布式 ID：雪花算法（SnowflakeIdWorker）。
核心模块
用户服务（User Service）
负责用户注册、登录、资料管理，核心类：ImUserService、LoginReq、GetUserInfoResp。
好友关系服务（Friendship Service）
管理好友添加、删除、分组、黑名单，核心类：ImFriendShipService、ImFriendShipGroupService、ImFriendShipEntity。
群组服务（Group Service）
处理群组创建、成员管理、权限控制，核心类：ImGroupService、GetGroupResp、UpdateGroupReq。
消息服务（Message Service）
消息发送、接收、存储、时序控制，核心类：P2PMessageService、GroupMessageService、ImMessageHistoryEntity。
存储模块（Storage）
消息体存储（ImMessageBodyEntity）、单聊 / 群聊消息历史（ImMessageHistoryEntity、ImGroupMessageHistoryEntity）。
通讯模块（Codec）
基于 Netty 的 TCP/WebSocket 协议编解码，核心类：WebSocketMessageEncoder、BootstrapConfig。
快速开始
环境要求
JDK 1.8+
MySQL 5.7+
Redis 5.0+
ZooKeeper 3.6+（可选，用于分布式部署）
Maven 3.6+
部署步骤
克隆代码
bash
git clone git@github.com:sitanwen/im_system.git
cd l-im

配置修改
数据库配置：修改 application.yml 中的 MySQL 连接信息。
Redis 配置：修改 BootstrapConfig 中的 Redis 地址、端口。
ZK 配置（可选）：修改 AppConfig 中的 zkAddr 用于服务注册。
初始化数据库
执行 sql/ 目录下的初始化脚本，创建用户、好友、消息等表结构。
编译打包
bash
mvn clean package -Dmaven.test.skip=true

启动服务
bash
java -jar im-service/target/im-service.jar
java -jar im-message-store/target/im-message-store.jar


核心设计亮点
消息时序保证
通过 Redis 的INCR命令为每个会话（单聊 / 群聊）生成单调递增序列号，确保消息按发送顺序存储和同步，客户端最终按序列号排序展示。
会话窗口管理
单聊会话 ID 通过 ConversationIdGenerate 生成双向一致的唯一标识（如 A 与 B 的会话 ID 固定），确保双向消息归属同一窗口。
分布式部署支持
基于 ZooKeeper 实现服务注册与发现，Redis 实现分布式锁和共享序列号，支持多节点水平扩展。
可配置的回调机制
通过 AppConfig 中的回调开关（如 sendMessageAfterCallback），支持消息发送、好友操作等事件的自定义业务处理。
