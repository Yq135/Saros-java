package com.kairon.saros.common;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 工具：emitter 创建（300s 超时，对齐 spring.mvc.async.request-timeout）与事件发送。
 *
 * <p>帧格式与阶段二一致：event: {name} + data: {紧凑 JSON，中文不转义}（前端 parseSSEBlock 兼容）。
 * {@link Channel} 为发送抽象（QaSseSink 依赖它，单测用记录实现）。
 */
public final class SseEmitterHelper {

    public static final long TIMEOUT_MS = 300_000L;

    private SseEmitterHelper() {
    }

    /** 事件发送通道（QaSseSink 的依赖抽象；生产实现直通 SseEmitter）。 */
    public interface Channel {

        void send(String event, String dataJson) throws IOException;

        void complete();
    }

    public static SseEmitter newEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    public static Channel channel(SseEmitter emitter) {
        return new Channel() {
            @Override
            public void send(String event, String dataJson) throws IOException {
                SseEmitterHelper.send(emitter, event, dataJson);
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        };
    }

    public static void send(SseEmitter emitter, String event, String dataJson) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(dataJson, MediaType.APPLICATION_JSON));
    }
}
