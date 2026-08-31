package com.kairon.saros.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内部 JSON 工具（JSONB 列 / SSE 事件 data 序列化共用）。
 *
 * <p>Boot 4 的 HTTP 消息转换走 Jackson 3（tools.jackson，自动装配的也是 Jackson 3 的
 * ObjectMapper），故此处显式注册 Jackson 2 实例仅供 service 内部使用；两版均识别
 * {@code com.fasterxml.jackson.annotation.JsonProperty} 蛇形注解，序列化结果一致。
 */
@Configuration
public class JsonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
