/**
 * 餐食浏览页（复用 pages/browse.js 共享实现）。
 * 无图餐食使用稳定无图状态，来源与营养字段直接展示后端返回。
 */
import { createBrowsePage } from "./browse.js";
import { listMeals } from "../api.js";

const FILTER_FIELDS = [
    { key: "mealTime", label: "用餐时间", options: ["三餐", "下午茶", "加餐", "午餐", "早午餐", "早餐", "晚餐"] },
    { key: "cuisine", label: "菜系", options: ["东南亚菜", "海鲜", "甜品", "粥汤", "素食", "西餐"] },
    { key: "taste", label: "口味", options: ["咸鲜", "奶香", "甜", "辣", "酸甜"] },
    { key: "healthGoal", label: "健康目标", options: ["低油", "均衡", "控碳水", "清淡", "高蛋白"] }
];

export const render = createBrowsePage({
    title: "餐食库",
    subtitle: "已审核的公共餐食，按来源与营养字段展示。",
    route: "/meals",
    load: listMeals,
    filterFields: FILTER_FIELDS,
    pageSize: 20
}).render;
