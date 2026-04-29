# AGENTS.md - AgenticHarness

## 项目状态（已校准，2026-04-29）

- 当前不是“纯规划”，而是“Reference MVP 已打通 + Phase 2 收尾中”阶段
- 已有内容：
  - `docker-compose-mac.yml`、`docker-compose-win.yml` 已覆盖 Redis/Kafka/PostgreSQL/ClickHouse/MinIO/RocketMQ/Neo4j/Prometheus/Grafana/SkyWalking
  - `services/` 已有 Maven 多模块结构与 `strategy-service` 基础代码
  - `strategy-service` 已有 `reference` 包，覆盖回测、信号、风控、模拟订单、任务存储、质检 API
  - `web-ui` 已有 Dashboard、历史任务列表、任务详情页和移动端底部导航
  - `miniprogram-lite` 已有微信原生小程序样板，可查看任务、触发快速回测、查看信号/订单/质检
  - `agents/technical`、`agents/orchestrator` 已能调用 Reference API
  - `harness/validators/quality_check.py` 已能触发新回测或按 `taskId` 复查历史任务
- 主要缺口：
  - `REFERENCE_STORE_TYPE=jdbc` 已支持 PostgreSQL store，但仍需在本机真实 PostgreSQL 跑一轮重启验证
  - 正式策略引擎、风控规则引擎、多策略参数扫描尚未实现
  - 小程序 Lite 已创建为本地开发样板，但正式发布前仍需 HTTPS 域名、鉴权和小程序后台域名配置

## 单人开发约束（必须遵守）

为避免“一期过大无法推进”，本项目采用单人可执行策略：

1. 先做端到端最小闭环，再做架构完美化
2. 一期只做“可运行 + 可验证 + 可演示”的 MVP
3. 非关键组件延后（如多 Agent 社会仿真、复杂云原生部署）
4. 每周必须有可见产物（接口、脚本、报告或页面）

## 一期目标（MVP）

在本地单机环境完成一条可演示链路：

1. 读取历史行情数据（ClickHouse）
2. 运行双均线策略并生成信号
3. 经过基础风控规则校验
4. 生成模拟订单并落库（默认内存；`REFERENCE_STORE_TYPE=jdbc` 时使用 PostgreSQL）
5. 在前端看到策略结果与订单列表
6. 质检脚本可输出回测指标和失败反馈

## 技术策略（一期收敛版）

| 维度 | 一期方案（收敛） | 二期再扩展 |
|---|---|---|
| 后端 | 以 `strategy-service` 为核心先跑通 | 完整微服务拆分（quote/order/risk/account/backtest） |
| Agent | 先做 1 个 orchestrator + 1 个 technical | sentiment/fundamental/risk_manager 多 Agent 协同 |
| 消息 | 一期优先同步调用或单 Kafka topic | Kafka + RocketMQ 全链路异步化 |
| 存储 | ClickHouse + PostgreSQL + Redis | MongoDB/InfluxDB/MinIO/Vector Sets 深化 |
| 可观测 | 健康检查 + 基础 metrics | SkyWalking 全链路 + 告警体系 |

## 目录约定

```
services/     Java 服务与核心业务逻辑
agents/       Python Agent 编排与分析能力
web-ui/       Vue 前端演示界面
miniprogram-lite/ 微信原生小程序 Lite，Phase 2.5 移动端入口
harness/      质检与反馈闭环
simulation/   二期情景推演（当前仅保留骨架）
scripts/      本地运维与数据脚本
docs/         架构文档、路线图、决策记录
```

## 8 周执行路线（单人版）

### 第 1-2 周：最小数据与回测闭环
- `docker-compose` 最小集启动（Redis/ClickHouse/PostgreSQL）
- 建表与数据导入脚本稳定运行
- Python 双均线回测可输出收益、回撤、夏普

### 第 3-4 周：策略服务化
- `strategy-service` 提供回测触发与结果查询接口（已完成 Reference API）
- 接入 ClickHouse 读行情、PostgreSQL 存回测记录（PostgreSQL store 已实现）
- 加入基础风控规则（仓位上限、止损阈值）（已完成 Reference 版）

### 第 5-6 周：Agent 最小协同
- `orchestrator` 能调用 `technical` 分析并生成建议
- 策略建议 -> 风控校验 -> 模拟下单流程打通
- 前端可查看信号与订单结果

### 第 7 周：质检与反馈
- `harness/validators/quality_check.py` 可执行
- 回测指标阈值检查（如 Sharpe < 1.0）触发结构化反馈
- 输出标准化日报/周报模板

### 第 8 周：稳态与演示
- 补齐关键单元测试与集成测试
- 固化启动脚本与一键演示流程（`scripts/demo/*` 已提供）
- 完成一期演示文档与已知问题清单（README/TODOLIST/ROADMAP 已更新）

## Definition of Done（一阶段）

- 任意新同学可按 README 在 30 分钟内拉起环境
- 一条回测任务可以从触发到结果展示完整跑通
- 关键脚本与服务失败时有明确报错与恢复指引
- 至少 1 条自动化质检链路可稳定执行

## 暂不纳入一期（明确延期）

- 全量微服务拆分与服务注册治理
- RocketMQ 全链路生产级幂等治理
- Redis Vector Sets 大规模记忆检索
- Neo4j 群体智能情景推演引擎（simulation）
- K8s 生产级部署与复杂弹性策略
