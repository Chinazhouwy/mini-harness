export function percent(value?: number): string {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return "--";
  }
  return `${(value * 100).toFixed(2)}%`;
}

export function money(value?: number): string {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return "--";
  }
  return value.toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
}

export function compactId(taskId?: string): string {
  if (!taskId) {
    return "--";
  }
  return taskId.length > 12 ? `${taskId.slice(0, 8)}...${taskId.slice(-4)}` : taskId;
}

export function statusText(status?: string): string {
  switch (status) {
    case "COMPLETED":
      return "已完成";
    case "FAILED":
      return "失败";
    case "RUNNING":
      return "运行中";
    case "PENDING":
      return "等待中";
    default:
      return "未知";
  }
}

export function signalText(signal?: string): string {
  switch (signal) {
    case "BUY":
      return "买入";
    case "SELL":
      return "卖出";
    case "HOLD":
      return "观望";
    default:
      return "--";
  }
}
