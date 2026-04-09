package com.stw.im.service;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.stw.im.service",
        "com.stw.im.common"})
@MapperScan("com.stw.im.service.*.dao.mapper")
//导入用户资料，删除用户资料，修改用户资料，查询用户资料
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }


}




