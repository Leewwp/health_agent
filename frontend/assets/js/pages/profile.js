/**
 * 健康档案页（M4：健康档案用户页）。
 *
 * 最小档案：年龄、身高、体重、活动水平、主要目标；生理性别、时区选填。
 * 保存后展示 Mifflin-St Jeor 估算的能量区间与计算依据，数值均标记为估算。
 */
import { escapeHtml } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { getProfile, saveProfile } from "../api.js";
import { currentRoute } from "../router.js";

const state = {
    profile: null,
    loading: false,
    loaded: false,
    saving: false,
    error: null,
    justSaved: false
};

/** 身体状况条件中文名（选项与摘要共用，顺序即展示顺序）。 */
const RISK_LABELS = {
    PREGNANCY: "孕产（孕期/哺乳期）",
    CURRENT_INJURY: "当前伤病（活动相关损伤未愈）",
    POST_SURGERY_REHAB: "术后/康复期",
    EATING_DISORDER: "进食障碍",
    CHRONIC_CONDITION: "需要医疗干预的慢性病"
};

let appElement = null;

export async function render(app) {
    appElement = app;
    if (!state.loaded && !state.loading) {
        state.loading = true;
        loadProfile().then(() => {
            if (currentRoute() === "/profile") {
                render(app);
            }
        });
        app.innerHTML = `<section class="section"><div class="empty">健康档案加载中...</div></section>`;
        return;
    }
    app.innerHTML = `
        <section class="section">
            <div class="card-title">
                <div>
                    <h2>健康档案</h2>
                    <p>用于估算每日能量区间与生成周计划；所有数值均为估算，不是医疗处方。</p>
                </div>
            </div>
            ${state.error && !state.profile ? `
                <div class="empty">
                    <span>${escapeHtml(state.error)}</span>
                    <div class="button-row"><button class="btn soft" data-action="retry-profile">重试</button></div>
                </div>
            ` : ""}
            ${state.profile ? renderSummary(state.profile) : ""}
            <form id="profileForm" class="form-grid" style="margin-top:${state.profile ? "18px" : "0"};">
                ${formField("age", "年龄", `<input type="number" name="age" min="18" max="100" value="${escapeHtml(state.profile?.age ?? "")}" required>`, "18-100 岁")}
                ${formField("sex", "生理性别（选填）", `<select name="sex">
                    <option value="">未填写</option>
                    <option value="MALE" ${state.profile?.sex === "MALE" ? "selected" : ""}>男</option>
                    <option value="FEMALE" ${state.profile?.sex === "FEMALE" ? "selected" : ""}>女</option>
                </select>`, "缺失时按两种性别取更宽区间")}
                ${formField("heightCm", "身高（cm）", `<input type="number" name="heightCm" min="100" max="250" step="0.1" value="${escapeHtml(state.profile?.heightCm ?? "")}" required>`, "100-250 cm")}
                ${formField("weightKg", "体重（kg）", `<input type="number" name="weightKg" min="30" max="300" step="0.1" value="${escapeHtml(state.profile?.weightKg ?? "")}" required>`, "30-300 kg")}
                ${formField("activityLevel", "活动水平", `<select name="activityLevel" required>
                    <option value="">请选择</option>
                    <option value="SEDENTARY" ${state.profile?.activityLevel === "SEDENTARY" ? "selected" : ""}>久坐（几乎不运动）</option>
                    <option value="LIGHT" ${state.profile?.activityLevel === "LIGHT" ? "selected" : ""}>轻度（每周 1-3 次）</option>
                    <option value="MODERATE" ${state.profile?.activityLevel === "MODERATE" ? "selected" : ""}>中度（每周 3-5 次）</option>
                </select>`, "活动系数 1.2 / 1.375 / 1.55")}
                ${formField("goal", "主要目标", `<select name="goal" required>
                    <option value="">请选择</option>
                    <option value="MAINTAIN" ${state.profile?.goal === "MAINTAIN" ? "selected" : ""}>维持体重</option>
                    <option value="LOSE" ${state.profile?.goal === "LOSE" ? "selected" : ""}>减脂</option>
                    <option value="GAIN" ${state.profile?.goal === "GAIN" ? "selected" : ""}>增重</option>
                </select>`, "目标调整 ±5% / -5%~-15% / +5%~+10%")}
                ${formField("timezone", "时区（选填）", `<input name="timezone" value="${escapeHtml(state.profile?.timezone ?? "Asia/Shanghai")}" placeholder="Asia/Shanghai">`, "默认 Asia/Shanghai")}
                <div class="field full">
                    <fieldset>
                        <legend>身体状况（选填）</legend>
                        <p class="field-hint">以下情况不会生成具体计划，仅返回固定安全提示，建议咨询专业医生或营养师。</p>
                        ${riskChecks(state.profile?.riskConditions ?? [])}
                    </fieldset>
                </div>
                ${formField("riskNote", "风险说明（选填）", `<textarea name="riskNote" maxlength="200" rows="2" placeholder="例如：右肩扭伤恢复中，遵医嘱服药">${escapeHtml(state.profile?.riskNote ?? "")}</textarea>`, "最长 200 字，仅作为补充说明")}
                <div class="field full">
                    <button class="btn primary" type="submit">${state.saving ? "保存中..." : "保存档案"}</button>
                    ${state.justSaved && new URLSearchParams((location.hash.split("?")[1] || "")).get("return") === "chat"
                        ? `<a class="btn soft" href="#/chat">回到聊天</a>` : ""}
                </div>
            </form>
        </section>
    `;
    bind(app);
}

function riskChecks(selected) {
    return `<div class="check-grid">` + Object.entries(RISK_LABELS).map(([value, label]) => `
        <label class="check-item">
            <input type="checkbox" name="riskCondition" value="${escapeHtml(value)}" ${selected.includes(value) ? "checked" : ""}>
            <span>${escapeHtml(label)}</span>
        </label>
    `).join("") + `</div>`;
}

let listenersBound = false;

function bind(app) {
    if (listenersBound) {
        return;
    }
    listenersBound = true;
    app.addEventListener("click", (event) => {
        const target = event.target.closest("[data-action]");
        if (target && target.dataset.action === "retry-profile") {
            state.error = null;
            state.loaded = false;
            render(appElement);
        }
    });
    app.addEventListener("submit", (event) => {
        if (event.target.id === "profileForm") {
            event.preventDefault();
            saveForm(event.target);
        }
    });
}

function formField(key, label, control, hint) {
    return `
        <div class="field">
            <label for="profile-${escapeHtml(key)}">${escapeHtml(label)}</label>
            <span id="profile-${escapeHtml(key)}">${control}</span>
            ${hint ? `<p class="field-hint">${escapeHtml(hint)}</p>` : ""}
        </div>
    `;
}

function renderSummary(profile) {
    const conditions = (profile.riskConditions || []).map((name) => RISK_LABELS[name] || name);
    return `
        <div class="calorie-range">
            <div class="stat-card"><span class="muted">每日能量下限（估算）</span><strong>${escapeHtml(profile.calorieLow)} kcal</strong></div>
            <div class="stat-card"><span class="muted">每日能量上限（估算）</span><strong>${escapeHtml(profile.calorieHigh)} kcal</strong></div>
        </div>
        <p class="muted" style="margin:0 0 4px;">${escapeHtml(profile.calcBasis)}</p>
        <p class="muted" style="margin:0;">档案版本 v${escapeHtml(profile.versionNo)} · 估算标记：${profile.estimated ? "是" : "否"}
            ${conditions.length ? ` · 身体状况：${escapeHtml(conditions.join("、"))}${profile.riskNote ? `（${escapeHtml(profile.riskNote)}）` : ""}` : ""}</p>
    `;
}

async function loadProfile() {
    try {
        state.profile = await getProfile();
    } catch (error) {
        if (error.status === 404) {
            state.profile = null;
        } else {
            state.error = error.message || "健康档案加载失败";
        }
    } finally {
        state.loading = false;
        state.loaded = true;
    }
}

async function saveForm(form) {
    const formData = new FormData(form);
    if (!form.checkValidity()) {
        form.reportValidity();
        return;
    }
    const payload = {
        age: Number(formData.get("age")),
        heightCm: Number(formData.get("heightCm")),
        weightKg: Number(formData.get("weightKg")),
        activityLevel: formData.get("activityLevel"),
        goal: formData.get("goal"),
        sex: formData.get("sex") || null,
        timezone: formData.get("timezone").trim() || "Asia/Shanghai",
        riskConditions: formData.getAll("riskCondition"),
        riskNote: (formData.get("riskNote") || "").trim() || null
    };
    state.saving = true;
    try {
        state.profile = await saveProfile(payload);
        state.justSaved = true;
        showToast("健康档案已保存");
    } catch (error) {
        showToast(error.message || "保存失败", "error");
    } finally {
        state.saving = false;
        render(appElement);
    }
}
