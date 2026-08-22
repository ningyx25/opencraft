package com.swaydy.opencraft.inventory;

import com.swaydy.opencraft.OpenCraftMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

public class ModMenuTypes {

    /** 双面板助手背包菜单类型（左侧助手36格 + 右侧玩家背包）。 */
    public static final MenuType<AssistantInventoryMenu> ASSISTANT_INVENTORY =
            Registry.register(
                    BuiltInRegistries.MENU,
                    OpenCraftMod.id("assistant_inventory"),
                    new MenuType<>(AssistantInventoryMenu::new, net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

    /** 在 {@code OpenCraftMod.onInitialize()} 中调用以触发静态初始化。 */
    public static void register() {
        OpenCraftMod.LOGGER.debug("[OpenCraft] 助手背包菜单类型已注册");
    }
}
