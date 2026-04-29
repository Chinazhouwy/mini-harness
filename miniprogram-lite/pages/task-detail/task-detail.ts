import { getTask, getTaskOrders, getTaskQuality, getTaskSignals } from "../../utils/api";
import { compactId, money, percent, signalText, statusText } from "../../utils/format";
import type {
  ReferenceBacktestTask,
  ReferenceQualityReport,
  SimulatedOrder,
  TradingSignal
} from "../../utils/types";

interface TaskDetailView {
  stockCode: string;
  dateRange: string;
  shortId: string;
  statusLabel: string;
  totalReturnLabel: string;
  maxDrawdownLabel: string;
  finalEquityLabel: string;
  tradeCount: number;
}

interface SignalView {
  key: string;
  signalLabel: string;
  tradeDate: string;
  line: string;
  reason: string;
}

interface OrderView {
  orderId: string;
  side: string;
  tradeDate: string;
  line: string;
  riskReason: string;
}

function toTaskDetailView(task: ReferenceBacktestTask): TaskDetailView {
  return {
    stockCode: task.stockCode,
    dateRange: `${task.startDate} 至 ${task.endDate}`,
    shortId: compactId(task.taskId),
    statusLabel: statusText(task.status),
    totalReturnLabel: percent(task.metrics?.totalReturn),
    maxDrawdownLabel: percent(task.metrics?.maxDrawdown),
    finalEquityLabel: money(task.metrics?.finalEquity),
    tradeCount: task.metrics?.tradeCount || 0
  };
}

function toSignalView(signal: TradingSignal): SignalView {
  return {
    key: `${signal.tradeDate}-${signal.signalType}`,
    signalLabel: signalText(signal.signalType),
    tradeDate: signal.tradeDate,
    line: `价格 ${money(signal.price)} · 快线 ${money(signal.fastAverage)} · 慢线 ${money(signal.slowAverage)}`,
    reason: signal.reason
  };
}

function toOrderView(order: SimulatedOrder): OrderView {
  return {
    orderId: order.orderId,
    side: order.side,
    tradeDate: order.tradeDate,
    line: `数量 ${order.quantity} · 成交额 ${money(order.amount)}`,
    riskReason: order.riskReason
  };
}

Page({
  data: {
    taskId: "",
    loading: false,
    task: null as ReferenceBacktestTask | null,
    taskView: null as TaskDetailView | null,
    signals: [] as TradingSignal[],
    signalViews: [] as SignalView[],
    orders: [] as SimulatedOrder[],
    orderViews: [] as OrderView[],
    quality: null as ReferenceQualityReport | null,
    error: ""
  },

  onLoad(query: Record<string, string>) {
    if (!query.taskId) {
      this.setData({ error: "缺少 taskId" });
      return;
    }
    this.setData({ taskId: query.taskId });
    this.loadDetail();
  },

  onPullDownRefresh() {
    this.loadDetail().finally(() => wx.stopPullDownRefresh());
  },

  async loadDetail() {
    const taskId = this.data.taskId;
    if (!taskId) {
      return;
    }

    this.setData({ loading: true, error: "" });
    try {
      const [task, signals, orders, quality] = await Promise.all([
        getTask(taskId),
        getTaskSignals(taskId),
        getTaskOrders(taskId),
        getTaskQuality(taskId)
      ]);

      this.setData({
        task,
        taskView: toTaskDetailView(task),
        signals,
        signalViews: signals.map(toSignalView),
        orders,
        orderViews: orders.map(toOrderView),
        quality
      });
    } catch (error) {
      this.setData({ error: error instanceof Error ? error.message : String(error) });
    } finally {
      this.setData({ loading: false });
    }
  }
});
