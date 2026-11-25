# 协议、状态与投影参考

目标为固定版本 `1.35.20.001`，玩法 `12/1201`。这里列出已实现的字段与代码入口，推导过程见[开发文档](DEVELOPMENT.md)。协议变化后需要重新核对。

实机反馈、离线对照和未测试项目见[测试记录](VALIDATION.md)。下文的相机参数仍是固定版本的近似值。

## 1. 传输层

协议在 UDP 载荷内；以下 offset 不包括 IP/UDP 头。ROOT 参考 `root/backend/protocol.py`，VPN 参考 `GameDecoder.java`。

| 项 | 入站 | 出站 |
|---|---|---|
| flag | byte1 | byte1 |
| sequence | offset2，LE16 | offset6，LE16 |
| 应用头起点 | offset10；续片 offset8 | offset14 |
| 已实现消息 | 场景、身份、视角报告 | 方向及视角请求 |

校验：`byte0 == (payload.length + byte1 + byte2 + byte3) & 255`。flag 低四位区分 kind4/5；`0x20` 为续片、`0x40` 为后续片标记、`0x80` 为 zstd。控制/ACK 类别按代码过滤。出站只处理已确认的完整布局，不套用入站分片逻辑。

分片按 stream/kind 保持序号连续，使用 16 位循环序号，限制 15 秒、16 条链、512 片及 8MiB 消息。已实现的保守粘连拆分只有在校验、头、连续序号构成唯一链时接受；搜索预算不足或有歧义则拒绝。zstd 解压校验 frame 大小和实际输出大小。应用头为两个 BE16：module、command，消息体紧随其后。

去重采用 flow + sequence + payload digest。状态新旧比较分 stream、kind、事件与方向，不能让一种消息的序号误杀另一种状态。

## 2. 消息路由

| 方向 | module/command | 用途 | 源码入口 |
|---|---|---|---|
| 入站 | 3/852 | 场景差量实体与动作 | `protocol.py` / `GameDecoder.scene()` |
| 入站 | 3/1 | 登录、自身身份、roster、玩法及视角字典 | `identity.py` + `camera_context.py` |
| 入站 | 3/3 | roster | `identity.py` |
| 入站 | 3/6 | roster 更新与移除 | `identity.py` |
| 入站 | 3/2005 | local UID | `identity.py` |
| 出站 | 3/851 | 报告方向向量 | `camera.py` / `GameDecoder.view()` |
| 出站 | 3/2055 | FPS/TPS 请求 | `camera_context.py` |
| 入站 | 3/2055 | 视角报告 | `camera_context.py` |

## 3. 场景与动作

场景 protobuf：field6 为 repeated entity，field7 为 removed IDs（packed/unpacked）；field4/5 为已识别但本显示不消费的 varint 字段。entity：field1=id1、field2=id2、field3=category、field4=repeated action。

action：field1=kind、field2=scale、field3=repeated int64（packed/unpacked）、field4=opaque block。动作值使用对应解析器的有符号语义，不统一套用 ZigZag。

| kind | 当前消费 | 限制 |
|---|---|---|
| 1 | 位置，恰为三个分量时更新；正 scale 时各分量除 scale | 不以配置 ID 或资源坐标代替 |
| 2 | 角色报告 Rotation | 不是最终相机旋转；缺分量不可复用旧槽 |
| 31 | `values[0]` 转 signed Int32 作为 HP | 不除 scale，无 maxHP 证据 |
| 32 | Squat，参与相机基础近似 | 具体消费者通过虚分派链确认 |
| 253 | UpDirection，参与基底 | 不是相机原点 |

动作及语义细节以 `action_state.py`、`action_semantics.py`、`GameState.java` 为准。HP 动作可独立于位置更新，已有实体须立即更新显示。缺失 HP 保持未知；不能把未知/零/负值直接推广为完整死亡状态。

实体键为 `stream/category/id1/id2`，stream 是本地端口与远端 IP:port 的组合。保留差量最后位置，按显式删除、登录重置、进程 epoch 与场景失活管理生命周期；活动实体上限512。没有本帧位置动作不代表删除。

## 4. 身份

roster 中 repeated player 在 field1；player 的 field1=entity_id、field2=UID、field3=UTF-8 name。登录 3/1 的 field2=local UID、field3=local entity ID、field5=roster。3/6 的 field2 接受 packed/unpacked 移除 ID；3/2005 的 field1=local UID。

UID 保持字符串，避免跨 JavaScript/JSON 精度丢失。昵称不作主键，id2 不冒充 UID，未收到自身身份时不随意选择一个玩家。相关 IL token：身份 parser `0x0601CD83`、登录 parser `0x060110F7`、roster `0x0601B524/0x0601BF43`、路由 `0x0600654A/0x0600651A`。

玩法字段在登录 field16/19；视角字典在 field51，其 entry field1=UID、field2=value。入站 3/2055 报告为 field1=value、field2=UID；请求只有 field1=value。登录字典和通知对象是独立来源，不按字段名字猜为同一个存储。

## 5. 报告方向

出站3/851的 field3 是本实现使用的向量。其他向量保留来源；field4 在当前玩法链来自 CameraLook.forward 或已有方向，不是位置。

向量子消息分量字段1/2/3的 wire 标记为5，但实际内容读 varint，再解 ZigZag 除100；不能使用标准 fixed32 float 解析。

```text
component = ((u >> 1) XOR -(u & 1)) / 100
h = hypot(x,z)
yaw = degrees(atan2(x,z)) mod 360
pitch = degrees(atan2(-y,h))
```

正 pitch 向下。零向量没有新方向；保留旧有效方向及旧时间戳。纯垂直向量的 yaw 未定义，不能填0。证据入口为 parser `0x0601AC75`、调用 `0x06016B32`，结合真实出站样本核对。

视角请求 `0/1` 只标为 `confirmed:false`。即使函数返回或请求已送出，也不能等价为当前视角已切换。缺少报告时 TPS 回退是候选策略。

## 6. 基础相机与屏幕投影

固定配置：TPS/FPS 垂直 FOV55°/66°；基础距离3.5、偏移2.5；FPS 静态根空间挂点约 `(0.0017079336,2.1794792300,0.2160116769)`。静态父链不等于动画后的当前挂点。

普通 TPS 候选：

```text
eye = 2.5 * (1 - 0.3 * reportedSquat)
shoulder = 2.5 / 7
boom = 3.5 - 1
origin = root + actorUp*eye + right*shoulder - forward*boom
```

`Projection.java` 使用 up 基底修正以下 yaw/pitch 坐标系：

```text
forward=(sin(yaw)*cos(pitch), -sin(pitch), cos(yaw)*cos(pitch))
right=(cos(yaw),0,-sin(yaw))
up=(sin(yaw)*sin(pitch),cos(pitch),cos(yaw)*sin(pitch))
focal=height/(2*tan(verticalFov/2))
d=target-origin
depth=dot(d,forward)
x=width/2+dot(d,right)*focal/depth
y=height/2-dot(d,up)*focal/depth
```

角度计算前转弧度。过滤背面、近裁面、非有限值及完全离屏目标。分辨率取 View 实际尺寸；游戏动态 viewport、黑边/inset 未完整恢复。

r4 方框用固定2.20高/0.90宽世界单位轮廓的角点投影，射线起点是屏幕底部中心，距离保持世界单位。HP 显示报告原值或未知。最终动态 FOV、ADS、碰撞、动画、局部插值和真实骨骼边界仍有缺口。

## 7. 输出与时序

ROOT `/api/esp` 为精简快照，loopback HTTP/1.1 keep-alive + TCP_NODELAY；`/api/state` 保留诊断细节。VPN `LiveRuntime.snapshot` 提供进程内快照。具体 JSON schema 以 `server.py:esp_snapshot()` 和 `GameState.snapshot()` 为准。

后台完成解码、快照和投影，UI 只消费最新帧。场景/视角失活上限1.5秒；ROOT 短暂请求异常最多保留300ms有效旧帧，不刷新其消息时间。旧0.35秒位置/方向skew清屏条件已移除，以适配差量更新。10ms/16ms是调度参数，不是端到端延迟指标。
