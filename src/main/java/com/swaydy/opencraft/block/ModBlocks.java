package com.swaydy.opencraft.block;

import com.swaydy.opencraft.OpenCraftMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * 集中注册本 mod 的所有方块和对应 BlockItem。
 * 1.21.11 起 Properties 必须先 setId(ResourceKey)，再注册。
 */
public final class ModBlocks {
	private ModBlocks() {}

	public static final Block AI_LOGO_BLOCK = register(
			"ai_logo_block",
			new AiLogoBlock(BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_CYAN)
					.strength(2.0F, 6.0F)                    // 硬度 / 抗爆
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops()
					// 激活时自身发光（亮度 15），未激活时不发光
					.lightLevel(state -> state.getValue(AiLogoBlock.POWERED) ? 15 : 0)
					.setId(blockKey("ai_logo_block")))
	);

	/** Fabric 加载器调 onInitialize 时调用，触发本类静态字段的初始化 + 注册 BlockItem + 加进创造标签页。 */
	public static void register() {
		// 注册对应的 BlockItem（方便玩家在物品栏拿到）
		String path = "ai_logo_block";
		ResourceKey<Item> itemKey = ResourceKey.create(BuiltInRegistries.ITEM.key(), OpenCraftMod.id(path));
		Registry.register(BuiltInRegistries.ITEM, OpenCraftMod.id(path),
				new BlockItem(AI_LOGO_BLOCK, new Item.Properties()
						.useBlockDescriptionPrefix()
						.setId(itemKey)));

		// 添加到"功能性方块"创造标签页
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(entries -> entries.accept(AI_LOGO_BLOCK));
	}

	private static Block register(String path, Block block) {
		return Registry.register(BuiltInRegistries.BLOCK, OpenCraftMod.id(path), block);
	}

	private static ResourceKey<Block> blockKey(String path) {
		return ResourceKey.create(BuiltInRegistries.BLOCK.key(), OpenCraftMod.id(path));
	}
}
