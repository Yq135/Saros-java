# Saros-java

Saros 阶段三：Java (Spring Boot 4.0.8 / Java 21) 生产化后端，绞杀者模式接管阶段二 FastAPI 服务（契约零漂移，前端零改动）。

## 开发前必读

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — **开发规范（DDD 分层 + MyBatis XML 流程），所有代码改动必须遵守**
- [docs/PLAN.md](docs/PLAN.md) — 里程碑计划（J0-J5）与验收条件
- [docs/数据库设计说明.md](docs/数据库设计说明.md) — 表结构（权威来源：仓库根 `db_init.sql`，只写不改）
- [docs/openapi-phase2.json](docs/openapi-phase2.json) — API 契约基线（一切 API 行为对齐它）

## 关键约定速览

- 分层：`controller/` 只路由绑定、`service/` 业务与事务、`dto/` 契约对象（@JsonProperty 蛇形）、`po/` 表对象（一张表一个类）、`mapper/` 仅接口签名（无 SQL）
- SQL 一律写 `src/main/resources/mapper/*.xml`（namespace = 接口全限定名，一表一文件；`<=>` 转义为 `&lt;=&gt;`）
- 异常：ApiException（404/400，文案对齐阶段二）/ ValidationException（422）；状态码用 @ResponseStatus
- 测试：集成测试走远程 PG 的独立 schema `saros_test` 沙箱；依赖 ONNX 模型的用例用 Assumptions 跳过

## 验证

```bash
# PG 不在本机，跑测试必须带环境变量
PG_HOST=100.109.98.117 PG_PORT=5432 PG_USER=saros PG_PASSWORD='saros#2026!' PG_DB=saros_db ./mvnw test
```
