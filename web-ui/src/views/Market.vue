<template>
  <div class="market">
    <h1>行情与链路说明</h1>
    <section>
      <h2>当前数据源</h2>
      <p>后端通过 `DailyKlineRecordRepository` 从 ClickHouse `harness_db.daily_kline` 读取日 K 数据。</p>
    </section>
    <section>
      <h2>一期链路</h2>
      <ol>
        <li>读取指定股票和日期区间的日 K。</li>
        <li>转换为策略内部模型 `MarketBar`。</li>
        <li>生成双均线买卖信号。</li>
        <li>通过仓位上限与止损规则。</li>
        <li>生成模拟订单、权益曲线和质检反馈。</li>
      </ol>
    </section>
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
.market {
  display: grid;
  gap: 18px;
  max-width: 860px;
}

h1 {
  margin: 0;
}

section {
  padding: 18px;
  border: 1px solid #d8ded8;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
}

h2 {
  margin: 0 0 10px;
}

p,
li {
  line-height: 1.7;
}
</style>
