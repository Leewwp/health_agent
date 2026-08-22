/**
 * 前端入口：注册路由并启动（原生 ES Modules，无构建步骤）。
 */
import { initRouter, register, navigate } from "./router.js";

register("/chat", () => import("./pages/chat.js"));
register("/meals", () => import("./pages/meals.js"));
register("/exercises", () => import("./pages/exercises.js"));
register("/plans", () => import("./pages/plans.js"));
register("/profile", () => import("./pages/profile.js"));

register("/diet", () => import("./pages/legacy.js").then((m) => m.dietHome()));
register("/diet/chat", () => import("./pages/legacy.js").then((m) => m.dietChatPage()));
register("/diet/meals/personal", () => import("./pages/legacy.js").then((m) => m.dietPersonalMeals()));
register("/diet/meals/public", () => import("./pages/legacy.js").then((m) => m.dietPublicMeals()));

register("/admin/traces", () => import("./admin/traces.js"));
register("/admin/evaluations", () => import("./admin/evaluations.js"));

const app = document.getElementById("app");
initRouter(app);

if (!location.hash) {
    navigate("/chat");
}
