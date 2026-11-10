# 开发者快速开始

圆伴由 Android App、Go relay 和 MCP 客户端组成。手机主动通过 WSS 连接 relay，电脑通过 HTTPS MCP 连接同一个 relay；运行时不需要 ADB 或同一局域网。

## 1. 启动 relay

按 `server/go.mod` 指定的 Go toolchain 在 `server/` 构建：

```bash
cd server
go test ./...
go build -o orbbuddy-server ./cmd/orbbuddy-server
```

为设备和 MCP 客户端分别生成一枚随机令牌，例如 `openssl rand -hex 32`。设置 `ORBBUDDY_DEVICE_TOKEN`、`ORBBUDDY_MCP_TOKEN` 和 `ORBBUDDY_LISTEN=127.0.0.1:18093`，将 Go 进程作为 systemd 服务运行。用 Nginx 等反向代理提供 HTTPS/WSS；从[自托管指南](self-host.md)生成自己的域名与证书配置。设备入口是 `wss://你的域名/device?id=primary`，MCP 入口是 `https://你的域名/mcp`。Go 监听地址保持为本机地址。

## 2. 安装 Android App

在 `android/` 用 Android Studio 构建，或运行：

```bash
cd android
./gradlew :app:assembleDebug
```

安装 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 的 `orbbuddy-debug-apk` 构建产物也可用于首次试装；每次 CI 的调试签名可能不同，不能保证覆盖安装上一轮 CI 包。长期使用请用自己保管的固定签名构建。不要将设备令牌放入 APK 或 Git 仓库。

在手机中打开圆伴，填入 WSS 地址、唯一设备 ID 和设备令牌，点击“保存并连接”，再手动启用圆伴无障碍服务。单机可用 `primary`；两台手机可设为 `xiaomi` 与 `samsung`，或保留既有小米的 `primary` 并将三星设为 `samsung`。同一 ID 的新连接会替换旧连接。Android 13+ 允许会话通知，并按设备需要调整后台运行设置。小米澎湃 OS 的实机验证还需允许自启动与通知、设置省电策略为“无限制”。会话开启后，前台通知会常驻；停止会话会释放部分唤醒锁。

“显示圆伴卡通悬浮球”默认关闭。打开后可拖动贴边；点击悬浮球可暂停或恢复远程访问、隐藏悬浮球。暂停会拒绝读屏、应用信息查询及所有远程操作，心跳仍保持连接，`device_status` 最多约 25 秒后显示 `paused: true`。如果隐藏了悬浮球，可回到 App 重新打开。悬浮球使用无障碍悬浮层，无需单独的“显示在其他应用上层”授权。

## 3. 接入智能体

按 [MCP 客户端接入](client-setup.md)配置 HTTPS URL 和 **MCP 令牌**。先调用 `device_status`，确认目标设备在线且未暂停；多设备时每次工具调用都填 `deviceId`。`get_screen` 默认返回界面树和压缩图片；省 token 的常用组合是 `includeImage=false`、`treeMode="compact"`，返回可点击元素及文字的平铺列表；随后可按当前 `revision` 和元素 `path` 调用 `tap_element`。
若手机息屏，先 `get_lock_state`，需要时再显式调用 `wake_screen`。若返回 `deviceLocked=true`，让用户在手机上完成系统解锁后再继续。

## 验收清单

标准 HTTPS 接口的手机临时授权与调用方式见 [HTTPS API 说明](https-api.md)，或打开自己部署实例的首页。原 MCP 设备令牌与新 HTTPS 临时会话令牌不能混用。

1. 手机保持移动网络，电脑不连接无线 ADB：连续多次调用 `get_screen`，都应返回图片和节点树。
2. 锁屏 30 分钟后再次读屏；记录响应耗时、`lastSeenAt` 和前台通知状态。
3. 在 Wi-Fi 与移动网络之间切换，再次读屏；服务器日志应记录重新连接。
4. 重启 relay，等待手机退避重连；重启手机或更新 App 后，再检查会话是否恢复。
5. 打开悬浮球并暂停：`get_screen` 应返回“remote access is paused on the phone”；恢复后应再次成功。截图中不应包含悬浮球。

遇到空设备列表时，先在手机检查会话是否开启、无障碍服务是否仍启用，以及前台通知和厂商后台设置。Go 服务在线不代表 Android 端在线。更多限制见 [README](../README.md)。
