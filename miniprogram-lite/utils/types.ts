export type TaskStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED";
export type SignalType = "BUY" | "SELL" | "HOLD";
export type OrderSide = "BUY" | "SELL";

export interface HealthResponse {
  status: string;
  storeType?: string;
  now?: string;
}

export interface BacktestRequest {
  stockCode: string;
  startDate: string;
  endDate: string;
  fastWindow: number;
  slowWindow: number;
  initialCash: number;
  maxPositionRatio: number;
  stopLossRatio: number;
}

export interface BacktestMetrics {
  initialCash: number;
  finalEquity: number;
  totalReturn: number;
  maxDrawdown: number;
  tradeCount: number;
  winRate: number;
}

export interface ReferenceBacktestTask {
  taskId: string;
  stockCode: string;
  startDate: string;
  endDate: string;
  fastWindow: number;
  slowWindow: number;
  initialCash: number;
  maxPositionRatio: number;
  stopLossRatio: number;
  status: TaskStatus;
  errorMessage?: string;
  createdAt?: string;
  completedAt?: string;
  metrics?: BacktestMetrics;
}

export interface ReferenceRunResponse {
  taskId: string;
  status: TaskStatus;
  task?: ReferenceBacktestTask;
  message?: string;
}

export interface TradingSignal {
  tradeDate: string;
  stockCode: string;
  signalType: SignalType;
  price: number;
  fastAverage: number;
  slowAverage: number;
  reason: string;
}

export interface SimulatedOrder {
  orderId: string;
  taskId: string;
  tradeDate: string;
  stockCode: string;
  side: OrderSide;
  price: number;
  quantity: number;
  amount: number;
  riskReason: string;
}

export interface ReferenceQualityCheck {
  name: string;
  passed: boolean;
  actualValue?: number;
  threshold?: number;
  message: string;
}

export interface ReferenceQualityReport {
  taskId: string;
  passed: boolean;
  summary: string;
  checks: ReferenceQualityCheck[];
  suggestions: string[];
}
