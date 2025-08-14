package com.stw.im.service.user.model.req;

import com.stw.im.common.model.RequestBase;
import com.stw.im.service.user.dao.ImUserDataEntity;
import lombok.Data;

import java.util.List;


@Data
public class ImportUserReq extends RequestBase {

    private List<ImUserDataEntity> userData;


}
