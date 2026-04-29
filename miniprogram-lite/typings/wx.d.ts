declare function App<T extends object>(options: T): void;
declare function Page<T extends object>(
  options: T & ThisType<T & { data: Record<string, unknown>; setData(data: Record<string, unknown>): void }>
): void;

declare namespace WechatMiniprogram {
  interface TouchEvent {
    currentTarget: {
      dataset: Record<string, string>;
    };
  }

  interface RequestSuccessCallbackResult {
    statusCode: number;
    data: unknown;
  }

  interface GeneralCallbackResult {
    errMsg: string;
  }
}

declare const wx: {
  request(options: {
    url: string;
    method?: string;
    data?: unknown;
    header?: Record<string, string>;
    success?: (response: WechatMiniprogram.RequestSuccessCallbackResult) => void;
    fail?: (error: WechatMiniprogram.GeneralCallbackResult) => void;
  }): void;
  navigateTo(options: { url: string }): void;
  switchTab(options: { url: string }): void;
  showToast(options: { title: string; icon?: "success" | "error" | "loading" | "none" }): void;
  stopPullDownRefresh(): void;
};
