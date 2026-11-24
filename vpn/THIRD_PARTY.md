# 第三方源码与许可

- zdtun，Emanuele Faranda，https://github.com/emanuele-f/zdtun ，LGPL-3.0-or-later；源文件保留版权声明，许可位于 `native/third_party/zdtun/COPYING`。
- zstd v1.5.7，Meta/Facebook，https://github.com/facebook/zstd ，BSD-3-Clause 或 GPL-2.0 双许可；此构建选择 BSD-3-Clause，见 `native/third_party/zstd/LICENSE`，另保留 COPYING。
- zdtun 自带 uthash（Troy D. Hanson，BSD）与 net_headers，原头文件版权与许可保留。

本候选完整保留 native 依赖源码及 JNI 代码，可自行替换/重新链接生成 libudpespvpn.so，再用自己的签名重打 APK。APK 内 assets/licenses 也包含主要许可文件。
不提供 root、特权守护进程或远程 VPN 服务器；不包含游戏 DLL 或资源。
离线核对使用 org.json 20240303 JAR，仅用于本机 JVM，不打入 Android APK（Android 自带 org.json）。
