const state = {
    data: null,
    range: 31,
    selectedTask: "collect-market-data",
    canvasPoints: []
};

const taskDetails = {
    "collect-market-data": "正在读取原始数据，并关联交易日期与来源证据。",
    "calculate-metrics": "从原始 Artifact 计算杠杆比例、融资强度、增长率、出清幅度和波动率。",
    "score-market-state": "将不同量纲统一到历史分位数，生成 Heat、Clear 和 Confirmation。",
    "write-report": "报告已通过结构验证：状态、评分、数据来源和风险限制均存在。"
};

const regimeCopy = {
    POTENTIAL_CLEARANCE: "杠杆已经释放了一部分，价格与市场宽度出现初步承接迹象。",
    CLEARANCE_UNCONFIRMED: "出清分数较高，但价格和市场宽度还没有给出足够确认。",
    DELEVERAGING: "融资杠杆正在收缩，当前更适合观察出清速度和价格反馈。",
    OVERHEATED: "杠杆扩张和融资交易强度偏高，市场处于拥挤观察区。",
    LOW_LEVERAGE: "公开可观测杠杆处于历史低位，市场压力相对有限。",
    NORMAL: "杠杆、出清与价格确认尚未形成极端组合。"
};

document.addEventListener("DOMContentLoaded", init);

async function init() {
    bindTabs();
    bindRanges();
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
    text("dateRange", `${data.from} — ${data.to}`);
    text("asOf", `AS OF ${data.to}`);
    text("regimeText", humanRegime(score.regime));
    text("regimeDescription", regimeCopy[score.regime] || "状态已计算，等待进一步解释。");
    const regimePill = document.getElementById("regimePill");
    if (regimePill?.lastChild) regimePill.lastChild.textContent = humanRegime(score.regime);
    setScore("heatScore", "heatBar", score.heatScore);
    setScore("clearScore", "clearBar", score.clearScore);
    setScore("confirmScore", "confirmBar", score.confirmationScore);
    text("latestLeverage", percent(latest.leverageRatio));
    text("latestClearance", percent(latest.clearanceFromPeak250));
    text("chartDate", `LATEST / ${data.to}`);
    renderInterpretation(score);
    renderMetrics(latest);
    renderEvidence(data.sources, score);
    updateTaskDetail();
    drawChart();
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

function renderInterpretation(score) {
    const signals = [
        ["去杠杆是否减速", score.evidence.find((item) => item.startsWith("declineSlowing="))?.split("=")[1] === "true"],
        ["指数是否脱离 20D 低点", score.evidence.find((item) => item.startsWith("indexStabilized="))?.split("=")[1] === "true"],
        ["市场宽度是否恢复", score.evidence.find((item) => item.startsWith("breadthRecovering="))?.split("=")[1] === "true"]
    ];
    text("interpretationCopy", regimeCopy[score.regime] || "状态已计算，等待进一步解释。");
    document.getElementById("signalList").innerHTML = signals.map(([label, value]) => `
        <div class="signal-item"><span>${label}</span><b class="${value ? "yes" : "no"}">${value ? "YES" : "NO"}</b></div>
    `).join("");
}

function renderMetrics(latest) {
    const rows = [
        ["融资余额 / 流通市值", percent(latest.leverageRatio)],
        ["融资买入 / 市场成交", percent(latest.buyIntensity)],
        ["60D 融资变化", percent(latest.marginGrowth60)],
        ["高点回撤", percent(latest.clearanceFromPeak250)],
        ["20D 净偿还压力", percent(latest.netRepaymentPressure20)],
        ["20D 实现波动率", percent(latest.realizedVolatility20)],
        ["上涨股票比例", percent(latest.upRatio)],
        ["指数收盘", number(latest.indexClose)]
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
    const rect = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.max(1, Math.floor(rect.width * ratio));
    canvas.height = Math.max(1, Math.floor(rect.height * ratio));
    const ctx = canvas.getContext("2d");
    ctx.scale(ratio, ratio);
    const width = rect.width;
    const height = rect.height;
    const padding = { left: 35, right: 10, top: 17, bottom: 28 };
    const series = state.range === "all" ? state.data.history : state.data.history.slice(-state.range);
    const max = Math.max(20, ...series.map((item) => Math.max(item.leverageRatio * 100, item.clearanceFromPeak250 * 100)));
    const yMax = Math.ceil(max / 5) * 5;
    const plotWidth = width - padding.left - padding.right;
    const plotHeight = height - padding.top - padding.bottom;
    const x = (index) => padding.left + (series.length === 1 ? plotWidth / 2 : index / (series.length - 1) * plotWidth);
    const y = (value) => padding.top + plotHeight - value / yMax * plotHeight;
    ctx.clearRect(0, 0, width, height);
    ctx.font = "10px ui-monospace, monospace";
    ctx.textAlign = "right";
    for (let tick = 0; tick <= yMax; tick += 5) {
        const lineY = y(tick);
        ctx.strokeStyle = "rgba(255,255,255,.08)";
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(padding.left, lineY); ctx.lineTo(width - padding.right, lineY); ctx.stroke();
        ctx.fillStyle = "#647078"; ctx.fillText(`${tick}%`, padding.left - 8, lineY + 3);
    }
    ctx.textAlign = "left";
    ctx.fillStyle = "#647078";
    ctx.fillText(series[0].tradeDate, padding.left, height - 7);
    ctx.textAlign = "right";
    ctx.fillText(series[series.length - 1].tradeDate, width - padding.right, height - 7);
    const drawLine = (key, color) => {
        ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.lineCap = "round"; ctx.beginPath();
        series.forEach((item, index) => { const value = item[key] * 100; if (index === 0) ctx.moveTo(x(index), y(value)); else ctx.lineTo(x(index), y(value)); });
        ctx.stroke();
    };
    drawLine("leverageRatio", "#69d1da");
    drawLine("clearanceFromPeak250", "#f3b86a");
    state.canvasPoints = series.map((item, index) => ({ item, x: x(index), y: y(Math.max(item.leverageRatio * 100, item.clearanceFromPeak250 * 100)) }));
    canvas.onmousemove = (event) => showTooltip(event, canvas, series, x);
    canvas.onmouseleave = () => { document.getElementById("chartTooltip").style.display = "none"; };
}

function showTooltip(event, canvas, series, x) {
    const rect = canvas.getBoundingClientRect();
    const cursorX = event.clientX - rect.left;
    const index = Math.max(0, Math.min(series.length - 1, Math.round((cursorX - 35) / (rect.width - 45) * (series.length - 1))));
    const item = series[index];
    const tooltip = document.getElementById("chartTooltip");
    tooltip.innerHTML = `<b>${item.tradeDate}</b><br>杠杆 ${percent(item.leverageRatio)}<br>回撤 ${percent(item.clearanceFromPeak250)}`;
    tooltip.style.display = "block";
    tooltip.style.left = `${Math.min(Math.max(12, cursorX + 10), rect.width - 165)}px`;
    tooltip.style.top = "12px";
}

function setScore(id, barId, value) { text(id, Math.round(value)); requestAnimationFrame(() => { document.getElementById(barId).style.width = `${Math.max(0, Math.min(100, value))}%`; }); }
function setConnection(ready, label) { const connection = document.querySelector(".connection"); connection.classList.toggle("ready", ready); text("connectionText", label); }
function text(id, value) { const element = document.getElementById(id); if (element) element.textContent = value; }
function percent(value) { return `${(Number(value) * 100).toFixed(2)}%`; }
function number(value) { return Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 }); }
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
    const latest = history.at(-1);
    return { market: "A_SHARE_PUBLIC_LEVERAGE_FIXTURE", from: history[0].tradeDate, to: latest.tradeDate, latest: { ...latest, financingBalanceYuan: 2041500000000, financingBuyYuan: 450000000000, financingRepaymentYuan: 580000000000, circulatingMarketCapYuan: 80000000000000, marketTurnoverYuan: 8000000000000, marginGrowth5: -.003, marginGrowth20: -.014, realizedVolatility20: 0 }, score: { tradeDate: latest.tradeDate, heatScore: 42, clearScore: 74, confirmationScore: 67, regime: "POTENTIAL_CLEARANCE", evidence: ["declineSlowing=false", "indexStabilized=true", "breadthRecovering=true"] }, history, sources: [{ name: "Fixture A 股公开杠杆数据", uri: "fixture://a-share-leverage", unit: "yuan / ratio", dataDate: latest.tradeDate }] };
}
