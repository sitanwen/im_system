package com.stw.im.common.model.message;

import com.stw.im.common.model.ClientInfo;
import lombok.Data;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
@Data
public class MessageReciveAckContent extends ClientInfo {

    private Long messageKey;

    private String fromId;

    private String toId;

    private Long messageSequence;


}
