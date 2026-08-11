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
