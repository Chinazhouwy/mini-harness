import { API_BASE_URL } from "./config";
import type {
  BacktestRequest,
  HealthResponse,
  ReferenceBacktestTask,
  ReferenceQualityReport,
  ReferenceRunResponse,
  SimulatedOrder,
  TradingSignal
} from "./types";

type HttpMethod = "GET" | "POST";

function request<T>(path: string, method: HttpMethod = "GET", data?: unknown): Promise<T> {
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${API_BASE_URL}${path}`,
      method,
      data,
      header: {
        "content-type": "application/json"
      },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T);
          return;
        }

        reject(new Error(`HTTP ${response.statusCode}: ${JSON.stringify(response.data)}`));
      },
      fail(error) {
        reject(new Error(error.errMsg || "request failed"));
      }
    });
  });
}

export function getHealth(): Promise<HealthResponse> {
  return request<HealthResponse>("/api/reference/health");
}

export function runBacktest(payload: BacktestRequest): Promise<ReferenceRunResponse> {
  return request<ReferenceRunResponse>("/api/reference/backtest", "POST", payload);
}

export function listTasks(limit = 20): Promise<ReferenceBacktestTask[]> {
  return request<ReferenceBacktestTask[]>(`/api/reference/tasks?limit=${limit}`);
}

export function getTask(taskId: string): Promise<ReferenceBacktestTask> {
  return request<ReferenceBacktestTask>(`/api/reference/tasks/${taskId}`);
}

export function getTaskSignals(taskId: string): Promise<TradingSignal[]> {
  return request<TradingSignal[]>(`/api/reference/tasks/${taskId}/signals`);
}

export function getTaskOrders(taskId: string): Promise<SimulatedOrder[]> {
  return request<SimulatedOrder[]>(`/api/reference/tasks/${taskId}/orders`);
}

export function getTaskQuality(taskId: string): Promise<ReferenceQualityReport> {
  return request<ReferenceQualityReport>(`/api/reference/tasks/${taskId}/quality`);
}
