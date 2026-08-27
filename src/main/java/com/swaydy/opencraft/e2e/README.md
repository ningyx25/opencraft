# OpenCraft 端到端测试模块

在**无头真实存档**（独立服务器，`runServer`）里，用真实 LLM + general_agent 驱动玩家形态 AI 助手完成内置任务，按世界方块状态与助手背包/容器物品计数验证真实结果。

## 设计思路

- **不是 gametest**：gametest 的空结构世界与脚本化断言面相单元验证；e2e 在真实世界跑完整 agentic loop（真实 LLM → 工具调用 → 真玩家式移动/挖掘/放置/合成/容器交互 → 世界状态变化），是"助手像真实玩家一样进服干活"的验收。
- **无头驱动**：所有 `/opencraft` 命令原本要求玩家源（`getPlayerOrException`），本模块绕开命令层，直接用公共 API。合成一个"主人玩家"并像助手一样用 `PlayerList.placeNewPlayer` + 黑洞连接（`FakeConnection`）正式进服——对 mod 就是一个真客户端，跟随/治疗/网络广播全部照常；每任务一个独立主人（独立 UUID/名字，留世界不送走）。
- **验证机制**：不是文本匹配，而是真实世界状态——`countInInventory(itemId)`、`countInOwnerInventory(itemId)`、`countInContainer(pos, itemId)`（读容器方块物品数）、`countBlockInRegion(blockId, center, radius)`、`hasBlockInRegion(...)`。

## 内置任务

### 基础任务（挖矿/砍树/合成）

| id | 描述 | 验证内容 |
|---|---|---|
| `chop_tree` | 砍一棵树并把原木收集起来 | 树干全破坏（区域无 `oak_log` 站立）+ 原木入包/地上 |
| `place_workbench` | 合成一个工作台并放置在空地上 | 区域内存在 `crafting_table` 方块 |
| `craft_wooden_pickaxe` | 用旁边的树做一把木镐 | 背包含 `wooden_pickaxe` |
| `craft_stone_pickaxe` | 做一把石镐 | 背包含 `stone_pickaxe` |
| `mine_stone` | 在平台上挖一些石头 | 背包含 `cobblestone` |

### 容器交互任务（箱子）

测试助手用 `player_container_open/list/take/put/close` 与容器交互的能力。

| id | 描述 | 验证内容 |
|---|---|---|
| `store_items_in_chest` | 把背包 8 块橡木板存入空箱子 | 箱子里有 8 块木板，背包无木板 |
| `retrieve_from_chest` | 从预填 8 块圆石的箱子取出 | 背包有 8 块圆石，箱子无圆石 |
| `organize_container` | 整理箱子：取出圆石 + 放入木板 | 背包有圆石、箱子有木板、箱子无圆石 |

### 熔炉任务

测试助手用容器工具操作熔炉（烧制/加料），并验证真实烧炼结果。

| id | 描述 | 验证内容 |
|---|---|---|
| `smelt_ore_in_furnace` | 放原铁+煤炭，等烧完取铁锭 | 背包或熔炉成品槽有 `iron_ingot`（烧炼约 10s，助手需等待后取出） |
| `add_fuel_and_ore_to_furnace` | 往冷熔炉放原铁+煤炭 | 熔炉输入槽有 `raw_iron`、燃料槽有 `coal` |

## 运行方式

### 全部任务（每任务全新世界）

```bash
bash bin/run_e2e_all.sh
```

等价于依次跑每个单任务（每任务先删 `run/world` 全新存档 + 独立服务器）。任务列表由 `./gradlew e2eList` 从 `e2e/tasks/*.java` 的 `id()` 自动发现——新增任务无需改脚本。

```bash
./gradlew e2eList    # 查看当前发现的任务列表
```

### 单个任务（全新存档 + 跑完自动退出）

```bash
./gradlew runE2E -Pe2eTask=chop_tree
./gradlew runE2E -Pe2eTask=store_items_in_chest
```

流程：删除 `run/world`（全新存档）→ 写 `run/server.properties`（`spawn-protection=0` + `online-mode=false`）→ 启动独立服务器 → 加载 mod → 自动运行该任务 → 写结果到 `run/logs/e2e-results.txt` → 优雅退出。

> **为什么不用 `runE2E` 一次跑全部？** 早期 `runE2E`（autorun=all）在**同一个世界里连续跑 5 个任务**，共享世界的残留假玩家/已加载区块会拖慢后续任务的掉落物拾取（挖 4 根原木只捡到 2 根 vs 新世界 3 根），导致 craft 类任务材料不足而失败。`bin/run_e2e_all.sh` 让每个任务都在自己的全新世界里跑（= 单任务语义），实测全量稳定通过。

### 手动运行（已有服务器 / 控制台）

```bash
./gradlew runServer
# 在控制台输入：
/opencraft e2e list
/opencraft e2e run chop_tree
/opencraft e2e run all
```

## 架构

```
e2e/
├── E2ETask.java        SPI：id/描述/任务指令/setup/verify/teardown
├── E2EContext.java     运行上下文：server/level/合成主人/助手/绑定方块/区域原点 + 验证辅助
├── E2EResult.java      结果 record
├── E2ERegistry.java    静态任务注册表
├── E2EHarness.java     编排器（场景准备/召唤/ask/等待/验证/报告/autorun/真实客户端截图 glue）
├── README.md
└── tasks/
    ├── TaskScenes.java         共用场景工具（种树/清树/放箱子/填箱子/放熔炉/填熔炉输入槽）
    ├── ChopTreeTask.java
    ├── PlaceWorkbenchTask.java
    ├── CraftWoodenPickaxeTask.java
    ├── CraftStonePickaxeTask.java
    ├── MineStoneTask.java
    ├── StoreItemsInChestTask.java
    ├── RetrieveFromChestTask.java
    ├── OrganizeContainerTask.java
    ├── SmeltOreInFurnaceTask.java
    └── AddFuelAndOreToFurnaceTask.java
```

## 结果

- 文本结果 `run/logs/e2e-results.txt`（追加，每次套件带时间戳分隔头）。
- 详细日志 `run/logs/e2e-<时间戳>.log`（任务头/工具序列/周期状态/验证细节；`run_e2e_all.sh` 的结果判定也据此）。
- **原版画质截图** `run/screenshots/`：见下方"真客户端截图"。

## 真客户端截图（原版画质）

需要：`xvfb`、`mesa-utils`（llvmpipe 软渲染）、游戏资源（首次 `runClient` 自动下载）。

```bash
bin/e2e_shot.sh [task] [interval_sec]    # 例: bin/e2e_shot.sh mine_stone 5
```

流程：起 Xvfb 虚拟显示 → 起 e2e 服务器（`online-mode=false` + `spawn-protection=0` 自动写好）→ 任务跑完后服务器 **hold**（`-Pe2eHoldMs`，默认 120s，让慢启动的客户端连入）→ 起真客户端（`--quickPlayMultiplayer` 自动进服 + 软渲染）→ 服务器每 tick 把客户端玩家粘到助手眼睛位置/朝向 → 客户端每 N 秒调 `Screenshot.grab` → 截图落 `run/screenshots/`（画面 = 助手第一人称）。

已实测验证：mock LLM + `-Pe2eBaseUrl=http://127.0.0.1:18923/v1` 下跑通，客户端在 Xvfb + llvmpipe（OpenGL 4.5）正常启动、hold 期间连入、连续产出 46 张含天空+平台的截图。

代码：客户端 `client/ShotAutoCapture.java`（`OPEN_CRAFT_SHOT_AUTOCAPTURE=<秒>` 或 `-Dopencraft.shot.autocapture=<秒>` 触发）+ 服务器 glue（`E2EHarness.registerShotClientGlue`，任务进行中把"非助手、非 E2E_ 合成主人"的玩家粘到 `shotTarget` 眼睛上；hold 结束才解除 glue）+ 任务后保持服务器（`-Dopencraft.e2e.holdMs` / `-Pe2eHoldMs`）。

## 注意事项

- 需要真实 LLM 端点（`.env` 配置的 `OPEN_CRAFT_BASE_URL`/`API_KEY`/`MODEL` 指向可用的 OpenAI 兼容服务）。
- 每任务默认超时 4 分钟；`run_e2e_all.sh` 全部 10 个任务约 5-25 分钟。
- 测试区在 (300, 120, 300) 起、每任务 x 方向间隔 50 格（远离出生点，避开出生点保护圈）；平台 3 层厚（防挖穿掉落）；`runE2E` 写 `spawn-protection=0` 兜底。
- 容器/熔炉任务：`TaskScenes` 在平台上放箱子/熔炉（`containerPos` = 区域原点东侧 3 格）；熔炉槽位 0=成品、1=燃料、2=输入；验证用 `countInContainer(pos, itemId)`。
- 合成主人玩家随任务创建（`PlayerList.placeNewPlayer` + `FakeConnection` 黑洞连接），对 mod 就是真客户端——助手会正常跟随主人；任务结束不送走假玩家（真实存档中 `PlayerList.remove` 会触发 vanilla 光照引擎崩溃），停服时 `saveAllChunks`+`halt` 直接退出（`halt(false)` 需在服务端线程上调用，否则空任务队列会抛 `NoSuchElementException` 崩溃）。
- gametest 不依赖 e2e 模块，互不影响。
