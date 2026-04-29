// 模拟器通常可以访问 127.0.0.1；真机调试请改成电脑局域网 IP 或 HTTPS 域名。
export const API_BASE_URL = "http://127.0.0.1:8082";

export const DEFAULT_BACKTEST_REQUEST = {
  stockCode: "000001",
  startDate: "2024-01-01",
  endDate: "2024-12-31",
  fastWindow: 5,
  slowWindow: 20,
  initialCash: 100000,
  maxPositionRatio: 0.8,
  stopLossRatio: 0.08
};
