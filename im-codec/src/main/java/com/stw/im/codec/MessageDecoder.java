package com.stw.im.codec;

import com.alibaba.fastjson.JSONObject;
import com.stw.im.codec.proto.Message;
import com.stw.im.codec.proto.MessageHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * @description: 消息解码类
 * @author: stw
 * @version: 1.0
 */
public class MessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx,
                          ByteBuf in, List<Object> out) throws Exception {
        // 请求头（指令、版本、clientType、消息解析类型、appId、imei长度、bodylen）+ imei号 + 请求体

        // 先标记读指针位置，用于半包时重置
        in.markReaderIndex();

        // 检查基础头部长度是否足够（7个int，每个4字节，共28字节）
        if(in.readableBytes() < 28){
            in.resetReaderIndex();
            return;
        }

        Message message = transition(in);
        if(message != null){
            out.add(message);
        }
    }

    /**
     * 将ByteBuf转换为Message对象
     */
    public static Message transition(ByteBuf in) {
        // 1. 读取消息头字段
        int command = in.readInt();
        int version = in.readInt();
        int clientType = in.readInt();
        int messageType = in.readInt();
        int appId = in.readInt();
        int imeiLength = in.readInt();
        int bodyLen = in.readInt();

        // 2. 检查 imei + body 数据是否完整
        if (in.readableBytes() < (imeiLength + bodyLen)) {
            in.resetReaderIndex(); // 重置读指针（解决拆包问题）
            return null;
        }

        // 3. 读取完整数据
        byte[] imeiData = new byte[imeiLength];
        in.readBytes(imeiData);
        String imei = new String(imeiData);

        byte[] bodyData = new byte[bodyLen];
        in.readBytes(bodyData);

        // 4. 构造消息对象
        MessageHeader header = new MessageHeader();
        header.setImei(imei);
        header.setLength(bodyLen);
        header.setCommand(command);
        header.setVersion(version);
        header.setClientType(clientType);
        header.setMessageType(messageType);
        header.setAppId(appId);

        Message message = new Message();
        message.setMessageHeader(header);
        message.setMessagePack(parseBody(bodyData, messageType));

        return message;
    }

    /**
     * 解析消息体
     */
    private static Object parseBody(byte[] bodyData, int messageType) {
        // 根据消息类型解析消息体
        // 这里假设messageType为1时使用JSON解析
        if (messageType == 1) {
            return JSONObject.parse(new String(bodyData));
        }
        // 可以添加其他类型的解析逻辑
        return bodyData;
    }
}
