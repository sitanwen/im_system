package com.stw.im.service.message.service;

import com.stw.im.common.ResponseVO;
import com.stw.im.common.config.AppConfig;
import com.stw.im.common.enums.*;
import com.stw.im.service.friendship.dao.ImFriendShipEntity;
import com.stw.im.service.friendship.model.req.GetRelationReq;
import com.stw.im.service.friendship.service.ImFriendService;
import com.stw.im.service.group.dao.ImGroupEntity;
import com.stw.im.service.group.model.resp.GetRoleInGroupResp;
import com.stw.im.service.group.service.ImGroupMemberService;
import com.stw.im.service.group.service.ImGroupService;
import com.stw.im.service.user.dao.ImUserDataEntity;
import com.stw.im.service.user.service.ImUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @description: 消息发送权限校验服务类
 * 负责在消息发送前进行各种合法性校验，包括：
 * 1. 发送者账号状态校验（是否被封禁、禁言）
 * 2. 点对点消息的好友关系校验（是否为好友、是否在黑名单中）
 * 3. 群消息的发送权限校验（群状态、成员身份、禁言状态等）
 * @author: stw
 * @version: 1.0
 */
@Service
public class CheckSendMessageService {

    @Autowired
    private ImUserService imUserService; // 用户信息服务，用于查询用户状态

    @Autowired
    private ImFriendService imFriendService; // 好友关系服务，用于校验好友关系

    @Autowired
    private ImGroupService imGroupService; // 群组服务，用于查询群组信息

    @Autowired
    private ImGroupMemberService imGroupMemberService; // 群成员服务，用于查询群成员角色

    @Autowired
    private AppConfig appConfig; // 应用配置，包含消息校验相关的开关配置


    /**
     * 校验发送者是否被封禁或禁言
     * @param fromId 发送者ID
     * @param appId 应用ID
     * @return 校验结果：成功则返回success，失败则返回对应的错误码
     */
    public ResponseVO checkSenderForvidAndMute(String fromId, Integer appId) {
        // 查询发送者的用户信息
        ResponseVO<ImUserDataEntity> singleUserInfo = imUserService.getSingleUserInfo(fromId, appId);
        if (!singleUserInfo.isOk()) {
            // 用户信息查询失败，直接返回错误结果
            return singleUserInfo;
        }

        ImUserDataEntity user = singleUserInfo.getData();
        // 校验用户是否被封禁
        if (user.getForbiddenFlag() == UserForbiddenFlagEnum.FORBIBBEN.getCode()) {
            return ResponseVO.errorResponse(MessageErrorCode.FROMER_IS_FORBIBBEN);
        }
        // 校验用户是否被禁言
        else if (user.getSilentFlag() == UserSilentFlagEnum.MUTE.getCode()) {
            return ResponseVO.errorResponse(MessageErrorCode.FROMER_IS_MUTE);
        }

        // 校验通过
        return ResponseVO.successResponse();
    }

    /**
     * 校验点对点消息的好友关系合法性
     * @param fromId 发送者ID
     * @param toId 接收者ID
     * @param appId 应用ID
     * @return 校验结果：成功则返回success，失败则返回对应的错误码
     */
    public ResponseVO checkFriendShip(String fromId, String toId, Integer appId) {
        // 若配置开启了好友关系校验
        if (appConfig.isSendMessageCheckFriend()) {
            // 构建发送者到接收者的关系查询请求
            GetRelationReq fromReq = new GetRelationReq();
            fromReq.setFromId(fromId);
            fromReq.setToId(toId);
            fromReq.setAppId(appId);
            ResponseVO<ImFriendShipEntity> fromRelation = imFriendService.getRelation(fromReq);
            if (!fromRelation.isOk()) {
                // 关系查询失败，返回错误结果
                return fromRelation;
            }

            // 构建接收者到发送者的关系查询请求（双向校验）
            GetRelationReq toReq = new GetRelationReq();
            toReq.setFromId(toId);
            toReq.setToId(fromId);
            toReq.setAppId(appId);
            ResponseVO<ImFriendShipEntity> toRelation = imFriendService.getRelation(toReq);
            if (!toRelation.isOk()) {
                // 关系查询失败，返回错误结果
                return toRelation;
            }

            // 校验双方好友关系是否为正常状态
            if (FriendShipStatusEnum.FRIEND_STATUS_NORMAL.getCode() != fromRelation.getData().getStatus()) {
                return ResponseVO.errorResponse(FriendShipErrorCode.FRIEND_IS_DELETED);
            }
            if (FriendShipStatusEnum.FRIEND_STATUS_NORMAL.getCode() != toRelation.getData().getStatus()) {
                return ResponseVO.errorResponse(FriendShipErrorCode.FRIEND_IS_DELETED);
            }

            // 若配置开启了黑名单校验
            if (appConfig.isSendMessageCheckBlack()) {
                // 校验发送者是否将接收者加入黑名单
                if (FriendShipStatusEnum.BLACK_STATUS_NORMAL.getCode() != fromRelation.getData().getBlack()) {
                    return ResponseVO.errorResponse(FriendShipErrorCode.FRIEND_IS_BLACK);
                }
                // 校验接收者是否将发送者加入黑名单
                if (FriendShipStatusEnum.BLACK_STATUS_NORMAL.getCode() != toRelation.getData().getBlack()) {
                    return ResponseVO.errorResponse(FriendShipErrorCode.TARGET_IS_BLACK_YOU);
                }
            }
        }

        // 校验通过
        return ResponseVO.successResponse();
    }

    /**
     * 校验群消息的发送权限
     * @param fromId 发送者ID
     * @param groupId 群组ID
     * @param appId 应用ID
     * @return 校验结果：成功则返回success，失败则返回对应的错误码
     */
    public ResponseVO checkGroupMessage(String fromId, String groupId, Integer appId) {
        // 先校验发送者是否被封禁或禁言
        ResponseVO responseVO = checkSenderForvidAndMute(fromId, appId);
        if (!responseVO.isOk()) {
            return responseVO;
        }

        // 校验群组是否存在且状态正常
        ResponseVO<ImGroupEntity> group = imGroupService.getGroup(groupId, appId);
        if (!group.isOk()) {
            return group;
        }

        // 校验发送者是否为群成员
        ResponseVO<GetRoleInGroupResp> roleInGroupOne = imGroupMemberService.getRoleInGroupOne(groupId, fromId, appId);
        if (!roleInGroupOne.isOk()) {
            return roleInGroupOne;
        }
        GetRoleInGroupResp memberRole = roleInGroupOne.getData();

        // 校验群组是否被禁言（仅管理员和群主可发言）
        ImGroupEntity groupData = group.getData();
        if (groupData.getMute() == GroupMuteTypeEnum.MUTE.getCode()
                && !(memberRole.getRole() == GroupMemberRoleEnum.MAMAGER.getCode()
                || memberRole.getRole() == GroupMemberRoleEnum.OWNER.getCode())) {
            return ResponseVO.errorResponse(GroupErrorCode.THIS_GROUP_IS_MUTE);
        }

        // 校验群成员是否被单独禁言（禁言时间未过期）
        if (memberRole.getSpeakDate() != null && memberRole.getSpeakDate() > System.currentTimeMillis()) {
            return ResponseVO.errorResponse(GroupErrorCode.GROUP_MEMBER_IS_SPEAK);
        }

        // 校验通过
        return ResponseVO.successResponse();
    }
}