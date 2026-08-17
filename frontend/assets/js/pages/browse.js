/**
 * 浏览页共享实现（餐食 / 动作复用，规格 6.2 分页浏览契约）。
 *
 * 后端浏览接口只支持 page/size 分页（无筛选参数，契约在 33/40 冻结），
 * 页面一次性拉取全部审核子集后在本地完成筛选与分页展示；
 * 数据规模扩量后保留服务端分页参数不变。
 * 列表统一提供加载占位、空结果、失败重试；媒体失败/无图使用稳定占位。
 *
 * 子类只需提供：load 函数、筛选字段定义、卡片渲染参数与事件选择器前缀。
 */
import { escapeHtml } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { getOrCreateClientSessionId } from "../store.js";
import { renderResourceCard } from "../ui/resource-card.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { bindDrawer } from "../ui/detail-drawer.js";
import { currentRoute } from "../router.js";

const MAX_LOAD_PAGES = 100;
const PAGE_SIZE = 20;

export function createBrowsePage(definition) {
    const { title, subtitle, route, load, filterFields, pageSize, resourceOptions } = definition;
    const size = pageSize || PAGE_SIZE;

    const state = {
        items: [],
        loading: false,
        loaded: false,
        error: null,
        page: 1,
        filters: {}
    };
    let listenersBound = false;

    function render(app) {
        if (!state.loaded && !state.loading && !state.error) {
            state.loading = true;
            state.error = null;
            loadAll().then(() => {
                if (currentRoute() === route) {
                    render(app);
                }
            });
            app.innerHTML = `
                <section class="section">
                    <div class="card-title"><div><h2>${escapeHtml(title)}</h2><p>${escapeHtml(subtitle)}</p></div></div>
                    ${renderSkeletons(6)}
                </section>
            `;
            return;
        }
        app.innerHTML = `
            <section class="section">
                <div class="card-title">
                    <div>
                        <h2>${escapeHtml(title)}</h2>
                        <p>共 ${state.items.length} 条${state.error ? "，本次加载失败" : ""}。</p>
                    </div>
                </div>
                ${state.error ? renderError() : ""}
                ${state.items.length ? `
                    <form class="card filter-bar" data-browse-filter="1">
                        ${renderFilterField("search", "搜索", `<input name="search" value="${escapeHtml(state.filters.search || "")}" placeholder="按名称搜索">`)}
                        ${filterFields.map((field) => renderFilterField(field.key, field.label, renderSelect(field.key, state.filters[field.key]))).join("")}
                        <button class="btn ghost" type="button" data-action="reset-browse-filters">重置</button>
                    </form>
                    <div class="grid three">${renderPage()}</div>
                    ${renderPagination()}
                ` : `<div class="empty">暂无数据。</div>`}
            </section>
        `;
        bind(app);
    }

    function bind(app) {
        if (listenersBound) {
            return;
        }
        listenersBound = true;
        app.addEventListener("click", handleClick);
        app.addEventListener("change", handleChange);
        app.addEventListener("submit", handleSubmit);
        bindDrawer(app);
        bindFeedbackControl(app);
    }

    async function loadAll() {
        try {
            const items = [];
            for (let page = 1; page <= MAX_LOAD_PAGES; page += 1) {
                const result = await load({ page, size: 50 });
                items.push(...result.items);
                if (page >= result.totalPages) {
                    break;
                }
                if (page === MAX_LOAD_PAGES) {
                    throw new Error("数据页数超过本地浏览安全上限");
                }
            }
            state.items = items;
        } catch (error) {
            state.error = error.message || "数据加载失败";
        } finally {
            state.loading = false;
            state.loaded = true;
        }
    }

    function filteredItems() {
        const search = (state.filters.search || "").trim().toLowerCase();
        return state.items.filter((item) => {
            if (search && !(item.name || "").toLowerCase().includes(search)) {
                return false;
            }
            return filterFields.every((field) => {
                const selected = state.filters[field.key];
                if (!selected) {
                    return true;
                }
                return (item[field.key] === selected) || ((item.tags || {})[field.key] || []).includes(selected);
            });
        });
    }

    function renderPage() {
        const list = filteredItems();
        if (!list.length) {
            return `<div class="empty" style="grid-column:1/-1;">没有符合筛选条件的数据。</div>`;
        }
        const start = (state.page - 1) * size;
        return list.slice(start, start + size).map((item) =>
            renderResourceCard(item, { sessionId: getOrCreateClientSessionId(), ...resourceOptions })
        ).join("");
    }

    function renderPagination() {
        const total = filteredItems().length;
        const totalPages = Math.max(1, Math.ceil(total / size));
        const page = Math.min(state.page, totalPages);
        const buttons = [];
        if (page > 1) {
            buttons.push(`<button class="btn ghost" data-action="browse-page" data-page="${page - 1}">上一页</button>`);
        }
        buttons.push(`<span>第 ${page} / ${totalPages} 页 · 共 ${total} 条</span>`);
        if (page < totalPages) {
            buttons.push(`<button class="btn ghost" data-action="browse-page" data-page="${page + 1}">下一页</button>`);
        }
        return `<div class="pagination">${buttons.join("")}</div>`;
    }

    function renderFilterField(key, label, control) {
        return `<div class="field"><label for="filter-${escapeHtml(key)}">${escapeHtml(label)}</label><span id="filter-${escapeHtml(key)}">${control}</span></div>`;
    }

    function renderSelect(key, selected) {
        return `
            <select name="${escapeHtml(key)}" data-browse-filter="${escapeHtml(key)}">
                <option value="">全部</option>
                ${distinctValues(key).map((value) => `<option value="${escapeHtml(value)}" ${selected === value ? "selected" : ""}>${escapeHtml(value)}</option>`).join("")}
            </select>
        `;
    }

    function distinctValues(fieldKey) {
        const set = new Set();
        state.items.forEach((item) => {
            const direct = item[fieldKey];
            if (direct) {
                set.add(direct);
            }
            ((item.tags || {})[fieldKey] || []).forEach((value) => set.add(value));
        });
        return Array.from(set).sort((a, b) => a.localeCompare(b, "zh-Hans-CN"));
    }

    function renderSkeletons(count) {
        return `<div class="skeleton-grid">${Array.from({ length: count }).map(() => `
            <div class="skeleton-card">
                <div class="skeleton-line title"></div>
                <div class="skeleton-line"></div>
                <div class="skeleton-line short"></div>
            </div>
        `).join("")}</div>`;
    }

    function renderError() {
        return `
            <div class="empty">
                <span>加载失败：${escapeHtml(state.error)}</span>
                <div class="button-row">
                    <button class="btn soft" data-action="retry-browse">重试</button>
                </div>
            </div>
        `;
    }

    function handleClick(event) {
        if (currentRoute() !== route) {
            return;
        }
        const target = event.target.closest("[data-action]");
        if (!target) {
            return;
        }
        const action = target.dataset.action;
        if (action === "retry-browse") {
            state.error = null;
            state.items = [];
            state.loading = false;
            state.loaded = false;
            render(document.getElementById("app"));
        } else if (action === "reset-browse-filters") {
            state.filters = {};
            state.page = 1;
            render(document.getElementById("app"));
        } else if (action === "browse-page") {
            state.page = Number(target.dataset.page);
            render(document.getElementById("app"));
        }
    }

    function handleChange(event) {
        if (currentRoute() !== route) {
            return;
        }
        const select = event.target.closest("[data-browse-filter]");
        if (!select) {
            return;
        }
        state.filters[select.dataset.browseFilter] = select.value || "";
        state.page = 1;
        render(document.getElementById("app"));
    }

    function handleSubmit(event) {
        if (currentRoute() !== route || !event.target.matches("form[data-browse-filter='1']")) {
            return;
        }
        event.preventDefault();
        const search = event.target.elements.search;
        if (search) {
            state.filters.search = search.value || "";
            state.page = 1;
            render(document.getElementById("app"));
        }
    }

    return { render };
}
