# MiniHarness Studio 前端导读（写给前端小白）

> 目标：读完这篇 + 照着代码里的注释，你能明白「页面上每个数字是从哪来的」，
> 并能自己动手改一个页面的小功能。
> 所有前端代码都在 `app/studio/` 目录，与 Java 后端**只通过 HTTP/SSE 通信**，互不依赖代码。

## 一、先把项目跑起来

```bash
# 终端 1：先启动 Java 后端（提供 /api 接口）
cd /Users/chinazhouwy/doc/MiniHarness
mvn clean package -DskipTests -pl app/task-assistant -am
MINIHARNESS_MODEL_MODE=offline java -jar app/task-assistant/target/task-assistant-0.1.0.jar

# 终端 2：启动前端开发服务器（带热更新：改代码浏览器自动刷新）
cd app/studio
npm install        # 第一次需要，安装 vue/vite 等依赖
npm run dev        # 打开 http://localhost:5173
```

开发模式下 Vite（前端构建工具）会把所有 `/api` 开头的请求自动转发给
`http://127.0.0.1:8080`（见 `app/studio/vite.config.ts`），所以前端代码里写的都是
相对路径 `/api/...`，不用关心后端在哪个端口。

也可以不跑后端、直接用打包好的单 jar：`java -jar app/task-assistant/target/task-assistant-0.1.0.jar`
然后访问 http://127.0.0.1:8080（前端已构建并打包进去了）。

## 二、目录地图（每个文件是干嘛的）

```text
app/studio/
├── index.html                 # 唯一的 HTML 文件，只有一个空 <div id="app">
├── vite.config.ts             # 构建配置：/api 代理到后端 8080
├── src/
│   ├── main.ts                # 入口：创建 Vue 应用，注册 Pinia 和 Router
│   ├── App.vue                # 页面骨架：左侧菜单 + 右侧内容区；加载时建立 SSE 连接
│   ├── style.css              # 全局暗色样式（表格/按钮/徽章/气泡）
│   ├── router/index.ts        # 路由表：网址 /chat /tasks /runs /canvas /evolution → 页面组件
│   ├── stores/harness.ts      # Pinia 全局状态：SSE 实时事件流（所有页面共享）
│   ├── api/
│   │   ├── client.ts          # 所有后端调用的封装（REST fetch + SSE EventSource）
│   │   └── types.ts           # 后端返回 JSON 的 TypeScript 字段定义
│   └── views/                 # 5 个页面组件（每个 .vue 一个页面）
│       ├── ChatView.vue               # 对话：发消息 → 路由 → 建任务/关联 → 自动执行
│       ├── TasksView.vue              # 任务列表/详情/执行历史/追加质疑
│       ├── RunInspectorView.vue       # 单次执行体检报告（节点/事件/输出 JSON）
│       ├── WorkflowCanvasView.vue     # 流程图可视化（Vue Flow）
│       └── EvolutionReviewView.vue    # 自进化实验：候选/评测/批准/拒绝/回滚
└── docs/STUDIO-GUIDE.md       # 本文档
```

**阅读顺序建议**：`main.ts` → `App.vue` → `router/index.ts` → `api/client.ts` →
`stores/harness.ts` → 挑一个最感兴趣的 `views/*.vue`。每个文件头部都有
「这是什么/做什么/数据从哪来」的大段注释。

## 三、一次对话的完整数据流（以 Chat 页为例）

```text
你在输入框打字，点“发送”
        │  POST /api/chat  {sessionId, input}
        ▼
Java 后端 ChatController
        │  IntentRouter 能力路由（规则 → 活跃任务 → 已发布 Workflow → Skill → 兜底）
        ├─ 新研究诉求 → 创建 Task + 发起 Run（异步执行）
        ├─ 质疑“利润率不对” → 关联原 Run + 发起核查 Run
        └─ 有歧义 → 返回确认问题（绝不擅自合并任务）
        │  返回 JSON {reply, action, taskId?, runId?}
        ▼
ChatView 显示回复气泡；记下 runId
        │
        │  （与此同时，后端执行 Workflow 产生的每条事件）
        ▼
EventBus ──SSE──▶ 浏览器 EventSource（stores/harness.ts 收下，按 seq 去重）
        │                │
        ▼                ▼
左侧边栏“已连接（N 条事件）”   ChatView 底部事件面板实时滚动
        │
        │  收到终止事件（RUN_COMPLETED / RUN_FAILED…）
        ▼
ChatView 调 GET /api/runs/{runId} 拉最终结果 → 显示“执行结果：SUCCEEDED + 结论”
```

其余页面的数据流同理，只是调用的接口不同：TasksView 用 `/api/tasks*`，
RunInspector 用 `/api/runs*` + `/api/events`，Canvas 用 `/api/workflows*`，
Evolution 用 `/api/evolution/*`。

## 四、几个必须懂的前端概念（只讲本项目用到的）

| 概念 | 一句话解释 | 在本项目哪里用到 |
| --- | --- | --- |
| **Vue 组件（.vue）** | 一个文件 = 一块页面，分 `<script>`（逻辑）、`<template>`（长相）、`<style>`（样式）三段 | 所有 views/*.vue |
| **ref / 响应式** | `ref(0)` 包起来的变量改了值，页面用到它的地方**自动更新**，不用手动刷 DOM | 几乎所有页面（如 `input`、`tasks`） |
| **v-model** | 输入框双向绑定：打字 → 变量变；代码改变量 → 输入框变 | Chat 输入框、审批备注 |
| **v-for / v-if** | 循环渲染列表 / 条件渲染 | 任务表格、事件面板、徽章 |
| **computed** | 从已有数据**算出来**的值，依赖变了自动重算 | RunInspector 的事件合并列表 |
| **watch** | 监听某数据变化后执行一段逻辑 | 「有新事件就刷新列表/拉结果」 |
| **Pinia store** | 多页面共享的全局数据仓库 | `stores/harness.ts` 的事件流 |
| **SSE（EventSource）** | 浏览器与后端的长连接，后端主动推消息，比轮询实时且省请求 | `api/client.ts` 的 `openEventStream` |
| **async / await** | 异步请求的等待写法：`await api.tasks()` 会等后端返回再执行下一行 | 所有 api 调用 |

## 五、常见修改场景怎么做

**想改页面文案/颜色** → 直接改对应 `views/*.vue` 的 `<template>`，或 `style.css`。

**想加一个接口调用** → 三步：
1. `api/types.ts` 加（或改）一个 interface，字段照抄后端 `ApiMappers.java` 的输出；
2. `api/client.ts` 的 `api` 对象里加一个方法（照抄现有写法）；
3. 页面里 `await api.你的方法()`，结果赋给一个 `ref(...)`。

**想在页面里用实时事件** → 不用自己连 SSE（App.vue 已经全局连了），直接：
```ts
const store = useHarnessStore()
// 拿某个执行的实时事件（自动去重）
store.eventsForRun('run-xxxx')
// 或监听“有新事件”做点什么
watch(() => store.events.length, () => { /* 你的逻辑 */ })
```

**页面显示“未连接”** → 后端没启动，或不在 5173 端口打开页面。先启动后端 jar，
再访问 http://localhost:5173（开发模式）或 http://127.0.0.1:8080（jar 模式）。

**构建报 TS 类型错误** → 说明 types.ts 与实际 JSON 字段不一致，按报错提示
对照 `ApiMappers.java` 修正字段名即可。

## 六、边界说明（不要误以为的能力）

- 前端**没有 Mock 数据**：页面上每个数字都来自后端真实接口；后端不启动，页面只有空列表。
- Workflow Canvas 第一版只做**可视化**（节点/边来自后端定义），不支持拖拽编排。
- 事件面板只保留最近 1000 条（store 上限），且 SSE 只收“连接之后”的新事件，
  历史事件靠 REST 回放（Run Inspector 已做合并，其他页面按需加）。
