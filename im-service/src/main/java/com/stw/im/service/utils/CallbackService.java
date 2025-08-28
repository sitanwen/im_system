package com.stw.im.service.utils;

import com.stw.im.common.ResponseVO;
import com.stw.im.common.config.AppConfig;
import com.stw.im.common.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @description: 回调服务工具类，负责处理系统事件的前置/后置回调通知
 * 主要功能是将系统内部关键事件（如消息发送、好友添加、群成员变动等）通过HTTP请求通知到外部服务
 * @author: stw
 * @version: 1.0
 */
@Component
public class CallbackService {

    private Logger logger = LoggerFactory.getLogger(CallbackService.class);

    /** HTTP请求工具类，用于发送回调HTTP请求 */
    @Autowired
    HttpRequestUtils httpRequestUtils;

    /** 应用配置类，包含回调URL等系统配置参数 */
    @Autowired
    AppConfig appConfig;

    /** 共享线程池，用于异步执行后置回调，避免阻塞主线程 */
    @Autowired
    ShareThreadPool shareThreadPool;


    /**
     * 异步执行后置回调（事件处理完成后通知）
     * @param appId 应用ID，标识当前操作所属的应用
     * @param callbackCommand 回调命令，对应具体事件类型（如消息发送后、好友添加后等，定义在Constants.CallbackCommand）
     * @param jsonBody 回调内容，事件相关的详细数据（JSON格式字符串）
     */
    public void callback(Integer appId,String callbackCommand,String jsonBody){
        // 提交到线程池异步执行，不阻塞调用方
        shareThreadPool.submit(() -> {
            try {
                // 调用HTTP工具类发送POST请求到配置的回调URL
                httpRequestUtils.doPost(
                        appConfig.getCallbackUrl(),  // 从配置获取回调地址
                        Object.class,                // 响应数据类型（此处不关心具体类型）
                        builderUrlParams(appId, callbackCommand),  // 构建URL参数（appId和命令）
                        jsonBody,                    // 请求体（事件数据）
                        null                         // 额外请求头（无）
                );
            }catch (Exception e){
                // 记录回调异常日志，不影响主流程
                logger.error("callback 回调{} : {}出现异常 ： {} ",callbackCommand , appId, e.getMessage());
            }
        });
    }

    /**
     * 同步执行前置回调（事件处理前校验）
     * @param appId 应用ID，标识当前操作所属的应用
     * @param callbackCommand 回调命令，对应具体事件类型（如消息发送前、好友添加前等）
     * @param jsonBody 回调内容，事件相关的详细数据（JSON格式字符串）
     * @return 外部服务的响应结果，用于判断是否允许继续执行后续操作
     */
    public ResponseVO beforeCallback(Integer appId,String callbackCommand,String jsonBody){
        try {
            // 发送POST请求到外部服务（URL为空可能是预留扩展，实际可能从配置获取）
            ResponseVO responseVO = httpRequestUtils.doPost(
                    "",  // 注意：此处URL为空，可能需要根据实际配置调整
                    ResponseVO.class,  // 响应数据类型为统一响应对象
                    builderUrlParams(appId, callbackCommand),  // 构建URL参数
                    jsonBody,          // 请求体（事件数据）
                    null
            );
            return responseVO;
        }catch (Exception e){
            // 回调异常时默认返回成功，避免阻塞主流程
            logger.error("callback 之前 回调{} : {}出现异常 ： {} ",callbackCommand , appId, e.getMessage());
            return ResponseVO.successResponse();
        }
    }

    /**
     * 构建回调请求的URL参数
     * @param appId 应用ID
     * @param command 回调命令
     * @return 包含appId和command的参数Map
     */
    public Map builderUrlParams(Integer appId, String command) {
        Map map = new HashMap();
        map.put("appId", appId);
        map.put("command", command);
        return map;
    }


}