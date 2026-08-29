# 回归矩阵与真实浏览器验收

Type: task
Status: resolved
Blocked by: 01, 02, 03, 04
Priority: P1

## Goal

以外部行为断言固化本轮修复（含判定原因与持久化合同），覆盖两轮审查要求的持久化、综合与可见性场景，并证明不回退既有结论，产出真实浏览器验收证据。

## Acceptance

- 补充续轮矩阵：简报完成后 +口味词/+菜系词/+烹饪时长词（含“我喜欢中餐”“烹饪时间短”原始句）→ 进简报且摘要更新；+明确推荐词 → 转单次推荐；+“换一批/替代推荐”→ ADJUST/ALTERNATIVE；+切域 → 暂停简报；+"回到计划" → 恢复；+作息提问 → 作息事实；+无关闲聊 → 保持待生成而非 RECOMMEND；无法解析 → 枚举指引且简报不变；社交短句 → 一行确认且简报保留。判定测试断言判定原因（escape 类型/activeSide/briefActive），不只断言最终 domain/task。
- 交叉输入优先级矩阵：固定裁决顺序（风险 > 切域/作息 > 替代/换一批 > 普通推荐 > 生命周期 > 侧归属 > 字段解析）下，"餐食：清淡""有什么推荐""再调整餐食计划"等交叉输入在所有调用点得到唯一裁决。
- 综合侧归属矩阵：NONE 写入餐食侧、焦点侧归属、BOTH 澄清要求前缀、显式前缀跨侧修改并重新展示生成入口。
- 综合 BOTH 回归例：两侧均完整时无前缀表达“改成下周”也必须要求“餐食：/训练：”前缀并返回侧归属澄清，不得直接写入训练侧。
- 生命周期矩阵：三个生成入口成功后对应范围转 GENERATED；MEAL-only/EXERCISE-only 不误关另一侧，COMPOSITE 同时关闭两侧；生成后"谢谢"为已生成确认；"再调整餐食计划"重开补充；幂等重放不改变状态且只保留一份草稿；计划写入/幂等记录/会话回写失败时请求 5xx，重试经 `GENERATE_<scope>` 写幂等最终一致；聊天补充与生成关闭并发时不得丢字段；新会话/显式切换关闭或暂停语义符合合同。
- 菜系解析矩阵：受支持值/未支持值/范围外拒绝三态；"中餐、川菜"采用川菜并记录中餐；已有值无改成语义时保留并提示只能选择一个。
- 预检矩阵：1/2/3+ 候选均先预检（含 Controller 层档案存在场景）；确认后直出；ADJUST/替代推荐直出；作息事实直出；零候选走追加条件流。
- 预期翻转清单：候选 1/2 时既有直接展示断言统一改为先预检、确认后直出；该行为变化是规格要求，不得以恢复候选数门槛维持旧断言。
- 文案断言：三个简报完成分支含"还可以补充"枚举；计划兜底通知为可行动指引；"中餐"未支持回应含可选值列表。
- 生成断言：两种 Provider 模式标签填充（fixture 按确定性标签表，含低油/高蛋白营养标签）、同字段 OR/跨字段 AND、偏好过滤命中、整天回退边界例（偏好池为空或交集无法形成完整餐次时整天回退，仍保留所选餐次，说明带日期）、纯热量回退不记偏好未满足、回退日多样性不放宽、generationNotes 在生成 metadata、版本快照、计划详情 API、版本详情 API、计划页两分区均可见、无偏好行为不变、综合计划偏好端到端。
- 持久化断言：新旧会话 JSON 序列化/反序列化往返（含 `_meta.briefLifecycle`、`_meta.recommendationConfirmationKey`、未支持项与新字段）；未支持项重启后保留；旧计划缺 metadata 时 generationNotes 返回非 null 空对象。
- 回归矩阵全绿：新会话模糊短句 OTHER+CHAT、五个快捷入口、跨域隔离、澄清短答继承、严格候选零候选不放宽、只生成所选餐次与热量归一化。
- 执行 `mvn -DskipTests compile`、聚焦测试、`mvn test`、`mvn test -Ditest.mysql=true`、前端 node 测试，全部通过并记录数字。
- 真实浏览器验收：完整走"简报完成 → 补充口味/菜系/烹饪时长 → 摘要更新 → 生成"与"简报完成 → 明确推荐词 → 预检 → 开始推荐"两条对话；补充"中餐"验证未支持回应；生成后说"谢谢"验证已生成关闭；计划页查看 generationNotes 说明；桌面 1440x900、移动 390x844 均无横向溢出，记录实际 URL、viewport、输入、点击、关键响应字段和结果并更新 `docs/frontend-browser-acceptance.md`。
- 证据回写本目录 map.md 与各票 Comments，票据转 resolved / ready-for-human。

## Comments

- 2026-08-29 resolved：回归矩阵全部落地——路由判定断言 reason/escape/activeSide/briefActive（`HealthBriefRouterTest`）；原始失败句与交叉输入优先级；综合 NONE/焦点侧/BOTH 澄清/显式前缀跨侧修改/BOTH 无前缀“改成下周”澄清；生命周期 MEAL-only/EXERCISE-only/COMPOSITE 关闭、生成后“谢谢”、显式计划词重开；生成幂等（同 requestId 一份草稿、跨 session/scope 冲突、回写失败 5xx、重试补偿）；会话并发（旧快照不覆盖 GENERATED、并发补充不丢字段）；候选标签两模式映射、同字段 OR/跨字段 AND；整天回退边界例与纯热量回退；generationNotes 五处可见 + 旧计划空对象；预检 1/2/3 候选 + 确认直出 + ADJUST/作息直出 + 零候选追加条件流。
- 既有回归全绿：新会话模糊短句 OTHER+CHAT、五快捷入口、跨域隔离、澄清短答继承、严格训练候选、零候选不放宽、只生成所选餐次、热量归一化、旧 `/api/v1/diet/**` 未改动。
- 执行记录：`mvn -DskipTests compile` 通过；`mvn test` 817 跑 / 0 失败 / 45 环境门控跳过；`mvn test -Ditest.mysql=true` 817 跑 / 0 失败 / 4 独立门控跳过（Qdrant/在线模型）；`node --test frontend/tests/*.test.mjs` 40/40 通过（基线 749 → 817，新增 68 例）。
- 真实浏览器验收：`http://localhost:8092`（REVIEWED_DB + 本机 MySQL），桌面 1440×900 与移动 390×844，13 项检查全通过（简报完成/口味/中餐/烹饪时长/摘要/开始生成/生成后谢谢/预检/开始推荐/计划页 generationNotes/双视口无横向溢出/chip 可点击/按钮无重叠），证据与截图见 `docs/frontend-browser-acceptance.md` 2026-08-29 小节与 `.local-run/acceptance/`。票据转 resolved / ready-for-human。
