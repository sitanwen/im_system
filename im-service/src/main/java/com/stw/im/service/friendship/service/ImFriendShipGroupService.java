package com.stw.im.service.friendship.service;

import com.stw.im.common.ResponseVO;
import com.stw.im.service.friendship.dao.ImFriendShipGroupEntity;
import com.stw.im.service.friendship.model.req.AddFriendShipGroupReq;
import com.stw.im.service.friendship.model.req.DeleteFriendShipGroupReq;

/**
 * @author: Chackylee
 * @description:
 **/
public interface ImFriendShipGroupService {

    public ResponseVO addGroup(AddFriendShipGroupReq req);

    public ResponseVO deleteGroup(DeleteFriendShipGroupReq req);

    public ResponseVO<ImFriendShipGroupEntity> getGroup(String fromId, String groupName, Integer appId);

    public Long updateSeq(String fromId, String groupName, Integer appId);
}
