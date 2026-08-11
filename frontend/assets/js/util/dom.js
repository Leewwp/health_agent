/**
 * 共享 DOM / 格式化工具。
 */

export function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

export function safeJson(value) {
    if (value === null || value === undefined || value === "") {
        return "";
    }
    try {
        const parsed = typeof value === "string" ? JSON.parse(value) : value;
        return JSON.stringify(parsed, null, 2);
    } catch (error) {
        return String(value);
    }
}

/** 生成唯一 requestId（聊天幂等键）。 */
export function newRequestId() {
    return typeof crypto !== "undefined" && crypto.randomUUID
        ? crypto.randomUUID()
        : `req_${Date.now()}_${Math.random().toString(36).slice(2)}`;
}

/** LocalDate 字符串转本地显示（YYYY-MM-DD -> MM-DD 周几）。 */
export function formatDateLabel(dateStr) {
    if (!dateStr) {
        return "";
    }
    const date = new Date(`${dateStr}T00:00:00`);
    const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
    return `${date.getMonth() + 1}月${date.getDate()}日 ${weekdays[date.getDay()]}`;
}

/** "HH:mm:ss" -> "HH:mm"。 */
export function formatTime(timeStr) {
    if (!timeStr) {
        return "";
    }
    return String(timeStr).slice(0, 5);
}

/** Date 对象转本地日期串（YYYY-MM-DD）；不用 toISOString 避免 +08:00 等时区回退一天。 */
export function localDateOf(date) {
    const pad = (value) => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function toLocalInputValue(date) {
    const pad = (value) => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
