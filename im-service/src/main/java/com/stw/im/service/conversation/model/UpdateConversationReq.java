package com.stw.im.service.conversation.model;

import com.stw.im.common.model.RequestBase;
import lombok.Data;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
@Data
public class UpdateConversationReq extends RequestBase {

    private String conversationId;

    private Integer isMute;

    private Integer isTop;

    private String fromId;


}
