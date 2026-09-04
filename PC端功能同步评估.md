# PC 端功能同步评估

## 结论

本轮以 `clash-party` 当前路由和实际调用链为基准，对 Android 功能进行了逐项核对。可由嵌入式 Mihomo 内核真实支持的功能已经复用或补齐；桌面操作系统专属能力没有以空页面或模拟开关冒充实现。

## 功能映射

| PC 功能 | Android 落点 | 状态 |
| --- | --- | --- |
| Proxies | 代理组、节点选择、延迟测试 | 已有并保留 |
| Profiles | URL/文件/二维码导入、自动更新、编辑 | 已有并保留 |
| Resources | Proxy/Rule Provider 查看、更新、健康检查 | 已有并保留 |
| Override | 覆写设置、外部控制器、DNS 等 | 已有并保留 |
| DNS | DNS 开关、监听、H3、Fake IP、Fallback、Policy | 已有完整覆写能力 |
| Sniffer | HTTP/TLS/QUIC 端口和目标覆写等 | 已有完整 Meta 设置 |
| TUN | Android VPN/TUN、栈模式、应用访问控制 | 已有并保留 |
| Network | 出口 IP、多源回退、默认及自定义延迟目标 | 本轮增强 |
| Connections | 连接快照、代理链、流量、关闭单条/全部 | 本轮新增 |
| Rules | 有序规则链、目标和运行命中次数 | 本轮新增 |
| Traffic | 实时速率、累计流量、15 分钟聚合、31 天留存 | 本轮新增 |
| Logs | 实时日志和历史日志 | 已有并保留 |
| Theme | 跟随系统、深色、浅色及统一 Material 视觉 | 本轮增强 |
| Language | 跟随系统及 8 种声明语言手动选择 | 本轮新增 |
| SubStore | URL 订阅导入、自动更新和配置文件管理 | 核心场景由 Profiles 覆盖 |
| SysProxy | 桌面系统代理开关 | Android 不适用，由 VPN/TUN 承担 |

## 平台边界

- PC 自定义 CSS 主题不能直接用于原生 Android View；Android 使用统一的浅色/深色设计令牌实现相同主题意图。
- PC 的系统代理、托盘、悬浮窗和桌面窗口控制属于桌面操作系统能力，Android 使用 VPN 前台服务、通知和快捷方式替代。
- 不嵌入 PC 的 SubStore Web 页面，避免额外 WebView、远端脚本和凭据边界；订阅生命周期继续由原生 Profiles 管理。
- 连接和规则数据通过 Go/JNI/Binder 直接读取，没有开放本地 Mihomo 控制端口，也没有新增可被其他应用访问的 Secret。

## 验证范围

- Meta Debug Kotlin、资源、Go、JNI、CMake 和 APK 全量构建通过。
- arm64 Debug APK 已覆盖安装并完成启动检查。
- 新增 native tunnel 包通过 `go test ./native/tunnel` 编译测试。
- 正式包需要继续通过 release 构建、签名证书比对和真机覆盖安装验证。
