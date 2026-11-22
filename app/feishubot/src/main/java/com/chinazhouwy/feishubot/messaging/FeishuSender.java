package com.chinazhouwy.feishubot.messaging;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.enums.MsgTypeEnum;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.ext.MessageText;

import java.util.UUID;

/** 通过官方 SDK 把文本发回同一飞书会话；SDK 自行管理 tenant_access_token。 */
public final class FeishuSender {
    private final Client client;

    public FeishuSender(Client client) { this.client = client; }

    public void sendText(String chatId, String text) throws Exception {
        String safeText = text == null || text.isBlank() ? "（空消息）" : text;
        String content = MessageText.newBuilder()
                .text(FeishuMessageCodec.escapeJsonString(safeText))
                .build();
        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(chatId)
                        .msgType(MsgTypeEnum.MSG_TYPE_TEXT.getValue())
                        .content(content)
                        .uuid(UUID.randomUUID().toString())
                        .build())
                .build();
        CreateMessageResp resp = client.im().v1().message().create(req);
        if (resp == null || !resp.success()) {
            String code = resp == null ? "null" : String.valueOf(resp.getCode());
            String msg = resp == null ? "null response" : resp.getMsg();
            String requestId = resp == null ? "" : resp.getRequestId();
            throw new IllegalStateException("飞书发送消息失败 code=" + code + ", msg=" + msg + ", requestId=" + requestId);
        }
    }
}
