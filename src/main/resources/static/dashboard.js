const state = { data: null, selectedId: null };

document.addEventListener("DOMContentLoaded", init);

async function init() {
    try {
        const response = await fetch("/api/finance/research", { headers: { Accept: "application/json" } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        state.data = await response.json();
        state.selectedId = state.data.markets[0]?.instrument.id || null;
        setConnection(true, `${state.data.markets.length} 个市场已连接`);
        render();
    } catch (error) {
        setConnection(false, "真实数据不可用");
        renderError(error);
    }
    window.addEventListener("resize", renderSelectedCharts);
}

function render() {
    const data = state.data;
    if (!data || !data.markets?.length) return renderError(new Error("没有返回可绘制的市场序列"));
    text("statusBadge", data.status || "REAL DATA");
    text("marketCount", data.markets.length);
    text("retrievedAt", `抓取 ${formatInstant(data.retrievedAt)}`);
    text("sourceAsOf", `RETRIEVED / ${formatInstant(data.retrievedAt)}`);
    renderCoverage();
    renderTabs();
    renderSelected();
}

function renderCoverage() {
    const unavailable = dataUnavailableMarkets();
    text("coverageMarkets", state.data.markets.map((market) => market.instrument.market).join(" · "));
    text("coverageMarketsNote", unavailable.length ? `未接入/失败：${unavailable.join("；")}` : "真实接口返回成功的市场");
    const koreaNote = unavailable.find((item) => item.includes("韩股"));
    text("coverageKoreaNote", koreaNote || "当前未发现韩国数据源限制");
}

function dataUnavailableMarkets() {
    return Array.isArray(state.data?.unavailableMarkets) ? state.data.unavailableMarkets : [];
}

function renderTabs() {
    const container = document.getElementById("marketTabs");
    container.innerHTML = state.data.markets.map((market) => {
        const instrument = market.instrument;
        const active = instrument.id === state.selectedId;
        return `<button class="market-tab ${active ? "active" : ""}" data-market="${escapeHtml(instrument.id)}" aria-pressed="${active}"><span>${escapeHtml(instrument.market)}</span><b>${escapeHtml(instrument.symbol)}</b><small>${escapeHtml(instrument.name)}</small></button>`;
    }).join("");
    container.querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
        state.selectedId = button.dataset.market;
        renderTabs();
        renderSelected();
    }));
}

function renderSelected() {
    const market = state.data.markets.find((item) => item.instrument.id === state.selectedId) || state.data.markets[0];
    if (!market) return;
    state.selectedId = market.instrument.id;
    const instrument = market.instrument;
    const fit = market.fit;
    text("selectedName", instrument.name);
    text("selectedDefinition", `${instrument.market} · ${instrument.symbol} · ${instrument.leverageDefinition}`);
    text("selectedTitle", `${instrument.market} / ${instrument.name}`);
    text("dateRange", `${market.from} → ${market.to}`);
    text("latestPrice", `最新 ${formatPrice(market.latestClose, instrument.currency)}`);
    text("latestLeverage", `杠杆 ${formatLeverage(market.latestLeverage, instrument.leverageUnit)}`);
    text("sampleFact", `${fit.sampleCount} 个日收益`);
    text("rmseFact", `${fit.validationRmsePct.toFixed(2)}%`);
    text("r2Fact", formatR2(fit.validationR2));
    text("modelName", fit.modelName);
    text("equation", fit.equation);
    text("methodNote", fit.notes.join(" · "));
    text("trainRmse", `${fit.trainRmsePct.toFixed(2)}%`);
    text("validationRmse", `${fit.validationRmsePct.toFixed(2)}%`);
    text("validationCorrelation", fit.validationCorrelation.toFixed(3));
    text("validationSamples", `${Math.max(0, fit.sampleCount - fit.trainCount)} / ${fit.sampleCount}`);
    renderCandidates(fit);
    text("leverageTag", instrument.leverageType);
    text("leverageDefinition", instrument.leverageDefinition);
    text("imputedNote", market.imputedLeverageCount ? `${market.imputedLeverageCount} 个点向前填充` : "无填充");
    text("priceChartRange", `${fit.points[0].tradeDate} → ${fit.points.at(-1).tradeDate}`);
    renderQuality(market);
    renderSources(market.sources);
    renderSelectedCharts();
}

function renderQuality(market) {
    const instrument = market.instrument;
    const rows = [
        ["数据点", `${market.observationCount} 个交易日`],
        ["价格数据", market.candles?.length ? "OHLC 日K" : "仅收盘价"],
        ["价格单位", instrument.currency],
        ["杠杆单位", instrument.leverageUnit],
        ["杠杆字段", instrument.leverageType],
        ["数据日期", `${market.from} 至 ${market.to}`],
        ["填充点", market.imputedLeverageCount ? `${market.imputedLeverageCount} 个，图上保持同色` : "0 个"]
    ];
    document.getElementById("qualityList").innerHTML = rows.map(([label, value]) => `<div><span>${escapeHtml(label)}</span><b>${escapeHtml(value)}</b></div>`).join("");
}

function renderCandidates(fit) {
    const candidates = [...(fit.candidates || [])].sort((left, right) => left.validationRmsePct - right.validationRmsePct);
    document.getElementById("candidateList").innerHTML = candidates.map((candidate) => `
        <div class="candidate-row ${candidate.modelName === fit.modelName ? "selected" : ""}">
            <span>${candidate.modelName === fit.modelName ? "✓" : "·"} ${escapeHtml(candidate.modelName)}</span>
            <b>验证 ${candidate.validationRmsePct.toFixed(2)}% · R² ${candidate.validationR2.toFixed(3)}</b>
        </div>
    `).join("");
}

function renderSources(sources) {
    document.getElementById("sourceList").innerHTML = sources.map((source, index) => `
        <div class="source-item"><span class="source-number">${String(index + 1).padStart(2, "0")}</span><div><strong>${escapeHtml(source.name)}</strong><p>${escapeHtml(source.field)} · ${escapeHtml(source.unit)} · ${escapeHtml(source.note)}</p><code>${escapeHtml(source.uri)}</code></div><time>${escapeHtml(source.dataDate)}</time></div>
    `).join("");
}

function renderSelectedCharts() {
    if (!state.data) return;
    const market = state.data.markets.find((item) => item.instrument.id === state.selectedId) || state.data.markets[0];
    if (!market) return;
    drawPriceChart(market);
    drawLeverageChart(market);
}

function drawPriceChart(market) {
    const canvas = document.getElementById("priceChart");
    const points = market.fit.points;
    const candleByDate = new Map((market.candles || []).map((candle) => [candle.tradeDate, candle]));
    const candles = points.map((point) => candleByDate.get(point.tradeDate)).filter(Boolean);
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.floor(rect.width * ratio);
    canvas.height = Math.floor(rect.height * ratio);
    const ctx = canvas.getContext("2d");
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    const actualBase = points[0].actualClose;
    const actual = points.map((point) => point.actualClose / actualBase * 100);
    const fitted = points.map((point) => point.fittedClose / actualBase * 100);
    const normalizedCandles = candles.map((candle) => ({
        ...candle,
        open: candle.open / actualBase * 100,
        high: candle.high / actualBase * 100,
        low: candle.low / actualBase * 100,
        close: candle.close / actualBase * 100
    }));
    const candleValues = normalizedCandles.flatMap((candle) => [candle.high, candle.low]);
    const bounds = boundsOf([...actual, ...fitted, ...candleValues]);
    const geometry = chartGeometry(rect.width, rect.height, bounds.min, bounds.max);
    clearCanvas(ctx, rect.width, rect.height);
    drawValidationBand(ctx, geometry, points);
    drawGrid(ctx, geometry, 100);
    if (normalizedCandles.length === points.length) drawCandles(ctx, geometry, normalizedCandles);
    else drawLine(ctx, geometry, actual, "#f4f5f0");
    drawLine(ctx, geometry, fitted, "#69d1d8");
    drawLabels(ctx, geometry, points);
    canvas.onmousemove = (event) => showChartTooltip(event, canvas, points, (point) => {
        const candle = candleByDate.get(point.tradeDate);
        const ohlc = candle ? `<br>O ${candle.open.toFixed(2)} · H ${candle.high.toFixed(2)} · L ${candle.low.toFixed(2)} · C ${candle.close.toFixed(2)}` : "";
        return `${point.tradeDate}${ohlc}<br>方程 ${point.fittedClose.toFixed(2)}${point.validationPoint ? " · 验证点" : " · 训练点"}`;
    }, "priceTooltip");
    canvas.onmouseleave = () => hideTooltip("priceTooltip");
}

function drawLeverageChart(market) {
    const canvas = document.getElementById("leverageChart");
    const points = market.fit.points;
    const rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = Math.floor(rect.width * ratio);
    canvas.height = Math.floor(rect.height * ratio);
    const ctx = canvas.getContext("2d");
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    const values = points.map((point) => point.leverageValue);
    const bounds = boundsOf(values);
    const geometry = chartGeometry(rect.width, rect.height, bounds.min, bounds.max);
    clearCanvas(ctx, rect.width, rect.height);
    drawValidationBand(ctx, geometry, points);
    drawGrid(ctx, geometry, bounds.min === bounds.max ? bounds.min : (bounds.min + bounds.max) / 2);
    drawLine(ctx, geometry, values, "#f3b86a");
    drawLabels(ctx, geometry, points);
    canvas.onmousemove = (event) => showChartTooltip(event, canvas, points, (point) => `${point.tradeDate}<br>杠杆 ${formatLeverage(point.leverageValue, market.instrument.leverageUnit)}${point.validationPoint ? " · 验证点" : " · 训练点"}`, "leverageTooltip");
    canvas.onmouseleave = () => hideTooltip("leverageTooltip");
}

function chartGeometry(width, height, min, max) {
    const padding = { left: 46, right: 15, top: 18, bottom: 27 };
    const span = Math.max(max - min, Math.abs(max) * .05, .01);
    return { width, height, padding, min: min - span * .14, max: max + span * .14, plotWidth: width - padding.left - padding.right, plotHeight: height - padding.top - padding.bottom };
}

function drawValidationBand(ctx, geometry, points) {
    const firstValidation = points.findIndex((point) => point.validationPoint);
    if (firstValidation < 0) return;
    const x = index => geometry.padding.left + index / Math.max(1, points.length - 1) * geometry.plotWidth;
    ctx.fillStyle = "rgba(105,209,216,.08)";
    ctx.fillRect(x(firstValidation), geometry.padding.top, geometry.width - geometry.padding.right - x(firstValidation), geometry.plotHeight);
    ctx.fillStyle = "#7a8a8d";
    ctx.font = "10px ui-monospace, monospace";
    ctx.fillText("VALIDATION", x(firstValidation) + 8, geometry.padding.top + 14);
}

function drawGrid(ctx, geometry, midpoint) {
    const y = valueToY(geometry, midpoint);
    ctx.strokeStyle = "rgba(255,255,255,.12)";
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(geometry.padding.left, y); ctx.lineTo(geometry.width - geometry.padding.right, y); ctx.stroke();
    ctx.fillStyle = "#637176"; ctx.font = "10px ui-monospace, monospace"; ctx.textAlign = "right";
    ctx.fillText(formatAxis(midpoint), geometry.padding.left - 8, y + 3);
    ctx.textAlign = "left";
}

function drawLine(ctx, geometry, values, color) {
    const x = index => geometry.padding.left + index / Math.max(1, values.length - 1) * geometry.plotWidth;
    ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = "round"; ctx.lineCap = "round"; ctx.beginPath();
    values.forEach((value, index) => { const px = x(index), py = valueToY(geometry, value); if (index === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py); });
    ctx.stroke();
}

function drawCandles(ctx, geometry, candles) {
    const x = index => geometry.padding.left + index / Math.max(1, candles.length - 1) * geometry.plotWidth;
    const bodyWidth = Math.max(2, Math.min(8, geometry.plotWidth / Math.max(1, candles.length) * .62));
    candles.forEach((candle, index) => {
        const px = x(index);
        const openY = valueToY(geometry, candle.open);
        const highY = valueToY(geometry, candle.high);
        const lowY = valueToY(geometry, candle.low);
        const closeY = valueToY(geometry, candle.close);
        const rising = candle.close >= candle.open;
        const color = rising ? "#e88074" : "#79b8a5";
        ctx.strokeStyle = color;
        ctx.fillStyle = color;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px, highY);
        ctx.lineTo(px, lowY);
        ctx.stroke();
        const top = Math.min(openY, closeY);
        const height = Math.max(1, Math.abs(closeY - openY));
        ctx.fillRect(px - bodyWidth / 2, top, bodyWidth, height);
    });
}

function drawLabels(ctx, geometry, points) {
    ctx.fillStyle = "#637176"; ctx.font = "10px ui-monospace, monospace";
    ctx.textAlign = "left"; ctx.fillText(points[0].tradeDate, geometry.padding.left, geometry.height - 7);
    ctx.textAlign = "right"; ctx.fillText(points.at(-1).tradeDate, geometry.width - geometry.padding.right, geometry.height - 7); ctx.textAlign = "left";
}

function valueToY(geometry, value) { return geometry.padding.top + geometry.plotHeight - (value - geometry.min) / (geometry.max - geometry.min) * geometry.plotHeight; }
function clearCanvas(ctx, width, height) { ctx.clearRect(0, 0, width, height); }
function boundsOf(values) { const min = Math.min(...values), max = Math.max(...values); return { min, max }; }
function formatAxis(value) { return Math.abs(value) >= 10 ? value.toFixed(1) : value.toFixed(2); }

function showChartTooltip(event, canvas, points, content, id) {
    const rect = canvas.getBoundingClientRect();
    const cursorX = event.clientX - rect.left;
    const index = Math.max(0, Math.min(points.length - 1, Math.round((cursorX - 46) / Math.max(1, rect.width - 61) * (points.length - 1))));
    const tooltip = document.getElementById(id);
    tooltip.innerHTML = content(points[index]); tooltip.style.display = "block"; tooltip.style.left = `${Math.min(Math.max(10, cursorX + 10), rect.width - 200)}px`; tooltip.style.top = "14px";
}

function hideTooltip(id) { document.getElementById(id).style.display = "none"; }
function setConnection(ready, label) { const connection = document.getElementById("connection"); connection.classList.toggle("ready", ready); text("connectionText", label); }
function renderError(error) { document.querySelector("main").innerHTML = `<section class="error-state"><p class="eyebrow">REAL DATA REQUIRED</p><h1>数据源暂时不可用<span class="headline-dot">.</span></h1><p>${escapeHtml(error.message || "未知错误")}</p><small>页面没有回退到 Fixture；请检查 npx、网络和上交所接口后刷新。</small></section>`; }
function text(id, value) { const element = document.getElementById(id); if (element) element.textContent = value; }
function formatPrice(value, currency) { return `${currency} ${Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 2 })}`; }
function formatLeverage(value, unit) { return `${Number(value).toLocaleString("zh-CN", { maximumFractionDigits: 3 })}${unit}`; }
function formatR2(value) { return Number(value).toFixed(3); }
function formatInstant(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"; }
function escapeHtml(value) { return String(value ?? "").replace(/[&<>'"]/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character]); }
