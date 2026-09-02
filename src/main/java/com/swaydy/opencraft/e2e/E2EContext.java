package com.swaydy.opencraft.e2e;

import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 单个自然世界端到端测试任务的运行上下文 + 验证辅助方法。
 *
 * <p>所有方法都应在<b>服务端线程</b>调用（E2EHarness 保证在 verify/setup/teardown 时
 * 已切回服务端线程），可直接读写 ServerLevel 与助手实体。</p>
 *
 * @param server                当前服务端
 * @param level                 测试所在维度（主世界）
 * @param owner                  合成的主人玩家（真实进入 PlayerList，自然出生在世界出生点）
 * @param assistant             召唤的玩家形态助手（真玩家，出生在主人旁边）
 * @param configBlock           配置方块的 GlobalPos（助手的绑定方块，agentic loop 的记忆键）
 * @param spawnPos              合成主人实际自然落地位置（任务场景锚点）
 * @param worldSpawnPos         服务器自然世界出生点
 * @param configOriginalState   配置方块放置前的自然方块状态（任务结束必须恢复）
 */
public record E2EContext(MinecraftServer server, ServerLevel level,
                         ServerPlayer owner, AiAssistantPlayer assistant,
                         GlobalPos configBlock, BlockPos spawnPos,
                         BlockPos worldSpawnPos, BlockState configOriginalState) {

	/** 助手主背包（36 格，含快捷栏）中某物品的总数；物品按注册表 id 解析，未知 id 返回 0。 */
	public int countInInventory(String itemId) {
		Item item = resolveItem(itemId);
		if (item == null) {
			return 0;
		}
		int count = 0;
		for (ItemStack stack : assistant.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty() && stack.getItem() == item) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** 主人背包中某物品的总数（助手可能把产出递给主人）。 */
	public int countInOwnerInventory(String itemId) {
		Item item = resolveItem(itemId);
		if (item == null) {
			return 0;
		}
		int count = 0;
		for (ItemStack stack : owner.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty() && stack.getItem() == item) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/** 助手/主人合计持有某物品的数量（自然生存任务的唯一结果口径）。 */
	public int countInAnyInventory(String itemId) {
		return countInInventory(itemId) + countInOwnerInventory(itemId);
	}

	/** 助手主背包中的物品栈总数（非空栈数，粗略判断"有没有拿到东西"）。 */
	public int nonEmptySlotCount() {
		int count = 0;
		for (ItemStack stack : assistant.getInventory().getNonEquipmentItems()) {
			if (!stack.isEmpty()) {
				count++;
			}
		}
		return count;
	}

	/** 以 center 为中心、边长 2*radius+1 的立方体内某方块的数量（自然世界只读诊断）。 */
	public int countBlockInRegion(String blockId, BlockPos center, int radius) {
		Block block = resolveBlock(blockId);
		if (block == null) {
			return 0;
		}
		int count = 0;
		int cx = center.getX(), cy = center.getY(), cz = center.getZ();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (level.getBlockState(new BlockPos(cx + dx, cy + dy, cz + dz)).getBlock() == block) {
						count++;
					}
				}
			}
		}
		return count;
	}

	/** 坐标处容器方块（chest/barrel/furnace 等，实现 {@code Container}）内某物品的总数。 */
	public int countInContainer(BlockPos pos, String itemId) {
		Item item = resolveItem(itemId);
		if (item == null) {
			return 0;
		}
		if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
			int count = 0;
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack stack = c.getItem(i);
				if (!stack.isEmpty() && stack.getItem() == item) {
					count += stack.getCount();
				}
			}
			return count;
		}
		return 0;
	}

	/** 以 center 为中心、边长 2*radius+1 的立方体内是否存在某方块。 */
	public boolean hasBlockInRegion(String blockId, BlockPos center, int radius) {
		return countBlockInRegion(blockId, center, radius) > 0;
	}

	private static Item resolveItem(String itemId) {
		var holder = AiCompanionService.resolveItem(itemId);
		return holder == null ? null : holder.value();
	}

	private static Block resolveBlock(String blockId) {
		var holder = AiCompanionService.resolveBlock(blockId);
		return holder == null ? null : holder.value();
	}
}
