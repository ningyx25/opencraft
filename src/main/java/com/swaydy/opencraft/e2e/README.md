# OpenCraft 端到端测试模块

在**无头真实存档**（独立服务器，`runServer`）里，用真实 LLM + general_agent 驱动玩家形态 AI 助手完成内置任务，按世界方块状态与助手背包物品计数验证真实结果。

## 设计思路

- **不是 gametest**：gametest 的空结构世界与脚本化断言面相单元验证；e2e 在真实世界跑完整 agentic loop（真实 LLM → 工具调用 → 真玩家式移动/挖掘/放置/合成 → 世界状态变化），是"助手像真实玩家一样进服干活"的验收。
- **无头驱动**：所有 `/opencraft` 命令原本要求玩家源（`getPlayerOrException`），本模块绕开命令层，直接用公共 API。合成一个"主人玩家"并像助手一样用 `PlayerList.placeNewPlayer` + 黑洞连接（`FakeConnection`）正式进服——对 mod 就是一个真客户端，跟随/治疗/网络广播全部照常；每任务一个独立主人（独立 UUID/名字，留世界不送走）。
- **验证机制**：不是文本匹配，而是真实世界状态——`countInInventory(itemId)`、`countBlockInRegion(blockId, center, radius)`、`hasBlockInRegion(...)`。

## 内置任务

| id | 描述 | 验证内容 |
|---|---|---|
| `chop_tree` | 砍一棵树并把原木收集起来 | 树干全破坏（区域无 `oak_log` 站立）+ 原木入包/地上 |
| `place_workbench` | 合成一个工作台并放置在空地上 | 区域内存在 `crafting_table` 方块 |
| `craft_wooden_pickaxe` | 用旁边的树做一把木镐 | 背包含 `wooden_pickaxe` |
| `craft_stone_pickaxe` | 做一把石镐 | 背包含 `stone_pickaxe` |
| `mine_stone` | 在平台上挖一些石头 | 背包含 `cobblestone` |

## 运行方式

### 自动运行（全新存档 + 跑完自动退出）

```bash
# 跑全部任务（默认）
./gradlew runE2E

# 跑单个任务
./gradlew runE2E -Pe2eTask=chop_tree
```

流程：删除 `run/world`（全新存档）→ 写 `run/server.properties`（`spawn-protection=0`）→ 启动独立服务器 → 加载 mod → 自动运行 e2e 套件 → 写结果到 `run/logs/e2e-results.txt` → 优雅退出。

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
    ├── TaskScenes.java         共用场景工具（种树/清树）
    ├── ChopTreeTask.java
    ├── PlaceWorkbenchTask.java
    ├── CraftWoodenPickaxeTask.java
    ├── CraftStonePickaxeTask.java
    └── MineStoneTask.java
```

## 结果

- 文本结果 `run/logs/e2e-results.txt`（追加，每次套件带时间戳分隔头）。
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
- 每任务默认超时 4 分钟；`run all` 全部 5 个任务约 5-20 分钟。
- 测试区在 (300, 120, 300) 起、每任务 x 方向间隔 50 格（远离出生点，避开出生点保护圈）；平台 3 层厚（防挖穿掉落）；`runE2E` 写 `spawn-protection=0` 兜底。
- 合成主人玩家随任务创建（`PlayerList.placeNewPlayer` + `FakeConnection` 黑洞连接），对 mod 就是真客户端——助手会正常跟随主人；任务结束不送走假玩家（真实存档中 `PlayerList.remove` 会触发 vanilla 光照引擎崩溃），停服时 `saveAllChunks`+`halt` 直接退出。
- 5 个 gametest 不依赖 e2e 模块，互不影响。