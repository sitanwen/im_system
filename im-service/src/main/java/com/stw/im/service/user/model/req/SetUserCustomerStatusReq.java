package com.stw.im.service.user.model.req;

import com.stw.im.common.model.RequestBase;
import lombok.Data;

/**
 * @description:
 * @author: stw
 * @version: 1.0
 */
@Data
public class SetUserCustomerStatusReq extends RequestBase {

    private String userId;

    private String customText;

    private Integer customStatus;

}
