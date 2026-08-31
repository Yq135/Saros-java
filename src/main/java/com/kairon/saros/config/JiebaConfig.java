package com.kairon.saros.config;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jieba 分词器单例 Bean（词典只读，跨请求复用安全；混合检索打分用）。
 */
@Configuration
public class JiebaConfig {

    @Bean
    JiebaSegmenter jiebaSegmenter() {
        return new JiebaSegmenter();
    }
}
