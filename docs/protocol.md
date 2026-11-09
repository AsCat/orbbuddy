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

失败返回 `{"id":"...","ok":false,"error":"原因"}`。截图失败时 `get_screen` 仍可成功，`result.imageError` 描述失败原因。截图为最长边不超过 1280 像素的 JPEG，节点坐标和点击坐标始终为原始屏幕像素。节点树最多 300 个节点、深度最多 20 层。

方法及参数：

- `get_screen`: `{}`。
- `tap_element`: `{"revision":1,"path":"0.2.1"}`。界面修订号变化时拒绝旧请求。
- `tap`: `{"x":120,"y":300}`。
- `swipe`: `{"x1":500,"y1":1500,"x2":500,"y2":500,"durationMs":400}`。
- `input_text`: `{"text":"hello"}`，替换当前聚焦输入框内容，最多 1000 字符。
- `press_key`: `{"key":"back"}` 或 `home`。
- `get_app_info`: `{"packageName":"com.example.app"}`。
- `heartbeat`: `{}`。仅服务端保活使用，客户端返回 `{"alive":true,"paused":false}`，不作为 MCP 工具公开。

MCP 客户端另持 MCP 令牌，连接 `POST /mcp`（Streamable HTTP，JSON 响应）。令牌不出现在 URL 中。服务器不缓存截图、节点树或输入文本。

`device_status` 的 `lastSeenAt` 表示服务端最后一次收到设备消息的时间。设备长时间无响应或单次工具调用超时，服务端会断开连接，避免将失联设备继续报告为在线。
`paused` 表示手机端最近一次心跳报告的暂停状态，最长约 25 秒更新一次。手机暂停后立即拒绝除心跳外的所有远程命令，返回 `remote access is paused on the phone`；MCP 连接本身保持在线。
