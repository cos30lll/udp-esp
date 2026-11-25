# 维护说明

所有命令从仓库根目录执行。环境配置见[构建说明](BUILD.md)，历史测试见[测试记录](VALIDATION.md)。

## 更新源码

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py all --jobs 2
```

只改文档时检查链接和源码清单即可；修改代码或构建工具后再运行相关构建。提交前检查文件列表和差异，保留两套源码、第三方许可证、开发文档及必要测试记录。

`build/`、`dist/`、PCAP、身份日志、游戏二进制、SDK、私钥和访问令牌不提交。新增第三方依赖时同步[第三方声明](../THIRD_PARTY.md)。

现有仓库正常提交、推送即可，不需要重新初始化或强推。推送后核对远端提交和 Actions 结果。`validation/FILE_MANIFEST.json` 保存文件大小和 SHA-256；更改文档后也需要更新清单，清单本身不参与计算。`validation/STATUS.json` 只在实际测试或发布状态变化时更新。

## 创建新仓库

`tools/publish_github.py` 用于尚未初始化 Git 的源码目录，需要 Git、GitHub CLI 和已登录账号。

先查看计划，将 `OWNER/REPOSITORY` 换成自己的目标仓库：

```sh
python3 -B tools/publish_github.py --repo OWNER/REPOSITORY --public
```

确认文件和目标无误后，加 `--push` 创建并上传：

```sh
python3 -B tools/publish_github.py --repo OWNER/REPOSITORY --public --push
```

工具检查源码和账号配置，创建 `main`，按白名单暂存文件，再创建公开仓库并推送。它不覆盖已有仓库或 checkout，也不强推。网络失败时先检查本地和远端状态，再继续剩下的步骤。

## CI 和 Release

Actions 在 `main` 推送、PR 和手动触发时构建，把 APK 和构建报告保存为 artifact。更换默认分支后需同步修改工作流。构建成功与手机实测分别记录。

ROOT r4 和 VPN r1 都有原设备上的成功反馈，VPN 另有真实 PCAP 的 Java 解码对照；跨设备、IPv6、网络切换等专项结果尚不齐全。相机使用固定参数，方框使用固定站姿，最大血量仍未恢复。

源码仓库不提交 APK。另发 Release 时，写明版本、对应提交、哈希、签名和实测情况。新构建默认生成本地开发签名，不能沿用历史 APK 的哈希，私钥保留在本机。
