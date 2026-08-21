# Common Mixins 说明

本目录存放通用（common）Mixin 类，注册于 `src/main/resources/opencraft.mixins.json`。
新增 Mixin 类后必须同步在 JSON 的 `mixins` 数组中登记，否则不会生效。

| 文件 | 目标类 | 注入点 | 作用 |
|---|---|---|---|
| `OpenCraftMixin.java` | `net.minecraft.server.MinecraftServer` | `loadLevel()` 方法头部（`@At("HEAD")`） | 模板遗留的占位 Mixin：在世界加载开始时注入 `init()` 回调，当前为空实现（no-op），可作为后续服务器逻辑注入的起点 |
