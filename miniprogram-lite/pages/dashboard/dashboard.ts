import { getHealth, listTasks, runBacktest } from "../../utils/api";
import { DEFAULT_BACKTEST_REQUEST } from "../../utils/config";
import { compactId, percent, statusText } from "../../utils/format";
import type { HealthResponse, ReferenceBacktestTask } from "../../utils/types";

interface LatestTaskView {
  taskId: string;
  stockCode: string;
  shortId: string;
  statusLabel: string;
  totalReturnLabel: string;
  maxDrawdownLabel: string;
}

function toLatestTaskView(task?: ReferenceBacktestTask): LatestTaskView | null {
  if (!task) {
    return null;
  }

  return {
    taskId: task.taskId,
    stockCode: task.stockCode,
    shortId: compactId(task.taskId),
    statusLabel: statusText(task.status),
    totalReturnLabel: percent(task.metrics?.totalReturn),
    maxDrawdownLabel: percent(task.metrics?.maxDrawdown)
  };
}

Page({
  data: {
    loading: false,
    running: false,
    health: null as HealthResponse | null,
    latestTask: null as ReferenceBacktestTask | null,
    latestTaskView: null as LatestTaskView | null,
    error: ""
  },

  onLoad() {
    this.refresh();
  },

  onPullDownRefresh() {
    this.refresh().finally(() => wx.stopPullDownRefresh());
  },

  async refresh() {
    this.setData({ loading: true, error: "" });
    try {
      const [health, tasks] = await Promise.all([getHealth(), listTasks(1)]);
      this.setData({
        health,
        latestTask: tasks[0] || null,
        latestTaskView: toLatestTaskView(tasks[0])
      });
    } catch (error) {
      this.setData({ error: error instanceof Error ? error.message : String(error) });
    } finally {
      this.setData({ loading: false });
    }
  },

  async runQuickBacktest() {
    this.setData({ running: true, error: "" });
    try {
      const response = await runBacktest(DEFAULT_BACKTEST_REQUEST);
      wx.showToast({ title: "回测完成", icon: "success" });
      if (response.taskId) {
        wx.navigateTo({
          url: `/pages/task-detail/task-detail?taskId=${response.taskId}`
        });
      } else {
        await this.refresh();
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.setData({ error: message });
      wx.showToast({ title: "回测失败", icon: "error" });
    } finally {
      this.setData({ running: false });
    }
  },

  openLatestTask() {
    const task = this.data.latestTask;
    if (!task?.taskId) {
      return;
    }
    wx.navigateTo({
      url: `/pages/task-detail/task-detail?taskId=${task.taskId}`
    });
  },

  openTasks() {
    wx.switchTab({ url: "/pages/tasks/tasks" });
  }
});
