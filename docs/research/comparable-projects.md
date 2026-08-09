# 同类项目前端方案对比调研:Spring Boot + LLM 聊天项目的常见形态

## 背景

本项目是 Spring Boot 3.3 + MyBatis + MySQL + 阿里云 DashScope 的"AI 饮食推荐聊天"课程项目,前端是零构建 vanilla JS 单页(放在 `src/main/resources/static/`)。本调研回答:GitHub 上规模相近、功能相近(几个 controller/service + 一个 LLM 接口调用;AI 聊天 + 数据管理)的项目,前端一般用什么方案。

调研日期:2026-08-09。所有 star 数通过 GitHub REST API 逐仓库实测(当日数据),仓库真实存在,无编造。

## 一、同类项目清单(GitHub API 逐仓库验证)

| 仓库 | star | 规模/功能 | 前端方案 | 验证方式 |
|---|---|---|---|---|
| [hncboy/ai-beehive](https://github.com/hncboy/ai-beehive) | 2,212 | 多模块 Spring Boot 3 + MyBatis Plus + WebSocket 聊天室 | **前后端分离**:后端仓库纯 Java(`beehive-web/src/main` 只有 `java`,无 static/templates);前端是独立 Vue 工程(README 链接的 `mjjh1717/chatgpt-shuowen` 现已删库,404) | 目录 API + README 原文 |
| [liyupi/yu-ai-code-mother](https://github.com/liyupi/yu-ai-code-mother) | 1,867 | Spring Boot 后端 + 微服务模块 | **前后端分离**:独立 Vite + Vue3 + TS 工程 `yu-ai-code-mother-frontend/`(package.json 依赖 ant-design-vue/pinia) | 目录 API + package.json |
| [274056675/springboot-openai-chatgpt](https://github.com/274056675/springboot-openai-chatgpt) | 1,009 | Spring Boot 后端 + ChatGPT 聊天与后台管理 | **前后端分离**:仓库内两个独立 Vue 工程 `chatgpt_pc/`(前台)与 `mng_web/`(后台),均有 vue.config.js/package.json;后端 `chatgpt_boot` 纯 API | 目录 API |
| [NiuXiangQian/chatgpt-stream](https://github.com/NiuXiangQian/chatgpt-stream) | 275 | 单模块 Spring Boot,SSE 流式聊天 + 画图,聊天记忆 | **Spring Boot 托管静态页(无构建)**:`src/main/resources/static/` 下 index.html + css + js;**Vue3 走 CDN(unpkg)引入**,不用打包工具 | 目录 API + 读 index.html 原文 |
| [lenyanjgk/len-ai-agent](https://github.com/lenyanjgk/len-ai-agent) | 119 | Spring Boot 3.4 + Spring AI + LangChain4j + **DashScope 通义千问** + Ollama 智能体 | **前后端分离**:独立前端工程 `len-ai-agent-frontend/` | 目录 API + README |
| [suimz/chatgpt-web-java](https://github.com/suimz/chatgpt-web-java) | 90 | Spring Boot 纯 API 后端,适配 Vue 版 chatgpt-web 接口 | **前后端分离 + 产物合入**:源码无前端;Docker `-full` 镜像把 Vue 前端构建产物打进 jar 的静态资源一起发布(README 原文) | 目录 API + README |
| [java-up-up/damai-ai](https://github.com/java-up-up/damai-ai) | 68 | Spring Boot + SpringAI,支持 Ollama/OpenAI/DeepSeek/阿里百炼 | **前后端分离**:仓库内 `vue/` 独立 Vite + Vue3 TS 工程 | 目录 API |
| [cciliayang/springboot-chatgpt](https://github.com/cciliayang/springboot-chatgpt) | 0 | 最小 demo,一个页面调 OpenAI 接口 | **Thymeleaf SSR**:pom 依赖 `spring-boot-starter-thymeleaf`,`src/main/resources/templates/index.html` | pom.xml + 目录 API |

**样本统计(8 个)**:前后端分离(Vue 独立工程)6 个、Spring Boot 托管无构建静态页 1 个、Thymeleaf SSR 1 个。

### 与本项目最接近的两个对照

- **chatgpt-stream(275★)**:与本项目形态几乎一致——单模块 Spring Boot、SSE/HTTP 流式聊天、前端放在 `static/` 零构建。它只引入一个 CDN 版 Vue3(不装 Node、不跑构建),README 自述"非专业前端,样式略丑",并记录了自己从纯 JS 演进到"CDN Vue 渲染更便捷"的过程。
- **suimz/chatgpt-web-java(90★)**:是"独立 Vue 工程开发 → 构建产物合入 Spring Boot 静态目录 → 一个 jar 发布"这一路线的真实样例(README 中 `-full` 镜像说明)。

## 二、中国课程设计/毕设生态的主流说法

结论先说:**主流 = 前后端分离(Spring Boot 纯 API + Vue 独立工程);Thymeleaf 是旧式/轻量选择;vanilla/无构建静态页在课程生态里普遍存在但不占主流**。依据(均为可核实的一手来源,非二手转述):

1. **若依(RuoYi)双版本对照** —— 中国后台管理系统事实标准:
   - [RuoYi 单体版](https://github.com/yangzongzhuan/RuoYi)(8,470★):pom.xml 实测含 `thymeleaf-extras-shiro`,即 Thymeleaf 服务端渲染,定位"没有任何其它重度依赖。直接运行即可用"。
   - [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue)(3,163★)与 [RuoYi-Vue3](https://github.com/yangzongzhuan/RuoYi-Vue3)(6,700★):README 原文"基于SpringBoot+Vue前后端分离的Java快速开发框架","前端采用Vue、Element UI"。
   - 即:同一个框架,老版本用 Thymeleaf,新版本默认前后端分离;后者是主流选择。

2. **newbee-mall(11,620★,国内经典教学商城)的自我演进** —— [README 项目表格](https://github.com/newbee-ltd/newbee-mall):初始版本"Spring Boot、**Thymeleaf**、MyBatis、MySQL"→ 后续新增"**前后端分离**"的 `newbee-mall-api`(纯 API)+ `newbee-mall-vue-app`/`vue3-app`(Vue 独立工程)。同一个作者的同一项目展示了"Thymeleaf → 分离"的迁移路径。

3. **课程项目合集(ZHENFENG13/spring-boot-projects,5,758★)的三代演进** —— README 项目导航原文列出:
   - "Spring Boot + Mybatis + **Thymeleaf** 实现的开源博客系统"(My-Blog)、BBS、仿知乎;
   - "Spring Boot + **layui** 实现的后台管理系统"(My-Blog-layui,329★)——layui 是无需构建工具、直接以静态 JS/CSS 引用的国产 UI 库,即"无构建静态页"形态在课程生态的典型代表;
   - "Spring Boot + Vue **前后端分离**商城项目"(newbee-mall)。
   说明课程教学链路里三种形态都存在,时间线上 Thymeleaf → 静态无构建(layui)→ Vue 分离,最新教学重心是分离。

4. **头部教学/课程旗舰项目全部是前后端分离**:
   - [lenve/vhr 微人事](https://github.com/lenve/vhr)(28,080★):README 原文"前后端分离的人力资源管理系统,项目采用 SpringBoot+Vue 开发",前端独立工程 `vuehr/`。
   - [201206030/novel](https://github.com/201206030/novel)(5,785★):README"基于时下最新 Java 技术栈 Spring Boot 3 + Vue 3 开发的前后端分离学习型小说项目"。
   - [maliangnansheng/bbs-springboot 南生论坛](https://github.com/maliangnansheng/bbs-springboot)(2,232★):README"SpringBoot + Vue,前后端分离",前端在独立仓库 `bbs-vue-ui`。
   - 另一个同类调研也印证了这一点,见 [frontend-backend-split.md](frontend-backend-split.md)(同目录,含 vhr/ruoyi 等 10 个仓库的分离改造细节)。

5. **社区文章(次要来源,未采信)**:本调研环境无法访问通用搜索引擎(Bing/百度/掘金搜索接口均被阻断),未能定位并核实掘金/CSDN 高赞文;以上结论全部基于可复现的 GitHub 仓库原文,可信度高于二手文章,故不再补次要来源。

## 三、"纯 vanilla JS + Spring Boot 托管静态资源"是否过时

**结论:不算"过时"(技术上完全受官方支持),但属于"低复杂度场景"定位,不是大厂/团队项目的主流选择。**

1. **Spring Boot 官方把静态托管当一等公民** —— Spring Boot 3.3 官方参考文档 "Static Content" 节:"By default, Spring Boot serves static content from a directory called `/static` (or `/public` or `/resources` or `/META-INF/resources`) in the classpath"。资源默认映射 `/**`。即"前端放 static、后端一起打包"是官方文档明确支持的标准形态。(https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.static-content)

2. **Vue 官方明确承认无构建用法** —— Vue 官方文档 "Ways of Using Vue" 的 **Standalone Script** 节原文:"Vue can be used as a standalone script file - **no build step required**! If you have a backend framework already rendering most of the HTML, or **your frontend logic isn't complex enough to justify a build step**, this is the easiest way to integrate Vue into your stack."(https://vuejs.org/guide/extras/ways-of-using-vue.html#standalone-script)官方 Introduction 也给出生产建议:"Go with Options API **if you are not using build tools**... e.g. progressive enhancement"(https://vuejs.org/guide/introduction.html)。

3. **真实存在"静态托管 + 聊天"形态的成名项目**:chatgpt-stream(275★,见第一节)整个 UI 就是 static 目录 + CDN 框架;国内课程生态里的 layui 系后台(如 My-Blog-layui,329★)也是"静态目录直接放 UI 库"的无构建形态。说明这不是孤例。

4. **为什么它"被认为过时"**:从招聘/团队视角,前后端分离(Vue/React 独立工程)意味着组件化、类型化、可测试、可并行开发,是工程主流;纯 vanilla 在简历和面试上显得"没有工程化"。但这是**市场偏好**而非技术否定——官方文档(Spring Boot、Vue)都保留并背书了这一形态。对"几个 controller + 一个 LLM 调用 + 数据管理"规模的课程项目,vanilla 静态页在"够用、零依赖、一个 jar 部署"维度上是合理的,不是错误选择;chatgpt-stream 的 README 自嘲"非专业前端,样式略丑"也侧面说明其定位是"能用即可"。

## 四、LLM 聊天项目:SSR/模板渲染还是纯 API?

- **样本结论(8 个):纯 API 是绝对主流**。6 个前后端分离项目全部是"后端纯 API + 前端独立 SPA";chatgpt-stream 是"静态页 + SSE 流式 API";只有 cciliayang/springboot-chatgpt(0★ 最小 demo)用 Thymeleaf SSR。
- **聊天场景不适合 SSR 的原因**(技术事实):流式输出(SSE/WebSocket)、消息局部更新、长会话状态都在前端,服务端模板渲染既无必要也难做流式;因此 LLM 对话项目普遍是"纯 API + 前端框架",Thymeleaf 只出现在"一个页面调接口"的最小演示里。
- **但要区分两个问题**:("纯 API" vs "SSR")与("独立 Vue 工程" vs "静态页")。主流项目两者都选"API + 独立工程";但"API + 静态页"(chatgpt-stream、本项目)同样是有效组合——API 形态一致,只是前端不引入 Node 构建链。因此本项目当前的形态在同类项目中**有先例、非异类**;与主流差距主要在"前端是否独立工程化",而非"后端是否纯 API"。

## 五、给本项目的结论

1. 同类(个人/课程、Spring Boot + LLM 聊天)项目的主流前端方案是 **前后端分离的 Vue 独立工程**;次要方案是 **Spring Boot 托管的无构建静态页(vanilla 或 CDN 框架)**;Thymeleaf 仅见于最小 demo。
2. 本项目现状("vanilla 静态页 + 纯 API 后端")不是过时或错误形态,官方文档与真实仓库(chatgpt-stream)都支持它;若为面试/简历加分,可考虑升级为"分离 + Vue/Vite"(工作量大)或至少"前端独立目录 + Nginx 同源反代"(改动小,见 [frontend-backend-split.md](frontend-backend-split.md) 方案 B)。
3. 若保持现状,建议面试时主动说明选择理由:无构建链、一个 jar 部署、课程规模下收益大于成本——这比回避问题更符合面试预期。

## 来源清单

**官方文档(权威)**
- Spring Boot 3.3 参考 "Static Content":https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.static-content
- Vue 官方 "Ways of Using Vue"(Standalone Script,无构建用法):https://vuejs.org/guide/extras/ways-of-using-vue.html
- Vue 官方 "Introduction"(无构建生产建议):https://vuejs.org/guide/introduction.html

**真实仓库(全部 GitHub API 2026-08-09 实测,star 为当日值)**
- 同类 LLM 项目:hncboy/ai-beehive(2,212)、liyupi/yu-ai-code-mother(1,867)、274056675/springboot-openai-chatgpt(1,009)、NiuXiangQian/chatgpt-stream(275)、lenyanjgk/len-ai-agent(119)、suimz/chatgpt-web-java(90)、java-up-up/damai-ai(68)、cciliayang/springboot-chatgpt(0)
- 生态证据:yangzongzhuan/RuoYi(8,470)、RuoYi-Vue(3,163)、RuoYi-Vue3(6,700)、newbee-ltd/newbee-mall(11,620)、ZHENFENG13/spring-boot-projects(5,758)、ZHENFENG13/My-Blog-layui(329)、lenve/vhr(28,080)、201206030/novel(5,785)、maliangnansheng/bbs-springboot(2,232)
- 相关调研:docs/research/frontend-backend-split.md(同仓库,前后端分离改造细节)

**未采信**:掘金/CSDN 高赞文(本环境搜索引擎不可达,未验证,按用户要求如实注明;所有结论改用可复现的 GitHub 一手来源)。
