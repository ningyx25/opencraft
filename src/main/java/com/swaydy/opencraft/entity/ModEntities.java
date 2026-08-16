package com.swaydy.opencraft.entity;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 注册 AI 助手实体与属性，并提供“按玩家 / 按方块查找助手”的辅助方法。
 *
 * 多助手共存规则：**每个 AI 徽标方块最多绑定一个 AI 助手**，一个玩家可以同时拥有
 * 多个助手（各绑定不同的方块）。因此查找以“方块 → 助手”为核心，
 * 不再有“玩家 → 唯一助手”的一对一方法。
 *
 * 注意：不再提供刷怪蛋——新规则下助手必须绑定 AI 徽标方块，
 * 无绑定的助手会在约 2 秒内被清除，刷怪蛋只能产出无意义的野助手。
 */
public final class ModEntities {
	private ModEntities() {
	}

	public static final EntityType<AiAssistantEntity> AI_ASSISTANT = registerEntityType();

	public static void register() {
		FabricDefaultAttributeRegistry.register(AI_ASSISTANT, AiAssistantEntity.createAttributes());
	}

	private static EntityType<AiAssistantEntity> registerEntityType() {
		ResourceKey<EntityType<?>> key =
				ResourceKey.create(BuiltInRegistries.ENTITY_TYPE.key(), OpenCraftMod.id("ai_assistant"));
		EntityType<AiAssistantEntity> type = EntityType.Builder
				.of(AiAssistantEntity::new, MobCategory.CREATURE)
				.sized(0.6F, 1.8F)
				.clientTrackingRange(10)
				.updateInterval(3)
				.build(key);
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
	}

	/** 找出某位玩家拥有的全部助手（跨所有维度）。 */
	public static List<AiAssistantEntity> findAssistantsFor(ServerPlayer player) {
		List<AiAssistantEntity> result = new ArrayList<>();
		for (ServerLevel level : player.level().getServer().getAllLevels()) {
			for (AiAssistantEntity assistant : level.getEntities(
					EntityTypeTest.forClass(AiAssistantEntity.class),
					e -> player.getUUID().equals(e.getOwnerUuid()))) {
				result.add(assistant);
			}
		}
		return result;
	}

	/**
	 * 玩家“最近”的助手：按绑定方块到玩家的距离排序（同维度优先，曼哈顿距离），
	 * 用于 /opencraft ask、dismiss、reset 等需要确定“哪个助手”的指令；没有则返回 null。
	 */
	public static AiAssistantEntity findNearestAssistantFor(ServerPlayer player) {
		List<AiAssistantEntity> owned = findAssistantsFor(player);
		if (owned.isEmpty()) {
			return null;
		}
		BlockPos playerPos = player.blockPosition();
		owned.sort(Comparator.comparingInt(a -> {
			GlobalPos block = a.getConfigBlock();
			if (block == null) {
				return Integer.MAX_VALUE;
			}
			if (!block.dimension().equals(player.level().dimension())) {
				return Integer.MAX_VALUE - 1;
			}
			return Math.abs(block.pos().getX() - playerPos.getX())
					+ Math.abs(block.pos().getY() - playerPos.getY())
					+ Math.abs(block.pos().getZ() - playerPos.getZ());
		}));
		return owned.get(0);
	}

	/**
	 * 按玩家输入的选择器（助手名字）查找该玩家的助手，用于 /opencraft ask <名字> <消息>。
	 * 支持（大小写不敏感、首尾空白忽略）：
	 *   - 纯名字："小智"
	 *   - 显示名："小智 (12,64,45)"
	 *   - 紧凑带坐标："小智(12,64,45)" / "小智@12,64,45"（同名字的助手用坐标消歧）
	 * 返回所有匹配（配置名字可以重复，因此可能有多个）；无匹配返回空列表。
	 */
	public static List<AiAssistantEntity> findAssistantsBySelector(ServerPlayer player, String selector) {
		List<AiAssistantEntity> result = new ArrayList<>();
		if (selector == null) {
			return result;
		}
		String s = selector.trim().toLowerCase(java.util.Locale.ROOT);
		if (s.isEmpty()) {
			return result;
		}
		for (AiAssistantEntity assistant : findAssistantsFor(player)) {
			AiBlockConfig config = assistant.getConfig();
			String name = config == null ? "" : config.effectiveName();
			GlobalPos block = assistant.getConfigBlock();
			if (s.equals(name.toLowerCase(java.util.Locale.ROOT))) {
				result.add(assistant);
				continue;
			}
			if (block == null) {
				continue;
			}
			String xyz = block.pos().getX() + "," + block.pos().getY() + "," + block.pos().getZ();
			String display = name + " (" + xyz + ")";
			String compact = name + "(" + xyz + ")";
			String atForm = name + "@" + xyz;
			String lower = s;
			if (lower.equals(display.toLowerCase(java.util.Locale.ROOT))
					|| lower.equals(compact.toLowerCase(java.util.Locale.ROOT))
					|| lower.equals(atForm.toLowerCase(java.util.Locale.ROOT))) {
				result.add(assistant);
			}
		}
		return result;
	}

	/** 是否有任意助手（任意维度）绑定到指定 AI 徽标方块。 */
	public static boolean isConfigBlockBound(ServerLevel anyLevel, GlobalPos blockPos) {
		return !findAssistantsBoundTo(anyLevel, blockPos).isEmpty();
	}

	/** 找出所有（任意维度）绑定到指定 AI 徽标方块的助手。 */
	public static List<AiAssistantEntity> findAssistantsBoundTo(
			ServerLevel anyLevel, GlobalPos blockPos) {
		List<AiAssistantEntity> result = new ArrayList<>();
		for (ServerLevel level : anyLevel.getServer().getAllLevels()) {
			for (AiAssistantEntity assistant : level.getEntities(
					EntityTypeTest.forClass(AiAssistantEntity.class),
					e -> blockPos.equals(e.getConfigBlock()))) {
				result.add(assistant);
			}
		}
		return result;
	}

	/** 绑定到指定 AI 徽标方块的助手（一方块至多一个；没有则返回 null）。 */
	public static AiAssistantEntity findAssistantBoundTo(ServerLevel anyLevel, GlobalPos blockPos) {
		List<AiAssistantEntity> bound = findAssistantsBoundTo(anyLevel, blockPos);
		return bound.isEmpty() ? null : bound.get(0);
	}
}
