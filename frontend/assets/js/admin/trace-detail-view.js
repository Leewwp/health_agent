/** 原始 Trace 仅作为默认折叠的次级视图。参数必须已完成 HTML 转义。 */
export function renderRawTraceJson(escapedJson) {
    return `<details>
        <summary>脱敏原始 JSON（Agent 路由、校验与降级）</summary>
        <pre class="json-box">${escapedJson}</pre>
    </details>`;
}
