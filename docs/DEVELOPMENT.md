# Saros-java 开发规范（DDD 分层与 MyBatis XML 流程）

> 版本：v0.3（2026-08-31，J2 完成后修订）。**本文档是后续所有里程碑（J3-J5）开发 agent 必读的开发约定**，与 [PLAN.md](PLAN.md)（里程碑计划）、[数据库设计说明.md](数据库设计说明.md)（表结构）、[openapi-phase2.json](openapi-phase2.json)（API 契约基线）配套使用。若本文档与 PLAN.md 冲突，以 PLAN.md 为准并同步修订本文档。

## 1. 架构原则

1. **单体优先，DDD 分层**：按层分包（controller/dto/service/mapper/po + 领域专属包），保留领域边界（见 ROADMAP §5.1）。不引微服务、不做多模块。
2. **契约优先**：一切 API 行为以 `docs/openapi-phase2.json`（阶段二 FastAPI 导出）为唯一基线，前端零改动是对齐的最终验收。
3. **零迁移**：PG 表结构不变（以仓库根 `db_init.sql` 为权威），只写不改；阶段二沉淀数据直接可用。
4. **SQL 不进 Java**：mapper 目录下的 Java 接口只有方法签名，SQL 一律写在 `src/main/resources/mapper/*.xml`。
5. **一张表一个 PO、一个 Mapper（接口 + XML）**，按表拆分数据访问。

## 2. 分层结构与职责

```
src/main/java/com/kairon/saros/
  controller/   # 仅路由绑定与参数接收：@RestController + 路径/方法/状态码与契约基线一致，不含业务逻辑
  service/      # 领域/应用服务：业务规则、事务边界（@Transactional）、异常抛出
  dto/          # API 契约对象：record + @JsonProperty 蛇形字段名，逐字段对齐 openapi-phase2.json
  po/           # 表对象：一张表一个类（users/manual_knowledge/tags/embeddings...）+ 查询投影
  mapper/       # MyBatis Java 接口：仅抽象方法签名（@Mapper + @Param），禁止 SQL 注解
  common/       # 横切：异常（ApiException/GlobalExceptionHandler）、JSON 工具、SseEmitterHelper
  config/       # Spring 配置装配
  embed/        # 嵌入：OnnxEmbedder、BertWordPieceTokenizer、EmbeddingService
  llm/ agent/ search/ retrieval/ webpages/ videos/ settings/ media/   # 领域专属包（按 PLAN.md §4 逐步落地）
src/main/resources/
  application.yml    # 端口 8080、PG（环境变量占位）、虚拟线程、mybatis.mapper-locations
  mapper/            # Mapper XML：namespace 对应 mapper/ 下接口全限定名，一表一文件
```

**职责红线**：controller 不做校验业务/拼装；service 不写 SQL 字符串、不碰 HTTP 语义（状态码）；mapper 不出现 SQL 注解；po 不放 API 序列化注解（@JsonProperty 属于 dto）。

**注入规范**：Spring bean（@Service/@Component/@RestController）一律用 **`@Resource` 字段注入**（jakarta.annotation），不写构造器注入声明；配置类 @Bean 方法参数与 @PostConstruct 装配除外（如 SearchFacade 按 SarosProperties 装配搜索源、LlmConfig 装配模型 bean）。测试类内注入沿用 @Autowired。注意 @Resource **名字优先**（字段名先按 bean 名匹配，再按类型）：需要 @Primary 假件覆盖的 bean（ChatModel/StreamingChatModel 等），真实 bean 名不得与任何 @Resource 字段名撞名（LlmConfig 已用 openAi* 前缀规避——撞名会让假件失效、注入真实模型）。

## 3. 新增一个领域模块的标准流程

以 J2 的 qa_conversations/qa_messages 为例，新增表数据访问时按以下顺序落地：

1. **查契约**：在 `docs/openapi-phase2.json` 找对应端点，记录路径/方法/请求体字段/响应体字段/状态码/SSE 事件序列。
2. **查表结构**：以仓库根 `db_init.sql` 为准（解读见 [数据库设计说明.md](数据库设计说明.md)）。**只写不改**——禁止 ALTER 已有表；新表如需创建，先在 db_init.sql 中补充并同步更新数据库设计说明。
3. **建 PO**（`po/`）：表名 → 类名（`manual_knowledge` → `ManualKnowledge`）；字段蛇形 → 驼峰；`TIMESTAMPTZ` → `OffsetDateTime`；`VECTOR` → `PGvector`。private 字段 + Lombok `@Data`（见 §4）。
4. **建 Mapper 接口**（`mapper/`）：`@Mapper` 接口，方法只签名；多参数一律 `@Param`；Javadoc 注明「SQL 实现见 src/main/resources/mapper/XxxMapper.xml」。
5. **写 XML**（`resources/mapper/`）：文件名与接口同名；`<mapper namespace="com.kairon.saros.mapper.XxxMapper">`；SQL 语义对齐阶段二实现（见 §5 细则）。
6. **建 DTO**（`dto/`）：字段名与基线蛇形一致，用 `@JsonProperty` 显式声明；校验注解对齐 FastAPI 语义（见 §6）。
7. **建 Service**（`service/`）：@Resource 字段注入；事务边界 @Transactional；异常走 ApiException / ValidationException（见 §7）。
8. **建 Controller**（`controller/`）：路径/方法/状态码对齐基线；蛇形查询参数用 `@RequestParam(name = "page_size")` 显式声明。
9. **写测试**：集成测试走独立 schema 沙箱 + 契约断言（见 §8）。
10. **验证**：带 PG 环境变量跑全量测试（见 §9），全绿后更新 PLAN.md 对应里程碑勾选。

## 4. PO 规范（po/）

- 一张表一个类，类名 = 表名转大驼峰：`users` → `User`、`manual_knowledge` → `ManualKnowledge`、`tags` → `Tag`、`embeddings` → `Embedding`。
- 字段 = 表列蛇形转驼峰，**private 字段 + Lombok `@Data`**（getter/setter 自动生成）；service 层一律经 setter 赋值、getter 读取，禁止直接字段访问；MyBatis 经 setter 映射列（map-underscore-to-camel-case 与 useGeneratedKeys 均走 setter，无需 XML 改动）。类型映射：

| PG 类型 | Java 类型 |
|---|---|
| BIGSERIAL / BIGINT | long / Long |
| INT | int / Integer |
| VARCHAR / TEXT | String |
| TIMESTAMPTZ | OffsetDateTime |
| VECTOR(512) | PGvector（com.pgvector） |
| TEXT[] / JSONB / BIGINT[] | String[] / String / Long[]（用到时再定） |

- 查询投影（非表对象，如 KNN 命中带计算列 similarity）也放 po/，Javadoc 注明「查询投影，非纯表对象」（KnnHit/QaConversationListItem/QaHistoryRow 等）；同样 private + @Data。
- PO 不写任何序列化注解；API 输出一律经 service 转 dto。

## 5. Mapper 规范

### 5.1 Java 接口（mapper/）

```java
@Mapper
public interface ManualKnowledgeMapper {

    int insertKnowledge(ManualKnowledge row);          // 单 PO 参数：直接传对象

    ManualKnowledge findById(@Param("id") long id, @Param("userId") long userId);  // 多参数必须 @Param

    List<ManualKnowledge> listPage(@Param("userId") long userId, @Param("q") String q, ...);
}
```

- 禁止 `@Select/@Insert/@Update/@Delete` 注解 SQL、禁止内嵌行类。
- 只放「操作表的函数定义」，行模型/结果模型一律引用 po/ 下的类。
- 方法命名沿用现有习惯：`insertXxx / updateXxx / deleteXxx / findById / findByIds / listPage / countFiltered` 等，Javadoc 写清语义对齐点（如「标签按 tags.id 升序，对应阶段二 array_agg ORDER BY t.id」）。

### 5.2 XML 实现（resources/mapper/）

- `namespace` 必须等于接口全限定名；文件名与接口同名（`ManualKnowledgeMapper.xml`）。
- `application.yml` 已配置 `mybatis.mapper-locations: classpath*:mapper/*.xml` 与 `map-underscore-to-camel-case: true`——列名蛇形自动映射 PO 驼峰字段，`resultType` 直接写 PO 全限定名，无需 resultMap（除非类型特殊）。
- `resultType` 简写别名可用：`long`、`string`、PO 全限定名。

**必须注意的细节**：

| 场景 | 写法 |
|---|---|
| pgvector 余弦距离 `<=>` | XML 内转义为 `&lt;=&gt;`（`ORDER BY embedding &lt;=&gt; #{vector}`） |
| 自增主键回填 | `<insert id="..." useGeneratedKeys="true" keyProperty="id">` |
| 动态条件 | 原生 `<if test="...">` / `<foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>`（注解时代的 `<script>` 包装不再需要） |
| PGvector 参数 | 直接 `#{vector}`——PGvector 继承 PGobject，JDBC 驱动可直接序列化，无需自定义 TypeHandler |
| 排序/分页稳定 | 列表统一 `ORDER BY updated_at DESC, id DESC` + `LIMIT #{limit} OFFSET #{offset}`（对齐阶段二） |

### 5.3 一个查询涉及多表怎么办

SQL 里直接 JOIN / EXISTS 子查询（如 manual_knowledge 列表按标签过滤 EXISTS tags），**不因 JOIN 拆方法**——XML 归属按主表定（主表是 manual_knowledge 就放 ManualKnowledgeMapper.xml）。

## 6. DTO 规范（dto/）

- record 定义，字段名与 openapi-phase2.json 蛇形一致，用 `@JsonProperty` 显式声明（对齐 [KnowledgeDtos.java](../src/main/java/com/kairon/saros/dto/KnowledgeDtos.java) 写法）。
- 校验语义对齐 FastAPI：必填用 `@NotNull(message = "Field required")`；空串 422 但纯空格放行 → `@NotNull` 配 service 内 `isEmpty()` 检查（FastAPI minLength 只数长度）；取值范围/长度 → service 内检查后抛 ValidationException。
- 一个端点一组请求/响应 record，收口在一个类里（如 `KnowledgeDtos`）或用独立文件，二选一保持一致。
- `OffsetDateTime` 序列化保持 ISO-8601 带时区（现有 Jackson 默认行为，勿改全局格式）。

## 7. 异常与响应规范

- 业务错误抛 [ApiException.java](../src/main/java/com/kairon/saros/common/ApiException.java)：`notFound("知识点不存在")`（404）、`badRequest(...)`（400）——错误文案与阶段二一致（前端依赖）。
- 校验错误抛 `ValidationException(field, message)`（经 [GlobalExceptionHandler.java](../src/main/java/com/kairon/saros/common/GlobalExceptionHandler.java) 转 422，`detail` 数组形状对齐 FastAPI）。
- controller 层状态码用 `@ResponseStatus` 声明（201 创建 / 204 删除）。
- 禁止在 service 里返回 ResponseEntity；响应体形状完全由 dto 决定。

## 8. 测试规范

| 类型 | 规则 |
|---|---|
| 集成测试 | 远程 PG 上建**独立 schema `saros_test` 沙箱**（`currentSchema=saros_test,public`，`@DynamicPropertySource` 覆盖 datasource url），绝不触碰真实数据；PG 不可达 → `@BeforeAll` 内 `Assumptions.assumeTrue(false, ...)` 整类跳过 |
| 依赖模型的用例 | `Assumptions.assumeTrue(Files.exists(MODEL_ONNX), ...)`，模型缺失跳过（如语义检索用例） |
| 契约测试 | 自写断言对照 `docs/openapi-phase2.json` 基线（路径/方法/字段名/类型/状态码/SSE 事件序列），不引 spring-cloud-contract |
| live 测试 | 真实 DeepSeek/搜索/yt-dlp 的用例默认 skip，手动开启 |

参照实现：[KnowledgeApiIntegrationTest.java](../src/test/java/com/kairon/saros/knowledge/KnowledgeApiIntegrationTest.java)（沙箱 + 契约断言）、[EmbeddingAlignmentTest.java](../src/test/java/com/kairon/saros/embed/EmbeddingAlignmentTest.java)（模型对齐）。

## 9. 验证命令

PG 不在本机，跑测试必须带环境变量（连接信息见下；本机无 PG 时 health 会 503、集成测试整类跳过，属环境问题）：

```bash
PG_HOST=100.109.98.117 PG_PORT=5432 PG_USER=saros PG_PASSWORD='saros#2026!' PG_DB=saros_db ./mvnw test
```

日常步骤：`./mvnw -q compile`（编译）→ 上述命令跑全量测试 → 全绿才算一个里程碑内改动完成。**改动前后测试数量与结果要对照**（J1 基线：15 个全绿）。

## 10. 日志规范（slf4j + logback）

- 门面统一 **slf4j**（`org.slf4j.Logger` / `LoggerFactory`），实现为 Spring Boot 默认 **logback**，配置见 [logback-spring.xml](../src/main/resources/logback-spring.xml)：控制台 + 滚动文件双 appender（`logs/saros.log` 按天+50MB 切分、压缩保留 30 天、总量 1GB 封顶）。
- 级别约定：业务包 `com.kairon.saros` INFO 起；关键业务节点打 INFO（如问答开始/完成：会话 id、来源/引用/标签条数、耗时），业务中断打 WARN，异常打 ERROR（附上下文参数）；第三方框架（MyBatis/Hikari/Tomcat）统一 WARN 降噪。
- 禁止 `System.out.println`；日志信息不包含敏感数据（API key 等）。`start.sh` 的 nohup 输出走 `logs/console.log`，与应用日志文件分离。

## 11. J1 参考实现索引

以下文件是全部约定的「活样例」，新模块照抄模式：

| 层 | 文件 |
|---|---|
| PO | [po/User.java](../src/main/java/com/kairon/saros/po/User.java)、[po/ManualKnowledge.java](../src/main/java/com/kairon/saros/po/ManualKnowledge.java)、[po/Tag.java](../src/main/java/com/kairon/saros/po/Tag.java)、[po/Embedding.java](../src/main/java/com/kairon/saros/po/Embedding.java)、[po/KnnHit.java](../src/main/java/com/kairon/saros/po/KnnHit.java)（查询投影示例） |
| Mapper 接口 | [mapper/ManualKnowledgeMapper.java](../src/main/java/com/kairon/saros/mapper/ManualKnowledgeMapper.java) 等 4 个 |
| Mapper XML | [resources/mapper/ManualKnowledgeMapper.xml](../src/main/resources/mapper/ManualKnowledgeMapper.xml)（动态 SQL + useGeneratedKeys）、[resources/mapper/EmbeddingMapper.xml](../src/main/resources/mapper/EmbeddingMapper.xml)（`&lt;=&gt;` 转义 + PGvector） |
| DTO | [dto/KnowledgeDtos.java](../src/main/java/com/kairon/saros/dto/KnowledgeDtos.java) |
| Service | [service/KnowledgeService.java](../src/main/java/com/kairon/saros/service/KnowledgeService.java)（事务边界 + 异常规范）、[service/UserService.java](../src/main/java/com/kairon/saros/service/UserService.java) |
| Controller | [controller/KnowledgeController.java](../src/main/java/com/kairon/saros/controller/KnowledgeController.java)（蛇形参数显式声明示例） |

## 12. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-08-30 | 初稿：J1 完成时确立 DDD 分层 + MyBatis XML 流程约定 |
| v0.2 | 2026-08-30 | J2 完成补充：① PG 数组列用自定义 TypeHandler（BIGINT[]↔Long[]、TEXT[]↔String[]，经 JDBC Array，`mybatis.type-handlers-package` 注册，样例 common/LongArrayTypeHandler）；② JSONB↔String + Jackson 手动序列化（XML 写 `CAST(#{x} AS JSONB)`、读 `CAST(x AS TEXT)`）；③ SSE 用 SseEmitter + common/SseEmitterHelper（300s 超时），状态机类依赖 `SseEmitterHelper.Channel` 抽象以便单测（QaSseSink 样例）；④ 多构造器 bean 需在注入构造器上加 @Autowired（SearchFacade 样例）；⑤ 集成测试假件接缝：AiServices 唯一构建处（QaAgentFactory）用 @TestConfiguration @Primary 覆盖、@MockitoBean 打桩外部依赖、包私有测试构造器注入假源（SearchFacade 样例） |
| v0.3 | 2026-08-31 | J2 完成后修订：①PO 字段全部私有化（Lombok @Data），service 经 setter/getter 访问（§4）；②新增日志规范章节（slf4j + logback-spring.xml，§10） |
| v0.4 | 2026-08-31 | 注入规范：Spring bean 统一 @Resource 字段注入，弃构造器注入声明（§2 注入规范）；SearchFacade 改 @PostConstruct 按配置装配（v0.2 ④ 的 @Autowired 注入构造器写法作废）；@Resource 名字优先坑：LlmConfig 模型 bean 改名 openAi* 规避字段撞名；同步 §3 步骤 3/7 |
