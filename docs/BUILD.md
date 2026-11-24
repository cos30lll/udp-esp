# 构建与部署布局

所有命令从仓库根执行。开发流程见 [DEVELOPMENT](DEVELOPMENT.md)。构建工具只生成文件；安装、抓包、VPN 与游戏操作由用户执行。

## 1. 构建环境

推荐 Linux x86_64、Python 3.9+、JDK 17。需要 Android SDK：

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;26.3.11579264'
```

先按 Android SDK 提示接受许可证；设置实际安装位置：

```sh
export ANDROID_SDK_ROOT='/your/android-sdk'
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.3.11579264"
```

SDK command-line tools 由 Google 提供，未随仓库分发。ROOT APK 不用 NDK；VPN APK 需要 NDK sysroot、Android ARM64 builtins 和可执行的 clang/lld。

Linux ARM64 主机的 NDK 预编译 clang 常为 x86_64，不能直接执行。脚本会检测；此时安装系统 `clang-18` 和 `lld-18`，仍使用 NDK 的 Android 头文件、crt、libc stub 和 builtins。脚本输出的 Android native 目标为 `aarch64-linux-android26`，不是把 Ubuntu libc 链入 APK。

编译前检查：

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py all --jobs 2
```

也可只编 `root` 或 `vpn`。VPN 当前仅输出 arm64-v8a；两个 APK 均为 minSdk 26 / targetSdk 35。

Windows x64 也已完成两套 APK 的源码构建、签名验签和 ZIP CRC 检查，构建工具已适配 `.exe` 和中文路径。准备 Python 3.9+、JDK 17、SDK Platform 35、Build Tools 35.0.0、NDK 26.3.11579264，以及供源码检查执行 `sh -n` 的 Git for Windows。在 PowerShell 中按实际安装位置设置：

```powershell
$env:ANDROID_SDK_ROOT="$env:LOCALAPPDATA/Android/Sdk"
$env:ANDROID_NDK_HOME="$env:ANDROID_SDK_ROOT/ndk/26.3.11579264"
$env:PYTHONUTF8="1"
$env:Path="C:/Program Files/Git/usr/bin;$env:Path"
python -B tools/check_release.py
python -B tools/build_android.py all --jobs 2
```

本次 Windows 构建记录见 [WINDOWS_BUILD.json](../validation/WINDOWS_BUILD.json)，未安装或启动生成的 APK。

## 2. 输出与签名

```text
dist/udp-esp-root.apk
dist/udp-esp-vpn.apk
dist/root-build.json
dist/vpn-build.json
dist/*.apk.sha256
build/root/build.log
build/vpn/build.log
build/signing/local-debug.p12
```

脚本执行 Java 编译、D8、资源链接、VPN 原生编译、APK 打包签名、apksigner 验签和 ZIP CRC 检查。native 使用 16KiB 最大页对齐参数；这只是构建属性，不代表所有 16KiB 页设备实机通过。

默认生成新的本地开发密钥，开发密码为公开默认值 `android`。该密钥与历史成品签名不同；同包名覆盖安装可能因签名不匹配失败。`build/`、`dist/` 全部排除在源码包和提交之外。

发行者可通过环境变量指定自己的签名：`UDP_ESP_KEYSTORE`、`UDP_ESP_KEY_ALIAS`、`UDP_ESP_STOREPASS`、`UDP_ESP_KEYPASS`。密码通过 apksigner 环境传递，工具不记录密码；不要将真实密码写进仓库、终端共享日志或对话。签名文件保留在发行者私有目录。

每次新构建的哈希、来源文件哈希和验签状态记录在 JSON 中；默认签名和 ZIP 元数据变化会使 APK 与历史哈希不同。历史与新构建不得混称同一个冻结成品。

## 3. ROOT 后端复制布局

ROOT APK 只负责显示。后端仍依赖 Android ROOT、`/system/bin/tcpdump`（须支持 `--immediate-mode`）、`ss`、Operit 的 Ubuntu/proot 运行时、Python3 和 `libzstd.so.1`。默认 Operit 路径由 `root/backend/runner.sh` 指定；其他发行版需要改运行入口，不能宣称任意裸机安装 APK 即可使用。

默认布局：

```text
/storage/emulated/0/Download/超自然行动/
  成品/udp-radar-v1/   后端与启停脚本
  日志/udp-radar-v1/   限时会话、PCAP、诊断与 tmp
```

可以先复制到一个**新的空目录**：

```sh
python3 -B tools/stage_root_backend.py --destination '/storage/emulated/0/Download/超自然行动/临时/root-public-layout/成品/udp-radar-v1'
```

上述命令仅示范隔离复制，不会启动，也不覆盖现用后端。启停脚本实际期望 `$UDP_ESP_HOME/成品/udp-radar-v1/`；非默认根需保持此相对结构。示例根为 `/storage/emulated/0/Download/超自然行动/临时/root-public-layout`。运行自定义根时先进入保留该环境变量的root shell，在该shell中设置 `UDP_ESP_HOME` 并调用脚本；不能假定su自动保留调用者的环境。

实际准备默认运行目录时，用相同工具复制到空的 `成品/udp-radar-v1/`；现用目录更新由用户停机后处理。复制工具同时生成 `start.sh/stop.sh` 与历史中文入口。共享存储通常 noexec，因此 Android 上通过 `/system/bin/sh` 执行：

```sh
/system/bin/sh '/storage/emulated/0/Download/超自然行动/成品/udp-radar-v1/start.sh' 600 8765
/system/bin/sh '/storage/emulated/0/Download/超自然行动/成品/udp-radar-v1/stop.sh'
```

运行时间范围 30–3600 秒，默认 600 秒；会话达到约 64MiB 时停止。ROOT runner 限时捕获全机 UDP，状态解码只接收由游戏 socket 确认的端口。日志和 PCAP 保留在用户设备，不进入源码仓库。

用户安装 ROOT APK 后授予悬浮权限，端口与后端一致（默认 8765），再启动显示；应在游戏建立连接前启动捕获，保留身份消息。`127.0.0.1` 后端不对局域网开放。

## 4. VPN 运行条件

独立包名 `local.udp.esp.vpn`，不依赖 Python、tcpdump 或 root。用户安装后授予悬浮权限，点击“启动 VPN + ESP”并确认系统 VPN 授权，先建立 VPN 再进入/重连游戏。

- allowlist 只包含 `com.pi.czrxdfirst`，渠道包名需改 Java 常量和 Manifest queries 后重编译。
- TUN 使用 `10.111.0.2/32`、`fd66:111::2/128`、双栈默认路由、MTU1500；DNS 优先使用系统配置，缺失回退 `223.5.5.5`。
- Android 同时只能有一个活动 VPN，与其他 VPN/加速器冲突。
- zdtun 转发网络；每个 socket 经 `protect(fd)` 避免回环。分析副本有界，队列满时丢分析包并显示计数。
- 停止按钮和系统撤销权限进入资源释放链；网络切换、IPv6、停止与 onRevoke 等专项场景仍需分别记录验证结果。

2026-11-24，用户反馈“vpn实测成功了”，VPN r1 已获用户本机运行成功确认。此前的编译与离线解码记录、这次用户反馈和本次 Windows 重建分别记录在 [VALIDATION](VALIDATION.md)；尚无跨设备、网络切换、IPv6 或 JNI 专项仪器记录。本次源码发布过程未自动安装、启动或捕获。
