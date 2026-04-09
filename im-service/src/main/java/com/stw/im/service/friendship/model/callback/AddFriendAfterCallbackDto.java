package com.stw.im.service.friendship.model.callback;

import com.stw.im.service.friendship.model.req.FriendDto;
import lombok.Data;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
@Data
public class AddFriendAfterCallbackDto {

    private String fromId;

    private FriendDto toItem;
}
