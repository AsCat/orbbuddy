# 参与开发

欢迎提交 Android 机型兼容性、断线重连和 MCP 接口方面的问题与改进。

- Go：`cd server && go test ./...`。
- Android：`cd android && ./gradlew :app:assembleDebug`，需要 Android SDK 34 和 JDK 17。
- 修改协议时同时更新 `docs/protocol.md`，确保旧版手机与服务端的基础连接仍可用。
- 报告实机问题时附 Android 版本、厂商系统、锁屏时长、网络类型切换过程、客户端和服务端日志时间点。提交日志前删去令牌、截图、输入内容及其他个人数据。
- 涉及远程操作的变更应覆盖断线、超时、暂停与服务重建场景。支付与下单能力需要手机端逐单确认，当前项目不包含这些流程。

CI 构建 Go 服务并生成 Android 调试 APK；调试 APK 的 CI 签名不保证跨运行一致。正式发布版本需要维护者配置固定签名并验证升级路径。
