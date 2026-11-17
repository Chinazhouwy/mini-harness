/** 全局状态：SSE 事件流 + 数据刷新（Pinia）。 */
import { defineStore } from 'pinia'
import { openEventStream } from '../api/client'
import type { HarnessEvent } from '../api/types'

export const useHarnessStore = defineStore('harness', {
  state: () => ({
    events: [] as HarnessEvent[],
    connected: false,
    sseStarted: false
  }),
  getters: {
    lastSeq(state): number {
      return state.events.length ? state.events[state.events.length - 1].seq : 0
    },
    eventsForRun(state) {
      return (runId: string) => state.events.filter(e => e.runId === runId)
    }
  },
  actions: {
    /** 首次调用建立 SSE 连接；断线由 EventSource 自动重连（重新从 0 拉取由调用方处理）。 */
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
    reset() {
      this.events = []
    }
  }
})
