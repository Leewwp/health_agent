# 推荐前预检、可补充项契约与文案

Type: task
Status: resolved
Blocked by: 02
Priority: P1

## Goal

修正推荐前预检触发条件（去掉候选数门槛）、统一按钮规范标签（以 ADR-0016 为准）、定义前后端"可补充项"稳定契约，并把简报完成文案统一为"已整理条件 + 可补充项枚举 + 开始/补充"。

## Acceptance

- 推荐前预检触发条件改为"候选非空 且 任务非 ADJUST 且 `escape` 非 ALTERNATIVE 且当前请求确认指纹未命中"；确认指纹为 `domain + task + 规范化有效槽位 + resourceProvider.resourceVersion` 的稳定 SHA-256，写入 `_meta.recommendationConfirmationKey`。槽位、领域、资源版本、替代推荐或新会话变化都会使旧确认失效；旧会话只有布尔确认而无指纹时按未确认处理。候选一两个时同样先预检，不再直接出结果。
- 预检内容保持"已确认条件 + 可补充槽位（仅未选项）+ 两个动作"；推荐预检沿用既有 optionalSlots 响应字段。
- **按钮规范标签以 ADR-0016 为准：推荐侧"开始推荐/补充"，计划侧"开始生成/补充"**；现有"为我推荐/继续补充需求"实现标签与确认短语清单同步收敛到规范标签；ADR、spec、动作标签、确认短语、自动化测试与浏览器证据使用同一套标签。
- 作息事实问答直出、零候选追加条件流、"换一批/替代推荐"不重复预检的行为保持不变。
- **前后端契约**：健康聊天响应新增计划简报"可补充项"字段，结构为 `{key, label, examples: string[], filled: boolean}` 列表（MEAL/EXERCISE/COMPOSITE 通用）；新增简报可选偏好与未支持项的响应字段（随既有简报对象序列化下发）；训练、餐食、综合三个简报完成分支回复统一为"已整理：{摘要}。还可以补充：{可补充项枚举}。可以直接开始生成，或回复想补充的条件。"，文案由后端该字段拼接。
- 前端：计划"补充"动作把可补充项渲染为可点 chip，点击把"属性名："参考输入填入输入框并聚焦；计划简报摘要组件渲染新增偏好与未支持项（不只依赖 speechText）；预检/生成按钮语义不变。
- 前端新增模块测试：chip 渲染与点击填充、计划摘要含新增偏好字段、generationNotes 两分区渲染。
- Controller 层真实 API 回归：档案存在前提下，候选 1/2/3 个均先预检、确认后直出、ADJUST 直出。
- 确认指纹规范化：使用 UTF-8 canonical 输入串 `domain=<值>\ntask=<值>\nslots=<按键名字典序、列表去重并按规范值排序的 canonical JSON>\nresourceVersion=<值>`，空值显式编码后计算 SHA-256；前端“开始推荐”发送同名确认短语，后端确认清单必须包含该短语。
- 既有断言翻转清单：`HealthOrchestratorServiceTest.信息不足先澄清再继续会话` 第三轮“清淡点”由直接 `ANSWER + displayBlocks` 改为候选非空时的预检响应；其他候选 1/2 直出断言按同一规则更新。ADJUST/ALTERNATIVE、零候选、作息事实直出不翻转，不得恢复候选数门槛。

## Comments

- 2026-08-29 resolved：预检触发改为“候选非空 且 task!=ADJUST 且 escape!=ALTERNATIVE 且确认指纹未命中”，候选 1/2/3 均先预检（编排器 `HealthBriefLifecycleOrchestratorTest` + Controller 层 `HealthChatPreflightControllerTest` 真实 API 断言）。确认指纹 `ConfirmationFingerprints.recommendation`：UTF-8 canonical 串 `domain=
task=
slots=<按键字典序、列表去重按规范值排序的 canonical JSON>
resourceVersion=`，空值显式编码后 SHA-256，写入 `_meta.recommendationConfirmationKey`；槽位/领域/资源版本变化使旧确认失效，替代推荐不重复预检，新会话不复用旧确认，旧会话只有布尔确认按未确认处理。
- 按钮规范标签 ADR-0016：推荐侧“开始推荐/补充”、计划侧“开始生成/补充”；前端 CONFIRM_RECOMMENDATION 发送同名确认短语“开始推荐”，后端 `isRecommendationConfirmation` 清单包含该短语（旧别名仅兼容）。
- 可补充项契约 `{key, label, examples, filled}` 随响应 `supplementable` 下发（MEAL/EXERCISE/COMPOSITE 通用，只列未填项）；三个简报完成分支统一输出“已整理：{摘要}。还可以补充：{枚举}。可以直接开始生成，或回复想补充的条件。”；计划兜底文案改为可行动指引（“可以说‘帮我安排这周的餐食计划’……我会先和你确认条件”）。预期翻转：`HealthOrchestratorServiceTest` 候选 1/2 直出断言按新合同更新（含“清淡点”第三轮改预检、综合 BOTH 无前缀“目标周”改侧归属澄清），未恢复候选数门槛。
- 前端：chip 渲染与点击填充“属性名：”并聚焦、结构化简报摘要、generationNotes 两分区（`frontend/tests/brief-supplement-loop.test.mjs` 6 例，全套 40/40）。
