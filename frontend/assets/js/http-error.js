export const BACKEND_UNAVAILABLE_MESSAGE = "后端服务暂时不可用，请确认应用已启动后重试。";

/**
 * 将代理和后端错误收敛为可安全展示的文本，避免把 Nginx HTML 错误页直接塞进界面。
 */
export function readResponseError(status, contentType, text) {
    const normalized = String(text || "").trim();
    if (!normalized) {
        return status >= 500 ? BACKEND_UNAVAILABLE_MESSAGE : `请求失败：${status}`;
    }

    const type = String(contentType || "").toLowerCase();
    if (type.includes("application/json")) {
        try {
            const payload = JSON.parse(normalized);
            return payload.message || payload.error || `请求失败：${status}`;
        } catch (error) {
            return status >= 500 ? BACKEND_UNAVAILABLE_MESSAGE : `请求失败：${status}`;
        }
    }

    const looksLikeHtml = type.includes("text/html") || /^\s*<(?:!doctype|html|head|body)\b/i.test(normalized);
    if (looksLikeHtml || status >= 500) {
        return BACKEND_UNAVAILABLE_MESSAGE;
    }
    return normalized;
}
