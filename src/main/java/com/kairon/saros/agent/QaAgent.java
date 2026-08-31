package com.kairon.saros.agent;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * QA Agent 接口（AiServices 动态代理）。
 *
 * <p>@SystemMessage 不在接口上声明：守卫重试需要注入不同的系统文案
 * （基础版 / 强化版），由 QaAgentFactory.create(systemMessage, ctx) 动态装配。
 */
public interface QaAgent {

    TokenStream answer(@UserMessage String userMessage);
}
