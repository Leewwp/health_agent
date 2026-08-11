/**
 * admin 评估页（旧评估报告的模块化迁移）。
 *
 * 基于已落库 Trace 生成规则评分、可选 LLM Judge 和反馈归因指标；
 * 与 Trace 页一样位于 admin 路由，用户导航不展示，生产由 X-Admin-Token 保护。
 */
import { escapeHtml, toLocalInputValue } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { runEvaluation, getAdminToken, setAdminToken } from "../api.js";

function defaultRange() {
    const end = new Date();
    const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
    return { startAt: toLocalInputValue(start), endAt: toLocalInputValue(end) };
}

const state = {
    report: null,
    loading: false,
    form: { ...defaultRange(), limit: 50, includeLlmJudge: false }
};

export async function render(app) {
    app.innerHTML = `
        <section class="section">
            <div class="card-title">
                <div>
                    <h2>评估报告</h2>
                    <p>基于已落库 Trace 生成规则评分、可选 LLM Judge 和反馈归因指标。</p>
                </div>
                <button class="btn ghost" data-action="toggle-eval-token">${getAdminToken() ? "已配置 Admin Token" : "配置 Admin Token"}</button>
            </div>
            <form id="evaluationForm" class="form-grid">
                <div class="field">
                    <label>开始时间</label>
                    <input type="datetime-local" name="startAt" value="${escapeHtml(state.form.startAt)}" required>
                </div>
                <div class="field">
                    <label>结束时间</label>
                    <input type="datetime-local" name="endAt" value="${escapeHtml(state.form.endAt)}" required>
                </div>
                <div class="field">
                    <label>数量上限</label>
                    <input type="number" min="1" max="500" name="limit" value="${escapeHtml(state.form.limit)}">
                </div>
                <div class="field">
                    <label>LLM Judge</label>
                    <select name="includeLlmJudge">
                        <option value="false" ${!state.form.includeLlmJudge ? "selected" : ""}>关闭</option>
                        <option value="true" ${state.form.includeLlmJudge ? "selected" : ""}>开启</option>
                    </select>
                </div>
                <div class="field full">
                    <button class="btn primary" type="submit">${state.loading ? "评估中..." : "生成评估报告"}</button>
                </div>
            </form>
        </section>
        <section class="section" style="margin-top:18px;">
            ${renderReport()}
        </section>
    `;
    bind(app);
}

let listenersBound = false;

function bind(app) {
    if (listenersBound) {
        return;
    }
    listenersBound = true;
    app.addEventListener("click", (event) => {
        const target = event.target.closest("[data-action]");
        if (target && target.dataset.action === "toggle-eval-token") {
            const next = window.prompt("Admin Token（生产环境需要，开发环境可留空）", getAdminToken()) ?? getAdminToken();
            setAdminToken(next);
            showToast(next ? "Admin Token 已配置" : "已清空 Admin Token");
            render(app);
        }
    });
    app.addEventListener("submit", (event) => {
        if (event.target.id === "evaluationForm") {
            event.preventDefault();
            runEval(event.target);
        }
    });
}

function renderReport() {
    const report = state.report;
    if (!report) {
        return `<div class="empty">暂无报告。选择时间范围后生成评估。</div>`;
    }
    return `
        <div class="grid three">
            ${statCard("Trace 总数", report.totalTraces, "本次纳入评估的请求数")}
            ${statCard("已标注", report.labeledTraces, "有人工标签的 Trace 数")}
            ${statCard("平均分", report.avgScore === null || report.avgScore === undefined ? "-" : Number(report.avgScore).toFixed(2), "综合评分")}
        </div>
        <div class="subtle-divider"></div>
        <div class="grid two">
            <div>
                <h3>指标均值</h3>
                ${renderMetrics(report.metricAverages)}
            </div>
            <div>
                <h3>报告范围</h3>
                <p class="muted">${escapeHtml(report.startAt)} 至 ${escapeHtml(report.endAt)}</p>
            </div>
        </div>
        <div class="subtle-divider"></div>
        ${renderTable(report.traceResults || [])}
    `;
}

function statCard(label, value, desc) {
    return `
        <div class="stat-card">
            <span class="muted">${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
            <p class="muted">${escapeHtml(desc)}</p>
        </div>
    `;
}

function renderMetrics(metrics) {
    const entries = Object.entries(metrics || {});
    if (!entries.length) {
        return `<div class="empty">暂无指标</div>`;
    }
    return `<div class="chips">${entries.map(([key, value]) => `<span class="chip selected">${escapeHtml(key)}：${Number(value).toFixed(2)}</span>`).join("")}</div>`;
}

function renderTable(rows) {
    if (!rows.length) {
        return `<div class="empty">暂无 Trace 明细</div>`;
    }
    return `
        <div class="table-wrap">
            <table>
                <thead>
                    <tr>
                        <th>Trace ID</th>
                        <th>会话</th>
                        <th>综合分</th>
                        <th>规则分</th>
                        <th>LLM 分</th>
                        <th>反馈分</th>
                        <th>指标 / 明细</th>
                    </tr>
                </thead>
                <tbody>
                    ${rows.map((row) => `
                        <tr>
                            <td>${escapeHtml(row.traceId)}</td>
                            <td>${escapeHtml(row.sessionId)}</td>
                            <td>${formatScore(row.score)}</td>
                            <td>${formatScore(row.ruleScore)}</td>
                            <td>${formatScore(row.llmJudgeScore)}</td>
                            <td>${formatScore(row.userFeedbackScore)}</td>
                            <td>
                                <details>
                                    <summary>查看 JSON</summary>
                                    <pre class="json-box">${escapeHtml(JSON.stringify({ metrics: row.metrics, detail: row.detail }, null, 2))}</pre>
                                </details>
                            </td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        </div>
    `;
}

function formatScore(value) {
    return value === null || value === undefined ? "-" : Number(value).toFixed(2);
}

async function runEval(form) {
    const formData = new FormData(form);
    state.form = {
        startAt: formData.get("startAt"),
        endAt: formData.get("endAt"),
        limit: Number(formData.get("limit") || 50),
        includeLlmJudge: formData.get("includeLlmJudge") === "true"
    };
    state.loading = true;
    render(document.getElementById("app"));
    try {
        state.report = await runEvaluation(state.form);
    } catch (error) {
        showToast(error.message || "评估失败", "error");
    } finally {
        state.loading = false;
        render(document.getElementById("app"));
    }
}
