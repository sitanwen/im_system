package com.stw.im.service.user.service;

import com.stw.im.service.user.model.UserStatusChangeNotifyContent;
import com.stw.im.service.user.model.req.PullFriendOnlineStatusReq;
import com.stw.im.service.user.model.req.PullUserOnlineStatusReq;
import com.stw.im.service.user.model.req.SetUserCustomerStatusReq;
import com.stw.im.service.user.model.req.SubscribeUserOnlineStatusReq;
import com.stw.im.service.user.model.resp.UserOnlineStatusResp;

import java.util.Map;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
public interface ImUserStatusService {

    public void processUserOnlineStatusNotify(UserStatusChangeNotifyContent content);

    void subscribeUserOnlineStatus(SubscribeUserOnlineStatusReq req);

    void setUserCustomerStatus(SetUserCustomerStatusReq req);

    Map<String, UserOnlineStatusResp> queryFriendOnlineStatus(PullFriendOnlineStatusReq req);

    Map<String, UserOnlineStatusResp> queryUserOnlineStatus(PullUserOnlineStatusReq req);
}
