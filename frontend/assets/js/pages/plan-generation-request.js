/** 训练计划生成的浏览器侧截止时间必须晚于 Nginx，同时不超过产品 20 秒上限。 */
export const PLAN_GENERATION_FRONTEND_TIMEOUT_MS = 20_000;
export const PLAN_GENERATION_TIMEOUT_MESSAGE = "训练计划生成超时，本次请求已结束，请重试。";

/** 生成参数的稳定去重键；同一请求防重复，不同 requestId 必须允许再次生成。 */
export function planGenerationRequestKey(hash) {
    const query = new URLSearchParams(String(hash || "").split("?")[1] || "");
    if (query.get("generate") !== "1") return null;
    return query.get("requestId") || "__generated_request__";
}

export async function runPlanGenerationRequest(request, payload, timeoutMs = PLAN_GENERATION_FRONTEND_TIMEOUT_MS) {
    const abortController = new AbortController();
    let timedOut = false;
    const timeout = setTimeout(() => {
        timedOut = true;
        abortController.abort();
    }, timeoutMs);
    try {
        return await request(payload, abortController.signal);
    } catch (error) {
        if (timedOut) {
            throw new Error(PLAN_GENERATION_TIMEOUT_MESSAGE);
        }
        throw error;
    } finally {
        clearTimeout(timeout);
    }
}
