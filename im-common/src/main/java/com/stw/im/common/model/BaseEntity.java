package com.stw.im.common.model;

import lombok.Data;

/**
 * 所有数据库实体的基类，定义公共字段
 */
@Data
public class BaseEntity {
    protected Integer appId;         // 多应用隔离ID（核心公共字段）
    protected Long createTime;       // 创建时间
    protected Long updateTime;       // 更新时间
    protected Integer delFlag;       // 删除标记（0-正常，1-删除）
    protected String operatorId;     // 操作人ID（谁创建/修改了记录）
}