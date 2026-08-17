/**
 * 页面内模态框：替代 window.prompt / window.confirm，统一焦点与键盘行为。
 */
import { escapeHtml } from "../util/dom.js";

const root = document.getElementById("modal-root");
let pending = null;
let openerElement = null;

export function requestText(options) {
    return openModal({ ...options, mode: "text" });
}

export function requestConfirmation(options) {
    return openModal({ ...options, mode: "confirm" });
}

function openModal(options) {
    closeModal(null);
    openerElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const config = options || {};
    const suffix = Date.now();
    const titleId = `modal-title-${suffix}`;
    const inputId = `modal-input-${suffix}`;
    const input = config.mode === "text"
        ? `<div class="field">
                <label for="${inputId}">${escapeHtml(config.label || "输入内容")}</label>
                <input id="${inputId}" name="modalValue" value="${escapeHtml(config.initialValue || "")}" placeholder="${escapeHtml(config.placeholder || "")}" autocomplete="off">
           </div>`
        : "";

    root.innerHTML = `
        <div class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="${titleId}">
            <div>
                <h2 id="${titleId}">${escapeHtml(config.title || "请确认")}</h2>
                ${config.description ? `<p class="modal-copy">${escapeHtml(config.description)}</p>` : ""}
            </div>
            <form data-modal-form="1">
                ${input}
                <div class="modal-actions">
                    <button class="btn ghost" type="button" data-modal-cancel="1">${escapeHtml(config.cancelLabel || "取消")}</button>
                    <button class="btn ${config.danger ? "danger" : "primary"}" type="submit">${escapeHtml(config.confirmLabel || "确认")}</button>
                </div>
            </form>
        </div>
    `;
    root.classList.add("open");
    root.setAttribute("aria-hidden", "false");

    return new Promise((resolve) => {
        pending = { resolve, mode: config.mode };
        const initialFocus = root.querySelector("input") || root.querySelector("button[type='submit']");
        initialFocus?.focus({ preventScroll: true });
    });
}

function closeModal(value) {
    const current = pending;
    pending = null;
    root.classList.remove("open");
    root.setAttribute("aria-hidden", "true");
    root.innerHTML = "";
    if (current) {
        current.resolve(value);
    }
    if (openerElement && openerElement.isConnected) {
        openerElement.focus({ preventScroll: true });
    }
    openerElement = null;
}

root.addEventListener("click", (event) => {
    if (event.target === root || event.target.closest("[data-modal-cancel]")) {
        closeModal(pending && pending.mode === "confirm" ? false : null);
    }
});

root.addEventListener("submit", (event) => {
    if (!event.target.matches("[data-modal-form]")) {
        return;
    }
    event.preventDefault();
    if (pending && pending.mode === "text") {
        closeModal(event.target.elements.modalValue.value.trim());
    } else {
        closeModal(true);
    }
});

window.addEventListener("keydown", (event) => {
    if (!root.classList.contains("open")) {
        return;
    }
    if (event.key === "Escape") {
        event.preventDefault();
        closeModal(pending && pending.mode === "confirm" ? false : null);
        return;
    }
    if (event.key === "Tab") {
        trapFocus(event);
    }
});

function trapFocus(event) {
    const focusable = [...root.querySelectorAll("input, button, select, textarea, a[href]")]
        .filter((element) => !element.disabled && element.getAttribute("aria-hidden") !== "true");
    if (!focusable.length) {
        event.preventDefault();
        return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
    }
}
