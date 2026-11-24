# 开发约定

本仓库包含 ROOT r4 和本地 VPN r1；公开整理不代表增加了运行验收。先阅读 README、docs/DEVELOPMENT.md、docs/VALIDATION.md 和 THIRD_PARTY.md。

- 保留 ROOT 与 VPN 独立目录、包名和启动生命周期。修改优先限于用户要求的范围。
- 用户说“停止”立即停止全部工具调用和操作，不继续状态确认。
- 工具长期没有回执时不重复提交或启动新批次，如实报告最后确认进度与未知状态。
- 默认只修源码、必要编译和最小相关离线检查。安装/启动 APK、抓包、启停 VPN、附加进程、启动游戏或操作场景需要用户明确要求；“继续开发/打包/发布”不等于动态执行授权。
- 不截图、不读取 XML/UI 层级，也不通过 JSON 层级绕过。源码中的 AndroidManifest.xml 和资源 XML 属于正常构建输入，可读取编辑。
- 验收以用户实机现象为准。编译、字段回读、函数返回或离线解码不能替代联网转发和投影实际效果。
- 不新增全量模拟或重复已通过测试来扩大验证范围；新改动只执行相关编译/语法/明确风险检查。
- 保留基础相机近似、固定站姿方框、HP 未知和未恢复最大血量等边界。不把请求视角写成已确认视角。
- ROOT 后端保持 loopback bind；VPN 保持指定游戏 allowlist、每个转发 socket 的 protect 和有界分析队列。
- 不提交 PCAP、UID/昵称日志、密钥、签名、SDK、游戏二进制、构建缓存或运行期状态。示例采用 schema，不复制用户会话。
- 第三方版权和许可不删除。提交白名单必须包含 licenses/，依赖来源及版本不能伪造固定 commit。
- 构建：`python3 -B tools/build_android.py all --jobs 2`。源码检查：`python3 -B tools/check_release.py`。构建产物留在 build/、dist/，默认不提交。
- 用户指定 OWNER/REPOSITORY 且已要求公开发布后，按 docs/PUBLISHING.md 在已登录环境执行。不擅自选账号、不强推、不覆盖已有远端，不在输出中展示凭据。
- 本仓库在 Android“超自然行动”分组内工作时，所有新增文件、临时产物、日志和中间文件都必须位于 `/storage/emulated/0/Download/超自然行动` 内。在独立开发工作区内使用相对目录。
- 只有验证状态发生真实变化时才更新 validation/；github_uploaded 只有实际推送并确认远端后才可写为 true。
