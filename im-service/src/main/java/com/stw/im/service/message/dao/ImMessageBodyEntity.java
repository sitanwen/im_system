// 文件：l-im/im-service/src/main/java/com/stw/im/service/message/dao/ImMessageBodyEntity.java
package com.stw.im.service.message.dao;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stw.im.common.model.BaseEntity; // 引入公共字段基类
import lombok.Data;

@Data
@TableName("im_message_body")
public class ImMessageBodyEntity extends BaseEntity { // 继承BaseEntity

    /** messageBodyId*/
    private Long messageKey;

    /** messageBody*/
    private String messageBody;

    private String securityKey;

    private Long messageTime;

    // 以下字段已在BaseEntity中定义，无需重复
    // private Integer appId;
    // private Long createTime;
    // private String extra;
    // private Integer delFlag;
}