# assistant 包文件说明

AI 助手模块：助手是**真正的 ServerPlayer 假玩家 bot**（`AiAssistantPlayer`，像多人联机客户端一样经 `PlayerList.placeNewPlayer` 进服），统一抽象为 `AiAssistant` 接口供 agentic loop / 命令 / 界面依赖。

## 文件一览

| 文件 | 类型 | 作用 |
|---|---|---|
| `AiAssistant.java` | 接口 | AI 助手的**统一抽象**。定义身体无关方法：`getConfig()`（当前生效配置）、`getConfigBlock()`（绑定的 AI 徽标方块）、`getOwnerUuid()`、`isFollowing()/setFollowing()`（跟随模式）；`level()/isAlive()/isRemoved()/blockPosition()/getDisplayName()` 写成 `default` 方法以 `(Entity) this` 委托——避免 Loom 重映射到 intermediary 后生产环境抛 `AbstractMethodError`。Agentic loop、对话、历史、命令、界面只依赖本接口。 |
| `AssistantFacade.java` | 类（静态工具） | **助手服务的统一入口/门面**。职责：查找某玩家全部助手 / 最近助手 / 绑定到指定方块的助手（跨维度）、`summon`/`summonNearest` 召唤（**真正的 ServerPlayer bot**，与 Agent 预设无关）、`dismiss/dismissAllFor/dismissFor` 送走、`resolveOwned` 按实体 ID 解析、`findAssistantsBySelector` 按名字/`名字(x,y,z)` 选择器匹配。维护「一个方块至多一个助手」规则。 |
| `player/AiAssistantPlayer.java` | 类 | **助手本体**：`extends ServerPlayer implements AiAssistant`，像多人联机客户端一样进服的真玩家 bot。拥有完整玩家能力：43 槽背包（36 主背包 + 装备槽）、游戏模式、经 `ServerPlayerGameMode` 的真实破坏/放置/合成、掉落物自动拾取（每 5 tick 扫脚边）。绑定主人 UUID 与配置方块（随玩家存档持久化 `OpenCraftOwner/OpenCraftDim/X/Y/Z`）；右键交互规则（无主→绑定主人，主人→打开双面板背包界面，他人→拒绝）；生存模式 + 无敌 + 不摔伤；每 tick 驱动移动控制器与安全状态。 |
| `player/FakeConnection.java` | 类 | **黑洞网络连接**：bot 没有真实客户端，发出的包全部丢弃。原理同 Carpet 的 FakeClientConnection——`new Connection(PacketFlow.SERVERBOUND)` 构造后 channel 为 null，`send()/disconnect()` 天然无害；必须重写 `setupInboundProtocol/setupOutboundProtocol` 为 no-op（否则 `placeNewPlayer` 配置 pipeline 时 NPE），另覆写 `isConnected/isMemoryConnection/getRemoteAddress`。 |
| `player/PlayerAssistantService.java` | 类（静态注册表） | **助手 bot 的注册表与生命周期管理**：按绑定方块键控的活动 bot 表（`ACTIVE`）+ 按 UUID 反查表。核心流程 `summonFor`：校验方块存在且未被占用 → 确定性 UUID（由绑定方块唯一决定，重启后仍是同一 bot）→ 建 GameProfile（进服系统名固定 `SYSTEM_NAME = "IAISwayDy"`）→ 载入旧存档 → 摆安全出生点 → `PlayerList.placeNewPlayer(FakeConnection, ...)` 正式进服 → 生存 + 无敌。另有：`dismiss`（送走并落盘存档）、按主人/实体 ID/距离的各类查找、每 tick `keepSafeState`（无敌+食物满）、每 40 tick 安全网（绑定方块被拆 → 送走并清空该方块记忆）、进服罐头欢迎语。 |
| `player/PlayerMovementController.java` | 类 | **bot 式移动控制器**：ServerPlayer 服务端不应用输入/重力（信任客户端移动包），故每 tick 直接驱动位置——`move(MoverType.PLAYER, delta)` 带碰撞移动 + 自施加重力/跳跃。关键细节：① 着地判定用脚底 2mm 薄切片实时碰撞计算，不依赖陈旧的 `onGround` 标志（否则走出平台边缘会浮空）；② 着地时只清零 `vy<=0`、保留上升速度（无条件清零会吞掉跳跃初速度，导致跳不起来）；③ 每 tick 限幅 15° 平滑转向 yRot/yHeadRot/yBodyRot（走路不侧滑）；④ 前方 1 格实心台阶主动起跳爬升，2 格以上高墙交给 60 tick 卡住传送回退；⑤ 支持 `whenArrived` 到达回调（如走到方块旁再挖掘）与手动指令标记。 |

## 结构关系

```
assistant/
├── AiAssistant.java            ← 统一接口（Agentic loop / 命令只依赖它）
├── AssistantFacade.java        ← 统一入口（召唤/查找/送走路由）
└── player/
    ├── AiAssistantPlayer.java          ← 助手本体（真 ServerPlayer）
    ├── FakeConnection.java             ← 黑洞连接（配合 placeNewPlayer）
    ├── PlayerAssistantService.java     ← 注册表 + 召唤/送走/安全网
    └── PlayerMovementController.java   ← 服务端自驱移动（重力/跳跃/转向）
```
