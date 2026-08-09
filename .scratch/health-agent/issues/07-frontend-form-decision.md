# 07 前端页面形态与项目定位

- Type: grilling
- Status: resolved
- Blocked by: —

## Question

前端形态与"**后端工程师**"项目定位如何统一（原 Q10，用户搁置后转为决策）？背景与约束：

- 现状：单页 vanilla JS（app.js ~1000 行、index.html + api.js + app.css），无构建步骤。
- 新增页面：健身动作浏览页、餐食浏览页、"我的计划"页。
- 用户画像：前端不熟、时间有限（1-2 周）、倾向"小而美"的后端项目、目标是让简历引导面试官提问后端内容。
- 候选方案：
  (a) 多页面 vanilla（零构建，最省时间，但页面越多 JS 越难维护）；
  (b) 单页 Tab 切换（SPA 手感，app.js 膨胀）；
  (c) 引入 Vue/React + Vite 构建（面试加分但违背"无 Node 构建"现状、增加成本，且部署需处理前端产物）；
  (d) 后端渲染（Thymeleaf 模板 + 少量 JS，纯后端视角，Spring Boot 原生能力）。

权衡维度：面试官视角的加分点、1-2 周可行性、用户自己的维护成本、与 03 号原型结果的契合。

技能：/grilling、/prototype（可复用 03 的原型验证交互）。产出：页面结构方案 + 理由 + 对规格"前端章节"的输入。

阻塞说明：03 号展示原型的结果（浏览页/浮窗交互形态）直接影响页面组织方式。

## Answer

已决策（用户 2026-08-09 前序会话拍板，见交接文档 `handoff-health-agent-frontend-split-rag-2026-08-09.md`）：

**方案 B：前后端分离 + Nginx 同源反向代理**
- `src/main/resources/static/` 移为独立目录 `frontend/`，jar 不再含前端；Nginx 托管 `frontend/`，`location /api/ { proxy_pass http://127.0.0.1:8080; }` + `try_files $uri $uri/ /index.html`。
- 保持 vanilla hash 路由（app.js 已有 6 个页签，新增页 = 加 hash 路由 + 渲染函数）；`api.js` 已用相对路径 `API_BASE="/api/v1/diet"` → API 路径可继续复用。实施时一次性迁移到 `frontend/`，不保留 static 双副本，回退依赖 git 历史。
- 部署形态：Docker Compose（nginx + jar + mysql）或单机 nginx + `java -jar`。
- 理由：vanilla 静态页技术上未过时（面试减分在"无工程化"而非技术错误）；同类 8 仓库中 6 个走前后端分离。
- 详细论证见 `docs/research/frontend-backend-split.md`（方案 B 实施前必读）。

仍开放的子问题：新页面（动作/餐食/我的计划）加入后 app.js 已 1000+ 行，原生 ES Modules 的模块边界与信息架构由 18 号票承接。

补充决策（2026-08-09）：方案 B 实施时**删除 `src/main/resources/static/`**，不保留双副本（避免前后端分家后两份前端漂移、面试官看到两份源码的困惑）；git 历史可找回。
