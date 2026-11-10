# 开发快捷操作与多设备

圆伴支持一台 relay 接入多部自己的 Android 手机。每台手机在主界面配置不同设备 ID；例如现有小米沿用 `primary`，三星用 `samsung`。`device_status` 列出在线 ID 和最近一次心跳报告的机型。两台或以上手机在线时，MCP 的 `get_screen`、点击等工具必须显式传 `deviceId`，以免操作错手机。同一 ID 的新 WebSocket 会替换旧连接。当前设备和 MCP 令牌仍是单用户全局令牌，尚无不同用户之间的隔离。

## 手机本地：悬浮角色快捷动作

在圆伴主界面打开悬浮球。长按角色，或点击角色后选择“开启无线调试”，会在手机本地按界面元素寻找桌面搜索、无线调试结果和系统开关。成功后读回当前 IP:端口才显示成功；匹配失败会停止操作并打开开发者选项供手动完成。该流程由手机端无障碍服务执行，圆伴 relay 离线时仍可从悬浮角色启动。手机须解锁；系统确认弹窗、厂商界面和网络状态可能要求用户手动处理。

成功匹配的元素 `viewId` 和成功/失败次数保存在手机的应用私有数据里；再次执行时优先用已验证的 ID，但仍需匹配当前界面的文字和控件状态。它不保存截图、输入文本或原始点击坐标。当前仅验证了小米澎湃 OS 的本地路径；三星的手机本地路径尚未验收。网络切换后的无线调试端口会变化，不能复用历史地址。

## 电脑端：`orb-adb`

仓库的 `tools/orb_adb_connect.py` 整合了已实测的小米桌面搜索流程和三星开发者选项流程：先检查目标设备在线，再通过 MCP 读取界面树、开启无线调试、读取动态端口，最后在电脑运行 `adb connect` 并核对 `adb devices -l`。脚本请求 `includeImage=false`，不需要截图。它只执行用户主动发起的一次操作；手机需解锁、圆伴在线。已配对的 ADB 主机通常无需每次重新配对。

配置客户端自己的 MCP URL 和令牌。令牌文件只供本机用户读取；也可用同名环境变量。可选的 `default-device` 文件指定 `orb-adb` 无参数时的目标；未配置默认设备且多台在线时，脚本要求 `--device`。

```bash
mkdir -p ~/.config/orbbuddy
printf '%s\n' 'https://your-domain.example/mcp' > ~/.config/orbbuddy/mcp-url
printf '%s\n' 'primary' > ~/.config/orbbuddy/default-device
chmod 600 ~/.config/orbbuddy/mcp-url ~/.config/orbbuddy/default-device ~/.config/orbbuddy/mcp-token
python3 tools/orb_adb_connect.py --list
python3 tools/orb_adb_connect.py --device samsung
python3 tools/orb_adb_connect.py
```

`~/.config/orbbuddy/mcp-token` 需由用户在本机创建并写入 MCP 令牌；不要把它提交到仓库或贴到聊天里。也可设置 `ORBBUDDY_MCP_URL`、`ORBBUDDY_MCP_TOKEN` 和 `ORBBUDDY_DEFAULT_DEVICE`。脚本不负责跨网络转发 ADB：Android 官方无线调试流程要求电脑和手机处在可互通的 Wi-Fi 网络，具体连接仍受网络路由和防火墙限制。[Android ADB 指南](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)

## 读屏成本

`get_screen` 默认把截图缩到最长边 1280 像素并以 JPEG 质量 70 编码；不发送原始分辨率图片。只需文字、控件 ID 或开关状态时，传 `includeImage=false`，手机不做截图，MCP 只返回界面树。需要视觉判断时可传 `maxImageEdge=640` 或 `960`；原始手机坐标不受缩图影响。图像进入大模型后如何计费由所用模型与客户端决定，文件字节数和视觉 token 数不是同一个指标。
