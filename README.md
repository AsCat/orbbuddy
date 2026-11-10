# 圆伴 OrbBuddy

让 Android 手机在移动网络下主动连回自己的服务器，通过 MCP 向电脑上的智能体提供按需读屏和界面操作。MVP 以开发测试为目标，无需运行时无线 ADB，也无需手机和电脑处在同一局域网。

## 模块

- `android/`：Android 11+ 客户端。用户手动启用无障碍服务，填写 `wss://` 地址和设备令牌，开启或停止测试会话；可选卡通悬浮球提供手机端暂停入口。
- `server/`：单个 Go 进程，接收手机 WebSocket 长连接，并提供 Streamable HTTP MCP 接口；不持久化屏幕内容。
- `deploy/`：systemd 与 Nginx 示例配置。线上入口为 `orb.yuanxuyinli.com`，Go 只监听本机端口。

## 运行原理

```text
Android 无障碍服务 ── WSS/移动网络 ──> 私有服务器 Go relay
                                                │
电脑 Codex / WorkBuddy ── HTTPS MCP ────────────┘
```

手机断线后自动退避重连。每次 `get_screen` 读取当前无障碍节点树并尝试截取一张压缩 JPEG；`tap_element` 使用该次读屏返回的 `revision` 与节点 `path`，界面变化后必须重新读屏。截图被 Android 拒绝或安全窗口遮挡时，仍可返回可见的节点树和 `imageError`。

## 本地开发

```bash
cd server
go test ./...
ORBBUDDY_DEVICE_TOKEN=dev-device ORBBUDDY_MCP_TOKEN=dev-mcp go run ./cmd/orbbuddy-server
```

另一个终端启动模拟设备：

```bash
cd server
ORBBUDDY_DEVICE_URL='ws://127.0.0.1:8080/device?id=primary' \
ORBBUDDY_DEVICE_TOKEN=dev-device go run ./cmd/mock-device
```

Android：用 Android Studio 打开 `android/`，或设置 `ANDROID_HOME` 后运行 `./gradlew :app:assembleDebug`。调试包允许明文 `ws://` 连接本机服务；正式包只接受 `wss://`。手机无需 USB 或无线 ADB 才能正常连接服务器。首次使用需手动安装 App、启用无障碍服务并保存连接配置。会话开启时显示前台通知、持有部分唤醒锁，网络切换时主动重连；**持续会话会增加耗电**，用 App 内“停止测试会话”可释放唤醒锁。用户保持会话开启时，App 会尝试在手机重启或更新后恢复前台会话；厂商后台限制仍可能阻止恢复。

可在 App 内打开卡通悬浮球：轻微呼吸、眨眼、显示连接状态，支持拖动贴边。点击后可暂停/恢复远程访问或隐藏悬浮球；暂停会拒绝除心跳外的所有设备命令。悬浮球默认关闭，使用无障碍悬浮层；读屏截图和远程手势执行时会暂时隐藏它。详见[快速开始](docs/quickstart.md)。

## MCP 工具

| 工具 | 主要参数 | 结果 |
| --- | --- | --- |
| `device_status` | 无 | 在线设备、连接时间、最后心跳时间及手机端暂停状态 |
| `get_screen` | `deviceId?` | 节点树、尺寸、修订号、JPEG |
| `tap_element` | `revision`, `path`, `deviceId?` | 点击结果 |
| `tap` | `x`, `y`, `deviceId?` | 点击结果 |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `durationMs`, `deviceId?` | 滑动结果 |
| `input_text` | `text`, `deviceId?` | 替换当前聚焦输入框内容 |
| `press_key` | `key=back/home`, `deviceId?` | 系统返回或主页 |
| `get_app_info` | `packageName`, `deviceId?` | 包名、版本、应用名称 |

`deviceId` 默认 `primary`。坐标使用手机原始像素尺寸，即使 JPEG 缩小也不改变坐标系。协议见 [docs/protocol.md](docs/protocol.md)。

## 部署

Go 服务配置 `ORBBUDDY_LISTEN=127.0.0.1:18093`、`ORBBUDDY_DEVICE_TOKEN` 和 `ORBBUDDY_MCP_TOKEN`；两个令牌分别给手机和 MCP 客户端，保存在服务器 `/etc/orbbuddy/server.env`，不要提交到 Git。Nginx 负责 HTTPS/WSS。示例见 `deploy/`，上线时执行 `nginx -t` 后再 reload。

手机地址：`wss://orb.yuanxuyinli.com/device?id=primary`

MCP 地址：`https://orb.yuanxuyinli.com/mcp`

安装与验收见 [docs/quickstart.md](docs/quickstart.md)，客户端配置见 [docs/client-setup.md](docs/client-setup.md)，后续阶段见 [docs/roadmap.md](docs/roadmap.md)。

## 当前边界与下一步

标准 HTTPS API 与使用说明已加入服务首页 https://orb.yuanxuyinli.com/ ，机器可读契约在 https://orb.yuanxuyinli.com/openapi.json 。手机端可生成短时一次性口令，分别授予只读或指定可调试 App 的测试控制；接口、一次性截图链接、错误与回滚见 [HTTPS API 说明](docs/https-api.md)。原 MCP 入口继续独立运行。

这是面向个人开发测试的首版。无障碍服务能读取的节点由目标 App 决定；`FLAG_SECURE` 等受保护窗口不能保证截图。Android 11+ 的包可见性限制会影响应用信息查询；当前安装包申请 `QUERY_ALL_PACKAGES`，适合开发者侧载，若发布到 Google Play 需按政策重新设计该功能。

目前**没有支付与下单确认流程**，不要让智能体执行付款或最终提交订单。悬浮球当前只提供会话暂停与恢复，不代表订单授权。未来增加下单能力时，以手机端本地确认为准：智能体仅可准备草稿，最终下单/支付由用户在手机上确认；确认有短时效且只适用于当前订单摘要；取消或超时立即拒绝；完成后提供可行的撤销/售后入口。敏感输入只由用户在手机本地填写，不返回到 MCP。

若 `device_status` 返回空列表，先在手机打开圆伴，确认测试会话已开启、无障碍服务已启用且会话通知仍在。部分厂商会限制后台运行，可使用 App 内“后台连接与省电设置”入口允许圆伴持续运行。服务器可用 `journalctl -u orbbuddy.service` 查看设备上下线时间；MCP 服务在线不代表手机一定在线。Android 的 Doze 可能暂停网络；即使使用前台服务也不能保证所有设备在长时间锁屏后立即可达，详见 [Android 官方说明](https://developer.android.com/training/monitoring-device-state/doze-standby)。

在小米澎湃 OS 实测时，还需检查系统“应用信息”里的“自启动”“通知管理”和“省电策略”：允许自启动与通知，并将省电策略设为“无限制”。更新或重启后若会话没有自动恢复，重新打开圆伴一次。若连接反复上下线，检查手机日志中的 `accessibility service destroyed`、`device socket failed` 和服务器日志；系统重新绑定无障碍服务时，旧连接应停止重试。

## 参考资料

- [Android AccessibilityService：手势、截图与无障碍节点](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 无障碍悬浮层窗口类型](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY)
- [Android 前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android package visibility](https://developer.android.com/training/package-visibility)
- [Model Context Protocol Go SDK](https://github.com/modelcontextprotocol/go-sdk)
- [MCP Streamable HTTP 规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。
