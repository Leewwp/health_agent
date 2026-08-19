/**
 * admin Trace 页（37 号：开发/演示配置中展示 Trace 摘要）。
 *
 * 按时间范围或会话查询请求链路，展示事件 JSON（意图、澄清、检索、Agent 调用、
 * 风险校验与降级），可标注预期意图。用户导航不展示本页；开发环境
 * （diet.security.admin-protected=false）无需 token，生产由 X-Admin-Token 保护。
 */
import { escapeHtml, safeJson, toLocalInputValue } from "../util/dom.js";
import { renderRawTraceJson } from "./trace-detail-view.js";
import { showToast } from "../ui/toast.js";
import {
    listTraces,
    getTrace,
    listSessionTraces,
    labelTrace,
    getAdminToken,
    setAdminToken
} from "../api.js";

const INTENTS = [
    "MEAL_RECOMMENDATION",
    "CLARIFY_NEEDED",
    "MEAL_ADJUST",
    "MEAL_PLAN",
    "HEALTH_RISK",
    "OTHER"
];

function defaultRange() {
    const end = new Date();
    const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
    return { startAt: toLocalInputValue(start), endAt: toLocalInputValue(end) };
}

const state = {
    rows: [],
    selected: null,
    loading: false,
    filters: { ...defaultRange(), onlyUnlabeled: false, limit: 50, sessionId: "" }
};

export async function render(app) {
    openPendingTrace(app);
    const selected = state.selected;
    app.innerHTML = `
        <section class="split">
            <div class="section">
                <div class="card-title">
                    <div>
                        <h2>Trace 调试</h2>
                        <p>按时间范围或会话查询请求链路，查看意图、槽位、检索、风险校验与降级事件。</p>
                    </div>
                    <button class="btn ghost" data-action="toggle-trace-token">${getAdminToken() ? "已配置 Admin Token" : "配置 Admin Token"}</button>
                </div>
                <form id="traceFilterForm" class="form-grid">
                    <div class="field">
                        <label>开始时间</label>
                        <input type="datetime-local" name="startAt" value="${escapeHtml(state.filters.startAt)}" required>
                    </div>
                    <div class="field">
                        <label>结束时间</label>
                        <input type="datetime-local" name="endAt" value="${escapeHtml(state.filters.endAt)}" required>
                    </div>
                    <div class="field">
                        <label>会话 ID（可选）</label>
                        <input name="sessionId" value="${escapeHtml(state.filters.sessionId)}" placeholder="填写后按会话查询">
                    </div>
                    <div class="field">
                        <label>数量上限</label>
                        <input type="number" min="1" max="500" name="limit" value="${escapeHtml(state.filters.limit)}">
                    </div>
                    <div class="field">
                        <label>标注状态</label>
                        <select name="onlyUnlabeled">
                            <option value="false" ${!state.filters.onlyUnlabeled ? "selected" : ""}>全部</option>
                            <option value="true" ${state.filters.onlyUnlabeled ? "selected" : ""}>仅未标注</option>
                        </select>
                    </div>
                    <div class="field">
                        <span>&nbsp;</span>
                        <button class="btn primary" type="submit">${state.loading ? "查询中..." : "查询 Trace"}</button>
                    </div>
                </form>
                <div class="subtle-divider"></div>
                ${renderTraceTable()}
            </div>
            <aside class="section">
                ${selected ? renderTraceDetail(selected) : `<div class="empty">选择一条 Trace 查看详情和标注表单。</div>`}
            </aside>
        </section>
    `;
    bind(app);
}

/** 从聊天或计划页进入时直接打开指定 Trace，不要求用户再次执行列表查询。 */
function openPendingTrace(app) {
    const traceId = window.healthPendingTraceId;
    if (!traceId) return;
    window.healthPendingTraceId = null;
    state.loading = true;
    getTrace(traceId).then((trace) => {
        state.selected = trace;
    }).catch((error) => {
        showToast(error.message || "Trace 详情加载失败", "error");
    }).finally(() => {
        state.loading = false;
        render(app);
    });
}

let listenersBound = false;

function bind(app) {
    if (listenersBound) {
        return;
    }
    listenersBound = true;
    app.addEventListener("click", handleClick);
    app.addEventListener("submit", (event) => {
        if (event.target.id === "traceFilterForm") {
            event.preventDefault();
            searchTraces(event.target);
        } else if (event.target.id === "traceLabelForm") {
            event.preventDefault();
            saveLabel(event.target);
        }
    });
}

function renderTraceTable() {
    if (!state.rows.length) {
        return `<div class="empty">暂无 Trace 数据。可以先在聊天页发起几轮对话。</div>`;
    }
    return `
        <div class="table-wrap">
            <table>
                <thead>
                    <tr>
                        <th>Trace ID</th>
                        <th>会话</th>
                        <th>状态</th>
                        <th>诊断</th>
                        <th>事件</th>
                        <th>耗时</th>
                        <th>创建时间</th>
                        <th>标注</th>
                        <th>操作</th>
                    </tr>
                </thead>
                <tbody>
                    ${state.rows.map((row) => `
                        <tr>
                            <td>${escapeHtml(row.traceId)}</td>
                            <td>${escapeHtml(row.sessionId)}</td>
                            <td>${escapeHtml(row.status || "-")}</td>
                            <td>${diagnosticBadge(row)}</td>
                            <td>${escapeHtml(row.eventCount ?? "-")}</td>
                            <td>${row.durationMs ? `${escapeHtml(row.durationMs)} ms` : "-"}</td>
                            <td>${escapeHtml(row.createdAt || "-")}</td>
                            <td>${row.expectedIntent ? `<span class="badge">${escapeHtml(row.expectedIntent)}</span>` : "<span class=\"muted\">未标注</span>"}</td>
                            <td><button class="btn soft" data-action="select-trace" data-trace-id="${escapeHtml(row.traceId)}">查看</button></td>
                        </tr>
                    `).join("")}
                </tbody>
            </table>
        </div>
    `;
}

export function renderTraceDetail(trace) {
    const models = (trace.modelNames || []).join("、") || "未提供";
    const fallbacks = (trace.fallbackReasons || []).filter(Boolean);
    const tokenStatus = {
        PROVIDED: "已提供",
        PARTIAL: "部分提供",
        NOT_PROVIDED: "未提供"
    }[trace.tokenStatus] || "未提供";
    return `
        <div class="card-title">
            <div>
                <h3>Trace 详情</h3>
                <p>${escapeHtml(trace.traceId)}</p>
            </div>
        </div>
        <div class="grid">
            <div>
                <span class="badge">${escapeHtml(trace.status || "UNKNOWN")}</span>
                ${diagnosticBadge(trace)}
                <p class="muted">Session：${escapeHtml(trace.sessionId || "-")} · Events：${escapeHtml(trace.eventCount ?? "-")} · 总耗时：${escapeHtml(trace.durationMs ?? "-")} ms</p>
                <p class="muted">Agent 耗时：${escapeHtml(trace.agentDurationMs ?? 0)} ms · 调用：${escapeHtml(trace.agentCallCount ?? 0)} · 降级：${escapeHtml(trace.degradedCount ?? 0)} · 模型：${escapeHtml(models)} · Token：${escapeHtml(tokenStatus)}</p>
                <p class="muted">解析：${escapeHtml((trace.parseStatuses || []).join("、") || "未提供")}</p>
                <p class="muted">Guard：${escapeHtml((trace.guardResults || []).join("、") || "未提供")}</p>
                ${fallbacks.length ? `<p class="trace-warning">降级原因：${escapeHtml(fallbacks.join("；"))}</p>` : ""}
            </div>
            ${renderTimeline(trace.timeline)}
            ${renderRawTraceJson(escapeHtml(safeJson(trace.traceJson)))}
            <form id="traceLabelForm" class="form-grid">
                <input type="hidden" name="traceId" value="${escapeHtml(trace.traceId)}">
                <div class="field">
                    <label>预期意图</label>
                    <select name="expectedIntent">
                        <option value="">不标注</option>
                        ${INTENTS.map((intent) => `<option value="${intent}" ${trace.expectedIntent === intent ? "selected" : ""}>${intent}</option>`).join("")}
                    </select>
                </div>
                <div class="field">
                    <label>澄清动作</label>
                    <select name="expectedClarifyAction">
                        <option value="">不标注</option>
                        <option value="ASK" ${trace.expectedClarifyAction === "ASK" ? "selected" : ""}>ASK</option>
                        <option value="READY" ${trace.expectedClarifyAction === "READY" ? "selected" : ""}>READY</option>
                    </select>
                </div>
                <div class="field full">
                    <label>预期槽位 JSON</label>
                    <textarea name="expectedSlots" placeholder='{"mealTime":["晚餐"],"taste":["清淡"]}'>${escapeHtml(safeJson(trace.expectedSlots))}</textarea>
                </div>
                <div class="field full">
                    <label>备注</label>
                    <textarea name="labelNote" placeholder="标注说明">${escapeHtml(trace.labelNote || "")}</textarea>
                </div>
                <div class="field full">
                    <button class="btn primary" type="submit">保存标注</button>
                </div>
            </form>
        </div>
    `;
}

function diagnosticBadge(trace) {
    const label = trace.diagnosticStatus === "DEGRADED" ? "DEGRADED" : "正常";
    return `<span class="badge ${trace.diagnosticStatus === "DEGRADED" ? "warn" : ""}">${label}</span>`;
}

function renderTimeline(timeline) {
    if (!timeline || !timeline.length) {
        return `<div class="empty">暂无可解析的事件摘要，原始 JSON 仍可查看。</div>`;
    }
    return `<div class="trace-timeline">
        <h4>事件时间线</h4>
        ${timeline.slice().sort((left, right) => left.stepOrder - right.stepOrder).map((event) => `
            <div class="trace-event">
                <span class="trace-step">${escapeHtml(event.stepOrder)}</span>
                <div>
                    <strong>${escapeHtml(event.eventType || "事件")}</strong>
                    <span class="muted">${escapeHtml(event.phase || "")} ${event.agentName ? `· ${escapeHtml(event.agentName)}` : ""}${event.modelName ? ` · ${escapeHtml(event.modelName)}` : ""}</span>
                    ${event.latencyMs != null ? `<span class="muted"> · ${escapeHtml(event.latencyMs)} ms</span>` : ""}
                    ${event.result ? `<div class="trace-event-result">${escapeHtml(event.result)}</div>` : ""}
                </div>
            </div>
        `).join("")}
    </div>`;
}

function promptAdminToken() {
    const current = getAdminToken();
    const next = window.prompt("Admin Token（生产环境需要，开发环境可留空）", current) ?? current;
    setAdminToken(next);
    showToast(next ? "Admin Token 已配置" : "已清空 Admin Token");
    render(document.getElementById("app"));
}

async function searchTraces(form) {
    const formData = new FormData(form);
    state.filters = {
        startAt: formData.get("startAt"),
        endAt: formData.get("endAt"),
        sessionId: String(formData.get("sessionId") || "").trim(),
        onlyUnlabeled: formData.get("onlyUnlabeled") === "true",
        limit: Number(formData.get("limit") || 50)
    };
    state.loading = true;
    render(document.getElementById("app"));
    try {
        if (state.filters.sessionId) {
            state.rows = await listSessionTraces(state.filters.sessionId, state.filters.limit);
        } else {
            state.rows = await listTraces({
                startAt: state.filters.startAt,
                endAt: state.filters.endAt,
                onlyUnlabeled: state.filters.onlyUnlabeled,
                limit: state.filters.limit
            });
        }
        const pending = window.healthPendingTraceId;
        window.healthPendingTraceId = null;
        state.selected = pending
            ? state.rows.find((row) => row.traceId === pending) || state.rows[0] || null
            : state.rows[0] || null;
    } catch (error) {
        showToast(error.message || "Trace 查询失败", "error");
    } finally {
        state.loading = false;
        render(document.getElementById("app"));
    }
}

async function selectTrace(traceId) {
    try {
        state.selected = await getTrace(traceId);
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "Trace 详情加载失败", "error");
    }
}

async function saveLabel(form) {
    const formData = new FormData(form);
    const traceId = formData.get("traceId");
    const slotsText = String(formData.get("expectedSlots") || "").trim();
    let expectedSlots = null;
    if (slotsText) {
        try {
            expectedSlots = JSON.parse(slotsText);
        } catch (error) {
            showToast("预期槽位必须是合法 JSON", "error");
            return;
        }
    }
    try {
        await labelTrace(traceId, {
            expectedIntent: formData.get("expectedIntent") || null,
            expectedSlots,
            expectedClarifyAction: formData.get("expectedClarifyAction") || null,
            labelNote: String(formData.get("labelNote") || "").trim()
        });
        state.selected = await getTrace(traceId);
        const index = state.rows.findIndex((row) => row.traceId === traceId);
        if (index >= 0) {
            state.rows[index] = state.selected;
        }
        render(document.getElementById("app"));
        showToast("Trace 标注已保存");
    } catch (error) {
        showToast(error.message || "标注保存失败", "error");
    }
}

function handleClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
        return;
    }
    if (target.dataset.action === "toggle-trace-token") {
        promptAdminToken();
    } else if (target.dataset.action === "select-trace") {
        selectTrace(target.dataset.traceId);
    }
}
