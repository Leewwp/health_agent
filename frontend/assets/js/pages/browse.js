/**
 * 浏览页共享实现（餐食 / 动作复用，规格 6.2 分页浏览契约）。
 *
 * 后端负责查询、结构化筛选、收藏过滤和分页，页面只渲染当前页结果。
 * 列表统一提供加载占位、空结果、失败重试；媒体失败/无图使用稳定占位。
 *
 * 子类只需提供：load 函数、筛选字段定义、卡片渲染参数与事件选择器前缀。
 */
import { escapeHtml } from "../util/dom.js";
import { showToast } from "../ui/toast.js";
import { getOrCreateClientSessionId, syncFavorites } from "../store.js";
import { renderResourceCard } from "../ui/resource-card.js";
import { bindFeedbackControl } from "../ui/feedback-control.js";
import { bindDrawer } from "../ui/detail-drawer.js";
import { currentRoute } from "../router.js";

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
        filters: {},
        favoriteOnly: false,
        total: 0
    };
    let listenersBound = false;

    function render(app) {
        if (!state.loaded && !state.loading && !state.error) {
            state.loading = true;
            state.error = null;
            loadPage().then(() => {
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
                <p>共 ${state.total} 条${state.error ? "，本次加载失败" : ""}。</p>
                    </div>
                </div>
                ${state.error ? renderError() : ""}
                ${state.loaded ? `
                    <form class="card filter-bar" data-browse-filter="1">
                        ${renderFilterField("search", "搜索", `<input name="search" value="${escapeHtml(state.filters.search || "")}" placeholder="按名称搜索">`)}
                        ${filterFields.map((field) => renderFilterField(field.key, field.label, renderSelect(field.key, state.filters[field.key]))).join("")}
                        <label class="check-field"><input type="checkbox" data-browse-favorite ${state.favoriteOnly ? "checked" : ""}> 仅看收藏</label>
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

    async function loadPage() {
        try {
            state.error = null;
            state.items = [];
            state.total = 0;
            try {
                await syncFavorites();
            } catch (error) {
                // 收藏服务暂时不可用时仍允许资源目录浏览，收藏按钮会在写入时报告错误。
            }
            const result = await load({
                page: state.page,
                size,
                favoriteOnly: state.favoriteOnly,
                q: state.filters.search,
                ...Object.fromEntries(filterFields.map((field) => [field.key, state.filters[field.key]]))
            });
            state.items = result.items || [];
            state.total = Number(result.total || 0);
        } catch (error) {
            state.error = error.message || "数据加载失败";
        } finally {
            state.loading = false;
            state.loaded = true;
        }
    }

    function filteredItems() {
        return state.items;
    }

    function renderPage() {
        const list = filteredItems();
        if (!list.length) {
            return `<div class="empty" style="grid-column:1/-1;">没有符合筛选条件的数据。</div>`;
        }
        return list.map((item) =>
            renderResourceCard(item, { sessionId: getOrCreateClientSessionId(), ...resourceOptions })
        ).join("");
    }

    function renderPagination() {
        const total = state.total;
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
                ${(fieldOptions(key)).map((value) => `<option value="${escapeHtml(value)}" ${selected === value ? "selected" : ""}>${escapeHtml(value)}</option>`).join("")}
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

    function fieldOptions(fieldKey) {
        const field = filterFields.find((entry) => entry.key === fieldKey);
        return field?.options?.length ? field.options : distinctValues(fieldKey);
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
            state.total = 0;
            state.loading = false;
            state.loaded = false;
            render(document.getElementById("app"));
        } else if (action === "reset-browse-filters") {
            state.error = null;
            state.filters = {};
            state.favoriteOnly = false;
            state.page = 1;
            state.loaded = false;
            state.loading = false;
            render(document.getElementById("app"));
        } else if (action === "browse-page") {
            state.error = null;
            state.page = Number(target.dataset.page);
            state.loaded = false;
            state.loading = false;
            render(document.getElementById("app"));
        }
    }

    function handleChange(event) {
        if (currentRoute() !== route) {
            return;
        }
        if (event.target.matches("[data-browse-favorite]")) {
            state.error = null;
            state.favoriteOnly = event.target.checked;
            state.page = 1;
            state.items = [];
            state.total = 0;
            state.loaded = false;
            state.loading = false;
            render(document.getElementById("app"));
            return;
        }
        const select = event.target.closest("[data-browse-filter]");
        if (!select) {
            return;
        }
        state.filters[select.dataset.browseFilter] = select.value || "";
        state.error = null;
        state.page = 1;
        state.loaded = false;
        state.loading = false;
        render(document.getElementById("app"));
    }

    function handleSubmit(event) {
        if (currentRoute() !== route || !event.target.matches("form[data-browse-filter='1']")) {
            return;
        }
        event.preventDefault();
        const search = event.target.elements.search;
        if (search) {
            state.error = null;
            state.filters.search = search.value || "";
            state.page = 1;
            state.loaded = false;
            state.loading = false;
            render(document.getElementById("app"));
        }
    }

    return { render };
}
