/**
 * 餐食浏览页（复用 pages/browse.js 共享实现）。
 * 无图餐食使用稳定无图状态，来源与营养字段直接展示后端返回。
 */
import { createBrowsePage } from "./browse.js";
import { listMeals } from "../api.js";

const FILTER_FIELDS = [
    { key: "mealTime", label: "用餐时间" },
    { key: "cuisine", label: "菜系" },
    { key: "taste", label: "口味" },
    { key: "healthGoal", label: "健康目标" }
];

export const render = createBrowsePage({
    title: "餐食库",
    subtitle: "已审核的公共餐食，按来源与营养字段展示。",
    route: "/meals",
    load: listMeals,
    filterFields: FILTER_FIELDS,
    pageSize: 20
}).render;
