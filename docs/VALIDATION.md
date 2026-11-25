# 测试记录和已知问题

更新于 2026-11-24。目标版本 `1.35.20.001`，玩法 `12/1201`。汇总见 [STATUS.json](../validation/STATUS.json)，源码基线见 [SOURCE_BASELINE.json](../validation/SOURCE_BASELINE.json)。

## ROOT r4

用户在原设备上确认了方框、射线和 HP 显示正常。对应文件如下：

- APK：`udp-esp-overlay-zero-r4-candidate.apk`，29156 字节。
- APK SHA-256：`daa383155fc72e67fa6db749564e165cf554d1b444458f5c0b80f4e8a5e3441a`。
- 包名 `local.udp.esp`，versionCode 5，版本 `0.5-box-tracer-hp-candidate`。
- 后端 `v2.3-esp-box-hp-r4-candidate`，包含 immediate capture、异步日志和精简快照。
- 归档 `udp-esp-root-r4-20261124.zip`，239222 字节、89 个文件，CRC 检查通过。
- 归档 SHA-256：`28c17ef66062b2a1cabe0f5fae2e97ae9ab9fdc44fdef87037cc3cc3e5e133a7`。

HP 的抓包检查共找到 11 条动作 31，其中 4 条在当时已有可显示实体，已立即关联；另外做了 3 次未知值检查，均保持缺失。方框仍是固定站姿估计，尚未取得最大血量。

## VPN r1

原 APK 为 `udp-esp-vpn-r1-candidate.apk`，131773 字节，SHA-256 为 `23973d5723b583721028056d269dda7a322acbd682b067b15ec00c8a45bbc293`。包名 `local.udp.esp.vpn`，versionCode 1，minSdk 26、targetSdk 35，仅支持 arm64-v8a。文件名保留了早期的 candidate 标记。

Java、资源、JNI/C 编译链接，以及 APK 签名和验签均已通过。Java 解码器与 ROOT r4 使用同一份真实 PCAP 做过对照，记录状态为 `passed_java_port_against_root_r4_existing_capture`：

| 统计 | 结果 |
| --- | ---: |
| PCAP 记录 | 8571 |
| 场景消息 | 2036 |
| 视角消息 | 1790 |
| 身份消息 | 5 |
| HP 动作 | 11 |
| Java 应用位置更新 | 6767 |
| 拒绝的 transport checksum 记录 | 2 |
| 最大视角差 | 7.105427357601002e-15 度 |

最终位置、身份、HP 和视角上下文与 ROOT 参考一致。两条 checksum 异常记录被拒绝。离线检查的压缩块由主机 libzstd 从同一 PCAP 解出，因此该检查没有执行 Android JNI、TUN 或 socket 转发。

2026-11-24，VPN r1 获得原设备上的实测成功反馈。跨设备、网络切换、IPv6、停止与 onRevoke、JNI 专项测试和端到端延迟测量仍缺少记录，设备及 Android 版本清单也未补充。

## 公开源码构建

源码整理调整了目录、相对路径构建、ROOT 的 `UDP_ESP_HOME`、英文启停入口和文档许可，没有覆盖原使用目录。ROOT 悬浮层和 VPN 应用源码与历史基线保持一致；ROOT 启停路径的调整只做过语法和静态检查，尚未重新抓包测试。

[PUBLIC_BUILD.json](../validation/PUBLIC_BUILD.json) 记录了两套 APK 的编译、签名和 ZIP CRC 检查：

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| ROOT APK | 45753 | `92403a4989fdfb63b82d108b2dd6aa1d1116cd0e3a703936c7e578dc5577bd00` |
| VPN APK | 160735 | `d426f97523f25879d7f48232b5cad790f7976ac233e664ec2ae6f15412d875d5` |

146 个历史应用源码文件的哈希一致，后端复制到新空目录的检查通过，源码检查见 [PUBLIC_SOURCE_CHECK.json](../validation/PUBLIC_SOURCE_CHECK.json)。

Windows x64 也已完成两套 APK 的构建、验签和 ZIP CRC 检查，工具已处理 `.exe` 名称及中文路径，记录见 [WINDOWS_BUILD.json](../validation/WINDOWS_BUILD.json)。重建使用新的开发签名，没有安装或启动 APK。不同构建的哈希分别保存在对应报告中。

云端构建结果可在 [GitHub Actions](https://github.com/cos30lll/udp-esp/actions) 查看。构建和离线对照不包含新的实机测试。

## 已知问题与反馈

两版都使用固定版本的近似相机和固定站姿方框，最大血量尚未恢复。原设备上的成功反馈不代表其他设备、版本或所有相机状态都已测试。

遇到卡顿时，记录界面上的“包→读”、视角龄、HTTP 和投影耗时；VPN 版另记录解码耗时及丢分析数。反馈附版本、发生时间、复现步骤和最小脱敏片段，不直接上传含 UID、姓名的完整日志或 PCAP。10ms/16ms 是调度参数，不是端到端延迟。
