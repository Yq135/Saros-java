# Saros 阶段三实施计划（Java 生产化）

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-08-21 | 初稿：G2 触发、阶段三启动；技术选型（LangChain4j / ONNX 嵌入 / Java 21）与 J0-J5 里程碑、验收标准 |
| v0.2 | 2026-08-21 | **J0 完成**：pom 全量替换（LangChain4j 1.19.0 核心包手动装配 / Java 21 / 虚拟线程）、契约基线导出 `docs/openapi-phase2.json`（17 端点，真实路径为 bilibili/tasks、settings 等）、契约 smoke + health（含 PG）测试全绿；/actuator/health 实跑 UP（db: PostgreSQL UP） |
| v0.3 | 2026-08-30 | **J1 完成**：ONNX 导出（CLS 池化，自检余弦 1.0）+ 自研 BertWordPieceTokenizer（13 组校准样本 token 全一致、余弦 ≥0.999）+ knowledge/tags API（契约对齐）+ 15 测试全绿 + **零迁移验证**（真实库 4 条阶段二沉淀可读可检索，语义排名正确） |

> 本文档是 Saros **阶段三（Java 生产化）**的详细技术计划，对应 [ROADMAP.md](ROADMAP.md) §5。
> 阶段二计划见 Saros-python 仓库 `docs/PLAN.md`；需求与验收基线见 [REQUIREMENTS.md](REQUIREMENTS.md)（v0.12）。

## 1. 背景与目标

阶段二（Saros-python：FastAPI + Vue 3 + PG/pgvector）已完成四大模块闭环（REQUIREMENTS §10 四条核心闭环通过）。**决策门 G2 已触发（2026-08-21 用户拍板）**，启动阶段三：以 Java（Spring Boot）重建后端，绞杀者模式逐步接管 `/api/*`，前端无感知，最终 Python 服务退役归档。

**本阶段目标**：

1. Java 后端逐模块接管阶段二全部 API（契约逐字段对齐）
2. PG 表结构不变、数据零迁移，阶段二沉淀资产直接可用
3. QA 问答以 LangChain4j Agent 重写（联网搜索 + 沉淀检索为工具，模型自主规划）
4. 工程化能力：Actuator、模型路由、配置热刷新、虚拟线程异步任务
5. 出口：前端仅改 vite proxy 指向 Java 服务，Saros-python 归档退役

**非目标（明确不做）**：多用户/账号体系、Spring Security（单用户本地，NFR-6）、微服务拆分（单体优先，按领域分包保留边界）、Redis 缓存（出现瓶颈再上）、云端部署。

## 2. 已确认决议（2026-08-21，用户拍板）

1. ✅ **LLM 框架：LangChain4j**（替代脚手架中的 Spring AI）。DeepSeek 走 OpenAI 兼容模块（`langchain4j-open-ai` + `baseUrl=https://api.deepseek.com/v1`，`deepseek-chat`）；流式 + 工具调用需设 `accumulateToolCallId(false)`（DeepSeek 分块返回完整 toolCallId 的兼容性要求）
2. ✅ **Agent 范围：QA 全 agent 化**（搜索/沉淀检索为 @Tool，模型自主规划调用，系统提示强制「先取资料再作答」+ 后端守卫兜底）；大纲/出题/标签保持确定性管线 + AiServices 结构化输出
3. ✅ **嵌入：ONNX 本地推理**（一次性从 Python 导出 bge-small-zh-v1.5 → ONNX + vocab.txt，导出脚本入库；Java 侧 onnxruntime + BERT 分词器，CPU 推理）——同模型同 512 维，与阶段二向量数据无缝兼容，Python 完全退役
4. ✅ **前端：留在 Saros-python 仓库**，代码不动，仅 J5 切换时改 vite.config proxy 指向 `127.0.0.1:8080`
5. ✅ **Java 21**（虚拟线程可用；Spring Boot 保持 4.0.8）
6. ✅ 沿用阶段二：MyBatis + pgvector-java、单体分包、Spring Security/Redis 暂不引入（ROADMAP §5.2）

## 3. 技术选型

| 用途 | 选择 | 说明 |
|---|---|---|
| 框架 | Spring Boot 4.0.8 + Java 21 | 保持现有脚手架版本；`spring.threads.virtual.enabled=true` |
| LLM / Agent | **LangChain4j 1.19.0** 核心包（`langchain4j` + `langchain4j-open-ai`，BOM 管理版本），手动 Builder 装配模型 bean | 移除 Spring AI；Spring starter 线仍是 beta（1.19.0-beta29）且热刷新需自建 bean，故不用 starter 自动配置（J0 已落地） |
| QA Agent | AiServices + @Tool（SearchTool / KnowledgeTool）+ OpenAiStreamingChatModel | 守卫式 agent 设计见 §5.2 |
| 数据访问 | MyBatis + `com.pgvector:pgvector` + VectorTypeHandler | KNN 用 `<=>`（cosine distance），SQL 语义与阶段二一致 |
| 嵌入 | onnxruntime（`com.microsoft.onnxruntime:onnxruntime`）+ DJL HuggingFace Tokenizers（BertTokenizer + vocab.txt） | 导出脚本 `scripts/export_bge_onnx.py`（池化+归一化固化进图）；模型文件 `data/models/`（gitignore） |
| 分词 | jieba-analysis（`com.huaban:jieba-analysis`） | 对齐阶段二 jieba 行为（关键词重叠/标签命中打分用） |
| 搜索 | SearchProvider 接口；DuckDuckGo HTML 端点（jsoup）+ Bing/百度抓取兜底 | 合并去重 top 8-10，对齐阶段二 |
| 网页抽取 | Jina Reader（`r.jina.ai/{url}`）主力 + jsoup 兜底 | ⚠️ trafilatura 为 Python 库、无 Java 等价物，顺序与阶段二对调，见 §10 待确认 |
| 视频 | yt-dlp / ffmpeg 外部 CLI（ProcessBuilder） | 独立命令行工具，与「Python 服务退役」不冲突；yt-dlp 锁版本 |
| ASR | Spring RestClient 调自建 mlx-qwen3-asr（OpenAI 兼容，multipart 上传音频） | 仅音频模式启用 |
| 流式 | Spring MVC `SseEmitter` | 不引 WebFlux；SSE 事件契约与阶段二逐字段对齐 |
| 异步任务 | `@Async` + 单线程执行器（同时只跑一个视频任务） | DB 状态机为唯一真相源（对齐阶段二） |
| 配置 | application.yml（启动配置）+ `data/saros.env`（设置页热刷新） | 键名与阶段二 .env 一致；见 §5.6 |
| 监控 | Spring Boot Actuator（health/metrics） | Prometheus/Grafana 按需（J4） |
| 测试 | JUnit + Spring Boot Test + Testcontainers（PG+pgvector）+ 契约测试 | 契约基线 = 阶段二导出的 OpenAPI |

## 4. 项目结构（com.kairon.saros）

DDD 分层约定（J1 起生效）：表对象放 `po/`（一张表一个类）；`mapper/` 下 Java 接口只放操作表的抽象函数定义（@Mapper + @Param，无 SQL），SQL 实现在 `src/main/resources/mapper/*.xml`（namespace 对应接口全限定名，一表一文件）；`dto/` 放 API 契约对象；`service/` 放领域/应用服务；`controller/` 只做参数绑定与路由。单体优先、按层分包保留领域边界。

```
src/main/java/com/kairon/saros/
  SarosApplication.java
  config/            # AppConfig、VirtualThreadConfig、MyBatis 配置、MediaResourceConfig
  llm/               # LangChain4j 装配：DeepSeek 模型 bean（流式/非流式）、
                     #   PromptTemplates（prompts.py 全量移植）、ModelRouter（J4）
  agent/             # QaAgent 接口（@SystemMessage + @Tool）、SearchTool、KnowledgeTool、AgentGuard
  qa/                # QaController(SSE)、QaService（J2）
  search/            # SearchProvider 接口 + DuckDuckGoProvider/BingProvider/BaiduProvider/SearchFacade
  retrieval/         # 混合检索：KnnCandidateQuery、LexicalScorer（jieba）、TagScorer、HybridRanker（0.6/0.3/0.15）
  embed/             # OnnxEmbedder、BertWordPieceTokenizer、EmbeddingService
  webpages/          # WebpageService、JinaReaderClient、JsoupExtractor、QuestionGenerator（J3）
  videos/            # VideoTaskService（状态机）、YtDlpRunner、SubtitleParser、
                     #   AsrClient、AudioSlicer、OutlineGenerator、VideoQuestionGenerator、StartupSweeper（J3）
  settings/          # SettingsService（热刷新，J4）
  media/             # /media/** HTTP Range 映射（J3）
  po/                # 表对象：users/manual_knowledge/tags/embeddings（一张表一个类）+ 查询投影
  mapper/            # MyBatis 接口（仅方法签名，无 SQL）
  service/           # KnowledgeService、UserService 等
  controller/        # KnowledgeController、HealthController 等
  dto/               # API 契约 DTO（对齐 OpenAPI 基线）
  common/            # 异常处理、JSON 工具、SseEmitterHelper
src/main/resources/
  application.yml    # server.port=8080、PG、虚拟线程、/media 映射
  mapper/            # Mapper XML（namespace 对应 mapper/ 下接口，一表一文件）
scripts/             # export_bge_onnx.py、openapi_export.sh（导出阶段二 OpenAPI）
data/                # gitignore：saros.env、cookies.txt、models/、media/{bvid}/
```

## 5. 核心设计

### 5.1 嵌入链路（J1 前置）

- **导出（一次性，Python 环境执行）**：`scripts/export_bge_onnx.py` 加载 `BAAI/bge-small-zh-v1.5` → 自定义 forward（token_ids + attention_mask → **CLS 池化**（模型 ST 配置 pooling_mode_cls_token=True，实测均值池化余弦仅 0.925 不可用）→ L2 归一化）固化进 ONNX 图 → 输出 `data/models/bge-small-zh-v1.5/model.onnx` + `vocab.txt` + 校准样本（原文 + token_ids + Python 侧 512 维向量 JSON）。
- **Java 推理**：BertTokenizer（vocab.txt，含 CJK 逐字切分）→ ONNX 前向 → 与 Python 输出余弦 ≥ 0.999 对齐测试。
- 查询侧沿用 BGE 前缀（阶段二同款），KNN 走 `embeddings.embedding <=> :qvec`。

### 5.2 QA Agent 设计（J2）

- `QaAgent`（AiServices）：`@SystemMessage` 强约束——①必须调用 `search` 与 `knowledge` 两个工具取得资料后才作答；②引用 `[n]` 对应 search 结果编号；③沉淀权威性高于搜索结果，冲突以沉淀为准并说明；④资料不足明说、绝不编造（阶段二 ANSWER_SYSTEM 全量移植，含「温柔陪伴」语气）。
- 工具：`SearchTool`（question → 合并去重 top 8-10 来源）、`KnowledgeTool`（question → 混合检索 top 5 沉淀笔记）。
- **AgentGuard 守卫**（保障 FR-1.7 降级与引用质量）：监听流式事件——首轮未调用工具即作答 → 注入「必须先使用工具获取资料」系统提示重试 1 轮；仍不调用 → 回退确定性兜底管线（代码强制 search + retrieve 后普通 chat 合成）。搜索全挂但有沉淀命中 → 仅基于沉淀回答并注明；两者均不可用 → 守卫直接短路为明确报错文案。
- 流式：OpenAiStreamingChatModel + `accumulateToolCallId(false)`；SSE 事件 `start`（来源 + 引用沉淀 id + conversation_id）→ `delta`（答案流）→ `done`（完整答案 + suggested_tags）。
- 多轮：每轮 agent 独立执行（fresh 状态），历史最近 6 轮（问题全文 + 回答截断 1000 字）作为上下文文本注入；推荐标签仅会话首轮 done 后一次轻量 AiService 调用。

### 5.3 混合检索（J2，算法与阶段二完全一致）

KNN 取 50 候选 → 标签命中 + jieba 关键词重叠打分 → `0.6*cosine + 0.3*lex_overlap + 0.15*tag_hit`，阈值 0.35 取 top 5；全低于阈值不带沉淀（避免噪音）。

### 5.4 B 站任务状态机（J3）

```
PENDING → PROCESSING（downloading 0-60 → audio_fallback 60-75（无CC/AI字幕时）→ outlining 75-90 → questions 90-98）→ SUCCESS(100)
任意步失败 → FAILED（error_msg；retry 已下载文件跳过 = 断点续跑）
```

- 步骤（与阶段二一致）：URL 校验（BV 正则 + b23.tv 解析，非 B 站立即报错）→ yt-dlp（cookie、字幕 CC→ai-zh 三级优先级、skip_subtitle 开关、720p 上限）→ 有字幕：解析 vtt/srt/json3 → segments 入 `video_segments` → **字幕/转写全文一次喂 LLM 出大纲**（超长截断保护；时间戳锚点只允许文本中真实存在的时间点，防幻觉）→ 5-8 题带 ts；无字幕：audio-only 流 → ffmpeg ≈5 分钟切片 → ASR verbose_json segments（精确时间戳）→ 共用大纲/出题流程。
- 单线程执行器保证「同时只跑一个视频任务」；服务启动清扫 PROCESSING → FAILED（"服务重启中断"）；删除任务级联删视频知识 + 清理 media 文件。
- 媒体文件：`data/media/{bvid}/`，`/media/**` 支持 HTTP Range（B 站 iframe 播放器拖动需要）。

### 5.5 网页出题（J3）

Jina Reader 取正文（失败 jsoup 兜底）→ AiService 一次生成 3-5 题 + 参考答案 + 推荐标签（JSON 结构化输出，解析失败重试 1 次）→ 入库 `web_articles` + `webpage_questions`。

### 5.6 设置页热刷新（J4）

`GET/PUT /api/settings` 契约对齐阶段二：可编辑项 LLM_* / ASR_* / COOKIE_PATH / SKIP_SUBTITLE；PUT 写 `data/saros.env`（行级更新保留注释）+ 进程内热刷新（LLM/ASR 客户端重建，无需重启）；B 站 cookie 在线校验登录态（`/api/settings/cookie/check`）。

### 5.7 ModelRouter（J4，可选增强）

DelegatingChatModel：主模型（DeepSeek）超时/限流/5xx → 自动切换备用模型 → 探测恢复。备用走同一 OpenAI 兼容协议（`LLM_BACKUP_*` 配置）。

## 6. API 契约对齐（零漂移防线）

- **契约基线**：从 Saros-python 导出 OpenAPI JSON（`scripts/openapi_export.sh`）存 `docs/openapi-phase2.json`。
- **契约测试**：JUnit 起真实 Boot 服务，对每个端点用基线断言请求路径/方法/响应结构（字段名、类型、SSE 事件序列）；自写断言即可（spring-cloud-contract 过重）。
- **前端不动 = 契约测试是唯一防线**：前端零改动跑通即验收。
- **契约清单**（前缀 /api，**J0 已导出真实基线 `docs/openapi-phase2.json`（17 个端点），后续以此为准**）：`POST /qa/ask`（SSE）、`GET /qa/conversations` + `GET,DELETE /qa/conversations/{cid}`、`POST,GET /knowledge` + `GET,PUT,DELETE /knowledge/{kid}` + `POST /knowledge/search`（语义查询）、`GET /tags`、`POST,GET /webpages` + `GET,DELETE /webpages/{aid}` + `POST /webpages/{aid}/regenerate`、`POST,GET /bilibili/tasks` + `GET,DELETE /bilibili/tasks/{tid}` + `POST /bilibili/tasks/{tid}/retry`、`GET,PUT /settings` + `GET,PUT /settings/cookie` + `POST /settings/cookie/check`、`GET /health`；媒体文件服务不在 OpenAPI 基线（FastAPI StaticFiles 不入 schema），对齐以阶段二实际行为为准（`data/media/{bvid}/`、支持 HTTP Range）。

## 7. 里程碑与验收条件

| 里程碑 | 内容 | 验收条件 |
|---|---|---|
| **J0 骨架**（0.5-1 天）✅ 2026-08-30 完成 | pom 替换（去 Spring AI、加 LangChain4j 核心包 / pgvector / onnxruntime / jieba / jsoup）、Java 21、application.yml、虚拟线程、Actuator health、契约测试基座、OpenAPI 导出 | ① `mvn spring-boot:run` 起服务 ✅；② `/actuator/health` UP 且 PG 检查通过 ✅；③ 契约框架示例用例跑通 ✅ |
| **J1 knowledge/tags**（1-2 天）✅ 2026-08-30 完成 | 嵌入导出脚本 + OnnxEmbedder + 对齐测试；knowledge/tags API（分页/标签/掌握度筛选/语义查询）；embeddings 读写 | ① 13 组校准样本 Java vs Python 余弦 ≥ 0.999（自检 1.0）✅；② PG 独立 schema 沙箱集成：录入→嵌入→KNN 命中 ✅（本机无 Docker，Testcontainers 留作 CI 备选）；③ **零迁移验证**：真实库 4 条阶段二沉淀可读、语义检索排名正确 ✅；④ /api/knowledge、/api/tags 契约测试通过 ✅ |
| **J2 qa agent**（2-3 天）✅ 2026-08-30 完成 | 三搜索源 + 混合检索 + QaAgent（@Tool + 守卫 + SSE 流式）+ 多轮会话 + 推荐标签 | ① SSE 事件序列（start/delta/done）契约对齐 ✅（QaAgentIntegrationTest 9 用例：事件字段名/顺序、error 场景、422 前置）；② FakeSearch/FakeLLM 集成：断言两工具被调用、引用编号正确、标签仅首轮 ✅；③ 守卫测试：模拟模型拒绝调工具 → 重试 1 轮（强化系统提示）→ 兜底管线仍产出合规回答 ✅；④ live：真实 DeepSeek（deepseek-v4-flash）提问 + 同会话追问上下文连贯 ✅（QaLiveTest，SAROS_LIVE=1） |
| **J3 webpages/videos**（3-5 天） | 网页抽取/出题；B 站全管线（yt-dlp CLI / 字幕三级优先级 / ASR 音频模式 / 大纲 / 出题 / 状态机 / retry / 启动清扫 / 级联删除）+ /media Range | ① 字幕视频 + 无字幕短视频（走 ASR）各一条 live 全流程成功；② 中断重启 → 中间态标记 FAILED → retry 断点续跑成功；③ 删除任务 → 媒体文件清理 + 视频知识级联删除；④ /api/webpages、/api/bilibili/tasks 契约测试通过 |
| **J4 工程化**（1-2 天） | ModelRouter 主备切换、/api/settings 设置热刷新、LLM 超时/重试打磨（JSON 失败重试 1、超时 300s 重试 1） | ① 主模型配错 key → 自动切换备用仍可回答；② 设置页改 LLM key 即时生效（无需重启）；③ cookie 校验接口对齐；④ 全部契约测试绿 |
| **J5 切换**（0.5 天） | vite proxy 改指 127.0.0.1:8080；对照验收；Saros-python 归档 | 见 §8 出口验收 |

## 8. 阶段三出口验收标准（对应 REQUIREMENTS §10 四条核心闭环 + 工程化）

1. **契约验收**：openapi-phase2.json 全端点对齐，契约测试全绿；前端零改动跑通。
2. **数据验收**：共用 PG 零迁移——阶段二已沉淀的手打笔记/问答历史/视频知识在 Java 服务下均可检索、引用、展示。
3. **功能验收**（REQUIREMENTS §10，Java 后端复验）：
   1. 录入知识点：禁粘贴、手打保存、重启数据仍在
   2. 提问：先出来源与沉淀引用 → 答案流式带引用 → 自动推荐标签 → 同会话多轮追问上下文连贯 → 历史可查可删
   3. 网页出题：URL → 正文 + 3-5 题 + 标签入库
   4. B 站：链接 → 归档 → 带时间戳大纲（无字幕走 ASR 音频模式）→ 点击时间点跳转 → 中断可重试续跑
4. **工程化验收**：Actuator health（含 PG）；视频任务串行（同时只跑一个）；服务重启中间态清扫；设置热刷新即时生效；模型路由自动切换。
5. **退役验收**：Saros-python 打 `v2-archive` tag + README 归档说明；本地起法从 dev.sh 替换为 Java 启动说明。

## 9. 验证策略

- **单测（无网络）**：BV/b23 链接校验、vtt/srt/json3 字幕解析、时间戳解析、混合打分、状态机（FakeExecutor）、Prompt 渲染、分词行为标定
- **嵌入对齐测试**：10 条中文校准样本，Java/ONNX vs Python/sentence-transformers 余弦 ≥ 0.999（CI 级断言）
- **集成（Testcontainers PG+pgvector）**：knowledge CRUD + 真实 KNN；qa agent 注入 FakeSearch/FakeLLM 断言工具调用与 RAG 注入；任务状态机流转
- **契约测试**：§6 基线全端点
- **live 标记（默认 skip）**：真实 DeepSeek、真实搜索、真实 yt-dlp 短视频、真实 ASR
- **并行对照（J5 前）**：同一组 curl/场景分别打 8000（Python）与 8080（Java），diff 响应结构；前端分别挂两后端人工走查

## 10. 待确认事项

1. ⚠️ **网页抽取顺序**：Java 无 trafilatura，拟 Jina Reader 主力 + jsoup 兜底（与阶段二顺序对调，Jina 免费额度可能有限）——需拍板
2. G2 触发原因：ROADMAP §5.1 补录（对外服务？性能瓶颈？工程化实践？）
3. 契约测试实现：拟自写断言（不引 spring-cloud-contract）——低风险，默认执行
4. 设置页热刷新写入文件：拟 Java 独立 `data/saros.env`（键名与阶段二 .env 一致），切换期两后端各写各的文件互不干扰
5. 大纲超长截断保护的具体上限：从 Saros-python video_task.py 提取沿用
6. ModelRouter 备用模型：是否配置第二个模型（如 qwen），或仅作超时重试增强——J4 时定
7. Prometheus/Grafana：J4 先 Actuator，出现观测需求再上

## 11. 风险与注意

- LangChain4j Boot 4 starter 较新（1.17.x），遇兼容问题可退 `langchain4j` 核心包手动装配模型 bean（DeepSeek 模型用 Builder 而非自动配置，规避 starter 差异）
- DeepSeek 流式工具调用：`accumulateToolCallId(false)` 必须设置，否则 agent 工具调用流式解析出错
- DeepSeek 单次输出默认上限（4K）：大纲/出题注意 max_tokens（沿用阶段二经验值）
- jieba-analysis 与 Python jieba 分词存在细微差异 → 混合打分允许轻微偏差，用「分词标定测试 + 对照阈值」把关（打分结果与阶段二同组 query 对照，top 5 重合率 ≥ 80%）
- ONNX 导出一次性依赖 Python 环境：导出脚本与产物校验样本入库，Java 侧永远有对齐断言兜底
- yt-dlp 升级破坏 stdout 进度解析：锁版本 + 解析容错（解析失败仅丢进度不丢功能）
- 免费搜索源限流：多 provider 兜底 + 搜索全挂降级路径（守卫短路）
- B 站 cookie 失效/403：明确提示更新（错误信息对齐阶段二文案）
- 媒体文件服务 Range：切换期检查 vite proxy 对 Range 头的透传
