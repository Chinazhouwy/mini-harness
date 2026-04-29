App({
  globalData: {
    bootAt: new Date().toISOString()
  },

  onLaunch() {
    console.info("QuantHarness MiniProgram Lite launched");
  }
});

interface IAppOption {
  globalData: {
    bootAt: string;
  };
}
