# OpenCraft 自然世界端到端测试

在固定种子的新生成真实存档里，用真实 LLM + `general_agent` 驱动玩家形态 AI 助手，从**真实新玩家状态**开始，在自然世界出生点附近完成早期生存任务，并按真实背包结果验收。

## 设计思路

- **没有独立测试区**：不铺人工平台、不清空地形、不种树、不埋矿、不预放工作台或熔炉。
- **测试区 = 自然出生点附近**：合成主人由 `PlayerList.placeNewPlayer` 正式进服，使用原版出生点选择与调整逻辑；助手召唤在主人旁边。
- **真实新玩家状态**：助手与主人背包均为空，不预给工具、材料或场景资源。
- **真实能力验证**：真实 LLM → `player_find`/`player_mine`/`player_craft` 等玩家式工具 → 自然世界状态变化 → 助手/主人背包物品计数。
- **唯一基础设施**：自然地面上的 AI 配置方块（LLM 绑定/记忆键）；任务结束后恢复原自然方块状态。其他真实游玩修改不回滚。

## 固定世界

默认固定种子：`opencraft-e2e-2026-09-02-04`（Minecraft `1.21.11` 下勘察通过）。

`runE2E` 会在每次任务前删除 `run/world`，写入：

- `level-seed=<固定种子>`
- `level-type=minecraft:normal`
- `generate-structures=true`
- `spawn-protection=0`
- `online-mode=false`

自然出生点勘察条件：

- 半径 20 格内至少 3 个 `oak_log`
- 半径 20 格内至少 8 个暴露 `stone`
- 无岩浆、火、仙人掌等危险方块
- 出生点地面安全，附近无大片水体

只读勘察命令：

```bash
./gradlew runE2E -Pe2eTask=chop_tree -Pe2eProbe=true
```

结果写入 `run/logs/e2e-world-probe.json`。不召唤助手、不调用 LLM、不修改任何方块。

## 内置任务

所有任务都从空背包开始，只允许使用自然生成资源，不提示目标坐标。

| id | 描述 | 验证 | 超时 |
|---|---|---|---|
| `chop_tree` | 在自然出生点附近砍树并收集原木 | 助手/主人背包 `oak_log >= 3` | 6 分钟 |
| `craft_workbench` | 从零收集木材并合成工作台 | `crafting_table >= 1` | 6 分钟 |
| `craft_wooden_pickaxe` | 从零收集木材并合成木镐 | `wooden_pickaxe >= 1` | 8 分钟 |
| `craft_stone_pickaxe` | 从零完成早期工具链并合成石镐 | `stone_pickaxe >= 1` | 10 分钟 |
| `mine_natural_stone` | 用木镐自然寻找并开采石头 | `cobblestone >= 3` | 8 分钟 |
| `place_workbench` | 在自然地面上放置工作台 | 出生点 24 格内有 `crafting_table` | 5 分钟 |
| `craft_furnace` | 给工作台和木镐，自然挖 8 石并合成熔炉 | `furnace >= 1` | 12 分钟 |

## 运行

### 单任务（每任务一个新生成世界）

```bash
./gradlew runE2E -Pe2eTask=chop_tree
./gradlew runE2E -Pe2eTask=craft_furnace
```

`runE2E` 不接受 `all`，因为自然 e2e 要求每个任务都在独立世界中运行。

### 全量（每任务删除世界后重新生成）

```bash
bash bin/run_e2e_all.sh
```

脚本通过 `./gradlew e2eList` 自动发现任务，逐个调用 `runE2E`。

### 手动单任务

已有服务器中可使用：

```bash
/opencraft e2e list
/opencraft e2e run chop_tree
```

`/opencraft e2e run all` 已拒绝，避免在同一世界里连续执行多个任务。

## 结果

- 汇总：`run/logs/e2e-results.txt`
- 详细日志：`run/logs/e2e-<timestamp>.log`
- 地形勘察：`run/logs/e2e-world-probe.json`

详细日志包含固定种子、自然出生点、地形勘察、配置方块位置、助手/主人落点、工具序列、周期状态、最终背包与验证结果。

## 注意事项

- 需要真实 LLM 端点（`.env` 的 `OPEN_CRAFT_BASE_URL` / `OPEN_CRAFT_API_KEY` / `OPEN_CRAFT_MODEL`）。
- `player_find` 搜索半径上限为 20 格；固定种子保证出生点附近有可达树木和石头。
- 助手设置为无敌，只用于避免随机环境死亡；不改变挖掘、合成或移动难度。
- 任务结束不移除假玩家，避免真实存档中 `PlayerList.remove` 触发 vanilla 光照引擎崩溃。
- 停服用 `saveAllChunks` + `halt(false)`，且必须通过服务端线程调度。
- gametest 与 e2e 互不影响。
