/**
 * ============================================================================
 * MiniHarness Studio 前端入口文件（整个前端的“总开关”）
 * ============================================================================
 *
 * 【这是什么？】
 * 每个 Vue 项目都有一个入口文件。Vite 打包时会从这里开始执行：
 *   1. 创建 Vue 应用实例（可以理解为“整个页面的总控制器”）；
 *   2. 注册 Pinia（全局状态管理，后面 harness.ts 里详细解释）；
 *   3. 注册 Vue Router（页面路由，负责“网址 → 页面组件”的映射）；
 *   4. 挂载到 index.html 里 id="app" 的那个 <div> 上——之后页面上看到的一切
 *      都是 Vue 渲染出来的。
 *
 * 【数据从哪来？】
 * 这个文件本身不加载任何业务数据。数据全部由各个页面组件在需要时通过
 * src/api/client.ts 里的 fetch 调用从 Java 后端（http://127.0.0.1:8080）获取，
 * 开发期 Vite 会把 /api 开头的请求自动转发给后端（见 vite.config.ts 的 proxy）。
 */
import { createApp } from 'vue'
// Pinia 是 Vue 官方推荐的“全局状态库”：多个页面需要共享的数据（比如实时事件流）
// 放在这里，任何一个页面改了它，其他页面能立刻感知。
import { createPinia } from 'pinia'
// 根组件：定义了整页的骨架（左侧导航栏 + 右侧内容区），见 App.vue。
import App from './App.vue'
// 路由表：/chat、/tasks、/runs、/canvas、/evolution 分别对应哪个页面组件。
import { router } from './router'
// 全局样式（暗色主题、表格、按钮、徽章等通用 CSS），详见 style.css。
import './style.css'

// 1) 创建 Vue 应用，并依次装上状态管理和路由——顺序无所谓，但都要在 mount 之前。
const app = createApp(App)
app.use(createPinia())
app.use(router)
// 2) 真正把应用渲染到页面上。执行完这一行，浏览器里才能看到内容。
app.mount('#app')
