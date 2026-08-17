/**
 * 媒体状态渲染（18 号决策 / 规格 10 前端章节）。
 *
 * - 无媒体 URL 或媒体状态不合格时使用稳定无图占位，不改变卡片尺寸；
 * - 有 URL 但加载失败（媒体 404 / 断网）时切换为同一占位；
 * - 动作媒体必须保留数据集署名（mediaCredit），餐食同样展示媒体署名。
 */
import { escapeHtml } from "../util/dom.js";

const PLACEHOLDER_MARK = "图";

/**
 * 渲染媒体区域。
 * @param {string|null} url 已通过授权状态校验的媒体地址
 * @param {string} label 资源名称（占位符可读性用）
 * @param {{credit?: string|null}} options 媒体署名
 */
export function renderMedia(url, label, options) {
    const credit = (options && options.credit) || "";
    if (!url) {
        return `${mediaPlaceholder(label)}${creditLine(credit)}`;
    }
    return `
        <figure class="media-frame" data-media-frame="1" data-media-label="${escapeHtml(label || "")}">
            <img src="${escapeHtml(url)}" alt="${escapeHtml(label)}"
                 data-media-img="1">
        </figure>
        ${creditLine(credit)}
    `;
}

document.addEventListener("error", (event) => {
    const image = event.target.closest?.("[data-media-img]");
    const frame = image?.closest("[data-media-frame]");
    if (frame) {
        frame.innerHTML = mediaPlaceholderInner(frame.dataset.mediaLabel || "");
    }
}, true);

function mediaPlaceholder(label) {
    return `<div class="media-frame">${mediaPlaceholderInner(label)}</div>`;
}

function mediaPlaceholderInner(label) {
    const mark = escapeHtml(PLACEHOLDER_MARK);
    const text = escapeHtml(label ? `${label} · 无图` : "无图");
    return `<span class="media-placeholder" data-media-placeholder="1">
                <span class="media-mark">${mark}</span>
                <span>${text}</span>
            </span>`;
}

function creditLine(credit) {
    if (!credit) {
        return "";
    }
    return `<p class="media-credit">媒体署名：${escapeHtml(credit)}</p>`;
}
