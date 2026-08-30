# loop 包文件说明

「循环事件模块」：由 **[条件]**（触发条件）、**[事件]**（执行事件）、**[监测函数]**（监测条件）组成的通用循环框架，语义为 **触发条件 → 执行事件 → 监测条件 →（继续）→ 触发条件 → …**。每个服务端 tick 由 `LoopEngine` 驱动所有活动实例推进一个阶段；循环实例在服务端线程同步执行，条件/事件/监测函数必须快速返回、不得阻塞。

核心为**纯 Java（零 Minecraft import）**，可 JUnit 单测；Minecraft 访问由接线层（`LoopModule` / `presets/`）在 lambda 闭包中捕获（`LoopModule.server()` 取当前服务端、`ctx.anchor()` 取实例锚点）。**内置循环事件集中放在 `presets/` 子包**（同 `agent/presets/BaseAgent` / `plugins/presets/AssistantPlugin` 的管理思路）：预设类继承 `presets/LoopPreset` 基类（SPI）、覆写访问器声明三组成部分与运行参数，框架本身与内置实现分离，方便扩展。

**当前内置 6 个守护型循环**（全部 `persistent=true`，召唤/绑定方块即自动启动，配置界面第 3 页可按方块开关）：`heal_aura` 治疗光环（主人受伤回血）、`feed_aura` 饱食光环（主人饥饿补食）、`breath_aura` 换气光环（主人溺水补氧）、`extinguish_fire` 灭火守护（主人着火灭火）、`pickup_aura` 拾取光环（助手收集身边掉落物）、`mob_repel` 驱怪光环（击退主人身边的敌对生物）。

## 文件一览

| 文件 | 作用 |
|---|---|
| `LoopCondition.java` | **触发条件**（`@FunctionalInterface`）：纯谓词 `boolean check(LoopContext)`，无副作用、幂等；返回 true 进入执行事件阶段，false 本轮跳过按间隔等待下次评估。 |
| `LoopEvent.java` | **执行事件**（`@FunctionalInterface`）：`void execute(LoopContext)`——触发条件成立后执行的动作；执行成功即一次有效迭代（iteration +1）。 |
| `LoopMonitor.java` | **监测函数**（`@FunctionalInterface`）：事件执行后评估监测条件，返回 `LoopVerdict.CONTINUE`（回到触发条件继续下一轮）或 `STOP`（结束本轮）。 |
| `LoopVerdict.java` | 监测函数裁决枚举：`CONTINUE` / `STOP`。 |
| `LoopContext.java` | 执行上下文（record）：`anchor`（实例锚点，如绑定方块的 `GlobalPos`）/ `tick`（当前服务端 tick）/ `iteration`（已执行事件次数）/ `state`（**实例级持久**共享 Map，跨 tick 复用同一个，供轮次间传数据）。 |
| `LoopPhase.java` | 三阶段状态机枚举：`WAITING`（评估触发条件，受 interval 门控）→ `EXECUTING`（执行事件一次）→ `MONITORING`（执行监测函数）→ 回 `WAITING` / 停止。每 tick 推进一个阶段。 |
| `LoopDefinition.java` | 循环事件定义（record + `Builder`）：`id`/`displayName`/`description` + 三组成部分 + 运行参数——`intervalTicks`（≥1，默认 20，两次触发条件评估的最小 tick 间隔）、`maxIterations`（0=不限；事件执行达到后正常停止实例）、`persistent`（默认 false：monitor STOP 移除实例——**一次性任务循环**；true：STOP 只结束本轮、回 WAITING 继续监视——**守护型循环**，如治疗光环）。构造校验非法参数抛 `IllegalArgumentException`。 |
| `LoopEngine.java` | 静态调度器（核心）：`start(def, anchor)`（幂等）/`stop(anchor, defId)`/`stopAll(anchor)`/`isRunning`/`activeCount`/`status()`/`clear()`/`tick(gameTick)`（服务端线程每 tick 调用，推进所有实例一个阶段）。**守卫**（全部 try/catch，异常不外泄）：trigger 抛异常按 false 处理并累计连续错误、连续 ≥ `MAX_CONSECUTIVE_ERRORS=5` 停止实例；event/monitor 抛异常立即停止实例并告警；达 `maxIterations` 正常停止。实例键 `LoopKey=(anchor, defId)`——同一锚点可同时运行多个不同定义的循环。 |
| `LoopRegistry.java` | 循环事件定义的静态注册表：`register`（重复 id 告警忽略）/`def(id)`/`all()`。纯 Java。 |
| `LoopStatus.java` | 活动实例只读快照（record）：`defId`/`anchor`/`phase`/`iteration`/`nextCheckTick`，供 `/opencraft loop status` 与测试断言。 |
| `LoopModule.java` | **Minecraft 接线层**：`init()`（`OpenCraftMod.onInitialize` 调用）注册内置定义（`presets/` 包的 `LoopPreset` 预设）到 `LoopRegistry` + `ServerTickEvents.END_SERVER_TICK → LoopEngine.tick(server.getTickCount())` + `ServerLifecycleEvents.SERVER_STOPPING → LoopEngine.clear()`；持有 `server()` 静态引用供内置循环闭包取实时服务端（定义在 mod 初始化时注册、彼时无服务端实例）。 |
| `presets/LoopPreset.java` | **内置循环事件预设的基类（SPI）**：预设 = 三组成部分（`trigger()`/`event()`/`monitor()` 必填）+ 显示信息 + 运行参数（`intervalTicks`/`maxIterations`/`persistent` 带默认值）；`definition()`（final）组装成框架的 `LoopDefinition`。预设类继承它、覆写访问器声明自身属性（同 `agent/presets/BaseAgent` 思路）。 |
| `presets/Owners.java` | **预设共享的锚点解析工具**（包私有）：`ownerOf(ctx)` 从锚点（绑定方块的 `GlobalPos`）解析**主人玩家**、`assistantOf(ctx)` 解析**助手本体**；无服务端/维度未加载/无绑定助手/主人离线等任何缺失环节返回 null，由调用方判空跳过本轮（引擎对 null 无感）。 |
| `presets/HealAuraLoop.java` | **`heal_aura`（治疗光环）**：触发=绑定方块的主人玩家在线、存活、生命不满；事件=`heal(1.0F)`；监测=仍不满→CONTINUE 否则 STOP；`intervalTicks=40`（每 2 秒）、`persistent=true`（满血后闲置监视，主人再次受伤自动再治疗）。守护光环家族的模板实现。 |
| `presets/FeedAuraLoop.java` | **`feed_aura`（饱食光环）**：主人饥饿值不满时每 2 秒 `FoodData.eat(1, 0.6F)` 恢复饥饿并等同口粮累积饱和度直到吃饱——只加饥饿不加饱和度会被自然回血烧穿、出现回血-掉饥饿拉锯；`intervalTicks=40`、`persistent=true`。饥饿 ≥18 后原版自然回血恢复生效——与 heal_aura 组成"保命"组合。 |
| `presets/BreathAuraLoop.java` | **`breath_aura`（换气光环）**：主人氧气不满（溺水）时每 0.5 秒恢复 60 点氧气（3 个气泡）直到离水；`intervalTicks=10`（溺水是急症，间隔取短）、`persistent=true`。 |
| `presets/ExtinguishLoop.java` | **`extinguish_fire`（灭火守护）**：主人着火时每 0.5 秒 `extinguishFire()` 灭火直到火熄灭；`intervalTicks=10`、`persistent=true`。 |
| `presets/PickupAuraLoop.java` | **`pickup_aura`（拾取光环）**：把绑定助手 5 格内**已过拾取保护期**且**非助手自己丢弃**的掉落物以速度拉向助手（**全 3D 方向**、越远越快——只按水平算正上/正下方的物品拉不到），由助手（真玩家接触拾取）收进背包；助手背包满（`getFreeSlot()==-1`）时不空拉；监测恒 STOP（每轮拉动一次即结束本轮）、`intervalTicks=20`、`persistent=true`。拉向**助手**而非主人：助手是"帮你收拾"的执行者，且不回收助手自己丢弃的物品（不干扰 e2e 物品流）。 |
| `presets/RepelMonstersLoop.java` | **`mob_repel`（驱怪光环）**：主人 6 格内有敌对生物（`Enemy` 接口，含苦力怕/僵尸/骷髅/史莱姆/幻翼等）时每 1 秒 `knockback` 沿"远离主人"方向击退一次（不造成伤害、尊重击退抗性）；监测恒 STOP、`intervalTicks=20`、`persistent=true`。间隔必须短于僵尸走近时间（约 2 秒）与苦力怕引信（1.5 秒）。 |

## 生命周期接线

循环实例**不持久化**（服务端重启后由再次召唤时重建），生命周期与「方块绑定助手」绑定：

- **召唤/绑定成功** → 启动：`PlayerAssistantService.summonFor` 的三个成功出口（幂等已有 / PlayerList 复用 / 新建）都调 `ensureLoopStarted(block)` → 遍历 `LoopRegistry.all()`，按方块配置 `AiBlockConfig.isLoopEnabled(id)` 逐个 `LoopEngine.start(def, block)`；
- **配置保存** → 同步：配置界面保存后 `AiConfigHandler.save` 对已绑定方块调 `PlayerAssistantService.syncLoopsForBlock`——启用的 `start`（幂等）、未启用的 `stop`，即时生效；
- **送走/方块被拆** → 停止：`PlayerAssistantService.dismiss` 解绑成功后 `LoopEngine.stopAll(block)`（`AiLogoBlockEntity.preRemoveSideEffects` 方块被拆与安全网 `onSlowTick` 无绑定方块均经此路径）；
- **服务端停止** → 清空：`LoopModule` 的 `SERVER_STOPPING` 回调。

## 命令与日志

- `/opencraft loop status`：列出已注册定义（id + description）与活动实例（anchor / 阶段 / 迭代数），只读、无需权限。
- DebugLog 分类 `loop`：循环启动 / 停止 / 迭代 / 异常。

## 新增一个内置循环事件

1. 在 `presets/` 子包建一个继承 `LoopPreset` 基类的类（参考 `presets/HealAuraLoop`：覆写 `id()`/`trigger()`/`event()`/`monitor()` 等访问器；主人/助手解析直接复用包私有的 `Owners.ownerOf` / `Owners.assistantOf`，Minecraft 对象经 `LoopModule.server()` + `ctx.anchor()` 在运行时取）；
2. 在 `LoopModule.init()` 里 `LoopRegistry.register(new XxxPreset())`；
3. 如需随召唤/送走自动启停，在 `PlayerAssistantService` 的 `ensureLoopStarted` / `dismiss` 处接线（或直接调 `LoopEngine.start(def, anchor)` / `stopAll(anchor)`）——**注册进 `LoopRegistry` 的定义自动获得**：召唤按方块配置启动、配置界面卡片（读 `displayName`/`description`，返回中文字面量即可）、`/opencraft loop status` 列出。

**设计提示**（参考现有 6 个预设的两种监测形态）：事件本身"一轮做不完"（如回血到满）用**条件式监测**——监测函数复判条件，成立 CONTINUE 不成立 STOP（heal/feed/breath/extinguish）；事件一轮完成即走（如拉动一次物品、击退一次）监测恒 STOP，靠 persistent 回等待（pickup/repel）。

## 测试

- JUnit（纯 Java，无 Minecraft 运行时）：`src/test/java/com/swaydy/opencraft/loop/LoopDefinitionTest.java`（构建器校验）、`LoopEngineTest.java`（状态机推进 / 间隔门控 / 迭代上限 / persistent 守护语义 / trigger-event-monitor 三种异常守卫 / start 幂等 / stop/stopAll/clear / 多实例 / state 跨 tick 持久）、`LoopPresetsTest.java`（全部内置预设的 `definition()` 组装校验：id 唯一小写、三组成部分非空、守护参数、persistent 语义 + 注册表注册/重复注册忽略）。
- Gametest：`OpenCraftGameTests.healAuraLoopHealsOwner` —— 召唤自动启动 → 主人受伤后每 ~40 tick 回 1 点血 → 满血后 persistent 循环闲置不消亡 → 送走即停止；`feedAuraLoopFeedsOwner` —— 饱食光环从饥饿 5 喂到 20 + 生命周期同上；`extinguishLoopExtinguishesOwner` —— 主人着火 ~10 tick 内被扑灭 + 生命周期同上；`breathAuraLoopRestoresAir` —— 氧气清零后 ~10 tick 内补氧回满 + 迭代断言 + 生命周期同上；`pickupAuraLoopCollectsDrops` —— 助手身边的苹果被拉向助手收进背包（布置前先把助手传送到主人身边:空结构外壳 barrier + 跟随不主动下降,见 CLAUDE.md）；`repelMonstersLoopPushesHostiles` —— `spawnWithNoFreeWill` 尸壳被推离出生点（位移或击退速度满足其一）+ 迭代断言；`configLoopToggleStartsAndStops` —— 配置界面开关与循环实例启停联动。
