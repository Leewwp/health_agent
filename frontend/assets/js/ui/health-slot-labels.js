/** 健康聊天槽位的用户文案，内部字段只在接口和状态机中使用。 */
export const HEALTH_SLOT_LABELS = Object.freeze({
    domain: "推荐类型",
    mealTime: "用餐时间",
    mood: "今天的心情",
    scene: "用餐场景",
    healthGoal: "健康目标",
    cuisine: "菜系或食材",
    taste: "口味",
    convenience: "能接受的耗时和购买方式",
    bodyPart: "训练部位",
    bodyParts: "训练部位",
    equipment: "器材",
    goal: "训练目标",
    trainingGoal: "训练目标",
    difficulty: "难度",
    weekStart: "目标周",
    trainingDays: "训练日期",
    timeWindow: "训练时段",
    wakeTime: "起床时间",
    bedtime: "入睡时间",
    sleepDuration: "睡眠时长"
});

/** 将接口字段名转换为普通用户能理解的槽位文案。 */
export function slotLabel(slot) {
    const value = String(slot || "").trim();
    if (!value) return "其他偏好";
    if (HEALTH_SLOT_LABELS[value]) return HEALTH_SLOT_LABELS[value];
    if (/^[\u4e00-\u9fff]/.test(value) && !/[A-Za-z]/.test(value)) return value;
    return "其他偏好";
}

/** 将已确认摘要中的内部字段名替换为中文，保留对应的用户值。 */
export function formatSlotSummary(summary) {
    const value = String(summary || "").trim();
    if (!value) return "";
    const separator = value.indexOf("：") >= 0 ? "：" : value.indexOf(":") >= 0 ? ":" : null;
    if (!separator) return slotLabel(value);
    const index = value.indexOf(separator);
    const key = value.slice(0, index).trim();
    const detail = value.slice(index + separator.length).trim();
    return detail ? `${slotLabel(key)}：${detail}` : slotLabel(key);
}
