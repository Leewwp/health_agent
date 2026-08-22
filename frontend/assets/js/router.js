/**
 * Hash 路由（18 号决策：保留 vanilla hash 路由，页面按需原生 import()）。
 */
import { clearResourceRegistry } from "./store.js";
import { closeDrawer } from "./ui/detail-drawer.js";

const ROUTES = new Map();
const NAV_ACTIVE_PREFIX = ["/chat", "/meals", "/exercises", "/plans", "/profile"];
const ROUTE_LEAVE_GUARDS = new Map();

export function register(path, loader) {
    ROUTES.set(path, loader);
}

/** 注册页面离开前的未保存确认，页面卸载时由页面自行处理原生 beforeunload。 */
export function registerRouteLeaveGuard(path, guard) {
    ROUTE_LEAVE_GUARDS.set(path, guard);
    return () => ROUTE_LEAVE_GUARDS.delete(path);
}

export function currentRoute() {
    const hash = location.hash || "#/chat";
    return hash.replace(/^#/, "").split("?")[0] || "/chat";
}

export function navigate(route) {
    const current = currentRoute();
    const next = String(route || "/chat").split("?")[0];
    const guard = ROUTE_LEAVE_GUARDS.get(current);
    if (guard && next !== current) {
        void Promise.resolve(guard(next)).then((allowed) => {
            if (allowed) location.hash = route;
        });
        return;
    }
    if (current === route) {
        render();
    } else {
        location.hash = route;
    }
}

export function initRouter(app) {
    window.addEventListener("hashchange", () => render(app));
    document.addEventListener("click", handleNavigationClick, true);
    app.addEventListener("click", (event) => {
        const retry = event.target.closest("[data-action='retry-render']");
        if (retry && retry.dataset.route) {
            render(app, true);
        }
    });
    render(app);
}

function handleNavigationClick(event) {
    const link = event.target.closest("a[href^='#/']");
    if (!link || event.defaultPrevented) return;
    const current = currentRoute();
    const nextHash = link.getAttribute("href").slice(1);
    const next = nextHash.split("?")[0];
    const guard = ROUTE_LEAVE_GUARDS.get(current);
    if (!guard || next === current) return;
    event.preventDefault();
    void Promise.resolve(guard(next)).then((allowed) => {
        if (allowed) location.hash = nextHash;
    });
}

async function render(app, force) {
    const route = currentRoute();
    setActiveNav(route);
    const loader = ROUTES.get(route);
    if (!loader) {
        navigate("/chat");
        return;
    }
    closeDrawer({ restoreFocus: false });
    clearResourceRegistry();
    try {
        const module = await loader();
        await module.render(app, route);
    } catch (error) {
        if (force) {
            return;
        }
        const message = String(error && error.message ? error.message : error || "未知错误")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;");
        app.innerHTML = `
            <section class="section">
                <div class="empty">
                    <span>页面加载失败：${message}</span>
                    <div class="button-row">
                        <button class="btn soft" data-action="retry-render" data-route="${route}">重试</button>
                    </div>
                </div>
            </section>
        `;
    }
    app.focus({ preventScroll: true });
}

function setActiveNav(route) {
    document.querySelectorAll("[data-nav]").forEach((item) => {
        const active = NAV_ACTIVE_PREFIX.some((prefix) => route === prefix || route.startsWith(`${prefix}/`));
        item.classList.toggle("active", item.dataset.nav === route && active);
    });
}
