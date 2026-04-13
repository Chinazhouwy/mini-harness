# strategy-service 一期实施计划（替代旧“模块合并计划”）

## 背景

旧计划假设 `strategy-service` 仍存在 `strategy-api/strategy-core/strategy-persistence/strategy-application` 子模块。  
当前仓库实际已是单模块结构，因此“模块合并”不再是主任务。

本计划更新为：在现有单模块基础上，完成一期 MVP 所需的可运行能力。

## 当前状态（2026-04-13）

- 已有：
  - `StrategyServiceApplication` 可作为启动入口
  - 基础配置文件与依赖框架已存在
  - 部分基础配置类存在（如虚拟线程配置）
- 缺口：
  - 缺少可用的 Controller/Service/Repository 业务实现
  - 策略计算、风控、订单落库链路未打通
  - 测试与验收脚本缺失

## 一期目标（strategy-service 维度）

在 `strategy-service` 内实现最小业务闭环：

1. 从 ClickHouse 读取历史行情
2. 执行双均线策略并生成信号
3. 执行基础风控规则
4. 将模拟订单与回测结果写入 PostgreSQL
5. 暴露可调用 API 给前端和 Agent

## 执行波次（Waves）

### Wave 0：基线与目录校准（0.5 周）

- [ ] 确认包结构边界：
  - `domain`：实体与规则模型
  - `core`：策略与风控逻辑
  - `web`：Controller 与应用服务
  - `infrastructure`：数据访问与配置
- [ ] 清理失效注释与遗留计划说明
- [ ] 约定统一 DTO 命名（`*Request`/`*Response`）

验收：
- 目录结构文档与代码一致

### Wave 1：数据访问打通（1 周）

- [ ] 实现 ClickHouse 行情读取组件
- [ ] 实现 PostgreSQL 持久化表与 Repository
- [ ] 完成最小可用的数据模型：
  - KLine
  - BacktestResult
  - SimulatedOrder

验收：
- 本地可读取指定标的历史数据
- 可写入并查询一条模拟订单记录

### Wave 2：策略与风控核心（1-1.5 周）

- [ ] 实现双均线策略核心逻辑
- [ ] 实现基础风控规则：
  - 仓位上限
  - 止损阈值
  - 单日最大亏损阈值
- [ ] 输出结构化信号（BUY/SELL/HOLD）

验收：
- 给定固定历史数据，策略结果稳定可复现
- 风控可拦截违规订单

### Wave 3：API 与应用服务（1 周）

- [ ] 实现回测触发接口
- [ ] 实现信号查询接口
- [ ] 实现模拟下单接口
- [ ] 补齐统一错误处理与参数校验

验收：
- 三类接口可通过 HTTP 调用并返回统一 JSON

### Wave 4：联调与质量（1 周）

- [ ] 与 `agents/orchestrator` 完成最小联调
- [ ] 与 `web-ui` 完成结果展示联调
- [ ] 增加单元测试与集成测试（关键路径）
- [ ] 增加最小运行说明与回归脚本

验收：
- 从策略计算到结果展示可跑通完整链路
- `mvn test` 可通过关键测试

## DoD（Definition of Done）

- [ ] `strategy-service` 可独立启动并提供稳定 API
- [ ] 完成“行情 -> 策略 -> 风控 -> 模拟下单 -> 查询结果”闭环
- [ ] 关键接口有测试覆盖与失败场景说明
- [ ] README 中的阶段目标可被真实演示

## 非目标（本计划不覆盖）

- 全量微服务拆分治理
- Kafka 与 RocketMQ 双通道生产级幂等
- 高级 Agent 记忆与向量检索
- K8s 生产化部署

## 下一步建议

1. 先执行 Wave 0 与 Wave 1，确保数据链路真实可用  
2. 再进入 Wave 2 做策略与风控，避免“先写接口后补逻辑”  
3. 每完成一波次，回写 README 里程碑，保持文档与代码一致

