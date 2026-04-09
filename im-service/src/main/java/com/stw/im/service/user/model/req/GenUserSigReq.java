package com.stw.im.service.user.model.req;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class GenUserSigReq {

    @NotBlank(message = "userId不能为空")
    private String userId;

    @NotNull(message = "appId不能为空")
    private Integer appId;

    private Long expireSeconds;
}
