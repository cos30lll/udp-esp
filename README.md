# UDP ESP

Android 上基于游戏 UDP 状态的屏幕叠加。显示目标姓名、距离、方框、底部射线和最近报告的 HP，提供 ROOT 与本地 VPN 两套实现。适配《超自然行动》版本 `1.35.20.001`，玩法 `12/1201`，包名 `com.pi.czrxdfirst`。

ROOT r4 已在原设备上获用户确认，VPN r1 也于 **2026-11-24 获用户实机测试成功的反馈**。两套实现均已通过源码构建；VPN 另有真实抓包的离线解码对照记录。实测反馈的范围见[验证记录](docs/VALIDATION.md)。两套实现都使用固定版本的近似相机和固定站姿方框；HP 没有最大值，不能显示可靠的百分比。

## 怎么用

本仓库发布源码，先按[构建与部署](docs/BUILD.md)准备环境。推荐 Linux x86_64，需要 Python 3.9+、JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0；VPN 版还需要 NDK 26.3.11579264。

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py all --jobs 2
```

输出为 `dist/udp-esp-root.apk` 和 `dist/udp-esp-vpn.apk`，使用新生成的本地开发签名。

- **ROOT 版**：手机需 Root、tcpdump、ss，以及 Operit Ubuntu 中的 Python 和 libzstd。部署 `root/backend` 并启动后端，再安装 ROOT APK、授予悬浮权限。后端仅监听 `127.0.0.1:8765`。具体目录和命令见[ROOT 部署](docs/BUILD.md#3-root-后端复制布局)。
- **VPN 版**：Android 8+、arm64-v8a。安装后授予悬浮权限，点击“启动 VPN + ESP”并确认系统授权，再进入或重连游戏。它只接管指定游戏，与其他 VPN、加速器可能冲突。

ROOT 版被动读取抓包，VPN 版通过 zdtun 转发网络并分析 UDP 副本，不改写或重放游戏应用消息。坐标、身份、方向和 HP 来自 UDP，镜头基础配置来自固定版本的离线分析，无需手填 FOV 或眼高。游戏更新后需要重新核对协议和相机配置。

## 文档

- [从零实现：从最初分析、协议恢复到投影、延迟排查与 VPN 迁移](docs/DEVELOPMENT.md)
- [构建、签名与部署](docs/BUILD.md) · [协议字段与投影公式](docs/PROTOCOL.md)
- [验证记录与未完成项](docs/VALIDATION.md) · [源码发布检查](docs/PUBLISHING.md)
- [第三方来源与许可证](THIRD_PARTY.md)

源码分别位于 `root/backend/`、`root/overlay/` 和 `vpn/`。第三方构建源码与许可证随仓库保留；不包含原始抓包、身份日志、游戏二进制或签名私钥。

[GPL-3.0-or-later 许可证](LICENSE) · [版权声明](NOTICE)
