/**
 * 共享状态（18 号决策：最小共享面）。
 *
 * - 收藏：按类型化资源身份 `MEAL:12` 索引，localStorage 持久化，
 *   页面内乐观更新后由本模块广播变更事件，保证不同入口收藏状态一致；
 * - 会话摘要：健康聊天 sessionId（localStorage，跨刷新保持）；
 * - 资源注册表：卡片与详情抽屉之间传递完整资源对象（避免把大 JSON
 *   塞进 data-* 属性）。
 */
import { showToast } from "./ui/toast.js";

const FAVORITES_KEY = "health.favorites.v1";
const SESSION_KEY = "health.chatSessionId";
const CLIENT_SESSION_KEY = "health.clientSessionId";
const REDUCED_KEY = "health.reducedRecommendations.v1";

const resourceRegistry = new Map();

/* ---------------- 收藏 ---------------- */

function loadFavorites() {
    try {
        const raw = JSON.parse(localStorage.getItem(FAVORITES_KEY) || "{}");
        return raw && typeof raw === "object" ? raw : {};
    } catch (error) {
        return {};
    }
}

function persistFavorites(favorites) {
    try {
        localStorage.setItem(FAVORITES_KEY, JSON.stringify(favorites));
    } catch (error) {
        // 存储不可用（如隐私模式）时只保留内存状态
    }
}

export function isFavorite(resourceType, resourceId) {
    return Boolean(loadFavorites()[`${resourceType}:${resourceId}`]);
}

function loadReduced() {
    try {
        const raw = JSON.parse(localStorage.getItem(REDUCED_KEY) || "{}");
        return raw && typeof raw === "object" ? raw : {};
    } catch (error) {
        return {};
    }
}

function persistReduced(reduced) {
    try {
        localStorage.setItem(REDUCED_KEY, JSON.stringify(reduced));
    } catch (error) {
        // 存储不可用时仍保留服务端记录。
    }
}

export function isReduced(resourceType, resourceId) {
    return Boolean(loadReduced()[`${resourceType}:${resourceId}`]);
}

/** 乐观更新减少推荐状态，失败时回滚。 */
export async function toggleReduced(resourceType, resourceId, sendFeedback) {
    const key = `${resourceType}:${resourceId}`;
    const reduced = loadReduced();
    const next = !reduced[key];
    reduced[key] = next;
    persistReduced(reduced);
    window.dispatchEvent(new CustomEvent("reducedchange"));
    try {
        await sendFeedback();
        return next;
    } catch (error) {
        const rolledBack = loadReduced();
        if (rolledBack[key] === next) {
            rolledBack[key] = !next;
            persistReduced(rolledBack);
            window.dispatchEvent(new CustomEvent("reducedchange"));
        }
        showToast(error.message || "减少推荐操作失败，已回滚", "error");
        throw error;
    }
}

/** 乐观更新收藏状态；失败时回滚并抛错（由调用方提示）。 */
export async function toggleFavorite(resourceType, resourceId, sendFeedback) {
    const key = `${resourceType}:${resourceId}`;
    const favorites = loadFavorites();
    const next = !favorites[key];
    favorites[key] = next;
    persistFavorites(favorites);
    notifyFavoritesChange();
    try {
        await sendFeedback();
        return next;
    } catch (error) {
        const rolledBack = loadFavorites();
        if (rolledBack[key] === next) {
            rolledBack[key] = !next;
            persistFavorites(rolledBack);
            notifyFavoritesChange();
        }
        showToast(error.message || "收藏操作失败，已回滚", "error");
        throw error;
    }
}

function notifyFavoritesChange() {
    window.dispatchEvent(new CustomEvent("favoriteschange"));
}

/* ---------------- 会话 ---------------- */

export function getChatSessionId() {
    return localStorage.getItem(SESSION_KEY) || null;
}

export function setChatSessionId(sessionId) {
    if (sessionId) {
        localStorage.setItem(SESSION_KEY, sessionId);
    } else {
        localStorage.removeItem(SESSION_KEY);
    }
}

export function clearChatSession() {
    localStorage.removeItem(SESSION_KEY);
}

/**
 * 显式创建新的聊天会话 ID。
 *
 * 后端在 sessionId 为空时会按匿名身份恢复稳定默认会话，因此“新会话”不能只清空
 * localStorage，否则会继续继承旧会话的槽位与风险信号。
 */
export function createChatSessionId() {
    const suffix = typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `${Date.now()}_${Math.random().toString(36).slice(2)}`;
    const sessionId = `sess_web_${suffix}`;
    localStorage.setItem(SESSION_KEY, sessionId);
    return sessionId;
}

/**
 * 反馈所需的会话上下文：优先使用真实聊天会话，否则生成一个稳定的
 * 客户端会话 ID（浏览页收藏/反馈同样需要后端 sessionId 字段）。
 */
export function getOrCreateClientSessionId() {
    const chatSession = getChatSessionId();
    if (chatSession) {
        return chatSession;
    }
    let clientSession = localStorage.getItem(CLIENT_SESSION_KEY);
    if (!clientSession) {
        clientSession = typeof crypto !== "undefined" && crypto.randomUUID
            ? `sess_client_${crypto.randomUUID()}`
            : `sess_client_${Date.now()}_${Math.random().toString(36).slice(2)}`;
        localStorage.setItem(CLIENT_SESSION_KEY, clientSession);
    }
    return clientSession;
}

/* ---------------- 资源注册表 ---------------- */

/**
 * 卡片与详情抽屉之间传递完整资源对象（避免把大 JSON 塞进 data-* 属性）。
 * 注册表只在当前页面生命周期内有效：路由切换时由 router 清空，
 * 避免跨页面累积。
 */
export function registerResource(key, resource) {
    resourceRegistry.set(key, resource);
}

export function getResource(key) {
    return resourceRegistry.get(key);
}

export function clearResourceRegistry() {
    resourceRegistry.clear();
}
