# UDP ESP  [ECAPI 中转站](https://ecmod.zhake.win)的许愿编程生成

Android 上的 UDP 屏幕叠加工具，显示玩家名、距离、方框、底部射线和 HP。有 ROOT 和免 Root 的本地 VPN 两个版本，适配《超自然行动》`1.35.20.001`、玩法 `12/1201`，包名 `com.pi.czrxdfirst`。

ROOT r4 和 VPN r1 都有用户在原设备测试成功的反馈。两版的相机和方框都按固定参数估算，HP 只显示最近上报值，尚未取得最大血量，暂不显示百分比。更多情况见[测试记录](docs/VALIDATION.md)。

## 怎么用

准备 Python 3.9+、JDK 17、Android SDK 35 和 Build Tools 35.0.0；构建 VPN 版还需要 NDK 26.3.11579264。Linux 和 Windows 的环境配置见[构建与部署](docs/BUILD.md)。

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py all --jobs 2
```

输出为 `dist/udp-esp-root.apk` 和 `dist/udp-esp-vpn.apk`，默认使用本机生成的开发签名。

- **ROOT 版**：需要 Root、tcpdump、ss，以及 Operit Ubuntu 中的 Python 和 libzstd。先部署并启动 `root/backend`，再安装 ROOT APK、授予悬浮权限。后端地址为 `127.0.0.1:8765`，具体目录和命令见[ROOT 部署](docs/BUILD.md#3-root-后端复制布局)。
- **VPN 版**：需要 Android 8+、arm64-v8a。安装后授予悬浮权限，点击“启动 VPN + ESP”并确认系统授权，再进入或重连游戏。它只接管指定游戏，与其他 VPN 或加速器可能冲突。

ROOT 版读取抓包，VPN 版通过 zdtun 转发流量并分析副本。两版都不改写或重放游戏应用消息。坐标、身份、方向和 HP 来自 UDP，无需手填 FOV 或眼高；游戏更新后需要重新核对协议和相机参数。

## 文档

- [从零实现](docs/DEVELOPMENT.md) · [协议与投影](docs/PROTOCOL.md)
- [构建与部署](docs/BUILD.md) · [测试记录和已知问题](docs/VALIDATION.md)
- [维护说明](docs/PUBLISHING.md) · [第三方依赖](THIRD_PARTY.md)

源码在 `root/backend/`、`root/overlay/` 和 `vpn/`，第三方源码及许可证一并保留。

整个项目都通过 [ECAPI 中转站](https://ecmod.zhake.win)的许愿编程完成。

[GPL-3.0-or-later](LICENSE) · [版权声明](NOTICE)
