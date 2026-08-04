const state = {
    data: null,
    range: 31,
    chartMetric: "clearance",
    selectedTask: "collect-market-data"
};

const taskDetails = {
    "collect-market-data": "读取 280 个交易日的原始观测，并关联交易日期与来源证据。",
    "calculate-metrics": "计算杠杆比例、融资强度、增长率、出清幅度、净偿还压力和波动率。",
    "score-market-state": "用历史分位数统一量纲，分别生成 Heat、Clear 和 Confirmation。",
    "write-report": "报告已通过结构验证：状态、评分、数据来源和风险限制均存在。"
};

const regimeCopy = {
    POTENTIAL_CLEARANCE: "去杠杆已经发生，价格和市场宽度出现初步承接，但去杠杆尚未确认减速。",
    CLEARANCE_UNCONFIRMED: "出清分数较高，但价格和市场宽度还没有给出足够确认。",
    DELEVERAGING: "融资杠杆正在收缩，当前更适合观察出清速度和价格反馈。",
    OVERHEATED: "杠杆扩张和融资交易强度偏高，市场处于拥挤观察区。",
    LOW_LEVERAGE: "公开可观测杠杆处于历史低位，市场压力相对有限。",
    NORMAL: "杠杆、出清与价格确认尚未形成极端组合。"
};

const chartDefinitions = {
    clearance: { key: "clearanceFromPeak250", label: "距融资高点回撤", color: "#69d1da" },
    growth60: { key: "marginGrowth60", label: "60D 融资余额变化", color: "#f3b86a" },
    leverage: { key: "leverageRatio", label: "融资余额 / 流通市值", color: "#b99cff" },
    breadth: { key: "upRatio", label: "上涨股票比例", color: "#8dd6a8" }
};

document.addEventListener("DOMContentLoaded", init);

async function init() {
    bindTabs();
    bindRanges();
    bindMetrics();
    bindPipeline();
    window.addEventListener("resize", drawChart);
    try {
        const response = await fetch("/api/finance/leverage", { headers: { Accept: "application/json" } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        state.data = await response.json();
        setConnection(true, "API 已连接");
    } catch (error) {
        state.data = fallbackData();
        setConnection(false, "Fixture 预览");
    }
    render();
}

function render() {
    const { data } = state;
    const { score, latest } = data;
    text("marketName", data.market);
    text("heroAsOf", data.to);
    text("asOf", `AS OF ${data.to}`);
    text("regimeText", humanRegime(score.regime));
    text("regimeDescription", regimeCopy[score.regime] || "状态已计算，等待进一步解释。");
    const regimePill = document.getElementById("regimePill");
    if (regimePill?.lastChild) regimePill.lastChild.textContent = humanRegime(score.regime);

    setScore("heatScore", "heatBar", score.heatScore);
    setScore("clearScore", "clearBar", score.clearScore);
    setScore("confirmScore", "confirmBar", score.confirmationScore);
    renderTakeaway(score, latest);
    renderScoreDrivers(score, latest);
    renderInterpretation(score, latest);
    renderMetrics(latest);
    renderEvidence(data.sources, score);
    updateTaskDetail();
    drawChart();
}

function renderTakeaway(score, latest) {
    const indexDistance = latest.indexLow20 === 0 ? 0 : latest.indexClose / latest.indexLow20 - 1;
    const confirmed = ["indexStabilized", "breadthRecovering"].filter((key) => evidenceBoolean(score, key)).length;
    text("clearScoreHeadline", `${Math.round(score.clearScore)} / 100`);
    text("confirmHeadline", `${confirmed} / 3`);
    text("growthHeadline", percent(latest.marginGrowth60));
    text("headlineLeverage", percent(latest.leverageRatio));
    text("leverageContext", `融资余额 ${money(latest.financingBalanceYuan)}`);
    text("headlineGrowth20", percent(latest.marginGrowth20));
    text("headlineIndexDistance", signedPercent(indexDistance));
    text("headlineBreadth", percent(latest.upRatio));
    text("breadthContext", `20D 变化 ${signedPercent(latest.breadthChange20)}`);
}

function renderScoreDrivers(score, latest) {
    renderDrivers("heatDrivers", [
        ["杠杆比例历史分位", percentile(score.leveragePercentile)],
        ["融资买入强度分位", percentile(score.buyIntensityPercentile)],
        ["60D 融资余额变化", percent(latest.marginGrowth60)]
    ]);
    renderDrivers("clearDrivers", [
        ["距融资高点回撤", percent(latest.clearanceFromPeak250)],
        ["20D 净偿还压力", percent(latest.netRepaymentPressure20)],
        ["20D 实现波动率", precisePercent(latest.realizedVolatility20)]
    ]);
    renderDrivers("confirmDrivers", [
        ["指数距离 20D 低点", signedPercent(latest.indexClose / latest.indexLow20 - 1)],
        ["上涨股票比例", percent(latest.upRatio)],
        ["市场宽度 20D 变化", signedPercent(latest.breadthChange20)]
    ]);
}

function renderDrivers(id, rows) {
    const element = document.getElementById(id);
    if (!element) return;
    element.innerHTML = rows.map(([label, value]) => `<div><span>${label}</span><b>${value}</b></div>`).join("");
}

function bindTabs() {
    document.querySelectorAll(".tab").forEach((button) => {
        button.addEventListener("click", () => {
            const view = button.dataset.view;
            document.querySelectorAll(".tab").forEach((tab) => {
                const active = tab === button;
                tab.classList.toggle("active", active);
                tab.setAttribute("aria-selected", String(active));
            });
            document.querySelectorAll(".view-panel").forEach((panel) => {
                const active = panel.dataset.panel === view;
                panel.classList.toggle("active", active);
                panel.hidden = !active;
            });
            if (view === "overview") drawChart();
        });
    });
}

function bindRanges() {
    document.querySelectorAll(".range-switch button").forEach((button) => {
        button.addEventListener("click", () => {
            document.querySelectorAll(".range-switch button").forEach((item) => item.classList.remove("active"));
            button.classList.add("active");
            state.range = button.dataset.range === "all" ? "all" : Number(button.dataset.range);
            drawChart();
        });
    });
}

function bindMetrics() {
    document.querySelectorAll(".metric-switch button").forEach((button) => {
        button.addEventListener("click", () => {
            document.querySelectorAll(".metric-switch button").forEach((item) => item.classList.remove("active"));
            button.classList.add("active");
            state.chartMetric = button.dataset.metric;
            drawChart();
        });
    });
}

function bindPipeline() {
    document.querySelectorAll(".pipeline-step").forEach((button) => {
        button.addEventListener("click", () => {
            document.querySelectorAll(".pipeline-step").forEach((item) => item.classList.remove("active"));
            button.classList.add("active");
            state.selectedTask = button.dataset.task;
            updateTaskDetail();
        });
    });
}

function renderInterpretation(score, latest) {
    const declineSlowing = evidenceBoolean(score, "declineSlowing");
    const indexStabilized = evidenceBoolean(score, "indexStabilized");
    const breadthRecovering = evidenceBoolean(score, "breadthRecovering");
    const indexDistance = latest.indexClose / latest.indexLow20 - 1;
    const signals = [
        ["去杠杆是否减速", declineSlowing, `5D ${percent(latest.marginGrowth5)} · 20D ${percent(latest.marginGrowth20)}`],
        ["指数是否脱离 20D 低点", indexStabilized, `当前高于低点 ${signedPercent(indexDistance)}`],
        ["市场宽度是否恢复", breadthRecovering, `20D 变化 ${signedPercent(latest.breadthChange20)}`]
    ];
    text("interpretationCopy", regimeCopy[score.regime] || "状态已计算，等待进一步解释。");
    document.getElementById("signalList").innerHTML = signals.map(([label, value, context]) => `
        <div class="signal-item"><div><span>${label}</span><small>${context}</small></div><b class="${value ? "yes" : "no"}">${value ? "YES" : "NO"}</b></div>
    `).join("");
    text("nextWatch", declineSlowing
        ? "去杠杆已经出现减速，继续观察价格承接和上涨股票比例是否保持。"
        : `融资余额 5D 变化仍为 ${percent(latest.marginGrowth5)}，尚未看到明确减速；优先观察短期变化是否持续收窄。`);
}

function renderMetrics(latest) {
    const rows = [
        ["融资余额", money(latest.financingBalanceYuan)],
        ["融资买入 / 市场成交", percent(latest.buyIntensity)],
        ["融资偿还", money(latest.financingRepaymentYuan)],
        ["60D 融资变化", percent(latest.marginGrowth60)],
        ["距离融资高点", percent(latest.clearanceFromPeak250)],
        ["20D 净偿还压力", percent(latest.netRepaymentPressure20)],
        ["指数收盘", number(latest.indexClose)],
        ["上涨股票比例", percent(latest.upRatio)]
    ];
    document.getElementById("metricList").innerHTML = rows.map(([label, value]) => `
        <div class="metric-row"><span>${label}</span><b>${value}</b></div>
    `).join("");
}

function renderEvidence(sources, score) {
    const items = [
        ...sources.map((source) => ({ title: source.name, note: `${source.uri} · ${source.unit}`, date: source.dataDate })),
        { title: "评分证据", note: score.evidence.join(" · "), date: score.tradeDate }
    ];
    document.getElementById("evidenceList").innerHTML = items.map((item, index) => `
        <div class="evidence-item"><span class="evidence-icon">${String(index + 1).padStart(2, "0")}</span><div><strong>${item.title}</strong><p>${item.note}</p></div><time>${item.date}</time></div>
    `).join("");
}

function updateTaskDetail() {
    text("taskDetail", taskDetails[state.selectedTask] || "任务已完成。");
}

function drawChart() {
    const canvas = document.getElementById("regimeChart");
    if (!canvas || !state.data || canvas.offsetWidth === 0) return;
    const definition = chartDefinitions[state.chartMetric] || chartDefinitions.clearance;
    const series = state.range === "all" ? state.data.history : state.data.history.slice(-state.range);
    if (!series.length) return;
    const values = series.map((item) => Number(item[definition.key]) * 100);
    const latestValue = Number(state.data.latest[definition.key]);
    text("chartMetricLabel", definition.label);
    text("chartMetricValue", percent(latestValue));
    text("chartMetricNote", state.range === "all"
        ? `全部 ${series.length} 个有效快照 · 选择单一指标，避免不同量纲叠加误读。`
        : `${series.length} 个有效快照 · 选择单一指标，避免不同量纲叠加误读。`);
    text("chartDate", `LATEST / ${state.data.to}`);
    const legend = document.getElementById("chartLegend");
    if (legend) legend.style.background = definition.color;

    const rect = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.floor(rect.width * ratio));
    canvas.height = Math.max(1, Math.floor(rect.height * ratio));
    const ctx = canvas.getContext("2d");
    ctx.scale(ratio, ratio);
    const width = rect.width;
    const height = rect.height;
    const padding = { left: 44, right: 14, top: 18, bottom: 30 };
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = Math.max(max - min, 0.1);
    const extra = Math.max(span * 0.2, state.chartMetric === "leverage" ? 0.03 : 0.5);
    const yMin = Math.floor((min - extra) * 10) / 10;
    const yMax = Math.ceil((max + extra) * 10) / 10;
    const plotWidth = width - padding.left - padding.right;
    const plotHeight = height - padding.top - padding.bottom;
    const x = (index) => padding.left + (series.length === 1 ? plotWidth / 2 : index / (series.length - 1) * plotWidth);
    const y = (value) => padding.top + plotHeight - (value - yMin) / (yMax - yMin) * plotHeight;

    ctx.clearRect(0, 0, width, height);
    ctx.font = "10px ui-monospace, monospace";
    ctx.textAlign = "right";
    for (let tick = 0; tick <= 4; tick++) {
        const value = yMin + (yMax - yMin) * tick / 4;
        const lineY = y(value);
        ctx.strokeStyle = "rgba(255,255,255,.08)";
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(padding.left, lineY); ctx.lineTo(width - padding.right, lineY); ctx.stroke();
        ctx.fillStyle = "#647078"; ctx.fillText(`${value.toFixed(1)}%`, padding.left - 8, lineY + 3);
    }
    ctx.textAlign = "left";
    ctx.fillStyle = "#647078";
    ctx.fillText(series[0].tradeDate, padding.left, height - 7);
    ctx.textAlign = "right";
    ctx.fillText(series[series.length - 1].tradeDate, width - padding.right, height - 7);

    ctx.strokeStyle = definition.color;
    ctx.lineWidth = 2.5;
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    ctx.beginPath();
    series.forEach((item, index) => {
        if (index === 0) ctx.moveTo(x(index), y(values[index]));
        else ctx.lineTo(x(index), y(values[index]));
    });
    ctx.stroke();
    ctx.fillStyle = definition.color;
    ctx.beginPath(); ctx.arc(x(series.length - 1), y(values[values.length - 1]), 4, 0, Math.PI * 2); ctx.fill();
    canvas.onmousemove = (event) => showTooltip(event, canvas, series, definition);
    canvas.onmouseleave = () => { document.getElementById("chartTooltip").style.display = "none"; };
}

function showTooltip(event, canvas, series, definition) {
    const rect = canvas.getBoundingClientRect();
    const cursorX = event.clientX - rect.left;
    const index = Math.max(0, Math.min(series.length - 1, Math.round((cursorX - 44) / (rect.width - 58) * (series.length - 1))));
    const item = series[index];
    const tooltip = document.getElementById("chartTooltip");
    tooltip.innerHTML = `<b>${item.tradeDate}</b><br>${definition.label} ${percent(item[definition.key])}`;
    tooltip.style.display = "block";
    tooltip.style.left = `${Math.min(Math.max(12, cursorX + 10), rect.width - 185)}px`;
    tooltip.style.top = "12px";
}

function setScore(id, barId, value) {
    text(id, Math.round(value));
    requestAnimationFrame(() => { document.getElementById(barId).style.width = `${Math.max(0, Math.min(100, value))}%`; });
}

function setConnection(ready, label) {
    const connection = document.querySelector(".connection");
    connection.classList.toggle("ready", ready);
    text("connectionText", label);
}

function evidenceBoolean(score, key) {
    return score.evidence.find((item) => item.startsWith(`${key}=`))?.endsWith("true") || false;
}

function text(id, value) {
    const element = document.getElementById(id);
    if (element) element.textContent = value;
}

function percent(value) { return `${(Number(value) * 100).toFixed(2)}%`; }
function precisePercent(value) { return `${(Number(value) * 100).toFixed(4)}%`; }
function signedPercent(value) { const numeric = Number(value) * 100; return `${numeric >= 0 ? "+" : ""}${numeric.toFixed(2)}%`; }
function percentile(value) { return `${Number(value).toFixed(1)} 分位`; }
function number(value) { return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 }); }
function money(value) {
    const numeric = Number(value);
    if (Math.abs(numeric) >= 1e12) return `¥${(numeric / 1e12).toFixed(2)}T`;
    if (Math.abs(numeric) >= 1e9) return `¥${(numeric / 1e9).toFixed(1)}B`;
    return `¥${number(numeric)}`;
}
function humanRegime(value) { return ({ POTENTIAL_CLEARANCE: "潜在出清", CLEARANCE_UNCONFIRMED: "出清待确认", DELEVERAGING: "去杠杆中", OVERHEATED: "杠杆偏热", LOW_LEVERAGE: "杠杆低位", NORMAL: "中性区间" })[value] || value; }

function fallbackData() {
    const history = Array.from({ length: 31 }, (_, index) => ({
        tradeDate: `2026-${String(index + 1).padStart(2, "0")}-01`,
        leverageRatio: .027 - index * .000015,
        clearanceFromPeak250: .08 + index * .0034,
        marginGrowth60: -.02 - index * .0026,
        netRepaymentPressure20: .7 + index * .015,
        upRatio: .46 + Math.min(index, 25) * .004,
        indexClose: 3020 + index * 2
    }));
    const latest = { ...history.at(-1), financingBalanceYuan: 2041500000000, financingBuyYuan: 450000000000, financingRepaymentYuan: 580000000000, circulatingMarketCapYuan: 80000000000000, marketTurnoverYuan: 8000000000000, marginGrowth5: -.003, marginGrowth20: -.014, realizedVolatility20: 0, indexLow20: 3000, breadthChange20: .07 };
    return { market: "A_SHARE_PUBLIC_LEVERAGE_FIXTURE", from: history[0].tradeDate, to: latest.tradeDate, latest, score: { tradeDate: latest.tradeDate, leveragePercentile: 3.2, buyIntensityPercentile: 51.6, marginGrowth60Percentile: 0, clearancePercentile: 100, repaymentPercentile: 67.7, volatilityPercentile: 3.2, heatScore: 17, clearScore: 74, confirmationScore: 67, regime: "POTENTIAL_CLEARANCE", evidence: ["declineSlowing=false", "indexStabilized=true", "breadthRecovering=true"] }, history, sources: [{ name: "Fixture A 股公开杠杆数据", uri: "fixture://a-share-leverage", unit: "yuan / ratio", dataDate: latest.tradeDate }] };
}
