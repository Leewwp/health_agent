// 测试钩子：从 plans.js 提取 renderGenerationNotes 纯函数用于 node 模块测试。
// plans.js 是页面模块（引入 router/api 依赖），直接 import 会触发副作用；
// 这里以源码函数体在受控作用域内求值，保持被测代码与生产一致。
import { readFile } from "node:fs/promises";

const escapeHtml = (value) => String(value ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");

export async function loadRenderGenerationNotes() {
    const source = await readFile(new URL("../assets/js/pages/plans.js", import.meta.url), "utf8");
    const start = source.indexOf("function renderGenerationNotes(plan)");
    const end = source.indexOf("\nfunction ", start + 10);
    const body = source.slice(start, end);
    return new Function("escapeHtml", `${body}; return renderGenerationNotes;`)(escapeHtml);
}

const renderGenerationNotes = await loadRenderGenerationNotes();
export function renderGenerationNotesForTest(plan) {
    return renderGenerationNotes(plan);
}
