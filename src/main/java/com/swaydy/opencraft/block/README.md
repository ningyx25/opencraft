# block 包文件说明

| 文件 | 类型 | 作用 |
|---|---|---|
| `ModBlocks.java` | 注册类 | 集中注册本 mod 的所有方块及对应 `BlockItem`。目前注册 `ai_logo_block`（青色金属方块，激活时发光亮度 15）；1.21.11 要求 `Properties.setId(ResourceKey)` 后再注册；`register()` 由 mod 初始化调用，负责注册 BlockItem 并把方块加入"功能性方块"创造标签页。 |
| `ModBlockEntities.java` | 注册类 | 注册 AI 徽标方块的方块实体类型（`BlockEntityType<AiLogoBlockEntity>`），用 Fabric 的 `FabricBlockEntityTypeBuilder` 绑定到 `ModBlocks.AI_LOGO_BLOCK`，静态字段初始化即完成注册。 |
| `AiLogoBlock.java` | 方块类 | AI 徽标方块本体，实现 `EntityBlock`。带 `powered` 布尔方块状态（由绑定的助手驱动亮灭）。普通右键 → 通过 `AiConfigHandler.openFor` 打开该方块的配置编辑器；潜行右键 → 显示自动控制状态说明。 |
| `AiLogoBlockEntity.java` | 方块实体类 | AI 徽标方块的方块实体：游戏内唯一的 AI 助手配置载体（每个方块一份 `AiBlockConfig`，随存档 NBT 持久化，不依赖外部配置文件）。提供 `getConfig()` / `markConfigChanged()` / `applyData()`；重写 `preRemoveSideEffects`：方块被破坏时 discard 所有绑定它的助手并清除该方块的对话记忆（刻意不用 `setRemoved()` 以避免区块卸载时的主线程死锁）。 |
