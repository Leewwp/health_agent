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
import { registerResource, getOrCreateClientSessionId, getChatSessionId, isFavorite, syncFavorites, toggleFavorite } from "../store.js";
import { openDrawer, closeDrawer, bindDrawer } from "../ui/detail-drawer.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { requestConfirmation } from "../ui/modal.js";
import { currentRoute, navigate } from "../router.js";
import { registerRouteLeaveGuard } from "../router.js";
import { planGenerationRequestKey, runPlanGenerationRequest } from "./plan-generation-request.js";
import { createPlanNameEditor } from "./plan-name-editor.js";
import { planGenerationSourceLabel } from "../ui/plan-generation-source.js";
import { renderMedia } from "../ui/media-state.js";

const WEEKDAY_LABELS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const RESOURCE_TYPE_LABELS = { MEAL: "餐食", EXERCISE: "动作", ROUTINE: "作息" };
const PICKER_PAGE_SIZE = 12;

const state = {
    summaries: [], detail: null, draftItems: [], selectedId: null,
    loading: false, loaded: false, error: null, saving: false, dirty: false, itemsDirty: false,
    nameEditor: null, nameEditorPlanId: null,
    sidebarCollapsed: false, detailFilter: "ALL", mobileDay: 0,
    picker: null, pickerToken: 0, pickerDetail: null, pickerDetailToken: 0, generationError: null, generating: false,
    generationAttemptedKey: null, generationTraceId: null, generationPlanId: null,
    appendAttemptedKey: null, appending: false, appendError: null,
    resourceCache: new Map()
};

export async function render(app) {
    const generationKey = planGenerationRequestKey(location.hash);
    if (generationKey && state.generationAttemptedKey !== generationKey) {
        state.generationAttemptedKey = generationKey;
        startGeneration(app);
        return;
    }
    const appendKey = appendPlanKey(location.hash);
    if (appendKey && state.appendAttemptedKey !== appendKey) {
        state.appendAttemptedKey = appendKey;
        startAppend(app, appendKey);
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
    if (state.appending) {
        app.innerHTML = `<section class="section"><div class="empty" aria-live="polite" aria-busy="true"><span class="loading-spinner" aria-hidden="true"></span><strong>正在打开当前计划的编辑副本</strong><p class="muted">原计划保持不变，稍后选择要追加的资源。</p></div></section>`;
        bind(app);
        return;
    }
    if (state.appendError) {
        app.innerHTML = `<section class="section"><div class="empty"><strong>无法打开当前计划</strong><p class="muted">${escapeHtml(state.appendError)}</p><a class="btn ghost" href="#/plans">返回计划</a></div></section>`;
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
    return `<button class="mp-plan-row ${selected ? "is-selected" : ""}" data-plan-id="${escapeHtml(plan.id)}" aria-current="${selected ? "page" : "false"}"><span class="mp-status-dot ${String(plan.status || "").toLowerCase()}" title="${escapeHtml(statusLabel(plan.status))}"></span><span class="mp-plan-row-copy"><strong title="${escapeHtml(plan.name || `${plan.weekStart} 当周计划`)}">${escapeHtml(plan.name || `${plan.weekStart} 当周计划`)}</strong><small>${escapeHtml(plan.weekStart)} · ${escapeHtml(statusLabel(plan.status))} · ${plan.itemCount ?? 0} 项</small></span></button>`;
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
    const name = planName(plan);
    const editingName = editablePlan(plan) && nameEditorState(plan)?.editing;
    const title = editablePlan(plan)
        ? editingName
            ? `<div class="mp-name-editor"><input class="mp-name-input" data-plan-name value="${escapeHtml(nameEditorState(plan).draftName)}" maxlength="128" aria-label="计划名称"><div class="mp-name-actions"><button class="mp-btn primary small" type="button" data-plan-action="save-name">保存</button><button class="mp-btn ghost small" type="button" data-plan-action="cancel-name">取消</button></div></div>`
            : `<h1 id="plans-title"><button class="mp-name-display" type="button" data-plan-action="edit-name" aria-label="编辑计划名称">${escapeHtml(name)}</button></h1>`
        : `<h1 id="plans-title">${escapeHtml(name)}</h1>`;
    return `<header class="mp-head"><div><span class="mp-eyebrow">我的计划</span>${title}<p>${escapeHtml(plan.weekStart)} 至 ${escapeHtml(buildDays(plan.weekStart)[6])} · ${escapeHtml(scopeLabel(plan.planScope))} · <span class="mp-badge ${String(status).toLowerCase()}">${escapeHtml(statusLabel(status))}</span>${plan.generationSource ? ` · ${escapeHtml(planGenerationSourceLabel(plan.generationSource))}` : ""}</p></div><div class="mp-actions"><button class="mp-btn ghost" data-plan-action="new" title="从聊天新建草稿">新建草稿</button>${actions}</div></header>`;
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
    const addButton = editable && items.length
        ? `<button type="button" data-plan-action="add" data-local-date="${escapeHtml(day)}" data-start-time="12:00" title="新增项目" aria-label="在${WEEKDAY_LABELS[index]}新增项目">＋</button>`
        : "";
    return `<section class="mp-card-day ${hidden}" data-day-column="${index}"><header><div><strong>${WEEKDAY_LABELS[index]}</strong><small>${escapeHtml(formatDateLabel(day))}</small></div>${addButton}</header><div class="mp-card-items">${items.length ? items.map((item) => renderEvent(item, editable)).join("") : ""}</div>${editable ? `<button class="mp-card-add" data-plan-action="add" data-local-date="${escapeHtml(day)}" data-start-time="12:00">添加项目</button>` : ""}</section>`;
}

function renderEvent(item, editable) {
    const type = String(item.resourceType || "").toLowerCase();
    const params = item.params || {};
    const end = shortTime(item.endTime);
    const detail = type === "meal" ? `${params.caloriesKcal ?? "-"} kcal · ${params.mealTime || "餐食"}` : `${params.durationMinutes ?? "-"} 分钟 · ${params.sets ?? "-"} 组 × ${params.reps ?? "-"} 次`;
    registerResource(`plan-card-${item.id}`, { ...item, resourceType: item.resourceType, resourceId: item.resourceId });
    return `<div class="mp-board-event ${type}"><button class="mp-event-open" data-plan-action="open" data-item-id="${escapeHtml(item.id)}" aria-label="查看${escapeHtml(item.name)}详情"><span class="mp-event-type">${escapeHtml(RESOURCE_TYPE_LABELS[item.resourceType] || item.resourceType)} · ${shortTime(item.startTime)}-${end}</span><strong>${escapeHtml(item.name || item.resourceId)}</strong><small>${escapeHtml(detail)}</small>${item.note ? `<em>${escapeHtml(item.note)}</em>` : ""}</button></div>`;
}

function planName(plan) {
    return plan?.name || `${plan?.weekStart || "本周"} 当周安排`;
}

function nameEditorState(plan) {
    if (!plan || !state.nameEditor || String(state.nameEditorPlanId) !== String(plan.id)) return null;
    return state.nameEditor.state();
}

function resetPlanEditing(plan) {
    state.itemsDirty = false;
    state.nameEditorPlanId = plan?.id || null;
    state.nameEditor = plan ? createPlanNameEditor(planName(plan)) : null;
    syncDirty();
}

function syncDirty() {
    state.dirty = state.itemsDirty || Boolean(state.nameEditor?.state().dirty);
}

function markItemsDirty() {
    state.itemsDirty = true;
    syncDirty();
}

function beginNameEdit() {
    if (!state.detail || !editablePlan(state.detail) || !state.nameEditor) return;
    state.nameEditor.begin();
    syncDirty();
    render(document.getElementById("app"));
    document.querySelector("[data-plan-name]")?.focus();
}

function cancelNameEdit(renderPage = true) {
    if (!state.nameEditor) return;
    state.nameEditor.cancel();
    syncDirty();
    if (renderPage) {
        render(document.getElementById("app"));
        showToast("已取消名称修改");
    }
}

async function confirmDiscardChanges(description) {
    if (!state.dirty) return true;
    const confirmed = await requestConfirmation({
        title: "放弃未保存修改？",
        description,
        confirmLabel: "放弃修改"
    });
    if (confirmed) {
        state.draftItems = cloneItems(state.detail?.items || []);
        resetPlanEditing(state.detail);
        closeDrawer({ restoreFocus: false });
    }
    return confirmed;
}

async function confirmLeavePlans() {
    if (!state.dirty) {
        cancelNameEdit(false);
        closeDrawer({ restoreFocus: false });
        return true;
    }
    return confirmDiscardChanges("离开当前页面会丢失未保存的计划修改。");
}

function renderPicker() {
    const picker = state.picker;
    const type = picker.type;
    const fields = type === "MEAL"
        ? [["mealTime", "餐次", ["三餐", "下午茶", "加餐", "午餐", "早午餐", "早餐", "晚餐"]], ["cuisine", "菜系", ["东南亚菜", "海鲜", "甜品", "粥汤", "素食", "西餐"]], ["healthGoal", "目标", ["低油", "均衡", "控碳水", "清淡", "高蛋白"]]]
        : [["bodyPart", "部位", ["全身", "胸", "背", "肩", "手臂", "腿", "核心", "臀", "颈部"]], ["equipment", "器材", ["徒手", "哑铃", "杠铃", "壶铃", "弹力带", "器械"]], ["difficulty", "难度", ["入门", "进阶", "挑战"]], ["movementPattern", "模式", ["推", "拉", "蹲", "髋", "踝", "核心", "有氧"]]];
    const pages = Math.max(1, Math.ceil((picker.total || 0) / PICKER_PAGE_SIZE));
    return `<div class="mp-overlay" data-picker-overlay><section class="mp-modal" role="dialog" aria-modal="true" aria-label="选择审核资源"><header><div><span class="mp-eyebrow">${picker.mode === "replace" ? "替换资源" : "新增项目"}</span><h2>选择${RESOURCE_TYPE_LABELS[type]}</h2></div><button class="mp-close" data-plan-action="close-picker" aria-label="关闭资源选择">×</button></header><div class="mp-modal-tabs">${["MEAL", "EXERCISE"].map((kind) => `<button class="${kind === type ? "active" : ""}" data-plan-action="picker-type" data-type="${kind}">${RESOURCE_TYPE_LABELS[kind]}</button>`).join("")}</div><form class="mp-search" data-picker-search><input name="q" value="${escapeHtml(picker.query)}" placeholder="搜索名称" autocomplete="off"><button class="mp-btn ghost small" type="submit">搜索</button><button class="mp-fav-toggle ${picker.favoriteOnly ? "active" : ""}" type="button" data-plan-action="picker-favorites" aria-pressed="${picker.favoriteOnly}">♡ 仅看收藏</button></form><div class="mp-structured-filters">${fields.map(([key, label, values]) => `<label>${label}<select data-plan-action="picker-filter" data-filter-key="${key}"><option value="">全部</option>${values.map((value) => `<option value="${escapeHtml(value)}" ${picker.filters[key] === value ? "selected" : ""}>${escapeHtml(value)}</option>`).join("")}</select></label>`).join("")}</div>${picker.loading ? `<div class="mp-empty">资源加载中...</div>` : picker.error ? `<div class="mp-empty">${escapeHtml(picker.error)}</div>` : picker.items.length ? `<div class="mp-resource-list">${picker.items.map(renderPickerItem).join("")}</div>` : `<div class="mp-empty">没有符合条件的审核资源。</div>`}<footer><span>第 ${picker.page} / ${pages} 页 · 共 ${picker.total || 0} 条</span><div class="mp-actions"><button class="mp-btn ghost small" data-plan-action="picker-page" data-page="${picker.page - 1}" ${picker.page <= 1 ? "disabled" : ""}>上一页</button><button class="mp-btn ghost small" data-plan-action="picker-page" data-page="${picker.page + 1}" ${picker.page >= pages ? "disabled" : ""}>下一页</button></div></footer></section>${pickerDetailMarkup()}</div>`;
}

function pickerDetailMarkup() {
    const detail = state.pickerDetail;
    if (!detail) return "";
    const item = detail.item;
    const resource = detail.data || item;
    const type = item.resourceType;
    const favorite = isFavorite(type, item.id);
    const body = detail.loading ? `<div class="mp-empty">详情加载中...</div>`
        : detail.error ? `<div class="mp-empty">${escapeHtml(detail.error)}</div>`
            : `<div class="mp-picker-detail-body">${renderPickerDetailBody(resource, type)}</div>`;
    return `<div class="mp-picker-detail-overlay" data-picker-detail-overlay><section class="mp-picker-detail" role="dialog" aria-modal="true" aria-label="资源详情"><header><div><span class="mp-eyebrow">${escapeHtml(RESOURCE_TYPE_LABELS[type] || "资源")}详情</span><h2>${escapeHtml(resource.name || item.id)}</h2></div><button class="mp-close" data-plan-action="close-picker-detail" aria-label="关闭资源详情">×</button></header>${body}<footer class="drawer-footer"><button class="mp-fav-toggle ${favorite ? "active" : ""}" data-plan-action="picker-favorite" data-resource-type="${escapeHtml(type)}" data-resource-id="${escapeHtml(item.id)}" aria-pressed="${favorite}" title="${favorite ? "取消收藏" : "收藏"}">${favorite ? "♥ 已收藏" : "♡ 收藏"}</button></footer></section></div>`;
}

function renderPickerDetailBody(resource, type) {
    const mediaUrl = type === "EXERCISE" ? (resource.mediaUrl || resource.thumbnailUrl || null) : (resource.mediaUrl || null);
    const media = resource.mediaUrl || resource.thumbnailUrl || resource.mediaStatus !== undefined || resource.mediaState !== undefined
        ? renderMedia(mediaUrl, resource.name, { credit: resource.mediaCredit }) : "";
    const common = `<div class="kv-list"><div class="kv"><span>资源 ID</span><span>${escapeHtml(resource.id)}</span></div><div class="kv"><span>审核状态</span><span>${escapeHtml(resource.reviewStatus || "未提供")}</span></div><div class="kv"><span>来源</span><span>${escapeHtml(resource.sourceName || resource.sourceType || "未提供")}${resource.sourceId ? ` · ${escapeHtml(resource.sourceId)}` : ""}${resource.sourceVersion ? ` · v${escapeHtml(resource.sourceVersion)}` : ""}</span></div></div>`;
    if (type === "MEAL") {
        const nutrition = resource.nutrition || {};
        const serving = resource.serving || {};
        const tags = Object.entries(resource.tags || {}).flatMap(([key, values]) => `${key}：${arrayText(values)}`);
        return `${media}<h3>餐食详情</h3>${common}<div class="kv-list"><div class="kv"><span>英文名/别名</span><span>${escapeHtml([resource.nameEn, arrayText(resource.aliases)].filter(Boolean).join(" · ") || "未提供")}</span></div><div class="kv"><span>媒体状态</span><span>${escapeHtml(resource.mediaStatus || "未提供")}</span></div><div class="kv"><span>描述</span><span>${escapeHtml(resource.description || "未提供")}</span></div><div class="kv"><span>食材</span><span>${escapeHtml(arrayText(resource.ingredients) || "未提供")}</span></div><div class="kv"><span>份量</span><span>${escapeHtml(serving.count == null ? "未提供" : `${serving.count} 份${serving.size == null ? "" : ` · ${serving.size} ${serving.unit || "g"}`}`)}</span></div><div class="kv"><span>营养</span><span>${escapeHtml(`热量 ${nutrition.caloriesKcal ?? "-"} kcal · 蛋白质 ${nutrition.proteinG ?? "-"} g · 脂肪 ${nutrition.fatG ?? "-"} g · 碳水 ${nutrition.carbohydrateG ?? "-"} g`)}</span></div><div class="kv"><span>营养依据</span><span>${escapeHtml(nutrition.basis || "未提供")}</span></div><div class="kv"><span>过敏原</span><span>${escapeHtml(arrayText(resource.allergens) || "未提供")} · ${escapeHtml(resource.allergenStatus || "未提供")}</span></div></div>${tags.length ? `<div class="chips">${tags.map((tag) => `<span class="chip selected">${escapeHtml(tag)}</span>`).join("")}</div>` : ""}`;
    }
    const steps = Array.isArray(resource.steps) ? resource.steps : [];
    const instruction = steps.length ? `<h3>动作步骤</h3><ol class="ordered-list">${steps.map((step) => `<li>${escapeHtml(step)}</li>`).join("")}</ol>` : `<h3>动作讲解</h3><p style="line-height:1.7;margin:0;">${escapeHtml(resource.instructionsZh || "未提供")}</p>`;
    return `${media}<h3>动作详情</h3>${common}<div class="kv-list"><div class="kv"><span>英文名/别名</span><span>${escapeHtml([resource.nameEn, arrayText(resource.aliases)].filter(Boolean).join(" · ") || "未提供")}</span></div><div class="kv"><span>媒体状态</span><span>${escapeHtml(resource.mediaState || "未提供")}</span></div><div class="kv"><span>类别</span><span>${escapeHtml(resource.category || "未标注")}</span></div><div class="kv"><span>部位</span><span>${escapeHtml(resource.bodyPart || "未标注")}</span></div><div class="kv"><span>目标肌群</span><span>${escapeHtml(arrayText(resource.targetMuscles) || "未标注")}</span></div><div class="kv"><span>辅助肌群</span><span>${escapeHtml(arrayText(resource.secondaryMuscles) || "未标注")}</span></div><div class="kv"><span>器材</span><span>${escapeHtml(resource.equipment || "未标注")}</span></div><div class="kv"><span>难度</span><span>${escapeHtml(resource.difficulty || "未标注")}</span></div><div class="kv"><span>动作模式</span><span>${escapeHtml(resource.movementPattern || "未标注")}</span></div><div class="kv"><span>风险标签</span><span>${escapeHtml(arrayText(resource.riskTags) || "未标注")}</span></div><div class="kv"><span>替代组</span><span>${escapeHtml(resource.alternativeGroup || "未标注")}</span></div><div class="kv"><span>计划资格</span><span>${resource.planReady ? "可入周计划" : "不可入周计划"}</span></div><div class="kv"><span>资格审核</span><span>${escapeHtml([resource.qualificationVersion, resource.qualificationVisible ? "可浏览" : "不可浏览", resource.qualificationRecommendable ? "可推荐" : "不可推荐", resource.qualificationPlanReady ? "可入计划" : "不可入计划"].filter(Boolean).join(" · ") || "未提供")}</span></div><div class="kv"><span>原始来源字段</span><span>${escapeHtml([resource.sourceCategory, resource.sourceBodyPart, resource.sourceEquipment, resource.sourceTarget, resource.sourceMuscleGroup, arrayText(resource.sourceSecondaryMuscles)].filter(Boolean).join(" · ") || "未提供")}</span></div></div>${instruction}`;
}

function arrayText(value) {
    if (Array.isArray(value)) return value.join("、");
    return value == null ? "" : String(value);
}

function renderPickerItem(item) {
    const type = item.resourceType;
    const favorite = isFavorite(type, item.id);
    const detail = type === "MEAL" ? `${item.nutrition?.caloriesKcal ?? "-"} kcal · ${(item.tags?.mealTime || []).join("、") || "餐食"}` : `${item.bodyPart || "-"} · ${item.equipment || "-"}${item.planReady ? " · 可入周计划" : " · 不可入周计划"}`;
    const key = `picker-${type}-${item.id}`;
    registerResource(key, { ...item, resourceType: type, resourceId: String(item.id), __detailLoader: type === "MEAL" ? () => getMealDetail(item.id) : () => getExerciseDetail(item.id) });
    return `<article class="mp-resource-card"><button class="mp-resource-main" data-plan-action="picker-detail" data-resource-key="${escapeHtml(key)}" data-resource-type="${type}" data-resource-id="${escapeHtml(item.id)}"><div class="mp-thumb ${type === "MEAL" ? "meal" : "exercise"}">${type === "MEAL" ? "餐" : "练"}</div><div><strong>${escapeHtml(item.name || item.id)}</strong><p><span>${escapeHtml(detail)}</span></p><small>${escapeHtml(item.sourceName || "审核资源")}</small></div></button><div class="mp-resource-actions"><button class="mp-fav-toggle ${favorite ? "active" : ""}" data-plan-action="picker-favorite" data-resource-type="${type}" data-resource-id="${escapeHtml(item.id)}" aria-pressed="${favorite}" title="${favorite ? "取消收藏" : "收藏"}">${favorite ? "♥" : "♡"}</button><button class="mp-btn primary small" data-plan-action="choose-resource" data-resource-type="${type}" data-resource-id="${escapeHtml(item.id)}">选择</button></div></article>`;
}

function statusLabel(status) { return { ENABLED: "已启用", UNENABLED: "未启用", DRAFT: "草稿", HISTORY: "历史" }[status] || status || "计划"; }
function scopeLabel(scope) { return { EXERCISE: "训练", MEAL: "餐食", COMPOSITE: "综合" }[scope] || "综合"; }
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

function appendPlanKey(hash) {
    const query = new URLSearchParams((hash || "").split("?")[1] || "");
    const planId = query.get("appendPlanId");
    return planId ? `${planId}:${query.get("appendScope") || "MEAL"}` : "";
}

async function startAppend(app, key) {
    const [planId, scope] = key.split(":");
    state.appending = true;
    state.appendError = null;
    render(app);
    try {
        const source = state.summaries.find((plan) => String(plan.id) === String(planId));
        const result = source?.status === "ENABLED" ? await editPlan(planId) : await getPlan(planId);
        state.summaries = await listPlans();
        state.detail = result;
        state.draftItems = cloneItems(result.items || []);
        state.selectedId = result.id;
        state.loaded = true;
        state.appending = false;
        resetPlanEditing(result);
        history.replaceState(null, "", "#/plans");
        render(app);
        openPicker("add", result.weekStart, "12:00", scope === "EXERCISE" ? "EXERCISE" : "MEAL");
    } catch (error) {
        state.appending = false;
        state.appendError = error.message || "当前计划编辑副本创建失败";
        render(app);
    }
}

async function startGeneration(app) {
    state.generating = true; state.generationError = null; render(app);
    try {
        const query = new URLSearchParams((location.hash || "").split("?")[1] || "");
        const result = await runPlanGenerationRequest((payload, signal) => generateTrainingPlan(payload, { signal }), { sessionId: getChatSessionId(), requestId: query.get("requestId") || newRequestId(), planScope: query.get("scope") || "COMPOSITE" });
        state.generating = false; state.detail = result.plan; state.draftItems = cloneItems(result.plan?.items || []); state.selectedId = result.plan?.id || null; state.summaries = await listPlans(); state.loaded = true; resetPlanEditing(result.plan);
        history.replaceState(null, "", "#/plans"); showToast(`计划已生成（${planGenerationSourceLabel(result.generationSource)}）`); render(app);
    } catch (error) { state.generating = false; state.generationError = error.message || "计划生成失败"; render(app); }
}

async function loadSummaries() {
    try { state.summaries = await listPlans(); const preferred = state.summaries.find((plan) => plan.status === "ENABLED") || state.summaries[0]; if (!state.selectedId && preferred) state.selectedId = preferred.id; if (state.selectedId) await loadDetail(state.selectedId); }
    catch (error) { state.error = error.message || "计划加载失败"; }
    finally { state.loading = false; state.loaded = true; }
}

async function loadDetail(planId) {
    try { const detail = await getPlan(planId); state.detail = detail; state.selectedId = planId; state.draftItems = cloneItems(detail.items || []); resetPlanEditing(detail); state.mobileDay = 0; state.resourceCache.clear(); }
    catch (error) { showToast(error.message || "计划详情加载失败", "error"); }
}

function bind(app) {
    if (app.dataset.plansBound === "true") return;
    app.dataset.plansBound = "true";
    app.addEventListener("click", handleClick); app.addEventListener("input", handleInput); app.addEventListener("change", handleChange); app.addEventListener("submit", handleSubmit); bindDrawer(app); bindFeedbackControl(app);
    const drawerRoot = document.getElementById("drawer-root");
    if (drawerRoot && drawerRoot.dataset.planBound !== "true") { drawerRoot.dataset.planBound = "true"; drawerRoot.addEventListener("click", handleDrawerClick); }
    window.addEventListener("beforeunload", handleBeforeUnload);
    if (!window.__plansPickerEscapeBound) { window.__plansPickerEscapeBound = true; window.addEventListener("keydown", handlePlansKeydown); }
    registerRouteLeaveGuard("/plans", confirmLeavePlans);
}
function handleBeforeUnload(event) { if (currentRoute() === "/plans" && state.dirty) { event.preventDefault(); event.returnValue = ""; } }
function handlePlansKeydown(event) { if (event.key === "Escape" && state.pickerDetail) { event.preventDefault(); closePickerDetail(true); } else if (event.key === "Escape" && state.picker) { event.preventDefault(); closePicker(); } }

function handleClick(event) {
    if (event.target.matches("[data-picker-detail-overlay]")) { closePickerDetail(true); return; }
    if (event.target.matches("[data-picker-overlay]")) { closePicker(); return; }
    const target = event.target.closest("[data-plan-action], [data-plan-id]"); if (!target) return;
    if (target.dataset.planId && !target.dataset.planAction) { if (state.sidebarCollapsed) state.sidebarCollapsed = false; selectPlan(target.dataset.planId); return; }
    const action = target.dataset.planAction;
    if (action === "collapse") { state.sidebarCollapsed = !state.sidebarCollapsed; render(document.getElementById("app")); return; }
    if (action === "new") { navigate("/chat"); return; }
    if (action === "retry") { resetAndReload(); return; }
    if (action === "retry-generation") { startGeneration(document.getElementById("app")); return; }
    if (action === "filter") { state.detailFilter = target.dataset.filter || "ALL"; render(document.getElementById("app")); return; }
    if (action === "edit-name") { beginNameEdit(); return; }
    if (action === "save-name") { saveAll(); return; }
    if (action === "cancel-name") { cancelNameEdit(); return; }
    if (action === "mobile-day") { state.mobileDay = Number(target.dataset.day) || 0; render(document.getElementById("app")); return; }
    if (action === "add") { openPicker("add", target.dataset.localDate, target.dataset.startTime, preferredPickerType()); return; }
    if (action === "open") { openItem(target.dataset.itemId); return; }
    if (action === "save") { saveAll(); return; }
    if (action === "cancel") { cancelEdits(); return; }
    if (action === "confirm" || action === "enable" || action === "disable" || action === "archive") { mutateStatus(action, target.dataset.planId); return; }
    if (action === "copy") { copyPlanToDraft(target.dataset.planId); return; }
    if (action === "edit") { editEnabledPlan(target.dataset.planId); return; }
    if (action === "discard") { discardDraft(target.dataset.planId); return; }
    if (action === "close-picker") { closePicker(); return; }
    if (action === "close-picker-detail") { closePickerDetail(true); return; }
    if (action === "picker-type") { state.picker.type = target.dataset.type; state.picker.page = 1; state.picker.filters = {}; loadPicker(); return; }
    if (action === "picker-favorites") { state.picker.favoriteOnly = !state.picker.favoriteOnly; state.picker.page = 1; loadPicker(); return; }
    if (action === "picker-page") { const page = Number(target.dataset.page); if (page > 0) { state.picker.page = page; loadPicker(); } return; }
    if (action === "choose-resource") { chooseResource(target.dataset.resourceType, target.dataset.resourceId); return; }
    if (action === "picker-detail") { openPickerDetail(target); return; }
    if (action === "picker-favorite") { togglePickerFavorite(target.dataset.resourceType, target.dataset.resourceId); }
}

function handleChange(event) {
    const target = event.target.closest("[data-plan-action='picker-filter']");
    if (target && state.picker) { state.picker.filters[target.dataset.filterKey] = target.value; state.picker.page = 1; loadPicker(); }
}
function handleInput(event) {
    const target = event.target.closest("[data-plan-name]");
    if (!target || !state.nameEditor) return;
    state.nameEditor.input(target.value);
    syncDirty();
}
function handleSubmit(event) { if (event.target.matches("[data-picker-search]")) { event.preventDefault(); state.picker.query = event.target.elements.q.value.trim(); state.picker.page = 1; loadPicker(); } }
function handleDrawerClick(event) { const button = event.target.closest("[data-plan-resource-picker]"); if (button) openPicker("replace", null, null, button.dataset.resourceType, button.dataset.itemId); }

async function selectPlan(planId) {
    if (!(await confirmDiscardChanges("切换计划会丢失当前编辑。"))) return;
    await loadDetail(planId); render(document.getElementById("app"));
}
function preferredPickerType() { return state.detail?.planScope === "EXERCISE" ? "EXERCISE" : "MEAL"; }
function closePicker() { state.picker = null; state.pickerDetail = null; state.pickerDetailToken++; render(document.getElementById("app")); }
function closePickerDetail(restoreFocus) {
    const resource = state.pickerDetail?.item;
    state.pickerDetail = null; state.pickerDetailToken++;
    render(document.getElementById("app"));
    if (restoreFocus && resource) document.querySelector(`[data-plan-action="picker-detail"][data-resource-id="${CSS.escape(String(resource.id))}"]`)?.focus();
}
function openPickerDetail(target) {
    const picker = state.picker;
    const item = picker?.items.find((entry) => entry.resourceType === target.dataset.resourceType && String(entry.id) === String(target.dataset.resourceId));
    if (!item) return;
    const token = ++state.pickerDetailToken;
    state.pickerDetail = { item, loading: true, error: null, data: null };
    render(document.getElementById("app"));
    const loader = item.resourceType === "MEAL" ? () => getMealDetail(item.id) : () => getExerciseDetail(item.id);
    Promise.resolve().then(loader).then((data) => {
        if (!state.pickerDetail || token !== state.pickerDetailToken || String(state.pickerDetail.item.id) !== String(item.id)) return;
        state.pickerDetail.loading = false; state.pickerDetail.data = data; render(document.getElementById("app"));
    }).catch((error) => {
        if (!state.pickerDetail || token !== state.pickerDetailToken || String(state.pickerDetail.item.id) !== String(item.id)) return;
        state.pickerDetail.loading = false; state.pickerDetail.error = error.message || "资源详情加载失败"; render(document.getElementById("app"));
    });
}
function openPicker(mode, localDate, startTime, type, itemId) {
    if (!editablePlan(state.detail)) return;
    const item = itemId ? findItem(itemId) : null;
    state.pickerDetail = null;
    state.picker = { mode, type: type || item?.resourceType || (mode === "add" ? "MEAL" : preferredPickerType()), itemId: itemId || null, localDate: localDate || item?.localDate || state.detail.weekStart, startTime: startTime || shortTime(item?.startTime) || "12:00", query: "", filters: {}, favoriteOnly: false, page: 1, total: 0, items: [], loading: true, error: null };
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
    const currentlyFavorite = isFavorite(type, id);
    try {
        await toggleFavorite(type, id, () => currentlyFavorite
            ? removeFavorite(type, id)
            : addFavorite(type, id));
        render(document.getElementById("app"));
        showToast(isFavorite(type, id) ? "已收藏" : "已取消收藏");
    } catch (error) {
        // toggleFavorite 已完成失败回滚和错误提示。
    }
}

function chooseResource(type, resourceId) {
    const resource = state.picker?.items.find((item) => item.resourceType === type && String(item.id) === String(resourceId)); if (!resource) return;
    state.resourceCache.set(`${type}:${resourceId}`, resource);
    if (state.picker.mode === "replace") {
        const item = findItem(state.picker.itemId);
        if (item) { item.resourceType = type; item.resourceId = String(resourceId); item.name = resource.name; item.params = defaultPlanParams(resource); markItemsDirty(); }
        state.picker = null; render(document.getElementById("app")); openItem(item?.id); showToast("资源已替换，保存后生效"); return;
    }
    const total = timeMinutes(state.picker.startTime) + 30;
    const item = { id: `new-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, resourceType: type, resourceId: String(resourceId), name: resource.name, localDate: state.picker.localDate, startTime: `${shortTime(state.picker.startTime)}:00`, endTime: `${timeValue(total)}:00`, note: "", params: defaultPlanParams(resource) };
    state.draftItems.push(item); markItemsDirty(); state.picker = null; render(document.getElementById("app")); showToast("项目已加入，保存后生效");
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
    item.localDate = values.localDate; item.startTime = `${values.startTime}:00`; item.endTime = `${values.endTime}:00`; item.note = values.note || ""; markItemsDirty(); closeDrawer(); render(document.getElementById("app")); showToast("项目修改已加入批量保存");
}
async function removeItem(item) { if (!(await requestConfirmation({ title: "删除计划项目", description: `确定删除“${item.name}”吗？`, confirmLabel: "删除" }))) return; state.draftItems = state.draftItems.filter((entry) => entry !== item); markItemsDirty(); closeDrawer(); render(document.getElementById("app")); }

async function saveAll() {
    if (state.saving || !state.detail || !editablePlan(state.detail)) return;
    const nameState = nameEditorState(state.detail);
    const nameError = nameState ? state.nameEditor.validate() : null;
    if (nameError) { showToast(nameError, "error"); return; }
    state.saving = true;
    try {
        const name = nameState?.draftName || planName(state.detail);
        const items = state.draftItems.map((item) => ({ id: /^\d+$/.test(String(item.id)) ? Number(item.id) : null, resourceType: item.resourceType, resourceId: item.resourceId, name: item.name, localDate: item.localDate, startTime: item.startTime, endTime: item.endTime, note: item.note || "", planParams: item.params || {} }));
        const saved = await updatePlanItems(state.detail.id, { requestId: newRequestId(), expectedVersion: state.detail.currentVersion, name, items });
        state.detail = saved; state.draftItems = cloneItems(saved.items || []); state.summaries = await listPlans(); resetPlanEditing(saved); closeDrawer({ restoreFocus: false }); showToast("计划已保存"); render(document.getElementById("app"));
    } catch (error) { showToast(error.message || "计划保存失败，修改仍保留", "error"); }
    finally { state.saving = false; }
}
async function cancelEdits() {
    if (!(await confirmDiscardChanges("取消编辑会丢失当前未保存修改。"))) return;
    state.draftItems = cloneItems(state.detail?.items || []);
    state.itemsDirty = false;
    cancelNameEdit(false);
    closeDrawer({ restoreFocus: false });
    render(document.getElementById("app"));
    showToast("已取消未保存修改");
}
function mutationPayload(planId) { const plan = state.detail && String(state.detail.id) === String(planId) ? state.detail : state.summaries.find((entry) => String(entry.id) === String(planId)); return { requestId: newRequestId(), expectedVersion: plan?.currentVersion || 1 }; }

async function mutateStatus(action, planId) {
    const labels = { confirm: ["确认草稿", "确认后可单独启用这份计划。"], enable: ["启用计划", "当前已启用计划会回到未启用。"], archive: ["转为历史", "历史计划只读，仍保留在计划库。"] };
    if (String(planId) === String(state.selectedId) && !(await confirmDiscardChanges("状态操作会丢失当前编辑。"))) return;
    if (labels[action] && !(await requestConfirmation({ title: labels[action][0], description: labels[action][1], confirmLabel: labels[action][0] }))) return;
    try { const fn = { confirm: confirmPlan, enable: enablePlan, disable: disablePlan, archive: archivePlan }[action]; const result = await fn(planId, mutationPayload(planId)); state.summaries = await listPlans(); state.detail = result; state.draftItems = cloneItems(result.items || []); resetPlanEditing(result); showToast({ confirm: "草稿已确认", enable: "计划已启用", disable: "计划已停用", archive: "计划已转为历史" }[action]); render(document.getElementById("app")); }
    catch (error) { showToast(error.message || "状态操作失败", "error"); }
}
async function copyPlanToDraft(planId) { if (String(planId) === String(state.selectedId) && !(await confirmDiscardChanges("复制计划会丢失当前编辑。"))) return; try { const result = await copyPlan(planId, mutationPayload(planId)); state.summaries = await listPlans(); state.detail = result; state.draftItems = cloneItems(result.items || []); state.selectedId = result.id; resetPlanEditing(result); showToast("已复制为草稿"); render(document.getElementById("app")); } catch (error) { showToast(error.message || "复制失败", "error"); } }
async function editEnabledPlan(planId) { if (String(planId) === String(state.selectedId) && !(await confirmDiscardChanges("创建编辑草稿会丢失当前编辑。"))) return; if (!(await requestConfirmation({ title: "创建编辑草稿", description: "原计划保持启用，系统会创建一份草稿副本。", confirmLabel: "创建草稿" }))) return; try { const result = await editPlan(planId); state.summaries = await listPlans(); state.detail = result; state.draftItems = cloneItems(result.items || []); state.selectedId = result.id; resetPlanEditing(result); showToast("已创建编辑草稿"); render(document.getElementById("app")); } catch (error) { showToast(error.message || "创建草稿失败", "error"); } }
async function discardDraft(planId) { if (String(planId) === String(state.selectedId) && !(await confirmDiscardChanges("删除草稿会丢失当前编辑。"))) return; if (!(await requestConfirmation({ title: "删除草稿", description: "删除后不能恢复。", confirmLabel: "删除草稿" }))) return; try { const response = await fetch(`/api/v1/health/plans/${encodeURIComponent(planId)}`, { method: "DELETE" }); if (!response.ok) throw new Error("删除草稿失败"); state.summaries = await listPlans(); state.selectedId = state.summaries[0]?.id || null; state.detail = state.selectedId ? await getPlan(state.selectedId) : null; state.draftItems = cloneItems(state.detail?.items || []); resetPlanEditing(state.detail); showToast("草稿已删除"); render(document.getElementById("app")); } catch (error) { showToast(error.message || "删除草稿失败", "error"); } }
function resetAndReload() { state.error = null; state.summaries = []; state.detail = null; state.selectedId = null; state.loaded = false; state.loading = false; state.nameEditor = null; state.nameEditorPlanId = null; state.itemsDirty = false; state.appendAttemptedKey = null; state.appendError = null; syncDirty(); render(document.getElementById("app")); }

export const __plansTest = { defaultPlanParams, timeMinutes, nextHalfHour };
