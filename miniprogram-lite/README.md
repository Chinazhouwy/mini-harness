# QuantHarness MiniProgram Lite

这是 AgenticHarness 的微信原生小程序 Lite 样板，定位是 Phase 2.5 的移动端查看入口。

它不是主线交易工作台，也不承载真实交易能力。当前目标很收敛：

1. 查看 Reference MVP 服务健康状态。
2. 快速触发一条双均线回测。
3. 查看最近任务列表。
4. 查看任务详情、信号、订单和质检建议。

## 目录结构

```text
miniprogram-lite/
  app.json
  app.ts
  app.wxss
  project.config.json
  sitemap.json
  utils/
    api.ts          后端 HTTP 客户端
    config.ts       API 地址配置
    format.ts       展示格式化工具
    types.ts        Reference API 类型
  pages/
    dashboard/      首页：健康检查、快速回测、最新任务
    tasks/          历史任务列表
    task-detail/    任务详情、信号、订单、质检
```

## 本地开发

1. 启动 Java 后端：

```bash
mvn -pl strategy-service spring-boot:run
```

2. 打开微信开发者工具。

3. 导入目录：

```text
miniprogram-lite
```

4. 开发者工具中关闭域名校验：

```text
详情 -> 本地设置 -> 不校验合法域名、web-view、TLS 版本以及 HTTPS 证书
```

5. 如果模拟器无法访问 `127.0.0.1`，修改：

```text
utils/config.ts
```

把 `API_BASE_URL` 改为你的局域网地址，例如：

```ts
export const API_BASE_URL = "http://192.168.31.20:8082";
```

## 真机调试注意

微信小程序正式环境要求 HTTPS 合法域名。Phase 2.5 先只做本地学习版，因此推荐：

- 模拟器：使用 `http://127.0.0.1:8082` 或局域网 IP。
- 真机预览：使用局域网 IP，并确认手机与电脑在同一 Wi-Fi。
- 正式发布：需要给后端配置 HTTPS 域名，并在小程序后台添加 request 合法域名。

## 已对接接口

```text
GET  /api/reference/health
POST /api/reference/backtest
GET  /api/reference/tasks?limit=20
GET  /api/reference/tasks/{taskId}
GET  /api/reference/tasks/{taskId}/signals
GET  /api/reference/tasks/{taskId}/orders
GET  /api/reference/tasks/{taskId}/quality
```

## 后续建议

- 增加简单登录态或本地访问 token，避免接口裸奔。
- 增加轮询最新任务状态。
- 质检失败时增加更明显的移动端提示。
- 将回测参数做成表单，而不是当前固定快速回测。
- 如果后续要一套代码多端复用，再考虑 uni-app；当前保留原生写法，学习成本更低。
