# 训练计划与资源选择修复规格复核

日期：2026-08-28

本复核把目标会话 `01a0467b-9f2c-7663-ae45-66f57b35f641` 的最终规格，与当前工作树、既有规格/ADR、自动化测试和 GitHub issue 状态逐项对照。结论不是重新设计产品，而是区分“已被原规格覆盖”“发现同构缺陷”“需要产品决策”的三类结果。

## 覆盖结论

| 领域 | 原规格覆盖情况 | 复核结论 |
|---|---|---|
| 健身单次推荐 | 已覆盖四项澄清、空结果边界、追加保留原条件 | 保留。需要补齐“澄清阶段不得调用 Provider/候选检索”的外部行为测试。 |
| 餐食单次推荐 | 原规格只写“最低槽位语义不改变” | 未发现必须改语义的同构 bug；餐食仍允许 `mealTime` 满足后进入推荐前预览。需补充跨域回归，防止套用健身四项门槛或污染训练简报。 |
| 训练计划 | 已覆盖全身连续日、全局动作去重、候选不足复用、fallback/Guard | 保留。现有 `PlanValidationService` 和 fallback 是修复主要接缝。 |
| 餐食计划 | 原规格未检查生成器是否消费已确认餐次 | 发现确定性缺陷：`MealPlanBrief.mealTimes` 被保存但 `MealPlanGenerationService -> WeeklyPlanComposerService` 未传递，组合器固定生成早/午/晚。 |
| 综合计划 | 原规格只覆盖训练子计划和一次性落库 | 发现两项同构缺陷：复用固定三餐；两份简报都确认后再修改输入不会失效对应确认。 |
| 计划上下文 | 已覆盖训练字段修改与新任务退出 | 需扩展到餐食和 COMPOSITE 子简报，并测试只失效被修改的一侧。 |
| 资源选择器 | 已覆盖 CSS 两列、独立详情弹窗、层级、Tab、焦点和异步竞态 | 保留。餐食/动作两类都适用，新增默认餐食 Tab 的约定明确。 |
| 详情与反馈 | 原规格未覆盖异步摘要重复、动作讲解重复、详情抽屉事件绑定和 picker 收藏状态 | 发现确定性前端缺陷：摘要与完整详情并存；`instructionsZh` 与 `steps` 重复；`#drawer-root` 未绑定反馈委托；picker 重渲染短路使用旧 `item.favorite`。 |
| 时间输入 | 原规格只覆盖分钟级中文/数字表达 | 发现 `HH:mm:ss` 会被正则从秒字段中间截取，需补充秒为 00 的归一化和非零秒拒绝。 |

## 代码证据

- `HealthClarifyRuleService.minimumRecommendationSlots` 对 `MEAL` 只要求 `mealTime`，对 `EXERCISE` 当前只要求目标和部位；目标会话要求将后者收紧为四项。餐食语义不能因修训练而被误改。
- `MealPlanGenerationService` 只传 `calorieLow/calorieHigh/weekStart` 给 `composeMeals`；`WeeklyPlanComposerService` 内部固定遍历 `MEAL_WINDOWS` 对应的早/午/晚选择。`mealTimes` 没有进入生成路径。
- `CompositePlanGenerationService` 调用同一 `mealComposer`，因此餐次遗漏会同时影响综合计划。
- `HealthOrchestratorService.handleCompositePlanBrief` 只在子简报未确认时更新；训练和餐食都确认后直接返回生成操作，未处理后续字段修改。
- `MealPlanPicker.pickForDay` 每日按相同排序和预算重新选择；`composeMeals` 连续七天调用该方法，当前会产生跨日重复。三餐内部已经有不重复约束，但没有周内约束。
- `PlanValidationService.checkBodyPartConsecutive` 对所有训练项目按 `bodyPart` 生效；“全身”被归一为主部位时会触发错误阻断，训练修复需改变这一规则，不应波及餐食能量/时间规则。
- 新进程复现确认：源码已移除 `BODY_PART_CONSECUTIVE` 执行路径，旧 8082 进程曾在工作树更新前启动，是用户示例阻断的运行时原因；仍需把“重启后真实生成”列为验收门槛。
- `HealthPlanIntentMatcher.matchesComposite` 未覆盖“健身和餐食计划”；`HealthIntentRevisionService.explicitDomain` 会把综合阶段单独输入“减脂”抢成 EXERCISE，导致餐食简报丢失。需要有序阶段、共享字段继承和组合表达补全。
- `detail-drawer.js` 在异步 loader 存在时渲染 `renderBody(resource)` 后再追加完整详情，导致两个媒体区域；`renderExerciseBody` 同时输出 `instructionsZh` 和 `steps`，导致重复讲解。
- `feedback-control.js` 只绑定页面容器，未绑定独立 `#drawer-root`；浏览页卡片反馈可用，详情抽屉反馈点击无效。`plans.js` 的 picker 收藏虽已写入后端，但 `item.favorite ?? isFavorite(...)` 优先取旧字段，重渲染显示未收藏。
- Picker 详情 API 已返回完整餐食/动作字段，现有弹窗只展示 ID、少量摘要和描述，属于前端字段遗漏，不需要新增后端详情接口。

## 与既有文档的一致性检查

- `CONTEXT.md` 的“推荐前预览”已区分餐食最低槽位与健身四项完整澄清；“计划上下文修改”和 picker detail 边界与目标规格一致。
- `docs/adr/0015-plan-context-diversity-and-picker-detail.md` 只记录训练多样性与 picker 边界，未覆盖餐食简报消费和 COMPOSITE 二次修改；目标规格现已补充，不修改 ADR 的既有决定。
- `docs/mvp-phases.md` 将餐食计划、训练计划、综合计划列为第一阶段，并把餐食简报多值偏好列为第二轮收口。这里的“多值偏好”指菜系/口味等字段，不应被误解为可以忽略已确认的 `mealTimes`；规格现已明确两者区别。
- `.scratch/health-agent/issues/101-interview-mvp-closeout.md` 仍记录 GitHub #101 已关闭和本地 `claimed` 审计状态；本轮不重开历史票，新修复另建票并在根地图建立指针。

## 已确认产品决策（2026-08-28）

1. 只选择一个或两个餐次时，只生成所选餐次，并将每日热量预算按所选餐次归一化权重分配。
2. 餐食候选足够时，七天内优先换餐；候选不足时允许最少重复，且不因去重不足阻断计划。
3. 综合计划默认先完成餐食追问，再完成训练追问；用户可在过程中补充另一侧，系统合并字段但保持当前追问阶段连续。餐食与训练共享目标（如减脂）可互相继承，两侧均完整后显示“开始生成”。
4. 异步详情只保留完整详情；动作有步骤时只显示步骤。媒体依据实际返回内容选择，不假设同一资源同时展示静态图和动图。
5. 资源库详情抽屉收藏/减少推荐走现有服务端反馈并即时更新；picker 详情允许保留收藏按钮。
6. 计划时间接受秒为 00 的 `HH:mm:ss` 并归一化，非零秒拒绝。

## GitHub 梳理结果

- #101 当前为 `CLOSED`，内容只证明第一阶段旧收口，不足以承载本轮新发现；不修改其关闭状态。
- 本轮已创建 [#102](https://github.com/Leewwp/health_agent/issues/102)，标题明确包含训练澄清、餐食/综合计划一致性和 picker 详情，正文链接本文件与 `spec.md`。
- #102 的六项新增产品决策已于 2026-08-28 确认；规格、实现票和本审计已同步，最终测试与浏览器证据已追加到 Issue（最新更正评论：[issuecomment-5450182922](https://github.com/Leewwp/health_agent/issues/102#issuecomment-5450182922)），审阅前保持 OPEN。

## 可安全开工的范围

可安全开工范围已解除：训练四项澄清门槛、训练全身连续日规则、训练周内去重/fallback、训练/餐食/综合计划上下文路由、综合有序阶段与共享字段、时间秒输入、详情去重/完整字段、详情反馈和 picker 独立详情弹窗与布局，以及餐食/综合生成的所选餐次、归一化热量分配和跨日软多样性均已具备明确合同。

## 最终实现与验收证据（2026-08-28）

- `mvn test`：729 项，725 通过，4 个环境门控跳过，无失败。
- `mvn test -Ditest.mysql=true`：729 项，725 通过，4 个独立环境门控跳过，无失败；40 个真实 MySQL 场景执行。
- `node --test frontend/tests/*.test.mjs`：31/31 通过（含详情异步竞态、步骤去重、反馈根节点和 picker 完整字段契约）；三个修改后的 ES Module 均通过 `node --check`；`git diff --check` 通过。
- 后端重启为 `http://127.0.0.1:8082` 后，经 `http://127.0.0.1:8092` 真实 API 验证：组合意图命中 `COMPOSITE/PLAN`；“三餐”继续追问餐食目标；“减脂”不跳出组合域；餐食简报完整后才追问训练；`HH:mm:ss` 秒为 `00` 时被接受，非零秒返回格式错误。
- 额外边界回归：餐食简报完整后，训练阶段输入“目标 增肌”只更新训练简报，餐食目标和简报内容保持不变；只有明确的“餐食/餐次/饮食”词才会修改餐食子简报，避免阶段倒退。
- ego-browser 真实页面 `http://127.0.0.1:8092/#/plans`：新增默认餐食 Tab；选择器详情展示餐食媒体、描述、食材、份量、完整营养、过敏原、标签、来源与审核状态；收藏按钮点击后即时切换。`#/exercises` 详情抽屉实际显示单一媒体和拆分动作步骤；抽屉内收藏/减少推荐控件已出现并可绑定。
- 规格状态改为 `ready-for-human`；GitHub #102 保持 `OPEN`，#101 保持 `CLOSED`。
