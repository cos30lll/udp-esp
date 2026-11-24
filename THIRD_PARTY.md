# 第三方来源与许可证

项目自有代码采用 GPL-3.0-or-later；以下第三方保留原许可，不因仓库根 LICENSE 改变。

| 组件 | 仓库内位置 | 来源与版本 | 本项目使用的许可 |
|---|---|---|---|
| zdtun | `vpn/native/third_party/zdtun/` | https://github.com/emanuele-f/zdtun ，2026-11-24 下载的 master 归档；没有固定 commit 证据 | LGPL-3.0-or-later |
| Zstandard | `vpn/native/third_party/zstd/` | https://github.com/facebook/zstd ，v1.5.7 | 双许可中选择 BSD-3-Clause |
| uthash | `vpn/native/third_party/zdtun/third_party/uthash.h` | 随 zdtun 归档，Troy D. Hanson | BSD 风格，完整文本在头文件 |
| net_headers 等头文件 | zdtun 的 `third_party/` | 随 zdtun 归档 | 各文件内原始声明 |

zdtun 原许可见其 `COPYING`；LGPL 依赖的 GPL 正文在根 `LICENSE`，额外副本在 `licenses/LGPL-3.0.txt`。zstd 的 `LICENSE`、`COPYING` 和 `licenses/ZSTD-BSD-3-Clause.txt` 均保留。Zstd 源码同时保留上游 GPL-2.0 选项的文本，本项目构建选择 BSD 选项。

完整 zstd v1.5.7 下载归档 SHA256 为 `37d7284556b20954e56e1ca85b80226768902e2edabd3b649e9e72c0c9012ee3`。本仓库实际携带编译所需的 common/decompress、相关头文件和许可证，以仓库源码哈希为准；不需要构建时联网下载。zdtun 上游 master 的 commit 尚未确认，不填写猜测的 commit；仓库携带源码和 `validation/SOURCE_BASELINE.json` 中的内容哈希固定本次快照。依赖抓取记录见 `vpn/dependency-sources.json`；zdtun归档SHA256为 `bbde6f3dafa9f3ca50c6843a11c660151c84b1a39d6dbeba60dc88ef5bed83e6`。记录中的archive名称是原获取位置，归档未随源码分发，构建直接使用仓库内的源码快照。

JNI 桥接代码在 `vpn/native/vpn_bridge.c`。构建时将 zdtun 和 zstd 解压部分链接到 `libudpespvpn.so`；本仓库提供相关源文件和链接命令，开发者可修改或替换依赖后运行 `python3 -B tools/build_android.py vpn`，使用自己的签名重新生成安装包。没有签名锁定或远程服务依赖。VPN APK 的 `assets/licenses/` 也保存主要许可证及第三方声明。

ROOT 运行依赖系统 tcpdump、Operit 的 proot/Ubuntu Python 和系统 libzstd。本仓库调用这些外部工具，不把对应二进制随源码分发；发行者若另外打包它们，须同时处理各自许可与对应源码要求。

历史离线 Java 对照使用 org.json 20240303 JAR，仅用于本机 JVM。它不在公开源码、构建输入或 Android APK 中；Android 实现使用系统自带 org.json。SDK、NDK、JDK、Python、clang/lld 由构建环境提供，许可证沿用其供应方。

游戏 DLL/APK/资源和用户抓包不在本仓库；文档里的方法 token、协议号和输入哈希只用于复查开发过程。
