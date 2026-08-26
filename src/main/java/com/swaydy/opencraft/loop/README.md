# loop 包文件说明

「循环事件模块」：由 **[条件]**（触发条件）、**[事件]**（执行事件）、**[监测函数]**（监测条件）组成的通用循环框架，语义为 **触发条件 → 执行事件 → 监测条件 →（继续）→ 触发条件 → …**。每个服务端 tick 由 `LoopEngine` 驱动所有活动实例推进一个阶段；循环实例在服务端线程同步执行，条件/事件/监测函数必须快速返回、不得阻塞。

核心为**纯 Java（零 Minecraft import）**，可 JUnit 单测；Minecraft 访问由接线层（`LoopModule` / `presets/HealAuraLoop`）在 lambda 闭包中捕获（`LoopModule.server()` 取当前服务端、`ctx.anchor()` 取实例锚点）。**内置循环事件集中放在 `presets/` 子包**（同 `agent/presets/BaseAgent` / `plugins/presets/AssistantPlugin` 的管理思路）：预设类继承 `presets/LoopPreset` 基类（SPI）、覆写访问器声明三组成部分与运行参数，框架本身与内置实现分离，方便扩展。

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
| `presets/HealAuraLoop.java` | **内置最小实现 `heal_aura`（治疗光环）**：触发=绑定方块的主人玩家在线、存活、生命不满；事件=`heal(1.0F)`；监测=仍不满→CONTINUE 否则 STOP；`intervalTicks=40`（每 2 秒）、`persistent=true`（满血后闲置监视，主人再次受伤自动再治疗）。锚点=绑定方块的 `GlobalPos`。 |

## 生命周期接线

循环实例**不持久化**（服务端重启后由再次召唤时重建），生命周期与「方块绑定助手」绑定：

- **召唤/绑定成功** → 启动：`PlayerAssistantService.summonFor` 的三个成功出口（幂等已有 / PlayerList 复用 / 新建）都调 `ensureLoopStarted(block)` → `LoopEngine.start(presets.HealAuraLoop.definition(), block)`；
- **送走/方块被拆** → 停止：`PlayerAssistantService.dismiss` 解绑成功后 `LoopEngine.stopAll(block)`（`AiLogoBlockEntity.preRemoveSideEffects` 方块被拆与安全网 `onSlowTick` 无绑定方块均经此路径）；
- **服务端停止** → 清空：`LoopModule` 的 `SERVER_STOPPING` 回调。

## 命令与日志

- `/opencraft loop status`：列出已注册定义（id + description）与活动实例（anchor / 阶段 / 迭代数），只读、无需权限。
- DebugLog 分类 `loop`：循环启动 / 停止 / 迭代 / 异常。

## 新增一个内置循环事件

1. 在 `presets/` 子包建一个继承 `LoopPreset` 基类的类（参考 `presets/HealAuraLoop`：覆写 `id()`/`trigger()`/`event()`/`monitor()` 等访问器，Minecraft 对象经 `LoopModule.server()` + `ctx.anchor()` 闭包捕获）；
2. 在 `LoopModule.init()` 里 `LoopRegistry.register(new XxxPreset())`；
3. 如需随召唤/送走自动启停，在 `PlayerAssistantService` 的 `ensureLoopStarted` / `dismiss` 处接线（或直接调 `LoopEngine.start(def, anchor)` / `stopAll(anchor)`）。

## 测试

- JUnit（纯 Java，无 Minecraft 运行时）：`src/test/java/com/swaydy/opencraft/loop/LoopDefinitionTest.java`（构建器校验）、`LoopEngineTest.java`（状态机推进 / 间隔门控 / 迭代上限 / persistent 守护语义 / trigger-event-monitor 三种异常守卫 / start 幂等 / stop/stopAll/clear / 多实例 / state 跨 tick 持久）。
- Gametest：`OpenCraftGameTests.healAuraLoopHealsOwner` —— 召唤自动启动 → 主人受伤后每 ~40 tick 回 1 点血 → 满血后 persistent 循环闲置不消亡 → 送走即停止。
