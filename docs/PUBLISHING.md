# 发布与维护

从仓库根目录执行命令。发布前先阅读 [验证记录](VALIDATION.md) 和 [第三方声明](../THIRD_PARTY.md)，区分源码检查、编译结果与实机验证。

## 发布前检查

```sh
python3 -B tools/check_release.py
python3 -B tools/build_android.py all --jobs 2
```

构建需要 [BUILD](BUILD.md) 中的 SDK、JDK 和 NDK；没有可用环境时如实说明，不能把历史结果当作本机验证。

提交前检查文件列表和差异。保留 `root/backend/`、`root/overlay/`、`vpn/`、`licenses/`、`tools/`、`docs/`、`validation/` 和 GitHub Actions，以及各源文件的版权声明。`build/`、`dist/`、PCAP、身份日志、游戏二进制、SDK、私钥和访问令牌不入库。

开发文档应保留从离线分析、传输解析、状态恢复到投影、延迟修复和 VPN 迁移的证据与限制。验证描述保持以下范围：

- ROOT r4 的显示效果获原设备用户反馈确认，不代表跨设备测试。
- VPN r1 已编译，并通过已有真实 PCAP 的 Java 解码对照；Android JNI、TUN 与联网转发仍待实机验收。
- 两套实现均采用固定版本相机近似、固定站姿方框，未恢复最大血量。

## 首次创建仓库

需要 git、GitHub CLI `gh` 和已登录账号。先用 `gh auth status` 确认发布账号，并检查真实的 `git user.name` / `git user.email`。凭据留在本机凭据管理器，不写入仓库或共享日志。

新仓库工具仅用于没有 `.git` 的源码目录。将 `OWNER/REPOSITORY` 替换为实际目标，默认只显示计划：

```sh
python3 -B tools/publish_github.py --repo OWNER/REPOSITORY --public
```

审阅源文件并确认公开目标后执行：

```sh
python3 -B tools/publish_github.py --repo OWNER/REPOSITORY --public --push
```

工具会检查源码和账号配置，创建 `main`，只暂存源码白名单，再提交、创建公开仓库并推送。它拒绝已存在的仓库或 checkout，不覆盖远端、不强推。网络失败后先核对本地和远端状态，再接续未完成步骤，避免重复创建。

## 更新已有仓库

沿用已有 Git 历史、远端和分支。先检查 `git status`、`git remote -v` 和差异，再选择性暂存修改；用 `git diff --cached --name-only`、`git diff --cached` 和 `git diff --cached --check` 复核后正常提交、推送，或按仓库协作流程创建 PR。不要为套用源码包删除已有文件，也不要重置或强推分支。

推送后核对仓库 URL、分支与提交 SHA，并确认远端包含两套源码、许可证和开发文档。`validation/STATUS.json` 中的 `github_uploaded` 仅在实际推送并确认后更新。

## GitHub Actions 与二进制

`.github/workflows/build.yml` 在 `main` 推送、PR 和手动触发时构建。若使用其他默认分支，需同步调整触发条件。Actions 将新开发签名 APK 和构建报告保存为 artifact；这不等于已创建 GitHub Release，也不代表 VPN 实机转发通过。

源码发布不需要提交 APK。另发 GitHub Release 时单独选择版本号和资产，核对哈希与签名，并标注 VPN 候选的未验证项目。历史 APK 与新构建的开发签名 APK 分开记录，不混用哈希；签名私钥始终保留在发行者本机。
