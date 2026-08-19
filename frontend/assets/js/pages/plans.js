/**
 * 我的计划页。
 *
 * - 计划列表：DRAFT / ACTIVE / ARCHIVED 状态与版本，历史（归档）计划同样列出；
 * - 详情：以 weekStart 为周一渲染七天网格，按 localDate 落位项目；
 * - DRAFT：点击项目在统一详情抽屉内修改日期/时间/备注（PATCH）；
 * - ACTIVE：编辑先复制为新 DRAFT（POST /edit），激活需确认（POST /activate）；
 * - 生成草稿需要健康档案；校验结果（validationHits）与档案过期标记展示给用户。
 */
import { escapeHtml, formatTime, formatDateLabel, localDateOf, newRequestId } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { listPlans, getPlan, createPlanDraft, generateTrainingPlan, activatePlan, editPlan, patchPlanItem } from "../api.js";
import { registerResource, getOrCreateClientSessionId, getChatSessionId } from "../store.js";
import { openDrawer, closeDrawer, bindDrawer } from "../ui/detail-drawer.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { requestConfirmation, requestText } from "../ui/modal.js";
import { currentRoute, navigate } from "../router.js";
import { devConfig } from "../config.js";
import { planGenerationRequestKey, runPlanGenerationRequest } from "./plan-generation-request.js";

const WEEKDAY_LABELS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const RESOURCE_TYPE_LABELS = { MEAL: "餐", EXERCISE: "练", ROUTINE: "息" };

const state = {
    summaries: [],
    detail: null,
    selectedId: null,
    loading: false,
    loaded: false,
    error: null,
    saving: false,
    generating: false,
    generationError: null,
    generationAttemptedKey: null,
    generationTraceId: null,
    generationPlanId: null
};

export async function render(app) {
    const generationRequestKey = planGenerationRequestKey(location.hash);
    if (generationRequestKey && state.generationAttemptedKey !== generationRequestKey) {
        state.generationAttemptedKey = generationRequestKey;
        startTrainingPlanGeneration(app);
        return;
    }
    if (!generationRequestKey) {
        state.generationAttemptedKey = null;
        state.generationError = null;
    }
    if (state.generating) {
        app.innerHTML = renderGenerationWaiting();
        bind(app);
        return;
    }
    if (state.generationError) {
        app.innerHTML = renderGenerationError();
        bind(app);
        return;
    }
    if (!state.loaded && !state.loading && !state.error) {
        state.loading = true;
        loadSummaries().then(() => {
            if (currentRoute() === "/plans") {
                render(app);
            }
        });
        app.innerHTML = `<section class="section"><div class="empty">计划加载中...</div></section>`;
        return;
    }
    if (state.error && !state.summaries.length) {
        app.innerHTML = `
            <section class="section">
                <div class="empty">
                    <span>加载失败：${escapeHtml(state.error)}</span>
                    <div class="button-row"><button class="btn soft" data-action="retry-plans">重试</button></div>
                </div>
            </section>
        `;
        bind(app);
        return;
    }

    app.innerHTML = `
        <section class="split">
            <div class="section">
                <div class="card-title">
                    <div>
                        <h2>我的计划</h2>
                        <p>每周计划草稿、激活与历史版本。</p>
                    </div>
                    <button class="btn primary" data-action="create-draft">生成新草稿</button>
                </div>
                <div class="plan-list">
                    ${state.summaries.length ? renderPlanList() : `<div class="empty">还没有周计划。完善健康档案后生成第一份草稿。</div>`}
                </div>
                <div class="button-row" style="margin-top:14px;">
                    <a class="btn ghost" href="#/profile">完善健康档案</a>
                </div>
            </div>
            <div class="section">
                ${state.detail ? renderDetail(state.detail) : `<div class="empty">选择一个计划查看七天视图。</div>`}
            </div>
        </section>
    `;
    bind(app);
}

function renderGenerationWaiting() {
    return `
        <section class="section plan-generation-state" aria-live="polite" aria-busy="true">
            <div class="empty">
                <span class="loading-spinner" aria-hidden="true"></span>
                <strong>正在生成训练计划草稿</strong>
                <p class="muted">正在读取已确认简报、筛选审核动作并执行规则校验，请稍候。</p>
            </div>
        </section>
    `;
}

function renderGenerationError() {
    return `
        <section class="section plan-generation-state">
            <div class="empty">
                <strong>训练计划生成失败</strong>
                <p class="muted">${escapeHtml(state.generationError)}</p>
                <div class="button-row">
                    <button class="btn primary" data-action="retry-generation">重新生成</button>
                    <a class="btn ghost" href="#/chat">返回聊天</a>
                    <a class="btn soft" href="#/profile">完善健康档案</a>
                </div>
            </div>
        </section>
    `;
}

async function startTrainingPlanGeneration(app) {
    state.generating = true;
    state.generationError = null;
    render(app);
    try {
        const query = new URLSearchParams((location.hash || "").split("?")[1] || "");
        const result = await runPlanGenerationRequest((payload, signal) => generateTrainingPlan(payload, { signal }), {
            sessionId: getChatSessionId(),
            requestId: query.get("requestId") || newRequestId()
        });
        state.generating = false;
        state.detail = result.plan;
        state.selectedId = result.plan?.id || null;
        state.generationTraceId = result.traceId || null;
        state.generationPlanId = result.plan?.id || result.planId || null;
        state.summaries = await listPlans();
        state.loaded = true;
        state.error = null;
        showToast(result.generationSource === "FALLBACK" ? "计划已生成（规则降级）" : "计划已生成（Agent）");
        history.replaceState(null, "", "#/plans");
        render(app);
    } catch (error) {
        state.generating = false;
        state.generationError = error.message || "训练计划生成失败";
        render(app);
    }
}

let listenersBound = false;

function bind(app) {
    if (listenersBound) {
        return;
    }
    listenersBound = true;
    app.addEventListener("click", handleClick);
    bindDrawer(app);
    bindFeedbackControl(app);
}

/* ---------------- 数据 ---------------- */

async function loadSummaries() {
    try {
        state.summaries = await listPlans();
        const active = state.summaries.find((plan) => plan.status === "ACTIVE");
        const preferred = active || state.summaries[0] || null;
        if (preferred && !state.selectedId) {
            state.selectedId = preferred.id;
        }
        if (state.selectedId) {
            await loadDetail(state.selectedId);
        }
    } catch (error) {
        state.error = error.message || "计划加载失败";
    } finally {
        state.loading = false;
        state.loaded = true;
    }
}

async function loadDetail(planId) {
    try {
        state.detail = await getPlan(planId);
        state.selectedId = planId;
    } catch (error) {
        showToast(error.message || "计划详情加载失败", "error");
    }
}

/* ---------------- 渲染 ---------------- */

function renderPlanList() {
    return state.summaries.map((plan) => {
        const selected = String(plan.id) === String(state.selectedId);
        return `
            <div class="plan-row ${selected ? "selected" : ""}">
                <div class="plan-row-meta">
                    <strong>${escapeHtml(plan.weekStart)} 当周计划 · ${statusBadge(plan.status)}${sourceBadge(plan.generationSource)}</strong>
                    <span>时区 ${escapeHtml(plan.timezone)} · 版本 v${escapeHtml(plan.currentVersion)} · ${escapeHtml(plan.itemCount)} 项${plan.validationLevel ? ` · 校验 ${escapeHtml(plan.validationLevel)}` : ""}</span>
                </div>
                <div class="button-row">
                    <button class="btn soft" data-action="select-plan" data-plan-id="${escapeHtml(plan.id)}">查看</button>
                    ${plan.status === "ACTIVE" ? `<button class="btn ghost" data-action="edit-plan" data-plan-id="${escapeHtml(plan.id)}">编辑副本</button>` : ""}
                    ${plan.status === "DRAFT" ? `<button class="btn primary" data-action="activate-plan" data-plan-id="${escapeHtml(plan.id)}">激活</button>` : ""}
                </div>
            </div>
        `;
    }).join("");
}

function statusBadge(status) {
    const map = {
        ACTIVE: `<span class="badge">已激活</span>`,
        DRAFT: `<span class="badge warn">草稿</span>`,
        ARCHIVED: `<span class="badge">已归档（历史版本）</span>`
    };
    return map[status] || escapeHtml(status || "");
}

function renderDetail(plan) {
    const days = buildDays(plan.weekStart);
    return `
        <div class="card-title">
            <div>
                <h2>${escapeHtml(plan.weekStart)} 当周计划 ${statusBadge(plan.status)}${sourceBadge(plan.generationSource)}</h2>
                <p>时区 ${escapeHtml(plan.timezone)} · 档案版本 v${escapeHtml(plan.profileVersionNo)} · 规则 ${escapeHtml(plan.rulesVersion)} · 当前版本 v${escapeHtml(plan.currentVersion)}${plan.profileStale ? " · 档案已更新，本计划按生成时快照计算" : ""}</p>
            </div>
            <div class="button-row">
                ${plan.status === "DRAFT" ? `<button class="btn primary" data-action="activate-plan" data-plan-id="${escapeHtml(plan.id)}">激活计划</button>` : ""}
                ${plan.status === "ACTIVE" ? `<button class="btn ghost" data-action="edit-plan" data-plan-id="${escapeHtml(plan.id)}">编辑副本</button>` : ""}
                ${devConfig.enableDevTraceLink && state.generationTraceId && String(state.generationPlanId) === String(plan.id)
                    ? `<button class="btn ghost" data-action="open-generation-trace" data-trace-id="${escapeHtml(state.generationTraceId)}">查看本次 Trace</button>` : ""}
            </div>
        </div>
        ${plan.calorieLow != null ? `
            <div class="calorie-range">
                <div class="stat-card"><span class="muted">每日能量下限（估算）</span><strong>${escapeHtml(plan.calorieLow)} kcal</strong></div>
                <div class="stat-card"><span class="muted">每日能量上限（估算）</span><strong>${escapeHtml(plan.calorieHigh)} kcal</strong></div>
            </div>
        ` : ""}
        ${plan.validationHits && plan.validationHits.length ? `
            <div class="card" style="margin-bottom:16px;">
                <h3 style="margin:0 0 10px;">计划校验结果</h3>
                <div class="chips">
                    ${plan.validationHits.map((hit) => `<span class="chip ${hit.severity === "BLOCK_PLAN" ? "warn" : "selected"}">${escapeHtml(hit.ruleCode)} · ${escapeHtml(hit.decision)}</span>`).join("")}
                </div>
                ${plan.validationHits.map((hit) => `
                    <p class="muted" style="line-height:1.6;margin:8px 0 0;">${escapeHtml(hit.copy)}${hit.detail ? `（${escapeHtml(hit.detail)}）` : ""}</p>
                `).join("")}
            </div>
        ` : ""}
        <div class="week-grid">
            ${days.map((day, index) => renderDay(day, index, plan)).join("")}
        </div>
        ${plan.note ? `<p class="muted" style="margin-top:14px;">计划备注：${escapeHtml(plan.note)}</p>` : ""}
        ${plan.explanation ? `<p class="muted" style="margin-top:14px;line-height:1.7;">${escapeHtml(plan.explanation)}</p>` : ""}
    `;
}

function sourceBadge(source) {
    const labels = { AGENT: "Agent 生成", FALLBACK: "规则降级", RULE_COMPOSER: "规则组合" };
    if (!source || !labels[source]) return "";
    return ` <span class="badge ${source === "FALLBACK" ? "warn" : ""}">${labels[source]}</span>`;
}

function buildDays(weekStart) {
    const start = new Date(`${weekStart}T00:00:00`);
    return Array.from({ length: 7 }, (_, index) => {
        const date = new Date(start.getTime() + index * 24 * 60 * 60 * 1000);
        return localDateOf(date);
    });
}

function renderDay(day, index, plan) {
    const items = (plan.items || []).filter((item) => item.localDate === day)
        .sort((left, right) => Number(right.resourceType === "EXERCISE") - Number(left.resourceType === "EXERCISE"));
    const editable = plan.status === "DRAFT";
    return `
        <div class="week-day">
            <h4><span>${escapeHtml(formatDateLabel(day))}</span>${WEEKDAY_LABELS[index]}</h4>
            ${items.length ? items.map((item) => renderPlanItem(item, editable)).join("") : `<span class="muted" style="font-size:12px;">无安排</span>`}
        </div>
    `;
}

function renderPlanItem(item, editable) {
    const key = `plan-item-${item.id}`;
    const type = RESOURCE_TYPE_LABELS[item.resourceType] || "·";
    const params = item.params || {};
    const kcal = params.caloriesKcal != null ? ` · ${escapeHtml(params.caloriesKcal)} kcal` : "";
    return `
        <button class="plan-item ${String(item.resourceType || "").toLowerCase()}" data-action="open-plan-item" data-item-key="${key}">
            <span>
                <span class="plan-item-type">${escapeHtml(type)}</span>
                <strong>${escapeHtml(item.name)}</strong>
            </span>
            <span class="plan-item-time">${escapeHtml(formatTime(item.startTime))} - ${escapeHtml(formatTime(item.endTime))}${kcal}</span>
            ${item.note ? `<span class="muted" style="font-size:12px;">备注：${escapeHtml(item.note)}</span>` : ""}
            ${editable ? `<span class="muted" style="font-size:12px;">点击编辑</span>` : ""}
        </button>
    `;
}

/* ---------------- 操作 ---------------- */

function handleClick(event) {
    const target = event.target.closest("[data-action]");
    if (!target) {
        return;
    }
    const action = target.dataset.action;
    if (action === "retry-plans") {
        state.error = null;
        state.summaries = [];
        state.detail = null;
        state.selectedId = null;
        state.loaded = false;
        render(document.getElementById("app"));
    } else if (action === "retry-generation") {
        startTrainingPlanGeneration(document.getElementById("app"));
    } else if (action === "select-plan") {
        selectPlan(target.dataset.planId);
    } else if (action === "open-plan-item") {
        openPlanItem(target.dataset.itemKey);
    } else if (action === "create-draft") {
        createDraft();
    } else if (action === "activate-plan") {
        activate(target.dataset.planId);
    } else if (action === "edit-plan") {
        editAsDraft(target.dataset.planId);
    } else if (action === "open-generation-trace") {
        window.healthPendingTraceId = target.dataset.traceId;
        navigate("/admin/traces");
    }
}

async function selectPlan(planId) {
    await loadDetail(planId);
    render(document.getElementById("app"));
}

function openPlanItem(itemKey) {
    const item = findPlanItem(itemKey);
    if (!item) {
        return;
    }
    const key = `plan-drawer-${item.id}`;
    registerResource(key, { ...item, resourceType: item.resourceType, resourceId: item.resourceId, name: item.name });
    const context = {
        planId: state.detail.id,
        planItemId: item.id,
        sessionId: getOrCreateClientSessionId(),
        editForm: state.detail.status === "DRAFT" ? renderEditForm(item) : ""
    };
    if (context.editForm) {
        context.onSave = (values) => savePlanItem(item, values);
    }
    openDrawer(key, context);
}

function findPlanItem(itemKey) {
    const items = (state.detail && state.detail.items) || [];
    const id = itemKey.replace("plan-item-", "");
    return items.find((item) => String(item.id) === String(id));
}

function renderEditForm(item) {
    return `
        <div class="card" data-edit-form="1">
            <h3 style="margin:0 0 10px;">编辑计划项目</h3>
            <p class="field-hint" style="margin-bottom:10px;">只允许修改日期、时间和备注；不修改营养、训练剂量与作息规则。</p>
            <div class="form-grid">
                <div class="field">
                    <label>日期</label>
                    <input type="date" data-edit-field="localDate" value="${escapeHtml(item.localDate || "")}" required>
                </div>
                <div class="field">
                    <label>开始时间</label>
                    <input type="time" data-edit-field="startTime" value="${escapeHtml((item.startTime || "").slice(0, 5))}" required>
                </div>
                <div class="field">
                    <label>结束时间</label>
                    <input type="time" data-edit-field="endTime" value="${escapeHtml((item.endTime || "").slice(0, 5))}" required>
                </div>
                <div class="field full">
                    <label>备注（留空可清除）</label>
                    <textarea data-edit-field="note" placeholder="例如：减半主食">${escapeHtml(item.note || "")}</textarea>
                </div>
            </div>
        </div>
    `;
}

async function savePlanItem(item, values) {
    if (!values.localDate || !values.startTime || !values.endTime) {
        showToast("日期和时间不能为空", "error");
        return;
    }
    if (values.startTime >= values.endTime) {
        showToast("结束时间必须晚于开始时间", "error");
        return;
    }
    if (state.saving) {
        return;
    }
    state.saving = true;
    try {
        await patchPlanItem(state.detail.id, item.id, {
            localDate: values.localDate,
            startTime: `${values.startTime}:00`,
            endTime: `${values.endTime}:00`,
            note: values.note || ""
        });
        showToast("计划项目已保存");
        closeDrawerFromSave();
        await loadDetail(state.detail.id);
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "保存失败", "error");
    } finally {
        state.saving = false;
    }
}

async function createDraft() {
    const weekStart = nextMonday();
    const focus = await requestText({
        title: `生成 ${weekStart} 当周草稿`,
        description: "系统会根据当前健康档案生成七天安排。",
        label: "训练重点（可选）",
        placeholder: "例如：胸、核心或全身",
        confirmLabel: "生成草稿"
    });
    if (focus === null) {
        return;
    }
    try {
        const plan = await createPlanDraft({
            weekStart,
            timezone: "Asia/Shanghai",
            trainingFocus: focus || null
        });
        state.summaries = await listPlans();
        state.detail = plan;
        state.selectedId = plan.id;
        showToast("草稿已生成");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "草稿生成失败", "error");
        if (error.status === 404) {
            showToast("请先完善健康档案", "error");
            navigate("/profile");
        }
    }
}

async function activate(planId) {
    const plan = state.detail && String(state.detail.id) === String(planId) ? state.detail : null;
    const label = plan ? `${plan.weekStart} 当周计划` : "该计划";
    const confirmed = await requestConfirmation({
        title: `激活${label}`,
        description: "激活后旧的已激活计划会自动归档，且本版本不可原地修改。",
        confirmLabel: "确认激活"
    });
    if (!confirmed) {
        return;
    }
    try {
        await activatePlan(planId);
        state.summaries = await listPlans();
        await loadDetail(planId);
        showToast("计划已激活");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "激活失败", "error");
    }
}

async function editAsDraft(planId) {
    const confirmed = await requestConfirmation({
        title: "创建编辑草稿",
        description: "系统会复制当前已激活计划，原版本继续作为历史记录保留。",
        confirmLabel: "创建草稿"
    });
    if (!confirmed) {
        return;
    }
    try {
        const draft = await editPlan(planId);
        state.summaries = await listPlans();
        state.detail = draft;
        state.selectedId = draft.id;
        showToast("已创建编辑草稿");
        render(document.getElementById("app"));
    } catch (error) {
        showToast(error.message || "创建编辑草稿失败", "error");
    }
}

function closeDrawerFromSave() {
    closeDrawer();
}

function nextMonday() {
    const now = new Date();
    const day = now.getDay() === 0 ? 7 : now.getDay();
    const monday = new Date(now.getTime() + (8 - day) * 24 * 60 * 60 * 1000);
    return localDateOf(monday);
}
