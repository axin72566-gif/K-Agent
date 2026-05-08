# ReAct Agent 生产化差距分析与改造方案

## Context

K-Agent 当前是教学/原型项目，通过 `ReAct`、`PlanAndSolve`、`Reflection` 三种范式演示 LLM Agent 核心思想，并附带一个基于 AgentScope 的"三国狼人杀"多 Agent 游戏。

**技术栈**: Java 17, Spring Boot 3.5, Spring AI 1.0, DeepSeek API, SerpApi, AgentScope 1.0.12

**核心问题**: 当前 ReActAgent 可以跑通 Demo，但距离能真正"服务他人"的 Agent，还差 **12 个维度**的系统性缺失。

---

## 差距全景图

### 1. 健壮性与容错（最关键）

- LlmClient 异常被 `catch` 吞掉返回 `null`，不区分网络错误/限流/鉴权失败
- 无重试、无熔断、无超时控制
- 正则解析对 LLM 输出格式零容忍——模型输出稍有偏差则解析完全失败
- 工具执行异常无隔离，可能污染后续推理

### 2. 可观测性

- 全项目使用 `System.out.println`，无日志框架（SLF4J/Logback）
- 无 Metrics：每次调用的延迟、Token 消耗、步数、成功率完全不可见
- 无 Tracing：多步推理中出错无法回溯定位

### 3. Agent 抽象

- 三种 Agent 范式无共同接口或基类，无统一生命周期
- 横切关注点（日志、metrics、鉴权）无处落地
- 无法统一管理和调度

### 4. 工具系统

- Tool 接口只有 `String execute(String)`，无结构化参数、无 schema
- 无参数校验、无异步执行、无缓存、无权限控制
- 仅有一个 SearchTool 实现

### 5. 会话与记忆

- `run()` 每次调用清空 history，无跨轮记忆
- 无 Session 概念，无用户身份
- 无长期记忆（向量数据库/知识图谱）

### 6. 安全

- API Key 在 `application.yml` 中有明文默认值
- 用户输入直接注入 Prompt 模板，无注入防护
- 无认证授权、无速率限制

### 7. API 与服务化

- 无 REST/gRPC 接口，所有执行通过 `CommandLineRunner` 触发
- 无流式响应 (SSE/WebSocket) 暴露给终端用户
- 无异步任务管理

### 8. 测试体系

- 4 个测试类全是 `@SpringBootTest` 集成测试，需要真实 LLM API
- 无单元测试，无 Mock，无法在 CI 运行

### 9. 提示词工程与输出解析

- Prompt 硬编码在 Java 常量中，修改需重新编译部署
- 正则解析脆弱——应优先使用 Function Calling 或 JSON Mode
- 无 Prompt 版本管理，无法 A/B 测试

### 10. 多 Agent 编排

- 三国狼人杀是特定场景，无通用编排框架
- 无 Agent 间通信协议、任务分解分发机制

### 11. 配置管理

- Agent 参数硬编码（maxSteps=5, temperature=0.0）
- 无分环境配置（dev/staging/prod）
- 关键参数无法运行时热更新

### 12. 性能与资源

- SearchTool 每次执行 `new HttpClient()`，无连接池
- 无 LLM 响应缓存，无并发控制，无 Token 预算

---

## 优先级路线图

### Phase 1 — 安全与稳定性（P0，1-2周）

- 移除硬编码 API Key，启动检查环境变量
- 引入 SLF4J + Logback 替代 System.out
- LlmClient 增加重试（Spring Retry + 指数退避）+ 超时
- 统一异常处理体系（不再返回 null）

### Phase 2 — Agent 抽象与工具增强（P0，2-3周）

- 定义 `Agent` 接口 + `AgentResult` 模型
- 增强 `Tool` 接口：JSON Schema 参数 + `ToolResult` 返回
- Prompt 外部化到 `prompts/` 目录
- 引入 Function Calling 替代正则解析

### Phase 3 — 可观测性与会话（P1，2-3周）

- Micrometer Metrics + Spring Boot Actuator
- 会话管理（SessionManager + Redis 持久化）
- 分布式追踪（traceId 贯穿调用链）

### Phase 4 — API 服务化（P1，2周）

- REST Controller：同步执行 + SSE 流式 + 会话查询
- 异步任务管理（提交/查询/取消）
- SpringDoc OpenAPI 文档

### Phase 5 — 安全加固与编排（P2，2-3周）

- Spring Security + JWT 认证
- 工具权限控制 + 速率限制
- 多 Agent 编排框架基础

### Phase 6 — 测试体系（持续）

- MockLlmClient + 单元测试覆盖核心逻辑
- WireMock 模拟外部 API 集成测试
- CI Pipeline

---

## 目标架构

```
  API Gateway (Auth / RateLimit)
         │
    ┌────┼────┐
    ▼    ▼     ▼
  REST  SSE  WebSocket
         │
    AgentService (任务调度 / 会话管理)
         │
    ┌────┼────┐
    ▼    ▼     ▼
  ReAct  P&S  Reflection  (统一 Agent 接口)
         │
    ┌────┼────┐
    ▼    ▼     ▼
  LlmClient  ToolExec  SessionMgr
  (重试/熔断) (权限/缓存) (Redis/DB)
```

## Verification

1. Phase 1 完成后：`./mvnw test` 不再依赖外部 API，Mock 模式下全部通过
2. Phase 4 完成后：`curl -X POST /api/v1/agents/react/run` 返回 AgentResult
3. Phase 3 完成后：`/actuator/prometheus` 暴露 Agent 运行指标
4. 全量回归：`./mvnw test -Dspring.profiles.active=mock` 在 CI 中通过
