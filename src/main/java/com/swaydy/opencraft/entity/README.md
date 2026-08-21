# entity 包文件说明

本包包含 AI 助手实体（旧存档遗留形态，`PathfinderMob` 底座）及其任务系统。

| 文件 | 类型 | 作用 |
|---|---|---|
| `AiAssistantEntity.java` | 实体类 | AI 助手实体本体（`PathfinderMob` 子类，实现 `AiAssistant` 接口）。管理主人绑定（`SynchedEntityData` + `EntityReference`）、绑定 AI 徽标方块（配置来源）、36 格背包与原生装备槽、自动拾取掉落物、挖掘时自动换最快工具、右键交互（绑主/开互动界面）、任务生命周期（下达/取消/完成）、存档持久化（主人/配置方块/背包），以及安全网（无绑定方块约 2 秒内自清除）。 |
| `ModEntities.java` | 注册与查找 | 注册 `ai_assistant` 实体类型与属性，并提供多助手共存下的查找辅助：按玩家找全部助手、按"绑定方块距玩家最近"找最近助手（供 ask/dismiss/reset 路由）、按名字选择器（纯名字 / `名字 (x,y,z)` / `名字@x,y,z`）精确指定助手、按方块查绑定助手。不提供刷怪蛋（无绑定助手会被清除）。 |
| `AssistantTask.java` | 抽象基类 | 助手异步任务基类（继承 `Goal`）。任务以高优先级 Goal 形式由 goalSelector 每 tick 驱动；定义 `isDone()`/`isFailed()`/`describe()` 抽象方法，任务只下达指令并自行判定成败，模型通过后续观察工具查看结果。 |
| `TaskHostGoal.java` | 常驻 Goal | 任务"宿主"Goal：在 goalSelector 优先级 0 常驻注册，代理驱动当前任务（`getCurrentTask()`）；任务活跃时压制低优先级散步 Goal，任务终结时回调实体清空当前任务。 |
| `MoveToBlockTask.java` | 具体任务 | 移动到指定方块旁：寻路走向目标，水平距离 ≤1.5 格即成功；超时（30s）或无法寻路则失败。 |
| `MineBlockTask.java` | 具体任务 | 挖掘指定方块：先寻路靠近（≤2.5 格），就位后持续挥手并破坏方块；掉落物按主手工具计算战利品后在挖掘点生成物品实体，由助手的自动拾取收进背包；超时 30s 或破坏失败（如基岩）则失败。开始前先自动换上最快的工具。 |
| `AttackTask.java` | 具体任务 | 攻击指定实体：靠近目标至近战距离（1.8 格）后每秒攻击一次，目标死亡/消失即完成；超时 30s 则失败。 |

## 关系概览

```
ModEntities ──注册──> AiAssistantEntity
                          │
                          ├── currentTask: AssistantTask（当前任务）
                          │        ├── MoveToBlockTask（移动）
                          │        ├── MineBlockTask（挖掘）
                          │        └── AttackTask（攻击）
                          │
                          └── TaskHostGoal（优先级 0，代理驱动 currentTask）
```
