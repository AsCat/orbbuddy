# Android 发布签名

正式 APK 必须使用长期固定的密钥，才能覆盖安装后续版本。仓库和 GitHub Actions 不保存发布私钥或口令。调试 APK 使用不同签名，首次从调试版切换到正式版通常需要先卸载调试版；从第一版正式 APK 开始，后续版本应保持相同包名和签名，并提高 `versionCode`。

项目维护者的签名证书 SHA-256 指纹：

```
82:E3:E6:B3:57:26:32:A1:41:1A:6E:F1:83:B3:17:DD:C9:61:13:DB:32:D1:7D:1C:9F:63:B7:4B:0D:FB:0C:41
```

维护者 keystore 保存在本机工作区外的 `~/Library/Application Support/OrbBuddy/signing/orbbuddy-release.p12`，文件权限 600；口令在 macOS 登录钥匙串，服务名为 `OrbBuddy Android release keystore`。文件与钥匙串需分别做离线备份；丢失任一项都无法继续用原签名更新 APK。不要把它们上传到仓库、Issue、CI 产物或聊天记录。

维护者在 macOS 执行 `./scripts/build-android-release.sh`，脚本从钥匙串读取口令，构建、用 `apksigner` 验证签名并打印 APK SHA-256。其他开发者也可用自己的 PKCS#12 keystore：设置 `ORBBUDDY_RELEASE_KEYSTORE` 和 `ORBBUDDY_RELEASE_STORE_PASSWORD`，别名必须为 `orbbuddy-release`。不同签名的 APK 不能覆盖维护者的正式 APK。

首个正式 Release 发布前还需核对版本号、准备变更说明、按源码 tag 构建、上传 APK 与校验值，并在至少一台手机上完成从上一正式版的覆盖升级和回滚演练。GitHub 的[Release 文档](https://docs.github.com/en/repositories/releasing-projects-on-github)说明了 tag、发布资源与发行说明的关系。

Go 服务的 Linux amd64/arm64 发布候选包可运行 `./scripts/build-server-release.sh` 构建，结果和 `checksums.sha256` 写入 Git 忽略的 `dist/`。发布前应在对应架构的 Linux 环境启动并验证 `/healthz`；仅交叉编译成功不代表已通过运行时验收。当前 APK 版本仍为 `0.4.1`，这些本机构建物尚不是公开 v0.5 Release。
