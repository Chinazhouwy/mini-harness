# TODOLIST - AgenticHarness（一期单人执行版）

> 版本日期：2026-04-13  
> 目标：8 周内完成可运行、可验证、可演示的一期 MVP。

详细执行手册：`docs/IMPLEMENTATION_PLAYBOOK.md`  
二期及长期路线：`docs/ROADMAP_PHASE2_PLUS.md`

## 使用规则

1. 只维护“一期必须做”的任务，二期内容放到末尾 Backlog。
2. 每周结束必须更新：完成项、阻塞项、下周计划。
3. 任务以“可验收”表述，避免空泛描述。

## 里程碑总览

> 2026-04-28 更新：`codex/reference-mvp-slice` 分支已提供一套完整 Reference MVP 参考实现。
> 它覆盖后端回测 API、信号、风控、模拟订单、任务查询、质检反馈、前端控制台、Harness 脚本和 Agent 最小协同。
> 当前订单/任务存储默认使用内存实现；PostgreSQL store 已提供，可通过 `REFERENCE_STORE_TYPE=jdbc` 启用。
> 2026-04-29 更新：Phase 2.5 小程序 Lite 原生样板已新增在 `miniprogram-lite/`，作为移动端学习与演示入口。

- [ ] M0 基础设施可启动
- [x] M1 数据导入与双均线回测可运行（Reference Java 版已完成；数据导入仍需固化）
- [x] M2 `strategy-service` API 可用（Reference API 已完成）
- [x] M3 风控与模拟下单可用（内存版已完成，PostgreSQL store 已提供）
- [x] M4 Agent 最小协同可用（Reference API 调用版）
- [x] M5 前端展示与质检闭环可用（Reference 控制台已完成）
- [x] M6 Mobile Lite 参考实现可用（H5 + 小程序 Lite 本地样板）

## Week 1-2：数据与回测闭环

- [ ] 启动最小基础设施（Redis、ClickHouse、PostgreSQL）
- [ ] 固化建表脚本与初始化脚本（可重复执行）
- [ ] 完成历史数据导入脚本（失败可重试）
- [x] 完成双均线回测脚本（Java Reference 实现）
- [x] 输出统一指标（总收益、最大回撤、交易次数、胜率）

验收标准：
- [ ] 回测命令连续运行 3 次均成功
- [ ] 指标输出格式固定，支持后续服务读取

## Week 3-4：策略服务化

- [x] 实现回测触发 API
- [x] 实现回测结果查询 API
- [x] 实现策略信号生成 API
- [x] 打通 ClickHouse 读取历史行情
- [x] 打通 PostgreSQL 落库回测结果（JdbcReferenceBacktestStore）
- [x] 统一错误码与参数校验（ReferenceBacktestException + Controller handler）

验收标准：
- [ ] 三类 API 可通过 HTTP 调用
- [ ] 返回结构化 JSON，错误信息可读

## Week 5-6：风控、下单、Agent 联调

- [ ] 实现基础风控规则：
- [x] 仓位上限
- [x] 止损阈值
- [ ] 单日最大亏损阈值
- [x] 实现模拟下单流程（内存任务仓储）
- [x] 模拟订单 PostgreSQL 落库（JdbcReferenceBacktestStore）
- [x] `orchestrator` 调用 `technical` 并汇总建议
- [x] 完成策略 -> 风控 -> 下单联调

验收标准：
- [ ] 违规订单能被风控拦截
- [ ] 正常订单可完整落库并查询

## Week 7：Harness 质检闭环

- [x] 实现 `harness/validators/quality_check.py` 最小可用能力
- [x] 增加指标阈值校验（收益、回撤、交易次数）
- [x] 输出结构化反馈（JSON）
- [x] 记录失败案例与建议动作

验收标准：
- [ ] 一条命令运行质检并输出报告
- [ ] 报告可定位到策略或参数问题

## Week 8：前端展示、稳定性、演示

- [x] 前端展示最近信号、回测指标、模拟订单
- [x] 补齐关键测试（Reference 策略、风控、任务、质检）
- [ ] 固化一键启动脚本与演示脚本
- [ ] 更新 README/AGENTS 与当前实现一致

验收标准：
- [ ] 新环境 30 分钟内可跑通最小闭环
- [ ] 可完成一次端到端演示

## 当前阻塞与风险

- [ ] 需求范围膨胀（一期混入二期能力）
- [ ] 外部数据源不稳定导致开发中断
- [ ] 组件过多导致排障复杂
- [ ] Testcontainers 首次拉取 PostgreSQL 镜像依赖 Docker Hub 网络，可能导致集成测试临时失败

对应策略：
- [ ] 新需求先标注“一期/二期”，默认进二期
- [ ] 保留本地样例数据用于离线开发
- [ ] 优先同步调用，后续再异步化
- [ ] PostgreSQL 本机验证由真实服务跑通，Testcontainers 作为补充自动化验证

## 当前交接任务（你拉代码后优先做）

- [ ] 执行 `scripts/create_reference_postgres_tables.sql` 初始化本机 PostgreSQL。
- [ ] 使用 `REFERENCE_STORE_TYPE=jdbc` 启动 `strategy-service`。
- [ ] 连续触发 3 次 `/api/reference/backtest`，确认任务列表有 3 条记录。
- [ ] 重启 `strategy-service`，确认历史任务仍可通过 `/api/reference/tasks/{taskId}` 查询。
- [ ] 启动 `web-ui`，确认 Dashboard、任务列表、任务详情页可正常展示。
- [ ] 用微信开发者工具打开 `miniprogram-lite/`，确认首页、任务列表、任务详情能访问本机后端。
- [ ] 运行 `harness/validators/quality_check.py --task-id <id>`，确认历史任务质检可输出 JSON/Markdown。

## Phase 2：持久化与服务内模块化（下一步）

目标：把 Reference MVP 从内存演示升级为可追踪、可回放、可审计的本地系统。

### Phase 2.1：PostgreSQL Store 完整闭环

- [x] 定义 `ReferenceBacktestStore` 接口
- [x] 保留 `InMemoryReferenceBacktestStore` 作为测试实现
- [x] 新增 PostgreSQL 多数据源配置
- [x] 新增 `backtest_task` 表
- [x] 新增 `backtest_signal` 表
- [x] 新增 `simulated_order` 表
- [x] 新增 `equity_curve_point` 表
- [x] 实现 `JdbcReferenceBacktestStore`
- [x] 增加 PostgreSQL Testcontainers 集成测试（当前环境 Docker socket 不可用时会自动跳过）
- [ ] 本机 PostgreSQL 执行 `scripts/create_reference_postgres_tables.sql`
- [ ] 使用 `REFERENCE_STORE_TYPE=jdbc` 完成真实服务验证
- [ ] 重启服务后确认历史任务仍可查询

### Phase 2.2：历史任务与任务详情

- [x] 后端增加 `GET /api/reference/tasks/latest`
- [x] 后端增加任务信号查询接口
- [x] 后端增加任务订单查询接口
- [x] 后端增加任务权益曲线查询接口
- [x] 后端增加任务质检别名接口
- [x] 前端增加历史任务列表
- [x] 前端增加任务详情页
- [x] 后端任务列表增加分页参数
- [x] Dashboard 改为“快速回测 + 最近任务摘要”

### Phase 2.3：Harness 历史任务质检

- [x] Harness 支持对历史任务 ID 做质检
- [x] `quality_check.py --task-id <id>` 复查历史任务
- [x] `quality_check.py --output report.json` 输出 JSON 文件
- [x] `quality_check.py --markdown report.md` 输出 Markdown 报告

### Phase 2.4：一期演示脚本

- [x] `scripts/demo/start-reference-mvp.sh`
- [x] `scripts/demo/run-reference-backtest.sh`
- [x] `scripts/demo/run-quality-check.sh`
- [x] `scripts/demo/stop-reference-mvp.sh`

验收标准：
- [ ] 重启服务后历史任务仍可查询
- [ ] 任意任务可回放参数、信号、订单、权益曲线、质检结果
- [ ] 本地测试覆盖内存 store 和 PostgreSQL store

## Phase 2.5：Mobile Lite（H5 + 小程序）

目标：提供轻量移动端入口，先支持查看和快速触发，不做复杂交易工作台。

### Phase 2.5.1：H5 移动适配

- [x] Dashboard 移动端单列布局
- [x] 指标卡片适配手机宽度
- [x] 任务页支持移动端卡片列表
- [x] 任务详情页信号改为移动端卡片列表
- [x] 任务详情页订单改为移动端卡片列表
- [x] 任务详情页支持窄屏
- [x] 增加移动端底部导航：`首页 / 任务 / 说明`

验收标准：
- [ ] 手机宽度下无文本重叠
- [ ] 手机浏览器可触发快速回测
- [ ] 手机浏览器可查看任务详情、信号、订单、质检建议

### Phase 2.5.2：小程序 Lite

- [x] 选型：微信原生小程序
- [x] 小程序首页展示最近任务
- [x] 小程序任务详情展示指标、信号、订单、质检建议
- [x] 小程序支持快速回测
- [x] 新增小程序 API 客户端、类型定义和格式化工具
- [x] 新增小程序本地开发说明
- [ ] 后端补简单鉴权方案
- [ ] 明确 HTTPS/本地代理开发方案

验收标准：
- [ ] 小程序开发者工具可打开并调用后端接口（需本机手动验证）
- [ ] 小程序能创建新回测任务（需本机手动验证）
- [ ] 小程序不暴露敏感凭据

### Phase 2.5.3：移动提醒

- [x] 后端增加 `GET /api/reference/tasks/latest`
- [ ] H5 轮询最近任务状态
- [ ] 质检失败时移动端明显提示
- [ ] 最新信号为 BUY/SELL 时移动端明显提示

暂不做：
- [ ] 真实交易
- [ ] 用户体系
- [ ] 支付订阅
- [ ] 原生 App

## Phase 3：正式策略与风控引擎

- [ ] 抽象 `StrategyEngine`
- [ ] 抽象 `StrategyParameters`
- [ ] 抽象 `RiskRule`
- [ ] 抽象 `RiskRuleEngine`
- [ ] 双均线策略迁移到 `StrategyEngine`
- [ ] JMA 策略迁移到 `StrategyEngine`
- [ ] 增加突破策略
- [ ] 增加参数扫描 API
- [ ] 增加年化收益、年化波动、Sharpe、Calmar、profit factor
- [ ] 增加单日最大亏损规则
- [ ] 增加最大回撤熔断规则
- [ ] 增加黑名单股票规则

验收标准：
- [ ] 同一批行情可运行至少 3 种策略
- [ ] 每条订单可追踪触发信号和风控决策
- [ ] 参数扫描结果可排序并导出 JSON/CSV

## Phase 4：Agent 工程化

- [ ] technical agent 生成结构化策略解释
- [ ] risk reviewer agent 解释风控拒绝原因
- [ ] orchestrator 汇总多个 Agent 输出
- [ ] 引入 LLM JSON schema 输出
- [ ] 保存 Agent 运行记录
- [ ] Agent 输出解析失败时降级为规则摘要
- [ ] Agent 建议接入 Harness 反馈报告

验收标准：
- [ ] 同一任务可生成 technical/risk/orchestrator 三类报告
- [ ] Agent 失败不影响 Java 主链路
- [ ] 至少 5 条失败样例可复现

## Phase 5：事件驱动与服务拆分

- [ ] 设计统一事件结构：`eventId/eventType/occurredAt/traceId/payload`
- [ ] 增加 Kafka topic：`strategy.signal.generated`
- [ ] 增加 Kafka topic：`risk.decision.created`
- [ ] 增加 Kafka topic：`order.simulated.created`
- [ ] 增加 Kafka topic：`backtest.task.completed`
- [ ] 拆出 `quote-service`
- [ ] 拆出 `backtest-service`
- [ ] 拆出 `risk-service`
- [ ] 拆出 `order-service`
- [ ] 保留 `strategy-service` 专注策略信号生成
- [ ] 增加幂等消费和死信处理

验收标准：
- [ ] 每个 topic 有生产者、消费者和失败重试策略
- [ ] 任意消费者失败后可恢复消费
- [ ] traceId 可串起一次回测链路

## Phase 6：记忆、知识图谱与仿真

- [ ] 引入向量记忆保存历史 Agent 结论
- [ ] 支持相似失败案例检索
- [ ] 支持相似市场状态检索
- [ ] 设计 Neo4j 股票/行业/概念/事件/策略表现图谱
- [ ] simulation 支持市场冲击情景
- [ ] simulation 支持滑点情景
- [ ] simulation 支持风控熔断情景

验收标准：
- [ ] 输入一个失败任务，能找出 3 个相似历史任务
- [ ] 给定一个回测任务，可生成至少 3 个压力情景

## Phase 7：生产化与可观测

- [ ] Prometheus 暴露任务成功率、回测耗时、Agent 失败率
- [ ] Grafana 展示核心面板
- [ ] SkyWalking 串联跨服务 trace
- [ ] JSON 结构化日志携带 traceId
- [ ] GitHub Actions 跑 Java 测试
- [ ] GitHub Actions 跑 Python 语法检查
- [ ] GitHub Actions 跑前端构建
- [ ] Docker Compose 一键演示脚本
- [ ] K8s 部署练习环境

验收标准：
- [ ] 任意任务失败能在 5 分钟内定位原因
- [ ] CI 可稳定验证 Java/Python/Web 三条线
- [ ] 本地演示环境一条命令启动
