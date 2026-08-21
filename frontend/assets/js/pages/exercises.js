/**
 * 动作浏览页（复用 pages/browse.js 共享实现）。
 * 展示难度、部位、器材与 plan_ready 资格；媒体失败/无图使用稳定占位，
 * 动作详情保留数据集署名。
 */
import { createBrowsePage } from "./browse.js";
import { listExercises } from "../api.js";

const FILTER_FIELDS = [
    { key: "bodyPart", label: "训练部位", options: ["全身", "胸", "背", "肩", "手臂", "腿", "核心", "臀", "颈部"] },
    { key: "equipment", label: "器材", options: ["徒手", "哑铃", "杠铃", "壶铃", "弹力带", "器械"] },
    { key: "difficulty", label: "难度", options: ["入门", "进阶", "挑战"] },
    { key: "movementPattern", label: "动作模式", options: ["推", "拉", "蹲", "髋", "踝", "核心", "有氧"] }
];

export const render = createBrowsePage({
    title: "健身动作库",
    subtitle: "1324 条真实动作资料；仅标记为可入周计划的动作会参与自动计划。",
    route: "/exercises",
    load: listExercises,
    filterFields: FILTER_FIELDS,
    pageSize: 18
}).render;
