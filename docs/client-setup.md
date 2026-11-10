# MCP 客户端接入

把下面的 `your-domain.example` 换成[自己部署](self-host.md)的域名；维护者的 `orb.yuanxuyinli.com` 是个人实例，不提供公共设备接入。客户端需支持 MCP Streamable HTTP，并在每个请求中发送 `Authorization: Bearer <MCP 令牌>`。手机使用的是另一枚设备令牌，不能混用。

## Codex CLI / 桌面端

在启动 Codex 的环境里设置 `ORBBUDDY_MCP_TOKEN`，配置 `~/.codex/config.toml`（若设置了 `CODEX_HOME`，则改为该目录下的 `config.toml`）：

```toml
[mcp_servers.orbbuddy]
url = "https://your-domain.example/mcp"
bearer_token_env_var = "ORBBUDDY_MCP_TOKEN"
default_tools_approval_mode = "writes"
tool_timeout_sec = 30
```

`writes` 模式会对点击、滑动、输入和按键操作提示审批；`device_status`、`get_screen` 和 `get_app_info` 已标记只读。配置后重启 Codex，运行 `codex mcp get orbbuddy` 核对配置，或在界面中用 `/mcp` 查看。桌面端若无法继承 shell 环境变量，可按 [Codex 官方 MCP 文档](https://learn.chatgpt.com/docs/extend/mcp?surface=cli) 使用 `http_headers_helper` 从本机凭据存储读取令牌。

## 其他智能体

若 WorkBuddy 或其他客户端支持远程 Streamable HTTP MCP，可填写相同 URL 和 Bearer 令牌。若客户端只支持 stdio，需要一个本地 stdio 转发适配器；该适配器尚未纳入 MVP。不能仅凭产品名称推断其 MCP 兼容性。

先调用 `device_status`，确认目标设备在线且 `paused` 为 `false`。多台手机在线时，所有读屏和操作工具都要显式传 `deviceId`，例如 `primary` 或 `samsung`；省略时会返回歧义错误。手机端暂停时，所有读屏和操作命令会被拒绝，用户需在手机 App 或悬浮球上恢复。界面树有 `path` 和 `revision`，元素操作优先使用 `tap_element`；修订号过期时重新读屏。只判断文字或控件状态时用 `get_screen` 的 `includeImage=false` 和 `treeMode="compact"`；精简结果返回 `elements` 平铺列表，每项的 `path` 仍可传给 `tap_element`，`bounds` 为原始像素 `[left,top,right,bottom]`。需要视觉判断时才取图片，可设 `maxImageEdge=640` 等较小尺寸。默认仍是完整界面树和最长边 1280 像素、JPEG 质量 70 的图片。

## 无线调试与圆伴的关系

圆伴运行时不依赖无线 ADB：手机用移动网络也可主动连接 relay。无线 ADB 只用于开发时安装、调试 APK。`open_wireless_debugging_settings` 尝试打开系统设置；是否已开启须继续读屏确认。手机本地悬浮球的小米快捷流程和电脑端 `orb-adb` 的小米、三星恢复方式见[快捷操作与多设备](shortcuts.md)。圆伴离线时电脑端命令无法靠它打开设置。

普通第三方 App 不能直接写入无线调试相关的安全设置；Android 将 `WRITE_SECURE_SETTINGS` 标为[不供第三方应用使用](https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS)。[Android 官方无线 ADB 流程](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)要求先在手机上启用无线调试、完成配对，并让工作站与设备处于同一 Wi-Fi 网络。跨网使用还需要另行解决两端的网络可达性；圆伴的 HTTPS/WSS 中转只转发圆伴命令，不转发 ADB 连接。
