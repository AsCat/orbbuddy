# Relay protocol v0.1

Android 持有设备令牌，连接 `GET /device?id=primary`，请求头 `Authorization: Bearer <device-token>`。服务端在同一设备 ID 重连时关闭旧连接；所有业务命令串行执行，单次 MCP 调用超时 20 秒，超时会关闭无响应的设备连接。设备 WebSocket 每 25 秒发送 ping；服务端每 25 秒发送应用级 `heartbeat` 命令并等待响应，保持 70 秒读超时。Android 端在约 75 秒未收到服务端消息时主动重连。

服务端发送：

```json
{"id":"随机请求 ID","method":"get_screen","params":{}}
```

手机返回：

```json
{"id":"随机请求 ID","ok":true,"result":{"revision":1,"width":1080,"height":2400,"tree":{"path":"0","children":[]},"imageBase64":"..."}}
```

失败返回 `{"id":"...","ok":false,"error":"原因"}`。截图失败时 `get_screen` 仍可成功，`result.imageError` 描述失败原因。默认截图为最长边不超过 1280 像素、质量 70 的 JPEG，可设 `maxImageEdge` 为 320–1280；`includeImage=false` 时不执行截图，仅返回界面树。节点坐标和点击坐标始终为原始屏幕像素。节点树最多 300 个节点、深度最多 20 层，节点包含 `viewId`、`checkable`、`checked` 和 `stateDescription` 等可用于动作验证的字段。

方法及参数：

- `get_screen`: `{"includeImage":false}`，或 `{"maxImageEdge":640}`；两参数均可省略。
- `get_lock_state`: `{}`，返回 `interactive`、`keyguardShowing`、`deviceLocked` 和 `deviceSecure`。
- `wake_screen`: `{}`，限时尝试唤亮屏幕，返回同样状态和 `wakeRequested`；不执行系统认证。
- `tap_element`: `{"revision":1,"path":"0.2.1"}`。界面修订号变化时拒绝旧请求。
- `tap`: `{"x":120,"y":300}`。
- `swipe`: `{"x1":500,"y1":1500,"x2":500,"y2":500,"durationMs":400}`。
- `input_text`: `{"text":"hello"}`，替换当前聚焦输入框内容，最多 1000 字符。
- `press_key`: `{"key":"back"}` 或 `home`。
- `get_app_info`: `{"packageName":"com.example.app"}`。
- `open_wireless_debugging_settings`: `{}`。尝试打开系统“无线调试”页面；机型不支持直达时只打开开发者选项，不直接改变开关。
- `heartbeat`: `{}`。仅服务端保活使用，客户端返回 `{"alive":true,"paused":false,"manufacturer":"...","model":"..."}`，不作为 MCP 工具公开。

MCP 客户端另持 MCP 令牌，连接 `POST /mcp`（Streamable HTTP，JSON 响应）。令牌不出现在 URL 中。服务器不缓存截图、节点树或输入文本。

MCP 的 `get_screen` 额外支持 `treeMode="compact"`，仅改变 MCP 返回的文本格式，不改变手机 WebSocket 协议。结果保留 `revision`、`width`、`height`、`treeAvailable` 和平铺 `elements`；每项保留原始 `path` 与原始像素 `bounds=[left,top,right,bottom]`。它略去没有文字、描述或可操作属性的布局节点，并省略空字段。`treeMode="full"` 或省略参数时仍返回原来的 `tree`。推荐先用 `{"deviceId":"primary","includeImage":false,"treeMode":"compact"}` 做文字或控件判断；需要完整层级关系再用 `full`。`treeMode` 不是发给 Android 的参数。

圆伴的常驻 `PARTIAL_WAKE_LOCK` 只保活后台会话，不会唤亮显示屏。`wake_screen` 使用 Android 的限时显示屏 wake lock；Android 已将 `ACQUIRE_CAUSES_WAKEUP` 标为弃用，因此调用方必须检查返回的 `interactive`。`deviceLocked=true` 表示仍需在手机完成系统认证；普通应用不能借此绕过安全锁屏。[PowerManager 官方文档](https://developer.android.com/reference/android/os/PowerManager#ACQUIRE_CAUSES_WAKEUP)、[KeyguardManager 官方文档](https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked())。

`device_status` 的 `lastSeenAt` 表示服务端最后一次收到设备消息的时间，并可返回机型与制造商。同一 relay 可连接多个不同 ID；两台或以上手机在线时，MCP 工具省略 `deviceId` 会返回歧义错误。设备长时间无响应或单次工具调用超时，服务端会断开连接，避免将失联设备继续报告为在线。
`paused` 表示手机端最近一次心跳报告的暂停状态，最长约 25 秒更新一次。手机暂停后立即拒绝除心跳外的所有远程命令，返回 `remote access is paused on the phone`；MCP 连接本身保持在线。
