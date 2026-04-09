package com.stw.message.model;

import com.stw.im.common.model.message.MessageContent;
import com.stw.message.dao.ImMessageBodyEntity;
import lombok.Data;

/**
 * @author: stw
 * @description:
 **/
@Data
public class DoStoreP2PMessageDto {

    private MessageContent messageContent;

    private ImMessageBodyEntity imMessageBodyEntity;

}
