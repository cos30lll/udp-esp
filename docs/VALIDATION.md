# 验证记录与验收范围

日期：2026-11-24。固定版本：1.35.20.001；玩法：12/1201。机器摘要在 [STATUS.json](../validation/STATUS.json)，原源码内容基线在 [SOURCE_BASELINE.json](../validation/SOURCE_BASELINE.json)。公开文档不含用户身份与原始会话载荷。

## 原 ROOT r4

用户对方框、射线、HP 版本反馈“好了完美了”。此反馈对应原设备、原 APK 与原后端，表明该环境的显示效果获认可，不是跨设备测试或最终相机精度证明。

- 原 APK：`udp-esp-overlay-zero-r4-candidate.apk`，29156字节。
- SHA256：`daa383155fc72e67fa6db749564e165cf554d1b444458f5c0b80f4e8a5e3441a`。
- 包名/版本：`local.udp.esp`，versionCode5，`0.5-box-tracer-hp-candidate`。
- 后端：`v2.3-esp-box-hp-r4-candidate`，继承 immediate capture、异步日志和精简快照。
- 冻结 ZIP：`udp-esp-root-r4-20261124.zip`，239222字节、89文件，CRC 检查通过。
- 冻结 ZIP SHA256：`28c17ef66062b2a1cabe0f5fae2e97ae9ab9fdc44fdef87037cc3cc3e5e133a7`。

HP 专项来自已有真实抓包：11条动作31，其中4条当时已有可显示实体并被立即关联；3次未知检查保持缺失。方框是固定站姿估计，HP没有最大值，原 r4 的“好用”不能消除这些实现边界。

## 原 VPN r1

最初冻结的候选 APK `udp-esp-vpn-r1-candidate.apk`，131773字节；SHA256 `23973d5723b583721028056d269dda7a322acbd682b067b15ec00c8a45bbc293`。包名 `local.udp.esp.vpn`、versionCode1，minSdk26/targetSdk35，仅arm64-v8a。文件名沿用当时的候选标记，后续用户运行反馈单独记于下文。

Java、资源、JNI/C 编译、链接、APK 签名及验签通过。对已存在的真实 PCAP 执行解码移植对照，状态为 `passed_java_port_against_root_r4_existing_capture`：

| 统计 | 结果 |
|---|---:|
| PCAP记录 | 8571 |
| 场景消息 | 2036 |
| 视角消息 | 1790 |
| 身份消息 | 5 |
| HP动作 | 11 |
| Java应用位置更新 | 6767 |
| 拒绝的transport checksum记录 | 2 |
| 最大视角差 | 7.105427357601002e-15度 |

最终位置、身份、HP及视角上下文与 ROOT 参考一致。样本有2条 checksum 异常被拒绝，不能写成零错误输入。离线 Java 对照使用同一 PCAP 经 host libzstd 解出的真实压缩块映射；它证明字段/状态移植一致，**该次离线检查未执行 Android JNI、TUN 或 socket 转发**。

### 2026-11-24 用户实机反馈

用户在本次发布过程中直接反馈“vpn实测成功了”。据此，VPN r1 的当前状态为用户本机实测成功，不再仅停留于编译和离线解码对照。该反馈与上面的历史离线检查分别作为证据保留。

用户未另行提供设备与 Android 版本清单、跨设备结果、网络切换、IPv6、停止与 onRevoke、JNI 专项仪器记录或端到端延迟测量，不能将本机成功扩展为这些项目逐项通过。固定版本相机近似、固定站姿方框和最大血量未恢复等实现边界仍保留。

## 公开源码副本

公开整理改动限于仓库布局、相对路径构建、ROOT的 `UDP_ESP_HOME`、英文启停入口和文档/许可。ROOT overlay及VPN应用源码与历史基线保持内容对应，原现用目录未覆盖。公开ROOT启停路径改动仅做语法和静态检查，未启动抓包验收。

原公开目录已分别执行 root 和 vpn 构建，两套 APK 编译、签名验签和 ZIP CRC 均通过，结果在 `validation/PUBLIC_BUILD.json`。该记录中的 ROOT APK 为45753字节，SHA256 `92403a4989fdfb63b82d108b2dd6aa1d1116cd0e3a703936c7e578dc5577bd00`；VPN APK 为160735字节，SHA256 `d426f97523f25879d7f48232b5cad790f7976ac233e664ec2ae6f15412d875d5`。146个历史应用源码基线文件哈希一致；后端空目录隔离复制通过。源码检查结果在 `validation/PUBLIC_SOURCE_CHECK.json`，仅是源码、语法和明确边界检查。

本次在 Windows x64 工作区修复构建工具的 `.exe` 和中文路径兼容后，重新完成 ROOT 与 VPN 两套 APK 的源码构建、签名验签和 ZIP CRC 检查，记录见 [WINDOWS_BUILD.json](../validation/WINDOWS_BUILD.json)。本次重建没有安装或启动 APK，不能代替或扩展上述用户实机反馈；哈希与签名按本次记录单独保存。

新构建使用新的本地开发签名，不能用原APK哈希指代；本次不安装、不启动应用、不新增抓包、不启停VPN、不操作游戏。CI配置仅随仓库提供，GitHub Actions的真实运行结果需推送后查看。

## 后续反馈方式

用户实机遇到卡顿时记录画面提示中的包→读、视角龄、HTTP、投影，VPN另记录解码和丢分析数。提交最小脱敏片段、发生时间、版本及复现步骤；不直接公开包含UID/姓名的完整日志或PCAP。性能调度的10ms/16ms不是端到端延迟承诺。

仓库发布状态以远端分支和提交 SHA 的实际复核结果为准，源码检查与编译记录不构成已上传的证明。发布和后续更新步骤见 [PUBLISHING](PUBLISHING.md)。
