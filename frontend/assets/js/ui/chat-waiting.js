/**
 * 聊天等待态通用文案（2026-08-31 严格路由规格：pending 指示器不暗示任务类型，
 * “正在为你推荐”只出现在完成响应中）。
 */
export function waitingMessageHtml() {
    return `<article class="message assistant chat-waiting" role="status"><div class="bubble"><span class="loading-spinner" aria-hidden="true"></span>正在生成回答，请稍候…</div></article>`;
}
