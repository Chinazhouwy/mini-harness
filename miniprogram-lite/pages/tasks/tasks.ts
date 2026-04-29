import { listTasks } from "../../utils/api";
import { compactId, percent, statusText } from "../../utils/format";
import type { ReferenceBacktestTask } from "../../utils/types";

interface TaskListItemView {
  taskId: string;
  stockCode: string;
  shortId: string;
  statusLabel: string;
  startDate: string;
  endDate: string;
  totalReturnLabel: string;
  maxDrawdownLabel: string;
  tradeCount: number;
}

function toTaskListItemView(task: ReferenceBacktestTask): TaskListItemView {
  return {
    taskId: task.taskId,
    stockCode: task.stockCode,
    shortId: compactId(task.taskId),
    statusLabel: statusText(task.status),
    startDate: task.startDate,
    endDate: task.endDate,
    totalReturnLabel: percent(task.metrics?.totalReturn),
    maxDrawdownLabel: percent(task.metrics?.maxDrawdown),
    tradeCount: task.metrics?.tradeCount || 0
  };
}

Page({
  data: {
    loading: false,
    tasks: [] as TaskListItemView[],
    error: ""
  },

  onShow() {
    this.loadTasks();
  },

  onPullDownRefresh() {
    this.loadTasks().finally(() => wx.stopPullDownRefresh());
  },

  async loadTasks() {
    this.setData({ loading: true, error: "" });
    try {
      const tasks = await listTasks(30);
      this.setData({ tasks: tasks.map(toTaskListItemView) });
    } catch (error) {
      this.setData({ error: error instanceof Error ? error.message : String(error) });
    } finally {
      this.setData({ loading: false });
    }
  },

  openTask(event: WechatMiniprogram.TouchEvent) {
    const taskId = event.currentTarget.dataset.taskId;
    if (!taskId) {
      return;
    }
    wx.navigateTo({
      url: `/pages/task-detail/task-detail?taskId=${taskId}`
    });
  }
});
