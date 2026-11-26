/* 手机版：首帧同步打标，.phone 规则（含 #cornerQr 隐藏）立即生效 */
document.documentElement.classList.add('phone');
/* 手机版内置服务只绑 127.0.0.1（EXPOSE_LAN=false），而 /api/status 返回的 lan
   是局域网 IP，WebView 根本连不上 → 海报代理与豆瓣片单代理全部失败。
   这里首帧记下「与页面同源」的地址，供 index.html 覆盖 lanUrl。 */
window.__phoneOrigin = location.origin;
