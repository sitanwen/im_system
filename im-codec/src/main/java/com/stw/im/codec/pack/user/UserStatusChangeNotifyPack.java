package com.stw.im.codec.pack.user;

import com.stw.im.common.model.UserSession;
import lombok.Data;

import java.util.List;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
@Data
public class UserStatusChangeNotifyPack {

    private Integer appId;

    private String userId;

    private Integer status;

    private List<UserSession> client;

}
