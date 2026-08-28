/** 健康聊天的浏览器侧请求生命周期。 */

export const CHAT_FRONTEND_TIMEOUT_MS = 25_000;
export const CHAT_TIMEOUT_MESSAGE = "等待时间较长，本次请求已结束，请重试。";
export const CHAT_FAILURE_MESSAGE = "暂时无法完成推荐，请稍后重试。";

/** 创建单请求控制器：同步进入等待态、拒绝重复提交，并在所有结束路径恢复控件。 */
export function createChatRequestController(options) {
    const { request, onPendingChange = () => {}, onSuccess = () => {}, onFailure = () => {}, timeoutMs = CHAT_FRONTEND_TIMEOUT_MS } = options;
    let pending = false;
    async function submit(payload) {
        if (pending) return false;
        pending = true;
        onPendingChange(true);
        const abortController = new AbortController();
        let timedOut = false;
        const timeout = setTimeout(() => { timedOut = true; abortController.abort(); }, timeoutMs);
        try {
            const response = await request(payload, abortController.signal);
            onSuccess(response);
            return true;
        } catch (error) {
            onFailure(timedOut ? CHAT_TIMEOUT_MESSAGE : CHAT_FAILURE_MESSAGE);
            return false;
        } finally {
            clearTimeout(timeout);
            pending = false;
            onPendingChange(false);
        }
    }
    return { submit, isPending: () => pending };
}
