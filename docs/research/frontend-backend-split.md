# 单体 Spring Boot 内嵌 vanilla 前端 → 前后端分离改造调研

## 背景

本项目是 Spring Boot 3.3 + MyBatis + MySQL 的"饮食推荐"课程项目,前端为纯 vanilla JS(零构建),放在 `src/main/resources/static/`(index.html + assets/js/app.js + api.js + assets/css/app.css),前后端一体打成一个 jar 部署;用户希望改造成"完整的前后端分离项目"后部署上线(用于简历/面试讲解)。

调研日期:2026-08-09。所有 star 数取自 GitHub API,为调研当日数据,仅供参考。

## 改造方案汇总表

| 方案 | 架构 | 改动量 | 是否需要 Node 构建 | 部署产物 | 适合场景 |
|---|---|---|---|---|---|
| A. 保留 Spring Boot 托管前端,引入构建链 | 前端构建产物拷回 `static/`,仍是一个 jar | 小 | 是 | 1 个 jar | 想保留一键部署,又想讲"构建链" |
| B. 前后端分离,Nginx 同源反向代理(推荐) | Nginx 托管静态前端 + `/api` 反代到后端 jar | 小(前端挪目录,API 保持相对路径) | 否(vanilla) | 静态目录 + 1 个 jar | 最主流、面试最好讲、无跨域问题 |
| C. 前后端分离,不同源 + CORS | 前端静态站与后端 API 不同域名/端口,CORS 放行 | 中(要加 CORS 配置) | 否 | 静态目录 + 1 个 jar | 前端挂在 OSS/CDN/Pages 之类 |
| D. 完整重构为 SPA 工程 | 前端改 Vue/React + Vite 独立工程,后端纯 API | 大 | 是 | dist + 1 个 jar | 想彻底"升级"、时间充裕 |

关键事实(均有来源,见下):

- Spring Boot 官方支持从 `classpath:/static`(或其下 `public`/`resources`/`META-INF/resources`)直接托管前端,`index.html` 自动作为欢迎页,但**仅对根路径 `/` 生效**;官方文档**没有**针对 SPA 深链接回退(history fallback)的章节。
- 前后端分离部署的官方/权威做法:Nginx `try_files $uri $uri/ /index.html`(Vue Router 官方文档给出)+ 精确 `location /api { proxy_pass ... }`(Nginx 官方文档)。
- 本项目前端 api.js 已经使用相对路径 `API_BASE = "/api/v1/diet"`(同源请求),这是方案 B 能"不改后端、几乎不改前端"的前提(见源码 `src/main/resources/static/assets/js/api.js:4`)。
- Spring Boot 3.3/3.4/4.1 均**没有** MVC CORS 的 yaml 属性(`spring.web.cors.*` 不存在,只有 actuator 的 `management.endpoints.web.cors.*`),CORS 只能用代码配置(`WebMvcConfigurer.addCorsMappings` 或 `@CrossOrigin`)——已逐一核对三个版本的官方属性附录。
- 本项目请求带自定义头 `X-User-Id`,一旦跨源就会触发 CORS 预检(preflight OPTIONS),这是方案 C 的坑;方案 B 同源代理可完全规避。

---

## 一、Spring Boot 官方对"静态资源 / 前端放哪"的权威说法

来源(Spring Boot 3.3.13,与本项目版本一致):

1. **静态内容章节(权威)** — https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.static-content
   - "By default, Spring Boot serves static content from a directory called `/static` (or `/public` or `/resources` or `/META-INF/resources`) in the classpath"。资源默认映射在 `/**`,可用 `spring.mvc.static-path-pattern` 调整;目录可用 `spring.web.resources.static-locations` 自定义。
   - **"Do not use the src/main/webapp directory if your application is packaged as a jar."**(jar 打包下 webapp 目录无效)。
   - 支持资源内容 hash 的 cache-busting(`spring.web.resources.chain.strategy.content.*`)。
   - 即:官方确认 jar 内嵌静态前端是合法且受支持的形态。

2. **Welcome page 章节** — https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.welcome-page
   - "It first looks for an `index.html` file in the configured static content locations... If either is found, it is automatically used as the welcome page"。但**只在根路径 `/` 生效**,且 "This only acts as a fallback for actual index routes defined by the application"(优先匹配 @Controller 路由)。
   - 结论:**官方没有**为 SPA 深链接(如 `/chat/history`)提供回退到 index.html 的内置机制,深链接刷新会 404,需要自己加 catch-all 转发或交由 Nginx 处理(社区普遍做法,见下)。

3. **SPA 回退的社区标准做法(非官方文档,但被 Vue Router 官方文档采用)** — https://router.vuejs.org/guide/essentials/history-mode.html
   - Nginx:`location / { try_files $uri $uri/ /index.html; }`
   - 官方文档还给出 Apache 的 mod_rewrite、IIS UrlRewrite、Caddy `try_files {path} /`、Netlify `/* /index.html 200`、Vercel rewrites 等各平台的 SPA fallback 写法。
   - 若在 Spring Boot 内部做 fallback,社区常见做法是加一个 catch-all `@Controller` 转发到 `forward:/index.html`(仅当路径不以 `.` 结尾且无匹配资源时)。此为社区实践,无官方文档背书。

4. **CORS 官方文档**:
   - Spring Boot 参考(含 Java 示例):https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.cors
   - Spring Framework 完整章节(含 credentialed requests 与通配符的约束):https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html
   - 关键引用:全局 CORS 通过注册 `WebMvcConfigurer` bean 覆写 `addCorsMappings(CorsRegistry)`;局部可用 `@CrossOrigin`。"Using CORS with credentialed requests requires enabling `allowedCredentials`"、"Wildcards are not authorized in `allowOrigins`"。
   - 已核实:Spring Boot 3.3 / 3.4 / 4.1 属性附录中均无 `spring.web.cors.*`,只有 `management.endpoints.web.cors.*`(actuator)。→ CORS 只能写 Java 代码配置。

---

## 二、前后端分离部署的成熟架构与关键配置(权威来源)

### 架构:前端 Nginx 托管 + 后端 jar(Tomcat)

请求流:浏览器 → Nginx(静态 HTML/JS/CSS;`/api/*` 反向代理)→ Spring Boot jar。

### 关键配置 1:history 路由回退

Nginx 官方 `try_files` 指令文档:https://nginx.org/en/docs/http/ngx_http_core_module.html#try_files
- Vue Router 官方文档直接给出本节配置:https://router.vuejs.org/guide/essentials/history-mode.html

```
server {
  listen 80;
  root /usr/share/nginx/html;   # 前端构建产物
  index index.html;
  location / {
    try_files $uri $uri/ /index.html;   # 找不到文件就回退到 index.html(SPA fallback)
  }
}
```

### 关键配置 2:代理 /api

Nginx 官方反向代理模块文档:https://nginx.org/en/docs/http/ngx_http_proxy_module.html
- 需用 `location /api/` 精确前缀匹配,与 `location /` 的 try_files 区分开(前缀匹配优先级高于普通 `location /`):

```
location /api/ {
    proxy_pass http://127.0.0.1:8080;   # 后端 jar
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

- 关键点:前端与 API 同域名同源 → **浏览器层面完全不需要 CORS**,自定义头 `X-User-Id` 也不触发预检,后端零改动。这正好匹配本项目现状(前端已是相对路径 `/api/v1/diet`)。

### 关键配置 3(备选):不同源部署时的 CORS

- 浏览器为什么拦截跨源请求(权威解释):https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS 和同源策略 https://developer.mozilla.org/en-US/docs/Web/Security/Same-origin_policy
- 后端放行:Spring 的 `WebMvcConfigurer.addCorsMappings`(见第一节来源 4)。注意本项目带 `X-User-Id` 自定义头 → 浏览器会发 preflight OPTIONS 请求,`allowedHeaders` 需包含 `X-User-Id`,`allowedMethods` 需包含 OPTIONS/POST/GET。
- 若涉及 Cookie/会话,`allowedOrigins` 不能是 `*` 且需 `allowedCredentials(true)`(Spring Framework 文档明确)。

### 开发期前端代理(vanilla 或 Vite)

- Vite 官方 `server.proxy`:https://vitejs.dev/config/server-options.html#server-proxy —— dev server 里把 `/api` 转发到后端,前端代码保持相对路径,与生产 Nginx 行为一致;这是官方文档级做法(如 `proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }`)。
- 开发期如果用 Vite,`server.cors` 也有默认只允许 localhost 的配置(https://vitejs.dev/config/server-options.html#server-cors),不必动后端。

---

## 三、GitHub 真实案例(均为 API 实测,star 为调研当日数据)

### 1. lenve/vhr — 微人事(最贴近的改造教科书)⭐ 28,080
https://github.com/lenve/vhr
- 描述原文:"微人事是一个**前后端分离**的人力资源管理系统,项目采用 SpringBoot+Vue 开发"。
- 目录结构:仓库根目录 = Spring Boot 后端(多模块),前端在子目录 `vuehr/`(Vue2 + vue-cli4)。
- 做法(README "快速部署"节,已读原文):
  - 开发:进入 `vuehr/`,`npm run serve` 起 8080,并配置了**端口转发**(即 dev proxy)把请求转发到 Spring Boot。
  - 上线:`npm run build` 生成 `dist/`,**把 dist 中的 `static` 和 `index.html` 拷贝到 Spring Boot 项目的 `resources/static/` 目录下**,然后直接访问——即"前后端分离开发、合并产物部署"的经典路径。
  - README 还附一篇部署参考:《使用 Nginx 部署前后端分离项目,解决跨域问题》(微信公众号,非权威源,仅记录)。
- 意义:该仓库同时示范了方案 A(产物拷回 static)和方案 B(分离开发 + Nginx 部署)两种形态。作者还用 SpringBoot3+Vue3 重写了一版:https://github.com/lenve/vhr2.0

### 2. macrozheng/mall — mall 商城 ⭐ 84,545(前端仓库 12,601)
- 后端:https://github.com/macrozheng/mall (Spring Boot + MyBatis,README 称采用 Docker 容器化部署)
- 前端:https://github.com/macrozheng/mall-admin-web (Vue 3 + Element Plus,独立仓库)
- 做法:前后端是**两个独立仓库**,前端独立构建部署,后端提供纯 API;大厂风格的严格前后端分离。

### 3. yangzongzhuan/RuoYi-Vue(若依官方)⭐ 3,163 / RuoYi-Vue3 ⭐ 6,700
- https://github.com/yangzongzhuan/RuoYi-Vue (SpringBoot + Spring Security + JWT + Vue2 & Element)
- https://github.com/yangzongzhuan/RuoYi-Vue3 (SpringBoot + Vue3 & **Vite** + Element Plus 前后端分离)
- 做法:经典中文"前后端分离权限管理系统"模板,前端独立 Vue 工程,后端纯 API + JWT,多数部署教程走 Nginx 静态托管 + 反代。
- 另见 38,621 star 的 Pro 版 https://github.com/YunaiV/ruoyi-vue-pro

### 4. spring-attic/tut-react-and-spring-data-rest — Spring 官方 React 教程 ⭐ 869(已归档)
- https://github.com/spring-attic/tut-react-and-spring-data-rest
- 这是 spring.io 官方教程《React.js and Spring Data REST》的配套代码(原页面 https://spring.io/guides/tutorials/react-and-spring-data-rest/ 现已 404,仓库已归档到 spring-attic,仍在 GitHub 可查)。
- 意义:它是"Spring 官方示范前端(React)与 Spring Boot 后端如何协作"的最接近官方的一手资料;文中演示了把 npm/webpack 构建产物并入 Spring Boot 静态资源的方式。属于"方案 A"官方血缘的参考。

### 5. 其他小型贴近案例
- weiwosuoai/WeBlog ⭐ 703:https://github.com/weiwosuoai/WeBlog —— "Spring Boot + Vue 3.2 + Vite 4.3 前后端分离博客",单仓库三个模块:`weblog-springboot`(后端)、`weblog-vue3`(前端)、`sql`(数据库脚本),结构与本项目最相似(单体仓库、前后端目录分离),可作目录结构范本。
- Zoctan/spring-boot-vue-admin ⭐ 338:https://github.com/Zoctan/spring-boot-vue-admin —— "Front-end Vue + back-end Spring Boot **completely separated**",小型教学模板。
- itbaima-study/SpringBoot-Vue-Template-Jwt ⭐ 373:https://github.com/itbaima-study/SpringBoot-Vue-Template-Jwt —— "SpringBoot 3 + Vue3 前后端分离项目模版"。

### 关于"vanilla JS 单体 → 分离"的完全贴合案例

未找到与"vanilla JS 零构建前端从 Spring Boot static 抽出"完全一致的知名改造案例——这类改造太小、太常见,一般以博客/问答形式存在而非独立仓库。最接近的真实案例是上面 vhr 的"部署路径"与 WeBlog 的"仓库结构"。如实说明,不编造。

---

## 四、现有项目"不改后端代码只挪前端"的可行路径与常见坑

### 可行路径(方案 B 细化)

前提已满足:本项目前端 api.js 用相对路径 `API_BASE = "/api/v1/diet"`(src/main/resources/static/assets/js/api.js:4),后端路由都在 `/api/v1/diet/**` 下(controller/chat/DietChatController.java:18、controller/trace/AgentTraceController.java:21)。

1. 把 `src/main/resources/static/` 整体移到独立目录(如仓库内 `frontend/`),`mvn package` 后 jar 里不再含前端。
2. 部署:Nginx `root` 指向前端目录,`location / { try_files $uri $uri/ /index.html; }`,`location /api/ { proxy_pass http://127.0.0.1:8080; }`(配置来源:nginx.org try_files/proxy_pass 官方文档,见第二节)。
3. 后端除删掉静态资源外零改动;前端零改动(仍是相对路径、同源请求、不触发 CORS)。

### 常见坑

1. **深链接 404**:浏览器直接打开非 `/` 的 URL(如有 history 路由的页面)会 404。方案 B 用 `try_files $uri $uri/ /index.html` 解决;若把页面放在 Spring Boot 里,注意官方 welcome page 只对 `/` 生效(见第一节 2)。
2. **`location /` 与 `location /api/` 冲突**:如果写成 `location / { try_files ... }` 而无 `/api/` 精确匹配,API 请求会被 try_files 误吞或 404。Nginx 前缀 location 匹配规则见 https://nginx.org/en/docs/http/ngx_http_core_module.html#location —— 这正是文档示例 `try_files $uri @drupal` 中用命名 location 区分动态请求的原因。
3. **回退吞掉 404**:SPA fallback 之后所有未知路径都返回 index.html,Vue Router 官方文档明确提醒要加 catch-all 路由显示 404 页(https://router.vuejs.org/guide/essentials/history-mode.html 的 Caveat 节)。
4. **CORS 与自定义头**:若走方案 C(不同源),本项目 `X-User-Id` 自定义头会触发 preflight,必须显式放行 header(Spring Framework CORS 文档);同源(方案 B)则完全无此问题。
5. **credentials 与 `*`**:不同源且要带 Cookie 时,`Access-Control-Allow-Origin` 不能用 `*`、`allowCredentials` 需显式开启(Spring 文档明确,见第一节 4)。
6. **缓存问题**:分离后静态资源通常走 CDN/Nginx 缓存,前端文件名不带 hash 时改版后容易命中旧缓存;Spring Boot 内置了 content-hash 策略(第一节 1),Vite 构建默认产 hash 文件名(https://vitejs.dev/guide/static-deploy.html)。
7. **CORS 不是后端唯一要改的**:若前端挂在 HTTPS(如 Pages/CDN)而后端 HTTP,浏览器混内容策略也会拦截;统一走 Nginx/网关代理可一并规避。

---

## 五、保留 Spring Boot 托管前端 + 引入构建链(Vite)的官方说法

- **Spring Boot 官方没有**针对 Vite/npm 的集成文档(已查 3.3/3.4/4.1 参考与 how-to,无相关内容;Maven 侧只有 spring-boot-maven-plugin,不含 node)。
- **Vite 官方有 "Backend Integration" 专门章节**:https://vitejs.dev/guide/backend-integration.html
  - 官方方案:让后端渲染 HTML、Vite 只产资源 —— `build.manifest: true` 生成 `.vite/manifest.json`,后端按 manifest 引哈希文件名;开发期在 HTML 注入 `http://localhost:5173/@vite/client` + 入口脚本,并把静态资源请求代理到 Vite(或设 `server.origin`)。
  - 即"后端 HTML + Vite 资源"模式是 Vite 官方一等公民场景,只是 Spring 侧没有官方封装,需自己写模板渲染逻辑。
- **Maven 集成 npm 的事实标准**:eirslett/frontend-maven-plugin ⭐ 4,382 https://github.com/eirslett/frontend-maven-plugin —— Maven 构建时自动安装 Node、跑 `npm build`,并把产物拷入 `target/classes/static`,最终仍是一个 jar。这是"方案 A"最常用的工具,非 Spring 官方,但被广泛使用。
- 中文生态里的常见实现(可观察到的做法,非官方):RuoYi-Vue3 等模板把前端独立成 Vue+Vite 工程、Nginx 部署;若坚持打进 jar,社区博客普遍采用 frontend-maven-plugin 或 maven-resources-plugin 拷贝 dist(vhr README 就是人工拷贝的示范,见第三节 1)。
- **结论**:想保留"一个 jar 一键部署"又想上构建链 → frontend-maven-plugin 或构建后拷贝 dist 进 `static/`;想彻底分离 → Vite 独立工程 + Nginx(方案 B/D),这是主流。

---

## 六、给本项目的最优路径建议

1. **首选方案 B(Nginx 同源反向代理)**:改动最小、面试最好讲、没有跨域隐患。理由:
   - 前端已是相对路径 `/api/v1/diet`,可零修改移出;后端唯一改动是删掉 static 目录(甚至可保留作为兜底)。
   - 架构一句话可讲清:"Nginx 托管静态前端,`/api` 反代到 Spring Boot jar,同源所以没有 CORS"。
   - 权威出处齐全(Vue Router 官方 Nginx 配置 + Nginx 官方 try_files/proxy 文档)。
2. **若时间允许想体现"工程化"**,可在方案 B 基础上把 vanilla 前端升级为 Vue3 + Vite 独立工程(RuoYi-Vue3 / WeBlog 的目录结构可参考),但注意这是工作量最大的一条路,且会让 "X-User-Id 模拟会话" 这类课程设计点被掩盖。
3. **不建议方案 C(跨域 CORS)** 作为首选:徒增 preflight、自定义头放行等坑,对课程项目没有额外收益;若前端最终要放 OSS/CDN 另当别论。
4. 部署演示建议用 Docker Compose(nginx + jar 两个服务)或单机 nginx + `java -jar`,与 macrozheng/mall 的 Docker 部署风格一致,面试时有加分点。
5. 面试讲法提示:先讲"一体 jar"怎么演进到"分离"(增量过程),再讲 Nginx 回退与代理两个关键配置的**原因**(为什么 `try_files` 回退 index.html、为什么 API 要代理而非跨域),这比直接甩架构图更有说服力。

## 来源清单(按可信度)

- 官方/权威:
  - Spring Boot 3.3 Static Content / Welcome Page:https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.static-content
  - Spring Boot 3.3 CORS 节:https://docs.spring.io/spring-boot/3.3/reference/web/servlet.html#web.servlet.spring-mvc.cors
  - Spring Framework CORS 完整文档:https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html
  - Vite Backend Integration:https://vitejs.dev/guide/backend-integration.html
  - Vite server.proxy / server.cors:https://vitejs.dev/config/server-options.html#server-proxy
  - Vue Router History Mode(含 Nginx/Apache/IIS/Caddy fallback 配置):https://router.vuejs.org/guide/essentials/history-mode.html
  - Nginx try_files:https://nginx.org/en/docs/http/ngx_http_core_module.html#try_files
  - Nginx proxy_pass 模块:https://nginx.org/en/docs/http/ngx_http_proxy_module.html
  - MDN CORS / 同源策略:https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS 、https://developer.mozilla.org/en-US/docs/Web/Security/Same-origin_policy
- 真实仓库(GitHub API 实测):
  - lenve/vhr(28,080):https://github.com/lenve/vhr
  - macrozheng/mall(84,545):https://github.com/macrozheng/mall
  - macrozheng/mall-admin-web(12,601):https://github.com/macrozheng/mall-admin-web
  - yangzongzhuan/RuoYi-Vue(3,163)/ RuoYi-Vue3(6,700):https://github.com/yangzongzhuan/RuoYi-Vue
  - YunaiV/ruoyi-vue-pro(38,621):https://github.com/YunaiV/ruoyi-vue-pro
  - spring-attic/tut-react-and-spring-data-rest(869,已归档):https://github.com/spring-attic/tut-react-and-spring-data-rest
  - weiwosuoai/WeBlog(703):https://github.com/weiwosuoai/WeBlog
  - eirslett/frontend-maven-plugin(4,382):https://github.com/eirslett/frontend-maven-plugin
  - Zoctan/spring-boot-vue-admin(338):https://github.com/Zoctan/spring-boot-vue-admin
  - itbaima-study/SpringBoot-Vue-Template-Jwt(373):https://github.com/itbaima-study/SpringBoot-Vue-Template-Jwt
