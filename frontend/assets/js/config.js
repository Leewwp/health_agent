/**
 * 前端开发/演示配置（37 号：Trace 摘要只在开发/演示配置中展示）。
 *
 * 生产部署（Nginx + prod profile）时改为 false：聊天消息只显示 traceId
 * 文本，不渲染跳转 admin 的入口；admin 页面本身也由后端
 * diet.security.admin-protected + ADMIN_TOKEN 隔离。
 */
export const devConfig = {
    enableDevTraceLink: true
};
