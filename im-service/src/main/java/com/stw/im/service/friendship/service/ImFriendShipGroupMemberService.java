package com.stw.im.service.friendship.service;

import com.stw.im.common.ResponseVO;
import com.stw.im.service.friendship.model.req.AddFriendShipGroupMemberReq;
import com.stw.im.service.friendship.model.req.DeleteFriendShipGroupMemberReq;

/**
 * @author: stw
 * @description:
 **/
public interface ImFriendShipGroupMemberService {

    public ResponseVO addGroupMember(AddFriendShipGroupMemberReq req);

    public ResponseVO delGroupMember(DeleteFriendShipGroupMemberReq req);

    public int doAddGroupMember(Long groupId, String toId);

    public int clearGroupMember(Long groupId);
}
