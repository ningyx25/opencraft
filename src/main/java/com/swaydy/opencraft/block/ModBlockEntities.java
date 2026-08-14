package com.swaydy.opencraft.block;

import com.swaydy.opencraft.OpenCraftMod;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * 注册 AI 徽标方块的方块实体类型。
 */
public final class ModBlockEntities {
	private ModBlockEntities() {
	}

	public static final BlockEntityType<AiLogoBlockEntity> AI_LOGO_BLOCK =
			Registry.register(
					BuiltInRegistries.BLOCK_ENTITY_TYPE,
					OpenCraftMod.id(AiLogoBlockEntity.ID),
					FabricBlockEntityTypeBuilder.create(
							AiLogoBlockEntity::new, ModBlocks.AI_LOGO_BLOCK).build());

	public static void register() {
		// 静态字段初始化即完成注册
	}
}
