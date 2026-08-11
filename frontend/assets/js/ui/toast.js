/**
 * 轻量 Toast 提示（全局唯一，供所有页面复用）。
 */
const element = document.getElementById("toast");

let timer = null;

export function showToast(message, type) {
    element.textContent = message || "";
    element.className = `toast show ${type === "error" ? "error" : ""}`;
    window.clearTimeout(timer);
    timer = window.setTimeout(() => {
        element.className = "toast";
    }, 3200);
}

export default { showToast };
