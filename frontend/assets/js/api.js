/**
 * 后端 API 封装（同源相对路径，方案 B 下经 Nginx /api/ 反代）。
 *
 * - 健康链路 `/api/v1/health/**`：匿名 Cookie 身份，无需自定义头；
 * - 旧饮食兼容链路 `/api/v1/diet/**`：继续使用 X-User-Id（开发默认允许）；
 * - admin 调试链路 `/api/v1/diet/debug/**`、`/evaluations`：开发环境开放，
 *   生产由 `X-Admin-Token` 保护（diet.security.admin-token）。
 */

import { BACKEND_UNAVAILABLE_MESSAGE, readResponseError } from "./http-error.js";

const HEALTH_BASE = "/api/v1/health";
const DIET_BASE = "/api/v1/diet";
const ADMIN_TOKEN_KEY = "health.adminToken";

export function getAdminToken() {
    return localStorage.getItem(ADMIN_TOKEN_KEY) || "";
}

export function setAdminToken(token) {
    const normalized = String(token || "").trim();
    if (normalized) {
        localStorage.setItem(ADMIN_TOKEN_KEY, normalized);
    } else {
        localStorage.removeItem(ADMIN_TOKEN_KEY);
    }
    return normalized;
}

export function getLegacyUserId() {
    return localStorage.getItem("diet.userId") || "1";
}

export function setLegacyUserId(userId) {
    const normalized = String(userId || "1").trim() || "1";
    localStorage.setItem("diet.userId", normalized);
    return normalized;
}

async function request(base, path, options) {
    const config = options || {};
    const headers = new Headers(config.headers || {});

    if (config.admin) {
        const token = getAdminToken();
        if (token) {
            headers.set("X-Admin-Token", token);
        }
    }
    if (config.legacy) {
        headers.set("X-User-Id", getLegacyUserId());
    }
    if (config.body !== undefined && !(config.body instanceof FormData)) {
        headers.set("Content-Type", "application/json");
    }

    let response;
    try {
        response = await fetch(`${base}${path}`, {
            ...config,
            headers,
            body: config.body === undefined || config.body instanceof FormData
                ? config.body
                : JSON.stringify(config.body)
        });
    } catch (error) {
        throw new Error(BACKEND_UNAVAILABLE_MESSAGE);
    }

    if (!response.ok) {
        const detail = await readError(response);
        const error = new Error(detail || `请求失败：${response.status}`);
        error.status = response.status;
        throw error;
    }
    if (response.status === 204) {
        return null;
    }

    const text = await response.text();
    if (!text) {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch (error) {
        return text;
    }
}

async function readError(response) {
    const text = await response.text();
    return readResponseError(response.status, response.headers.get("Content-Type"), text);
}

function toQuery(params) {
    const search = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== "") {
            search.set(key, value);
        }
    });
    const query = search.toString();
    return query ? `?${query}` : "";
}

/* ---------------- 健康链路 ---------------- */

export function healthChat(payload) {
    return request(HEALTH_BASE, "/chat", { method: "POST", body: payload });
}

export function listMeals(params) {
    return request(HEALTH_BASE, `/meals${toQuery(params)}`);
}

export function listExercises(params) {
    return request(HEALTH_BASE, `/exercises${toQuery(params)}`);
}

export function listPlans() {
    return request(HEALTH_BASE, "/plans");
}

export function getPlan(planId) {
    return request(HEALTH_BASE, `/plans/${encodeURIComponent(planId)}`);
}

export function createPlanDraft(payload) {
    return request(HEALTH_BASE, "/plans/drafts", { method: "POST", body: payload || {} });
}

export function activatePlan(planId) {
    return request(HEALTH_BASE, `/plans/${encodeURIComponent(planId)}/activate`, { method: "POST" });
}

export function editPlan(planId) {
    return request(HEALTH_BASE, `/plans/${encodeURIComponent(planId)}/edit`, { method: "POST" });
}

export function patchPlanItem(planId, itemId, payload) {
    return request(HEALTH_BASE, `/plans/${encodeURIComponent(planId)}/items/${encodeURIComponent(itemId)}`, {
        method: "PATCH",
        body: payload
    });
}

export function getProfile() {
    return request(HEALTH_BASE, "/profile");
}

export function saveProfile(payload) {
    return request(HEALTH_BASE, "/profile", { method: "PUT", body: payload });
}

export function sendFeedback(payload) {
    return request(HEALTH_BASE, "/feedback", { method: "POST", body: payload });
}

/* ---------------- 旧饮食兼容链路 ---------------- */

export function dietCreateSession() {
    return request(DIET_BASE, "/sessions", { method: "POST", legacy: true });
}

export function dietChat(payload) {
    return request(DIET_BASE, "/chat", { method: "POST", body: payload, legacy: true });
}

export function dietListPersonalMeals() {
    return request(DIET_BASE, "/meals/personal", { legacy: true });
}

export function dietCreatePersonalMeal(payload) {
    return request(DIET_BASE, "/meals/personal", { method: "POST", body: payload, legacy: true });
}

export function dietUpdatePersonalMeal(mealId, payload) {
    return request(DIET_BASE, `/meals/personal/${encodeURIComponent(mealId)}`, { method: "PUT", body: payload, legacy: true });
}

export function dietDeletePersonalMeal(mealId) {
    return request(DIET_BASE, `/meals/personal/${encodeURIComponent(mealId)}`, { method: "DELETE", legacy: true });
}

export function dietListPublicMeals() {
    return request(DIET_BASE, "/meals/public", { legacy: true });
}

export function dietSlotOptions() {
    return request(DIET_BASE, "/slot-options", { legacy: true });
}

export function dietSaveFeedback(payload) {
    return request(DIET_BASE, "/feedback", { method: "POST", body: payload, legacy: true });
}

/* ---------------- admin 调试链路 ---------------- */

export function listTraces(params) {
    return request(DIET_BASE, `/debug/traces${toQuery(params)}`, { admin: true });
}

export function getTrace(traceId) {
    return request(DIET_BASE, `/debug/traces/${encodeURIComponent(traceId)}`, { admin: true });
}

export function listSessionTraces(sessionId, limit) {
    return request(DIET_BASE, `/debug/sessions/${encodeURIComponent(sessionId)}/traces${toQuery({ limit })}`, { admin: true });
}

export function labelTrace(traceId, payload) {
    return request(DIET_BASE, `/debug/traces/${encodeURIComponent(traceId)}/label`, { method: "PUT", body: payload, admin: true });
}

export function runEvaluation(payload) {
    return request(DIET_BASE, "/evaluations", { method: "POST", body: payload, admin: true });
}
