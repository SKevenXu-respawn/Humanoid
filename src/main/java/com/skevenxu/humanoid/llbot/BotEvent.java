package com.skevenxu.humanoid.llbot;

import com.google.gson.JsonElement;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OneBot 11 事件（llbot 推送的 JSON 原文），附带接收序号和接收时间。
 */
public record BotEvent(long seq, long receivedAt, JsonElement payload) {

    private static final AtomicLong SEQ = new AtomicLong();

    public static BotEvent of(JsonElement payload) {
        return new BotEvent(SEQ.incrementAndGet(), System.currentTimeMillis(), payload);
    }
}
