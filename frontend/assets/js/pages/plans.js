/**
 * 生产我的计划页：可折叠计划库、横向七日工作区和服务端审核资源选择器。
 * 本页只维护尚未保存的编辑副本，资源事实和计划处方分开读取与提交。
 */
import { escapeHtml, formatDateLabel, localDateOf, newRequestId } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import {
    listPlans, getPlan, generateTrainingPlan, confirmPlan, enablePlan, disablePlan, archivePlan, copyPlan,
    editPlan, updatePlanItems, getMealDetail, getExerciseDetail, listMeals, listExercises,
    addFavorite, removeFavorite
} from "../api.js";
import { registerResource, getOrCreateClientSessionId, getChatSessionId, isFavorite, syncFavorites } from "../store.js";
import { openDrawer, closeDrawer, bindDrawer } from "../ui/detail-drawer.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { requestConfirmation } from "../ui/modal.js";
import { currentRoute, navigate } from "../router.js";
import { planGenerationRequestKey, runPlanGenerationRequest } from "./plan-generation-request.js";

const WEEKDAY_LABELS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const RESOURCE_TYPE_LABELS = { MEAL: "餐食", EXERCISE: "动作", ROUTINE: "作息" };
const PICKER_PAGE_SIZE = 12;

const state = {
    summaries: [], detail: null, draftItems: [], selectedId: null,
    loading: false, loaded: false, error: null, saving: false, dirty: false,
    sidebarCollapsed: false, detailFilter: "ALL", mobileDay: 0,
    picker: null, pickerToken: 0, generationError: null, generating: false,
    generationAttemptedKey: null, generationTraceId: null, generationPlanId: null,
    resourceCache: new Map()
};

export async function render(app) {
    const generationKey = planGenerationRequestKey(location.hash);
    if (generationKey && state.generationAttemptedKey !== generationKey) {
        state.generationAttemptedKey = generationKey;
        startGeneration(app);
        return;
    }
    if (!generationKey) {
        state.generationAttemptedKey = null;
        state.generationError = null;
    }
    if (state.generating) {
        app.innerHTML = generationState();
        bind(app);
        return;
    }
    if (state.generationError) {
        app.innerHTML = generationError();
        bind(app);
        return;
    }
    if (!state.loaded && !state.loading && !state.error) {
        state.loading = true;
        loadSummaries().then(() => currentRoute() === "/plans" && render(app));
        app.innerHTML = `<section class="section"><div class="empty">计划加载中...</div></section>`;
        bind(app);
        return;
    }
    if (state.error && !state.summaries.length) {
        app.innerHTML = `<section class="section"><div class="empty"><span>加载失败：${escapeHtml(state.error)}</span><div class="button-row"><button class="btn soft" data-plan-action="retry">重试</button></div></div></section>`;
        bind(app);
        return;
    }
    app.innerHTML = renderPage();
    bind(app);
}

function renderPage() {
    if (!state.summaries.length) {
        return `<section class="section"><div class="empty"><strong>还没有周计划</strong><p>从已确认的聊天简报生成第一份草稿。</p><a class="btn primary" href="#/chat">从聊天生成</a></div></section>`;
    }
    return `<section class="mp-production" aria-labelledby="plans-title">${state.detail ? renderWorkspace(state.detail) : `<div class="empty">请选择一份计划。</div>`}${state.picker ? renderPicker() : ""}</section>`;
}

function renderWorkspace(plan) {
    const collapsed = state.sidebarCollapsed;
    return `<div class="mp-layout variant-d ${collapsed ? "is-collapsed" : ""}">${collapsed ? renderRail() : renderSidebar()}<main class="mp-main">${renderHeader(plan)}${renderToolbar(plan)}${state.dirty ? renderDirtyBar() : ""}${renderValidation(plan)}<div class="mp-mobile-days" role="tablist" aria-label="选择日期">${buildDays(plan.weekStart).map((day, index) => `<button class="mp-day-tab ${state.mobileDay === index ? "active" : ""}" data-plan-action="mobile-day" data-day="${index}" role="tab" aria-selected="${state.mobileDay === index}"><strong>${WEEKDAY_LABELS[index]}</strong><small>${escapeHtml(formatDateLabel(day))}</small></button>`).join("")}</div><div class="mp-wide-board" aria-label="七日计划">${buildDays(plan.weekStart).map((day, index) => renderDay(plan, day, index)).join("")}</div></main></div>`;
}

function renderSidebar() {
    return `<aside class="mp-sidebar" aria-label="计划库"><div class="mp-side-title"><span>计划库</span><div class="mp-side-actions"><button type="button" data-plan-action="collapse" title="折叠计划库" aria-label="折叠计划库">‹</button><button type="button" data-plan-action="new" title="从聊天新建草稿" aria-label="从聊天新建草稿">＋</button></div></div><div class="mp-plan-list">${state.summaries.map(renderPlanRow).join("")}</div><p class="mp-side-note">已启用计划编辑会先创建草稿副本。</p></aside>`;
}

function renderRail() {
    return `<div class="mp-plan-rail" aria-label="收起的计划库"><button class="mp-rail-btn mp-rail-expand" data-plan-action="collapse" title="展开计划库" aria-label="展开计划库">›</button><button class="mp-rail-btn" data-plan-action="new" title="从聊天新建草稿" aria-label="从聊天新建草稿">＋</button>${state.summaries.map((plan) => `<button class="mp-rail-plan ${String(plan.id) === String(state.selectedId) ? "is-selected" : ""}" data-plan-id="${escapeHtml(plan.id)}" title="${escapeHtml(plan.name || "计划")} · ${escapeHtml(statusLabel(plan.status))}" aria-label="${escapeHtml(plan.name || "计划")}"><span class="mp-status-dot ${String(plan.status || "").toLowerCase()}"></span></button>`).join("")}</div>`;
}

function renderPlanRow(plan) {
    const selected = String(plan.id) === String(state.selectedId);
    return `<button class="mp-plan-row ${selected ? "is-selected" : ""}" data-plan-id="${escapeHtml(plan.id)}" aria-current="${selected ? "page" : "false"}"><span class="mp-status-dot ${String(plan.status || "").toLowerCase()}" title="${escapeHtml(statusLabel(plan.status))}"></span><span><strong>${escapeHtml(plan.name || `${plan.weekStart} 当周计划`)}</strong><small>${escapeHtml(plan.weekStart)} · ${escapeHtml(statusLabel(plan.status))} · ${plan.itemCount ?? 0} 项</small></span></button>`;
}

function renderHeader(plan) {
    const status = plan.status;
    const actions = status === "ENABLED"
        ? `<button class="mp-btn secondary" data-plan-action="edit" data-plan-id="${plan.id}">创建编辑草稿</button><button class="mp-btn primary" data-plan-action="disable" data-plan-id="${plan.id}">停用计划</button>`
        : status === "DRAFT"
            ? `<button class="mp-btn ghost danger" data-plan-action="discard" data-plan-id="${plan.id}">删除草稿</button><button class="mp-btn primary" data-plan-action="confirm" data-plan-id="${plan.id}">确认草稿</button>`
            : status === "UNENABLED"
                ? `<button class="mp-btn ghost" data-plan-action="archive" data-plan-id="${plan.id}">转为历史</button><button class="mp-btn primary" data-plan-action="enable" data-plan-id="${plan.id}">启用计划</button>`
                : `<button class="mp-btn primary" data-plan-action="copy" data-plan-id="${plan.id}">复制为草稿</button>`;
    const title = editablePlan(plan) ? `<input class="mp-name-input" data-plan-name value="${escapeHtml(plan.name || `${plan.weekStart} 当周安排`)}" maxlength="128" aria-label="计划名称">` : `<h1 id="plans-title">${escapeHtml(plan.name || `${plan.weekStart} 当周安排`)}</h1>`;
    return `<header class="mp-head"><div><span class="mp-eyebrow">我的计划</span>${title}<p>${escapeHtml(plan.weekStart)} 至 ${escapeHtml(buildDays(plan.weekStart)[6])} · ${escapeHtml(scopeLabel(plan.planScope))} · <span class="mp-badge ${String(status).toLowerCase()}">${escapeHtml(statusLabel(status))}</span>${plan.generationSource ? ` · ${escapeHtml(sourceLabel(plan.generationSource))}` : ""}</p></div><div class="mp-actions"><button class="mp-btn ghost" data-plan-action="new" title="从聊天新建草稿">新建草稿</button>${actions}</div></header>`;
}

function renderToolbar(plan) {
    const items = visibleItems(plan);
    return `<div class="mp-toolbar"><div class="mp-segment" role="tablist" aria-label="项目类型筛选">${[["ALL", "全部"], ["MEAL", "餐食"], ["EXERCISE", "动作"]].map(([value, label]) => `<button class="${state.detailFilter === value ? "active" : ""}" data-plan-action="filter" data-filter="${value}" role="tab" aria-selected="${state.detailFilter === value}">${label}<b>${value === "ALL" ? (plan.items || []).length : (plan.items || []).filter((item) => item.resourceType === value).length}</b></button>`).join("")}</div><span class="mp-toolbar-meta mp-muted">${items.length} 项显示 · v${escapeHtml(plan.currentVersion)}</span></div>`;
}

function renderDirtyBar() {
    return `<div class="mp-dirty-bar" role="status"><span class="mp-dirty">● 有未保存修改</span><div class="mp-actions"><button class="mp-btn ghost small" data-plan-action="cancel">取消</button><button class="mp-btn primary small" data-plan-action="save">保存全部修改</button></div></div>`;
}

function renderValidation(plan) {
    if (!plan.validationHits?.length && !plan.profileStale) return "";
    return `<div class="mp-validation">${plan.profileStale ? `<span class="badge warn">健康档案已更新，计划仍按生成快照</span>` : ""}${(plan.validationHits || []).map((hit) => `<span class="chip ${hit.severity === "BLOCK_PLAN" ? "warn" : "selected"}">${escapeHtml(hit.ruleCode)} · ${escapeHtml(hit.decision)}</span>`).join("")}</div>`;
}

function renderDay(plan, day, index) {
    const hidden = index !== state.mobileDay ? "mp-mobile-hidden" : "";
    const editable = editablePlan(plan);
    const items = visibleItems(plan).filter((item) => item.localDate === day).sort((a, b) => String(a.startTime).localeCompare(String(b.startTime)));
    return `<section class="mp-card-day ${hidden}" data-day-column="${index}"><header><div><strong>${WEEKDAY_LABELS[index]}</strong><small>${escapeHtml(formatDateLabel(day))}</small></div><button type="button" data-plan-action="add" data-local-date="${escapeHtml(day)}" data-start-time="12:00" title="新增项目" aria-label="在${WEEKDAY_LABELS[index]}新增项目">＋</button></header><div class="mp-card-items">${items.length ? items.map((item) => renderEvent(item, editable)).join("") : `<button class="mp-empty-slot" data-plan-action="add" data-local-date="${escapeHtml(day)}" data-start-time="12:00">暂无安排 · 新增</button>`}</div>${editable ? `<button class="mp-card-add" data-plan-action="add" data-local-date="${escapeHtml(day)}" data-start-time="12:00">添加项目</button>` : ""}</section>`;
}

function renderEvent(item, editable) {
    const type = String(item.resourceType || "").toLowerCase();
    const params = item.params || {};
    const end = shortTime(item.endTime);
    const detail = type === "meal" ? `${params.caloriesKcal ?? "-"} kcal · ${params.mealTime || "餐食"}` : `${params.durationMinutes ?? "-"} 分钟 · ${params.sets ?? "-"} 组 × ${params.reps ?? "-"} 次`;
    registerResource(`plan-card-${item.id}`, { ...item, resourceType: item.resourceType, resourceId: item.resourceId });
    return `<div class="mp-board-event ${type}"><button class="mp-event-open" data-plan-action="open" data-item-id="${escapeHtml(item.id)}" aria-label="查看${escapeHtml(item.name)}详情"><span class="mp-event-type">${escapeHtml(RESOURCE_TYPE_LABELS[item.resourceType] || item.resourceType)} · ${shortTime(item.startTime)}-${end}</span><strong>${escapeHtml(item.name || item.resourceId)}</strong><small>${escapeHtml(detail)}</small>${item.note ? `<em>${escapeHtml(item.note)}</em>` : ""}</button>${editable ? `<button class="mp-event-move" data-plan-action="move" data-item-id="${escapeHtml(item.id)}" title="移动项目" aria-label="移动${escapeHtml(item.name)}">↕</button>` : ""}</div>`;
}

function renderPicker() {
    const picker = state.picker;
    if (picker.mode === "move") return renderMovePicker(picker);
    const type = picker.type;
    const fields = type === "MEAL"
        ? [["mealTime", "餐次", ["三餐", "下午茶", "加餐", "午餐", "早午餐", "早餐", "晚餐"]], ["cuisine", "菜系", ["东南亚菜", "海鲜", "甜品", "粥汤", "素食", "西餐"]], ["healthGoal", "目标", ["低油", "均衡", "控碳水", "清淡", "高蛋白"]]]
        : [["bodyPart", "部位", ["全身", "胸", "背", "肩", "手臂", "腿", "核心", "臀", "颈部"]], ["equipment", "器材", ["徒手", "哑铃", "杠铃", "壶铃", "弹力带", "器械"]], ["difficulty", "难度", ["入门", "进阶", "挑战"]], ["movementPattern", "模式", ["推", "拉", "蹲", "髋", "踝", "核心", "有氧"]]];
    const pages = Math.max(1, Math.ceil((picker.total || 0) / PICKER_PAGE_SIZE));
    return `<div class="mp-overlay" data-picker-overlay><section class="mp-modal" role="dialog" aria-modal="true" aria-label="选择审核资源"><header><div><span class="mp-eyebrow">${picker.mode === "replace" ? "替换资源" : "新增项目"}</span><h2>选择${RESOURCE_TYPE_LABELS[type]}</h2></div><button class="mp-close" data-plan-action="close-picker" aria-label="关闭资源选择">×</button></header><div class="mp-modal-tabs">${["MEAL", "EXERCISE"].map((kind) => `<button class="${kind === type ? "active" : ""}" data-plan-action="picker-type" data-type="${kind}">${RESOURCE_TYPE_LABELS[kind]}</button>`).join("")}</div><form class="mp-search" data-picker-search><input name="q" value="${escapeHtml(picker.query)}" placeholder="搜索名称" autocomplete="off"><button class="mp-btn ghost small" type="submit">搜索</button><button class="mp-fav-toggle ${picker.favoriteOnly ? "active" : ""}" type="button" data-plan-action="picker-favorites" aria-pressed="${picker.favoriteOnly}">♡ 仅看收藏</button></form><div class="mp-structured-filters">${fields.map(([key, label, values]) => `<label>${label}<select data-plan-action="picker-filter" data-filter-key="${key}"><option value="">全部</option>${values.map((value) => `<option value="${escapeHtml(value)}" ${picker.filters[key] === value ? "selected" : ""}>${escapeHtml(value)}</option>`).join("")}</select></label>`).join("")}</div>${picker.loading ? `<div class="mp-empty">资源加载中...</div>` : picker.error ? `<div class="mp-empty">${escapeHtml(picker.error)}</div>` : picker.items.length ? `<div class="mp-resource-list">${picker.items.map(renderPickerItem).join("")}</div>` : `<div class="mp-empty">没有符合条件的审核资源。</div>`}<footer><span>第 ${picker.page} / ${pages} 页 · 共 ${picker.total || 0} 条</span><div class="mp-actions"><button class="mp-btn ghost small" data-plan-action="picker-page" data-page="${picker.page - 1}" ${picker.page <= 1 ? "disabled" : ""}>上一页</button><button class="mp-btn ghost small" data-plan-action="picker-page" data-page="${picker.page + 1}" ${picker.page >= pages ? "disabled" : ""}>下一页</button></div></footer></section></div>`;
}

function renderPickerItem(item) {
    const type = item.resourceType;
    const favorite = Boolean(item.favorite ?? isFavorite(type, item.id));
    const detail = type === "MEAL" ? `${item.nutrition?.caloriesKcal ?? "-"} kcal · ${(item.tags?.mealTime || []).join("、") || "餐食"}` : `${item.bodyPart || "-"} · ${item.equipment || "-"}${item.planReady ? " · 可入周计划" : " · 不可入周计划"}`;
    return `<article class="mp-resource-card"><div class="mp-thumb ${type === "MEAL" ? "meal" : "exercise"}">${type === "MEAL" ? "餐" : "练"}</div><div><strong>${escapeHtml(item.name || item.id)}</strong><p><span>${escapeHtml(detail)}</span></p><small>${escapeHtml(item.sourceName || "审核资源")}</small></div><div class="mp-resource-actions"><button class="mp-fav-toggle ${favorite ? "active" : ""}" data-plan-action="picker-favorite" data-resource-type="${type}" data-resource-id="${escapeHtml(item.id)}" aria-pressed="${favorite}" title="${favorite ? "取消收藏" : "收藏"}">${favorite ? "♥" : "♡"}</button><button class="mp-btn primary small" data-plan-action="choose-resource" data-resource-type="${type}" data-resource-id="${escapeHtml(item.id)}">选择</button></div></article>`;
}

function renderMovePicker(picker) {
    const item = findItem(picker.itemId);
    const duration = item ? timeMinutes(item.endTime) - timeMinutes(item.startTime) : 30;
    const slots = Array.from({ length: 48 }, (_, index) => index * 30).filter((start) => start + duration <= 1440);
    return `<div class="mp-overlay"><section class="mp-modal mp-move-modal" role="dialog" aria-modal="true" aria-label="选择移动目标"><header><div><span class="mp-eyebrow">移动项目</span><h2>${escapeHtml(item?.name || "计划项目")}</h2></div><button class="mp-close" data-plan-action="close-picker" aria-label="关闭移动选择">×</button></header><p class="mp-muted">选择目标日期和时间，保存时会校验整周冲突。</p><div class="mp-move-grid">${buildDays(state.detail.weekStart).map((day, index) => `<div class="mp-plan-move-day"><strong>${WEEKDAY_LABELS[index]} · ${escapeHtml(formatDateLabel(day))}</strong>${slots.map((start) => `<button class="mp-plan-move-slot" data-plan-action="move-target" data-local-date="${escapeHtml(day)}" data-start-time="${timeValue(start)}">${timeValue(start)}</button>`).join("")}</div>`).join("")}</div></section></div>`;
}

function statusLabel(status) { return { ENABLED: "已启用", UNENABLED: "未启用", DRAFT: "草稿", HISTORY: "历史" }[status] || status || "计划"; }
function scopeLabel(scope) { return { EXERCISE: "训练", MEAL: "餐食", COMPOSITE: "综合" }[scope] || "综合"; }
function sourceLabel(source) { return { AGENT: "Agent 生成", FALLBACK: "规则降级", RULE_COMPOSER: "规则组合", RULE_MEAL_COMPOSER: "餐食规则组合", COMPOSITE_RULE_MERGE: "综合规则合并" }[source] || source; }
function editablePlan(plan) { return plan && (plan.status === "DRAFT" || plan.status === "UNENABLED"); }
function buildDays(weekStart) { const start = new Date(`${weekStart}T00:00:00`); return Array.from({ length: 7 }, (_, index) => localDateOf(new Date(start.getTime() + index * 86400000))); }
function visibleItems(plan) { return (state.draftItems || plan.items || []).filter((item) => state.detailFilter === "ALL" || item.resourceType === state.detailFilter); }
function shortTime(value) { return String(value || "00:00").slice(0, 5); }
function nextHalfHour(value) { const [hour, minute] = shortTime(value).split(":").map(Number); const total = hour * 60 + minute + 30; return `${String(Math.floor(total / 60) % 24).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`; }
function cloneItems(items) { return (items || []).map((item) => ({ ...item, params: { ...(item.params || {}) } })); }
function timeMinutes(value) { const [hour, minute] = shortTime(value).split(":").map(Number); return hour * 60 + minute; }
function timeValue(value) { return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`; }
function positiveInteger(value) { const number = Number(value); return Number.isInteger(number) && number > 0 ? number : null; }

function generationState() { return `<section class="section"><div class="empty" aria-live="polite" aria-busy="true"><span class="loading-spinner" aria-hidden="true"></span><strong>正在生成计划草稿</strong><p class="muted">正在读取确认简报和审核资源。</p></div></section>`; }
function generationError() { return `<section class="section"><div class="empty"><strong>计划生成失败</strong><p class="muted">${escapeHtml(state.generationError)}</p><div class="button-row"><button class="btn primary" data-plan-action="retry-generation">重新生成</button><a class="btn ghost" href="#/chat">返回聊天</a><a class="btn soft" href="#/profile">完善档案</a></div></div></section>`; }

async function startGeneration(app) {
    state.generating = true; state.generationError = null; render(app);
    try {
        const query = new URLSearchParams((location.hash || "").split("?")[1] || "");
        const result = await runPlanGenerationRequest((payload, signal) => generateTrainingPlan(payload, { signal }), { sessionId: getChatSessionId(), requestId: query.get("requestId") || newRequestId(), planScope: query.get("scope") || "COMPOSITE" });
        state.generating = false; state.detail = result.plan; state.draftItems = cloneItems(result.plan?.items || []); state.selectedId = result.plan?.id || null; state.summaries = await listPlans(); state.loaded = true;
        history.replaceState(null, "", "#/plans"); showToast(result.generationSource === "FALLBACK" ? "计划已生成（规则降级）" : "计划已生成（Agent）"); render(app);
    } catch (error) { state.generating = false; state.generationError = error.message || "计划生成失败"; render(app); }
}

async function loadSummaries() {
    try { state.summaries = await listPlans(); const preferred = state.summaries.find((plan) => plan.status === "ENABLED") || state.summaries[0]; if (!state.selectedId && preferred) state.selectedId = preferred.id; if (state.selectedId) await loadDetail(state.selectedId); }
    catch (error) { state.error = error.message || "计划加载失败"; }
    finally { state.loading = false; state.loaded = true; }
}

async function loadDetail(planId) {
    try { const detail = await getPlan(planId); state.detail = detail; state.selectedId = planId; state.draftItems = cloneItems(detail.items || []); state.dirty = false; state.mobileDay = 0; state.resourceCache.clear(); }
    catch (error) { showToast(error.message || "计划详情加载失败", "error"); }
}

function bind(app) {
    if (app.dataset.plansBound === "true") return;
    app.dataset.plansBound = "true";
    app.addEventListener("click", handleClick); app.addEventListener("change", handleChange); app.addEventListener("submit", handleSubmit); bindDrawer(app); bindFeedbackControl(app);
    const drawerRoot = document.getElementById("drawer-root");
    if (drawerRoot && drawerRoot.dataset.planBound !== "true") { drawerRoot.dataset.planBound = "true"; drawerRoot.addEventListener("click", handleDrawerClick); }
    window.addEventListener("beforeunload", handleBeforeUnload);
}
function handleBeforeUnload(event) { if (currentRoute() === "/plans" && state.dirty) { event.preventDefault(); event.returnValue = ""; } }

function handleClick(event) {
    const target = event.target.closest("[data-plan-action], [data-plan-id]"); if (!target) return;
    if (target.dataset.planId && !target.dataset.planAction) { if (state.sidebarCollapsed) state.sidebarCollapsed = false; selectPlan(target.dataset.planId); return; }
    const action = target.dataset.planAction;
    if (action === "collapse") { state.sidebarCollapsed = !state.sidebarCollapsed; render(document.getElementById("app")); return; }
    if (action === "new") { navigate("/chat"); return; }
    if (action === "retry") { resetAndReload(); return; }
    if (action === "retry-generation") { startGeneration(document.getElementById("app")); return; }
    if (action === "filter") { state.detailFilter = target.dataset.filter || "ALL"; render(document.getElementById("app")); return; }
    if (action === "mobile-day") { state.mobileDay = Number(target.dataset.day) || 0; render(document.getElementById("app")); return; }
    if (action === "add") { openPicker("add", target.dataset.localDate, target.dataset.startTime, preferredPickerType()); return; }
    if (action === "open") { openItem(target.dataset.itemId); return; }
    if (action === "move") { openMovePicker(target.dataset.itemId); return; }
    if (action === "move-target") { moveItem(target.dataset.localDate, target.dataset.startTime); return; }
    if (action === "save") { saveAll(); return; }
    if (action === "cancel") { cancelEdits(); return; }
    if (action === "confirm" || action === "enable" || action === "disable" || action === "archive") { mutateStatus(action, target.dataset.planId); return; }
    if (action === "copy") { copyPlanToDraft(target.dataset.planId); return; }
    if (action === "edit") { editEnabledPlan(target.dataset.planId); return; }
    if (action === "discard") { discardDraft(target.dataset.planId); return; }
    if (action === "close-picker") { state.picker = null; render(document.getElementById("app")); return; }
    if (action === "picker-type") { state.picker.type = target.dataset.type; state.picker.page = 1; state.picker.filters = {}; loadPicker(); return; }
    if (action === "picker-favorites") { state.picker.favoriteOnly = !state.picker.favoriteOnly; state.picker.page = 1; loadPicker(); return; }
    if (action === "picker-page") { const page = Number(target.dataset.page); if (page > 0) { state.picker.page = page; loadPicker(); } return; }
    if (action === "choose-resource") { chooseResource(target.dataset.resourceType, target.dataset.resourceId); return; }
    if (action === "picker-favorite") { togglePickerFavorite(target.dataset.resourceType, target.dataset.resourceId); }
}

function handleChange(event) {
    const target = event.target.closest("[data-plan-action='picker-filter']");
    if (target && state.picker) { state.picker.filters[target.dataset.filterKey] = target.value; state.picker.page = 1; loadPicker(); }
}
function handleSubmit(event) { if (event.target.matches("[data-picker-search]")) { event.preventDefault(); state.picker.query = event.target.elements.q.value.trim(); state.picker.page = 1; loadPicker(); } }
function handleDrawerClick(event) { const button = event.target.closest("[data-plan-resource-picker]"); if (button) openPicker("replace", null, null, button.dataset.resourceType, button.dataset.itemId); }

async function selectPlan(planId) {
    if (state.dirty && !(await requestConfirmation({ title: "放弃未保存修改？", description: "切换计划会丢失当前编辑。", confirmLabel: "放弃修改" }))) return;
    await loadDetail(planId); render(document.getElementById("app"));
}
function preferredPickerType() { return state.detail?.planScope === "EXERCISE" ? "EXERCISE" : "MEAL"; }
function openPicker(mode, localDate, startTime, type, itemId) {
    if (!editablePlan(state.detail)) return;
    const item = itemId ? findItem(itemId) : null;
    state.picker = { mode, type: type || item?.resourceType || preferredPickerType(), itemId: itemId || null, localDate: localDate || item?.localDate || state.detail.weekStart, startTime: startTime || shortTime(item?.startTime) || "12:00", query: "", filters: {}, favoriteOnly: false, page: 1, total: 0, items: [], loading: true, error: null };
    render(document.getElementById("app")); loadPicker();
}

async function loadPicker() {
    if (!state.picker) return;
    const picker = state.picker; const token = ++state.pickerToken; picker.loading = true; picker.error = null; render(document.getElementById("app"));
    try {
        await syncFavorites(picker.type);
        const params = { page: picker.page, size: PICKER_PAGE_SIZE, favoriteOnly: picker.favoriteOnly, q: picker.query, ...picker.filters };
        const response = picker.type === "MEAL" ? await listMeals(params) : await listExercises(params);
        if (!state.picker || token !== state.pickerToken) return;
        picker.items = (response.items || []).map((item) => ({ ...item, resourceType: picker.type })); picker.total = response.total || 0; picker.loading = false;
    } catch (error) { if (!state.picker || token !== state.pickerToken) return; picker.loading = false; picker.error = error.message || "审核资源加载失败"; }
    render(document.getElementById("app"));
}

async function togglePickerFavorite(type, id) {
    try { if (isFavorite(type, id)) await removeFavorite(type, id); else await addFavorite(type, id); await syncFavorites(type); render(document.getElementById("app")); }
    catch (error) { showToast(error.message || "收藏操作失败", "error"); }
}

function chooseResource(type, resourceId) {
    const resource = state.picker?.items.find((item) => item.resourceType === type && String(item.id) === String(resourceId)); if (!resource) return;
    state.resourceCache.set(`${type}:${resourceId}`, resource);
    if (state.picker.mode === "move") return;
    if (state.picker.mode === "replace") {
        const item = findItem(state.picker.itemId);
        if (item) { item.resourceType = type; item.resourceId = String(resourceId); item.name = resource.name; item.params = defaultPlanParams(resource); state.dirty = true; }
        state.picker = null; closeDrawer({ restoreFocus: false }); render(document.getElementById("app")); showToast("资源已替换，保存后生效"); return;
    }
    const total = timeMinutes(state.picker.startTime) + 30;
    const item = { id: `new-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, resourceType: type, resourceId: String(resourceId), name: resource.name, localDate: state.picker.localDate, startTime: `${shortTime(state.picker.startTime)}:00`, endTime: `${timeValue(total)}:00`, note: "", params: defaultPlanParams(resource) };
    state.draftItems.push(item); state.dirty = true; state.picker = null; render(document.getElementById("app")); showToast("项目已加入，保存后生效");
}
function defaultPlanParams(resource) { return resource.resourceType === "MEAL" ? { caloriesKcal: resource.nutrition?.caloriesKcal == null ? null : Math.round(Number(resource.nutrition.caloriesKcal)), mealTime: resource.tags?.mealTime?.[0] || "" } : { bodyPart: resource.bodyPart || "全身", durationMinutes: 30, sets: 3, reps: 10 }; }
function findItem(itemId) { return state.draftItems.find((item) => String(item.id) === String(itemId)); }

function openItem(itemId) {
    const item = findItem(itemId); if (!item) return;
    const key = `plan-item-${item.id}`; registerResource(key, { ...item, resourceType: item.resourceType, resourceId: item.resourceId, localDate: item.localDate });
    openDrawer(key, { planId: state.detail.id, planItemId: item.id, sessionId: getOrCreateClientSessionId(), editForm: editablePlan(state.detail) ? renderEditForm(item) : "", detailLoader: item.resourceType === "MEAL" ? () => getMealDetail(item.resourceId) : item.resourceType === "EXERCISE" ? () => getExerciseDetail(item.resourceId) : null, onSave: (values) => applyDrawerEdit(item, values), onDelete: editablePlan(state.detail) ? () => removeItem(item) : null });
}
function renderEditForm(item) {
    const params = item.params || {};
    return `<div class="mp-edit" data-edit-form="1"><div class="mp-readonly"><span>资源</span><strong>${escapeHtml(item.name || item.resourceId)}</strong></div><button class="mp-btn secondary" type="button" data-plan-resource-picker data-resource-type="${escapeHtml(item.resourceType)}" data-item-id="${escapeHtml(item.id)}">替换${escapeHtml(RESOURCE_TYPE_LABELS[item.resourceType] || "资源")}</button><div class="mp-form-row"><label>日期<input type="date" data-edit-field="localDate" value="${escapeHtml(item.localDate)}"></label><label>开始<input type="time" step="1800" data-edit-field="startTime" value="${escapeHtml(shortTime(item.startTime))}"></label><label>结束<input type="time" step="1800" data-edit-field="endTime" value="${escapeHtml(shortTime(item.endTime))}"></label></div>${item.resourceType === "EXERCISE" ? `<div class="mp-form-row"><label>时长（分钟）<input type="number" min="1" step="1" data-edit-field="durationMinutes" value="${escapeHtml(params.durationMinutes ?? 30)}"></label><label>组数<input type="number" min="1" step="1" data-edit-field="sets" value="${escapeHtml(params.sets ?? 3)}"></label><label>次数<input type="number" min="1" step="1" data-edit-field="reps" value="${escapeHtml(params.reps ?? 10)}"></label></div>` : `<div class="mp-readonly"><span>餐食营养事实</span><strong>${escapeHtml(params.caloriesKcal == null ? "未提供" : `${params.caloriesKcal} kcal`)} · 只读</strong></div>`}<label class="mp-full-label">备注<textarea data-edit-field="note">${escapeHtml(item.note || "")}</textarea></label>${item.id ? `<button class="mp-btn danger" type="button" data-drawer-delete="1">删除项目</button>` : ""}</div>`;
}
function applyDrawerEdit(item, values) {
    if (!values.localDate || !values.startTime || !values.endTime || values.startTime >= values.endTime || !/^\d{2}:(00|30)$/.test(values.startTime) || !/^\d{2}:(00|30)$/.test(values.endTime)) { showToast("请填写有效的半小时日期区间", "error"); return; }
    if (item.resourceType === "EXERCISE") { const durationMinutes = positiveInteger(values.durationMinutes); const sets = positiveInteger(values.sets); const reps = positiveInteger(values.reps); if (!durationMinutes || !sets || !reps) { showToast("动作处方必须为正整数", "error"); return; } item.params = { ...item.params, durationMinutes, sets, reps }; }
    item.localDate = values.localDate; item.startTime = `${values.startTime}:00`; item.endTime = `${values.endTime}:00`; item.note = values.note || ""; state.dirty = true; closeDrawer(); render(document.getElementById("app")); showToast("项目修改已加入批量保存");
}
async function removeItem(item) { if (!(await requestConfirmation({ title: "删除计划项目", description: `确定删除“${item.name}”吗？`, confirmLabel: "删除" }))) return; state.draftItems = state.draftItems.filter((entry) => entry !== item); state.dirty = true; closeDrawer(); render(document.getElementById("app")); }
function openMovePicker(itemId) { const item = findItem(itemId); if (item && editablePlan(state.detail)) openPicker("move", item.localDate, shortTime(item.startTime), item.resourceType, itemId); }
function moveItem(localDate, startTime) { const item = findItem(state.picker?.itemId); if (!item) return; const duration = timeMinutes(item.endTime) - timeMinutes(item.startTime); const start = timeMinutes(startTime); if (duration <= 0 || start + duration > 1440) { showToast("项目不能跨午夜", "error"); return; } item.localDate = localDate; item.startTime = `${startTime}:00`; item.endTime = `${timeValue(start + duration)}:00`; state.dirty = true; state.picker = null; render(document.getElementById("app")); showToast("项目已移动，保存后生效"); }

async function saveAll() {
    if (state.saving || !state.detail || !editablePlan(state.detail)) return;
    state.saving = true;
    try {
        const name = document.querySelector("[data-plan-name]")?.value.trim() || state.detail.name;
        const items = state.draftItems.map((item) => ({ id: /^\d+$/.test(String(item.id)) ? Number(item.id) : null, resourceType: item.resourceType, resourceId: item.resourceId, name: item.name, localDate: item.localDate, startTime: item.startTime, endTime: item.endTime, note: item.note || "", planParams: item.params || {} }));
        const saved = await updatePlanItems(state.detail.id, { requestId: newRequestId(), expectedVersion: state.detail.currentVersion, name, items });
        state.detail = saved; state.draftItems = cloneItems(saved.items || []); state.summaries = await listPlans(); state.dirty = false; closeDrawer({ restoreFocus: false }); showToast("计划已保存"); render(document.getElementById("app"));
    } catch (error) { showToast(error.message || "保存失败，编辑仍保留", "error"); }
    finally { state.saving = false; }
}
function cancelEdits() { state.draftItems = cloneItems(state.detail?.items || []); state.dirty = false; closeDrawer({ restoreFocus: false }); render(document.getElementById("app")); showToast("已取消未保存修改"); }
function mutationPayload(planId) { const plan = state.detail && String(state.detail.id) === String(planId) ? state.detail : state.summaries.find((entry) => String(entry.id) === String(planId)); return { requestId: newRequestId(), expectedVersion: plan?.currentVersion || 1 }; }

async function mutateStatus(action, planId) {
    const labels = { confirm: ["确认草稿", "确认后可单独启用这份计划。"], enable: ["启用计划", "当前已启用计划会回到未启用。"], archive: ["转为历史", "历史计划只读，仍保留在计划库。"] };
    if (labels[action] && !(await requestConfirmation({ title: labels[action][0], description: labels[action][1], confirmLabel: labels[action][0] }))) return;
    try { const fn = { confirm: confirmPlan, enable: enablePlan, disable: disablePlan, archive: archivePlan }[action]; const result = await fn(planId, mutationPayload(planId)); state.summaries = await listPlans(); state.detail = result; state.draftItems = cloneItems(result.items || []); state.dirty = false; showToast({ confirm: "草稿已确认", enable: "计划已启用", disable: "计划已停用", archive: "计划已转为历史" }[action]); render(document.getElementById("app")); }
    catch (error) { showToast(error.message || "状态操作失败", "error"); }
}
async function copyPlanToDraft(planId) { try { const result = await copyPlan(planId, mutationPayload(planId)); state.summaries = await listPlans(); state.detail = result; state.draftItems = cloneItems(result.items || []); state.selectedId = result.id; state.dirty = false; showToast("已复制为草稿"); render(document.getElementById("app")); } catch (error) { showToast(error.message || "复制失败", "error"); } }
async function editEnabledPlan(planId) { if (!(await requestConfirmation({ title: "创建编辑草稿", description: "原计划保持启用，系统会创建一份草稿副本。", confirmLabel: "创建草稿" }))) return; try { const result = await editPlan(planId); state.summaries = await listPlans(); state.detail = result; state.draftItems = cloneItems(result.items || []); state.selectedId = result.id; state.dirty = false; showToast("已创建编辑草稿"); render(document.getElementById("app")); } catch (error) { showToast(error.message || "创建草稿失败", "error"); } }
async function discardDraft(planId) { if (!(await requestConfirmation({ title: "删除草稿", description: "删除后不能恢复。", confirmLabel: "删除草稿" }))) return; try { const response = await fetch(`/api/v1/health/plans/${encodeURIComponent(planId)}`, { method: "DELETE" }); if (!response.ok) throw new Error("删除草稿失败"); state.summaries = await listPlans(); state.selectedId = state.summaries[0]?.id || null; state.detail = state.selectedId ? await getPlan(state.selectedId) : null; state.draftItems = cloneItems(state.detail?.items || []); state.dirty = false; showToast("草稿已删除"); render(document.getElementById("app")); } catch (error) { showToast(error.message || "删除草稿失败", "error"); } }
function resetAndReload() { state.error = null; state.summaries = []; state.detail = null; state.selectedId = null; state.loaded = false; state.loading = false; render(document.getElementById("app")); }

export const __plansTest = { defaultPlanParams, timeMinutes, nextHalfHour };
