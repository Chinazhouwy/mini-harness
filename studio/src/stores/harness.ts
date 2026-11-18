/**
 * ============================================================================
 * 全局状态（Pinia store）：实时事件流 + 各页面共用的数据刷新
 * ============================================================================
 *
 * 【为什么需要 store？】
 * 没有 store 时，两个页面各自请求后端，数据互不相干。而本系统的核心体验是：
 * 后端每产生一条事件（如“节点执行成功”），Chat 页、Run Inspector 页要同时看到。
 * 所以把“事件列表”放进全局 store：一条事件进来，所有正打开它的页面自动更新。
 *
 * 【本 store 有什么？】
 * - events:    收到的全部事件（最多保留 1000 条，防止内存无限增长）
 * - connected: SSE 是否连上了（左侧边栏的绿/红状态就是读它）
 * - connectSse(): 建立 SSE 连接的唯一入口（App.vue 在页面加载时调用一次）
 * - eventsForRun(runId): 按执行 ID 过滤事件的只读函数（给 Run Inspector 用）
 */
import { defineStore } from 'pinia'
// 底层的 EventSource 连接实现在 api/client.ts；store 只负责“收到的数据往哪放”。
import { openEventStream } from '../api/client'
// 事件的数据结构定义（和后端 ApiMappers.event() 输出的 JSON 字段一一对应）。
import type { HarnessEvent } from '../api/types'

// defineStore('harness', {...})：注册一个名为 harness 的全局状态仓库。
// state = 数据；getters = 从数据算出来的只读值；actions = 修改数据的方法。
export const useHarnessStore = defineStore('harness', {
  state: () => ({
    events: [] as HarnessEvent[],
    connected: false,
    // 防止重复建连：App.vue 只在首次挂载时调用 connectSse，这里再兜底一次。
    sseStarted: false
  }),
  getters: {
    /** 当前收到的最大事件序号 seq。后端给每个事件编号，用于排序与去重。 */
    lastSeq(state): number {
      return state.events.length ? state.events[state.events.length - 1].seq : 0
    },
    /** 取某个执行（Run）的事件子集。在模板里可以像函数一样用：
     *  store.eventsForRun('run-xxxx') */
    eventsForRun(state) {
      return (runId: string) => state.events.filter(e => e.runId === runId)
    }
  },
  actions: {
    /**
     * 建立 SSE 连接（幂等：已连接就不再建第二条）。
     *
     * 收到事件后的处理规则：
     * 1. 忽略握手消息（后端建连时会先推一条 {connected:true}，它没有 type/seq）；
     * 2. 按 seq 去重——浏览器断线自动重连时可能重复收到同一批事件；
     * 3. 追加进 events，超出 1000 条就从头部丢弃。
     */
    connectSse() {
      if (this.sseStarted) return
      this.sseStarted = true
      openEventStream(0, event => {
        // 握手消息（无 type/seq）与按 seq 重放的重复事件都跳过。
        if (!event.type || event.seq === undefined) return
        if (this.events.length && event.seq <= this.events[this.events.length - 1].seq) {
          return
        }
        this.events.push(event)
        if (this.events.length > 1000) {
          this.events.splice(0, this.events.length - 1000)
        }
      }, connected => { this.connected = connected })
    },
    /** 清空事件（目前仅调试用；页面刷新本身也会重置）。 */
    reset() {
      this.events = []
    }
  }
})
