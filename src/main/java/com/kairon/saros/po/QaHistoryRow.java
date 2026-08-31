package com.kairon.saros.po;

/**
 * 查询投影（qa_messages 历史行，非纯表对象）：
 * QaMessageMapper.recentHistory 的返回行（question + answer），
 * 用于多轮对话上下文注入（回答在 service 层截断 1000 字，对齐阶段二 load_history）。
 */
public class QaHistoryRow {

    public String question;
    public String answer;
}
