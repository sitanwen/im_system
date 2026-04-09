package com.stw.im.service.group.model.req;

import com.stw.im.common.model.RequestBase;
import lombok.Data;

/**
 * @author: stw
 * @description:
 **/
@Data
public class GetGroupReq extends RequestBase {

    private String groupId;

}
