package com.stw.im.service.message.controller;

import com.stw.im.common.ResponseVO;
import com.stw.im.common.model.SyncReq;
import com.stw.im.common.model.message.CheckSendMessageReq;
import com.stw.im.service.message.model.req.SendMessageReq;
import com.stw.im.service.message.service.MessageSyncService;
import com.stw.im.service.message.service.P2PMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息控制器
 * 负责处理与消息相关的HTTP请求，包括单聊消息发送、消息发送权限校验、离线消息同步等功能
 * @description: 消息模块核心接口控制器，提供消息发送、校验和同步能力
 * @author: stw
 * @version: 1.0
 */
@RestController
@RequestMapping("v1/message") // 接口基础路径，统一管理消息相关接口
public class MessageController {

    /**
     * 注入单聊消息服务
     * 负责处理单聊消息的发送、存储、分发等核心业务逻辑
     */
    @Autowired
    P2PMessageService p2PMessageService;

    /**
     * 注入消息同步服务
     * 负责处理离线消息的同步、消息已读状态同步等功能
     */
    @Autowired
    MessageSyncService messageSyncService;

    /**
     * 发送单聊消息接口
     * 接收客户端发送的单聊消息请求，转发给业务层处理并返回结果
     * @param req 发送消息请求参数，包含发送方、接收方、消息内容等信息（@Validated 用于参数校验）
     * @param appId 应用ID，用于多租户隔离
     * @return 响应结果，包含消息唯一标识（messageKey）和发送时间等信息
     */
    @RequestMapping("/send")
    public ResponseVO send(@RequestBody @Validated SendMessageReq req, Integer appId)  {
        // 设置应用ID到请求参数中，供业务层使用
        req.setAppId(appId);
        // 调用单聊消息服务的发送方法，返回处理结果
        return ResponseVO.successResponse(p2PMessageService.send(req));
    }

    /**
     * 消息发送权限校验接口
     * 校验发送方是否有权限向接收方发送消息（如检查是否为好友、是否被禁言等）
     * @param req 校验请求参数，包含发送方ID、接收方ID、应用ID等
     * @return 响应结果：成功表示有权限，失败包含具体错误原因（如被禁言、非好友等）
     */
    @RequestMapping("/checkSend")
    public ResponseVO checkSend(@RequestBody @Validated CheckSendMessageReq req)  {
        // 调用单聊消息服务的权限校验方法，返回校验结果
        return p2PMessageService.imServerPermissionCheck(req.getFromId(), req.getToId(), req.getAppId());
    }

    /**
     * 同步离线消息接口
     * 客户端上线后，同步未接收的离线消息
     * @param req 同步请求参数，包含用户ID、最后同步的消息序列号等（用于增量同步）
     * @param appId 应用ID，用于多租户隔离
     * @return 响应结果，包含离线消息列表、最新序列号等信息
     */
    @RequestMapping("/syncOfflineMessage")
    public ResponseVO syncOfflineMessage(@RequestBody @Validated SyncReq req, Integer appId)  {
        // 设置应用ID到请求参数中，供业务层使用
        req.setAppId(appId);
        // 调用消息同步服务的离线消息同步方法，返回同步结果
        return messageSyncService.syncOfflineMessage(req);
    }

}