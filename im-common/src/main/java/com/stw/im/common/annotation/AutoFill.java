package com.stw.im.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoFill {
    // 操作类型：新增/更新
    enum Operation { INSERT, UPDATE }
    Operation value(); // 必须指定操作类型
}