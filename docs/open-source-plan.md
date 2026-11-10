# 圆伴 OrbBuddy：开源发展计划

更新：2026-11-10。工作量是单人开发日粗估，包含实现、测试和文档，不包含外部审核等待。

## 定位与架构

圆伴先做成**自托管的 Android 开发测试工具**：手机通过移动网络主动连接 Go relay，电脑上的智能体通过 HTTPS/MCP 按需读屏和测试。保持单进程 Go 服务、不持久化截图的轻量方案。

移动网络和常见家庭接入可能位于运营商级 NAT 后，不能假定用户有可入站公网地址；[RFC 6598](https://www.rfc-editor.org/info/rfc6598/)定义了运营商共享地址空间。默认部署文档以已有 VPS/云主机的公网 HTTPS 入口为例，手机与电脑都只需主动发起出站连接。用户不一定要自己持有服务器，但跨网络使用始终需要可达的中转入口；当前项目没有公共中转服务，也没有可供陌生用户共用的多租户隔离。

部署形态按顺序推进：本地 mock 设备用于开发演示；单用户自托管 relay 用于真实异地手机；等逐用户/逐设备凭据、隔离、限额与运维能力成熟后，再评估可选的共享托管 relay。共享 relay 是独立运营服务，不把维护者个人实例直接开放给社区。

iOS 值得纳入项目，但按平台能力发布：先做用户启动的只读屏幕共享；自有 App 的动作通过明确的测试接口暴露；任意 App 的 UI 自动测试走 Mac/Xcode 设备实验室。服务端按设备报告 platform、capabilities、capture_mode，客户端据此决定可调用工具，不把 iOS 宣称为具备 Android 无障碍控制能力。

## 当前仓库基线

| 已有 | 待补齐 |
| --- | --- |
| Apache-2.0、README、快速开始、贡献指南、OpenAPI、协议说明 | 仓库简介、Topics、演示图、版本兼容承诺 |
| Go/Android CI 构建，最近一次 main CI 成功 | 正式 Release、可下载的 Go 二进制、版本标签、校验值、升级/回滚演练 |
| Android 11+ 侧载、WSS 重连、无障碍树与截图、悬浮球、临时 HTTPS 授权 | 多机型长期稳定性数据、Android 自动化测试、密钥轮换与多设备隔离 |
| 单个 Go relay、MCP Streamable HTTP、网页只读查看器 | 通用部署模板、观测指标、协议兼容性矩阵、更多 MCP 客户端验收 |

GitHub 目前没有 Release、公开 Issue 或规则集。CI 产物是签名可能变化的调试 APK，实机证据主要来自一台小米手机。手机设备令牌写入应用私有 SharedPreferences，服务端设备/MCP 令牌各为单枚全局令牌。这些做法能支撑个人 MVP，但不宜直接作为稳定的通用安装包或公共 relay 发布。发布签名已在维护者本机生成，正式版本及覆盖升级尚未验收。

## 推荐版本顺序

### v0.5：可安装的开源预览版，约 8–12 开发日

1. **发布链路**：固定保管 Android 发布签名；用 tag 构建并发布正式 APK、Linux amd64/arm64 Go 二进制、SHA-256 和变更说明。验证上一版覆盖安装、服务器升级/回滚后的手机重连。首次发布标记为 pre-release。
2. **独立部署**：提供不含维护者域名的 env.example、可替换域名的 Nginx/Caddy 示例、systemd 服务和完整命令序列。区分本地演示与公网自托管，把手机、服务器、MCP 客户端的配置写成 15 分钟快速开始。
3. **兼容性首轮**：至少覆盖 Android 11、13、15/16 的设备或模拟器，再找一台非小米真机。验证首次授权、断网重连、锁屏 30 分钟、Wi-Fi/移动网络切换、relay 重启、APK 升级、暂停和撤销。公布通过/失败矩阵。
4. **贡献入口**：补仓库简介、Topics、截图或短演示、Issue/PR 模板、SECURITY.md、CHANGELOG.md 和社区行为准则；设置 main 的必过 CI 规则。CI 加入 Go race 测试、格式检查和 Android 单元测试；长时间设备测试放在定期或发布前。
5. **手机凭据**：用 Android Keystore 管理加密密钥并加密持久化令牌；提供更换服务器与撤销设备令牌的本地流程，验证卸载、备份恢复和升级行为。日志继续避开截图、口令和输入文本。

发布门槛：新贡献者在空白 VPS 和手机上按文档完成 device_status、get_screen、暂停和撤销；APK 可由同一签名覆盖升级；Release 资源与源码 tag 对应。

### v0.6：可靠的开发测试版，约 10–15 开发日

1. **可观测性**：区分 relay 在线、设备在线、截图可用、控制可用；记录连接/重连原因、请求耗时和错误码，提供不含敏感内容的诊断包。公开 /health 仍只报告服务存活。
2. **协议兼容**：给设备 WSS 消息加版本和能力声明；不支持的命令明确返回 unsupported。建立旧 Android 客户端与新服务端的契约测试，避免服务器升级后旧手机静默失效。
3. **Android 回归**：为口令状态机、授权撤销、前台 App 限制、重连退避和截图失败补单元/集成测试。持续记录读屏 p50/p95、断线恢复时间、后台耗电等可比较数据。
4. **逐设备权限**：按设备分配令牌；MCP 端分只读和控制凭据。控制会话由手机明确开启且在设备状态中可见。现有全功能 MCP 令牌保留迁移期，再公布废止时间。

发布门槛：目标机型的锁屏和网络切换测试可重复通过；断线、过期和权限不足有稳定错误码；兼容策略与迁移说明写入 Release。

### v0.7：跨客户端与多设备，约 8–12 开发日

1. 手机扫描一次性二维码配置服务器地址和设备凭据；支持多设备命名、逐台撤销与轮换。
2. 为只支持本地 stdio 的智能体提供轻量适配器；对远程 MCP 客户端做协议版本测试。当前服务已使用 Streamable HTTP，但还需检验与 [MCP 2026-07-28 传输规范](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)及主流客户端的互通；若引入 OAuth，按[授权规范](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)做发现和资源校验。
3. 发布容量边界：同时在线设备数、截图大小、内存/CPU、带宽与超时配置。出现多实例需求时再加入共享状态存储。

### 可选共享 relay：在多租户隔离成熟后评估

为没有服务器的用户提供免部署入口，需要账户与设备归属、逐用户凭据、请求授权边界、资源限额、滥用处置、流量成本核算和故障运维。先以单用户自托管验证真实需求；不能把现有全局令牌架构直接扩展成公共服务。若社区需要免服务器体验，可在 v0.7 之后单独做托管试验，而不是让 Android 手机依赖公网 IP 或公司内网打洞。

### iOS 只读试验：与 v0.6 并行，iOS 27+ 约 5–8 开发日 PoC

| 目标 | 技术路线 | 能力边界 |
| --- | --- | --- |
| iOS 27+ 看屏 | Swift 客户端用 ScreenCaptureKit；用户从系统选择器开启整屏共享，screen-capture 后台模式保持已开启的流；从本机最近一帧经 WSS 返回 | 不能静默替用户开启采集；只有共享期间可取画面；耗电与后台表现需真机验证 |
| 较旧 iOS 看屏 | ReplayKit Broadcast Extension，由用户启动系统广播；扩展把画面传给 relay | 扩展生命周期、网络与权限提示需单独验证；没有 Android 式无提示按需截图 |
| 自有 iOS App 的动作 | 被测 App 内的测试 SDK 或明确的 App Intents 暴露业务语义动作 | 只操作接入 App 与其明确开放的动作 |
| 任意 App 的 UI 测试 | Mac + Xcode/XCUITest 或 Appium WebDriverAgent 的实验室适配器 | 需要 Mac、签名、开发者模式和设备测试链路；不作为手机独立通过移动网络运行的通用方案 |

Apple 的 [iOS ScreenCaptureKit 示例](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-on-ios)要求 iOS 27+、系统选择器和用户屏幕录制授权；[XCUITest](https://developer.apple.com/documentation/xcode/testing)是开发测试框架，[App Intents](https://developer.apple.com/documentation/appintents)公开 App 自己实现的动作。[FaceTime 远程控制](https://support.apple.com/guide/iphone/request-give-remote-control-a-facetime-call-iph5d70f34a3/ios)是通话中的用户授权功能，并非圆伴可调用的跨 App 手势接口；[iPhone Mirroring](https://support.apple.com/en-us/120421)需要附近的 Mac，不符合异地移动网络场景。普通第三方 iOS App 目前没有已确认的公开接口，可像 Android 无障碍服务一样读取其他 App 元素树并注入任意全局手势。

iOS PoC 先选 iOS 27+ 路线；支持旧版系统的 ReplayKit 预计另需 4–7 开发日。验收：手机使用移动网络、电脑异地时，用户启动共享后，relay 能在 10 分钟内多次取得带采集时间的当前画面；用户停止共享后立即返回 capture_not_active；不对 tap、swipe、ui_tree 宣称支持。真机验证系统版本与后台行为后再决定正式版范围。

### v1.0 门槛

Android 发布、升级、回滚和兼容矩阵稳定；外部贡献者完成一次独立部署；协议有版本化与兼容策略；安装包来源和问题反馈路径清晰。iOS 可以作为标明能力的独立预览模块发布，不阻塞 Android v1.0。

## 分发与政策决策

先以 GitHub Release 侧载开发工具为主。当前 targetSdk=34、QUERY_ALL_PACKAGES 和无障碍自动化用途尚不具备直接提交 Google Play 的条件。若考虑 Play 分发，应专门评估：2026 年新应用/更新的[目标 API 要求为 36](https://developer.android.com/google/play/requirements/target-sdk)；[宽泛包可见性](https://support.google.com/googleplay/android-developer/answer/10158779)需满足受限用途；[无障碍自动化政策](https://support.google.com/googleplay/android-developer/answer/10964491)禁止自主发起、规划并执行动作的通用代理；specialUse 前台服务用途会在 Play 审核中检查。[Android 开发者验证](https://developer.android.com/developer-verification)也正逐步扩展，公开 APK 的签名和注册需纳入发布计划。

点外卖和支付等消费者流程另作产品评估，不作为开发测试版的承诺。任何最终下单或付款都要由手机端针对当前订单进行本地确认，支持取消与超时。
