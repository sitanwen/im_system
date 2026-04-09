package com.stw.im.codec.pack.group;

import lombok.Data;

/**
 * @author: stw
 * @description: 转让群主通知报文
 **/
@Data
public class TransferGroupPack {

    private String groupId;

    private String ownerId;

}
