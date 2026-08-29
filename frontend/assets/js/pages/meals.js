/**
 * 餐食浏览页（复用 pages/browse.js 共享实现）。
 * 无图餐食使用稳定无图状态，来源与营养字段直接展示后端返回。
 */
import { createBrowsePage } from "./browse.js";
import { listMeals } from "../api.js";
// 菜系/餐食类型词表来自 data/meal/facets.json 的生成物（词表唯一事实源，漂移由守卫测试阻挡）
import { CUISINE_OPTIONS, FOOD_TYPE_OPTIONS } from "../data/mealFacets.js";

const FILTER_FIELDS = [
    { key: "mealTime", label: "用餐时间", options: ["三餐", "下午茶", "加餐", "午餐", "早午餐", "早餐", "晚餐"] },
    { key: "cuisine", label: "菜系", options: CUISINE_OPTIONS },
    { key: "foodType", label: "餐食类型", options: FOOD_TYPE_OPTIONS },
    { key: "taste", label: "口味", options: ["清淡", "咸鲜", "奶香", "甜", "辣", "酸甜", "麻辣", "番茄味"] },
    { key: "healthGoal", label: "健康目标", options: ["减脂", "增肌", "维持健康", "均衡"] }
];

export const render = createBrowsePage({
    title: "餐食库",
    subtitle: "已审核的公共餐食，按来源与营养字段展示。",
    route: "/meals",
    load: listMeals,
    filterFields: FILTER_FIELDS,
    pageSize: 20
}).render;
