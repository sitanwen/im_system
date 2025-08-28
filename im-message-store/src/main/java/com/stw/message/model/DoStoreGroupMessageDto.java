package com.stw.message.model;

import com.stw.im.common.model.message.GroupChatMessageContent;
import com.stw.message.dao.ImMessageBodyEntity;
import lombok.Data;

/**
 * @author: stw
 * @description:
 **/
@Data
public class DoStoreGroupMessageDto {

    private GroupChatMessageContent groupChatMessageContent;

    private ImMessageBodyEntity imMessageBodyEntity;

}
