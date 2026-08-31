package com.kairon.saros.controller;

import com.kairon.saros.common.SseEmitterHelper;
import com.kairon.saros.dto.QaDtos;
import com.kairon.saros.service.QaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 联网问答 REST 端点（路径/方法/状态码对齐 openapi-phase2.json）。
 */
@RestController
@RequestMapping("/api")
public class QaController {

    private final QaService qaService;

    public QaController(QaService qaService) {
        this.qaService = qaService;
    }

    /**
     * 提问/追问（SSE 流）：事件 start（来源+引用沉淀+conversation_id）→ delta（答案增量）
     * → done（完整答案+推荐标签）；失败 error（detail 中文提示）。成功与业务失败均为 HTTP 200。
     *
     * <p>422 校验（问题长度 1-2000）在 SSE 建立前以普通 JSON 返回（对齐 FastAPI 语义）；
     * 流编排在虚拟线程执行，controller 立即返回 emitter。
     */
    @PostMapping("/qa/ask")
    public ResponseEntity<SseEmitter> ask(@Valid @RequestBody QaDtos.AskRequest req) {
        QaService.validateQuestion(req.question());
        SseEmitter emitter = SseEmitterHelper.newEmitter();
        Thread.ofVirtual().start(() -> qaService.ask(req.question(), req.conversationId(), emitter));
        return ResponseEntity.ok()
                // 对齐阶段二 SSE_HEADERS：禁用缓存与代理缓冲，保证流式即时到达
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @GetMapping("/qa/conversations")
    public List<QaDtos.ConversationOut> listConversations(
            @RequestParam(name = "q", defaultValue = "") String q) {
        return qaService.listConversations(q);
    }

    @GetMapping("/qa/conversations/{cid}")
    public QaDtos.ConversationDetail getConversation(@PathVariable("cid") long cid) {
        return qaService.getConversation(cid);
    }

    @DeleteMapping("/qa/conversations/{cid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(@PathVariable("cid") long cid) {
        qaService.deleteConversation(cid);
    }
}
