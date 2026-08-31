package com.kairon.saros.service;

/**
 * 问答流中止异常：以 SSE error 事件返回（HTTP 仍 200），detail 为中文提示
 * （阶段二 QAAbort 语义；非 HTTP 状态码语义，勿与 ApiException 混用）。
 */
public class QaAbortException extends RuntimeException {

    private final String detail;

    public QaAbortException(String detail) {
        super(detail);
        this.detail = detail;
    }

    public String getDetail() {
        return detail;
    }
}
