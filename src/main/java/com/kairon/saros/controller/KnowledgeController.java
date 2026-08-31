package com.kairon.saros.controller;

import com.kairon.saros.service.KnowledgeService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.kairon.saros.dto.KnowledgeDtos.CreateRequest;
import static com.kairon.saros.dto.KnowledgeDtos.ListOut;
import static com.kairon.saros.dto.KnowledgeDtos.Out;
import static com.kairon.saros.dto.KnowledgeDtos.SearchOut;
import static com.kairon.saros.dto.KnowledgeDtos.SearchRequest;
import static com.kairon.saros.dto.KnowledgeDtos.UpdateRequest;

/**
 * 模块四 API（契约对齐阶段二 OpenAPI 基线 /api/knowledge、/api/tags）。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    @Resource
    private KnowledgeService service;

    @PostMapping("/knowledge")
    @ResponseStatus(HttpStatus.CREATED)
    public Out create(@Valid @RequestBody CreateRequest req) {
        return service.create(req);
    }

    @GetMapping("/knowledge")
    public ListOut list(@RequestParam(defaultValue = "") String q,
                        @RequestParam(defaultValue = "") String tag,
                        @RequestParam(required = false) Integer mastery,
                        @RequestParam(defaultValue = "1") int page,
                        // 显式指定蛇形参数名：契约是 page_size（FastAPI 风格），与 Java 参数名 pageSize 不同
                        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return service.list(q, tag, mastery, page, pageSize);
    }

    @GetMapping("/knowledge/{kid}")
    public Out get(@PathVariable long kid) {
        return service.get(kid);
    }

    @PutMapping("/knowledge/{kid}")
    public Out update(@PathVariable long kid, @Valid @RequestBody UpdateRequest req) {
        return service.update(kid, req);
    }

    @DeleteMapping("/knowledge/{kid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long kid) {
        service.delete(kid);
    }

    @PostMapping("/knowledge/search")
    public SearchOut search(@Valid @RequestBody SearchRequest req) {
        return service.semanticSearch(req);
    }

    @GetMapping("/tags")
    public List<String> suggestTags(@RequestParam(defaultValue = "") String q) {
        return service.suggestTags(q);
    }
}
