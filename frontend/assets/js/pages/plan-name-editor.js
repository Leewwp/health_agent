/** 计划名称编辑状态机：保存前只保留输入状态，便于页面与回归测试共用。 */
export function createPlanNameEditor(initialName, maxLength = 128) {
    const originalName = String(initialName || "").trim();
    let draftName = originalName;
    let editing = false;

    const snapshot = () => ({
        originalName,
        draftName,
        editing,
        dirty: editing && draftName !== originalName
    });

    return {
        begin() {
            draftName = originalName;
            editing = true;
            return snapshot();
        },
        input(value) {
            if (editing) draftName = String(value ?? "").slice(0, maxLength);
            return snapshot();
        },
        validate() {
            const value = draftName.trim();
            if (!value) return "计划名称不能为空";
            if (value.length > maxLength) return `计划名称不能超过 ${maxLength} 个字符`;
            return null;
        },
        cancel() {
            draftName = originalName;
            editing = false;
            return snapshot();
        },
        commit() {
            const value = draftName.trim();
            draftName = value;
            editing = false;
            return { ...snapshot(), savedName: value };
        },
        state() {
            return snapshot();
        }
    };
}
