package com.stw.im.common.model.message;

import lombok.Data;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
@Data
public class DoStoreP2PMessageDto {

    private MessageContent messageContent;

    private ImMessageBody messageBody;

}
