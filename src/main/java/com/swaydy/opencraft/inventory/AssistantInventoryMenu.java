package com.swaydy.opencraft.inventory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 右键 AI 助手打开的双面板背包菜单：左右两半都是原版按 E 的背包（{@code InventoryMenu}）
 * ——护甲 4 格 + 副手 + 2×2 合成 + 结果槽 + 主背包 3 行 + 快捷栏。
 *
 * <p>直接复用原版组件（不是仿制）：
 * <ul>
 * <li>玩家侧合成网格：基类 {@link AbstractCraftingMenu} 自带（{@code addCraftingGridSlots}
 *     / {@code addResultSlot} 原版方法，仅 x 偏移一个右面板宽度）；</li>
 * <li>助手侧合成网格：同样的原版容器与槽位（{@link TransientCraftingContainer} +
 *     {@link ResultContainer} + {@link ResultSlot}），配方的匹配/消耗走原版静态
 *     {@link CraftingMenu#slotChangedCraftingGrid}；</li>
 * <li>主背包 + 快捷栏：原版 {@code addStandardInventorySlots}（两侧各一次）；</li>
 * <li>护甲/副手：<b>双端对称、容器绑定</b>（原版 {@code InventoryMenu} 的做法）——
 *     助手（真 ServerPlayer）与查看者玩家都用 {@link PlayerEquipmentSlot}
 *     绑各自 {@code Inventory} 的原版装备索引（护甲 39-i、副手 40），客户端同步
 *     只写容器、不触碰实体装备 API（实体 API 只在服务端有意义，客户端调用会走进
 *     原版 onEquipItem 的 ServerLevel 强转直接闪退）；客户端工厂（无真实 Inventory）
 *     退化为占位容器槽、由槽位同步填充。</li>
 * </ul>
 *
 * <p>槽位索引（每侧 46 格，右面板 = 左面板 x 偏移 {@link #RIGHT_PANEL_X}）：
 * <pre>
 *   左（助手）: 结果 0 | 合成 1-4 | 护甲 5-8 | 副手 9 | 主背包 10-36 | 快捷栏 37-45
 *   右（玩家）: 结果 46 | 合成 47-50 | 护甲 51-54 | 副手 55 | 主背包 56-82 | 快捷栏 83-91
 * </pre>
 * 与原版 {@code InventoryMenu} 的槽位次序一致（结果/合成/护甲/背包/快捷栏）。
 */
public class AssistantInventoryMenu extends AbstractCraftingMenu {

	/** 助手背包格数（主背包 27 + 快捷栏 9 = 36，与生存玩家一致）。 */
	public static final int ASSISTANT_SLOTS = 36;

	/** 原版背包界面宽度（inventory.png 的 176px）。 */
	public static final int PANEL_W = 176;
	/** 两个面板之间的水平间距（px）。 */
	public static final int PANEL_GAP = 4;
	/** 右面板相对左面板的 X 偏移。 */
	public static final int RIGHT_PANEL_X = PANEL_W + PANEL_GAP;

	// —— 槽位索引常量（见类注释） ——
	public static final int LEFT_RESULT = 0;
	public static final int LEFT_CRAFT_START = 1;
	public static final int LEFT_ARMOR_START = 5;
	public static final int LEFT_OFFHAND = 9;
	public static final int LEFT_INV_START = 10;
	public static final int RIGHT_RESULT = 46;
	public static final int RIGHT_CRAFT_START = 47;
	public static final int RIGHT_ARMOR_START = 51;
	public static final int RIGHT_OFFHAND = 55;
	public static final int RIGHT_INV_START = 56;
	/** 总槽位数：每侧 46（4 护甲 + 副手 + 4 合成 + 结果 + 36 背包）。 */
	public static final int TOTAL_SLOTS = 92;

	private static final EquipmentSlot[] ARMOR_SLOTS =
			{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private final Player owner;
	private final Container assistantInventory;
	private final TransientCraftingContainer assistantCraftSlots;
	private final ResultContainer assistantResultSlots = new ResultContainer();
	/** 客户端工厂构造时的助手装备占位容器（头盔/胸/腿/靴/副手，内容来自槽位同步）。 */
	private final Container assistantEquipmentFallback;
	private final BooleanSupplier valid;

	/**
	 * 客户端侧构造器（MenuType 工厂）：助手侧内容经原版槽位同步协议自动填充。
	 */
	public AssistantInventoryMenu(int syncId, Inventory playerInventory) {
		this(syncId, playerInventory, new SimpleContainer(ASSISTANT_SLOTS), () -> true);
	}

	/**
	 * 服务端侧构造器：传入助手真实容器（{@code Inventory}）与有效性检查
	 * （助手被送走/死亡时自动关闭界面，防止往已销毁的背包里塞物品）。
	 */
	public AssistantInventoryMenu(int syncId, Inventory playerInventory,
	                              Container assistantInventory, BooleanSupplier valid) {
		// 基类自带 2×2 合成网格（craftSlots/resultSlots）——用作右面板（玩家侧）的合成区
		super(ModMenuTypes.ASSISTANT_INVENTORY, syncId, 2, 2);
		checkContainerSize(assistantInventory, ASSISTANT_SLOTS);
		this.owner = playerInventory.player;
		this.assistantInventory = assistantInventory;
		this.valid = valid;
		this.assistantEquipmentFallback = new SimpleContainer(5); // 头盔/胸/腿/靴/副手
		this.assistantCraftSlots = new TransientCraftingContainer(this, 2, 2);
		assistantInventory.startOpen(this.owner);

		// ------------------------------------------------------------------
		// 左面板：助手（布局 = 原版 InventoryMenu，槽位次序也一致）
		// ------------------------------------------------------------------
		this.addSlot(new ResultSlot(this.owner, this.assistantCraftSlots, this.assistantResultSlots,
				0, 154, 28));
		for (int col = 0; col < 2; col++) {
			for (int row = 0; row < 2; row++) {
				this.addSlot(new Slot(this.assistantCraftSlots, row + col * 2,
						98 + row * 18, 18 + col * 18));
			}
		}
		if (assistantInventory instanceof Inventory assistantPlayerInv) {
			// 服务端：助手装备就是它 Inventory 的原版索引——与真实玩家按 E 完全一致
			this.addPlayerEquipmentSlots(assistantPlayerInv, 0);
		} else {
			// 客户端工厂：无真实 Inventory，用占位容器槽承接槽位同步
			for (int i = 0; i < ARMOR_SLOTS.length; i++) {
				this.addSlot(new Slot(this.assistantEquipmentFallback, i, 8, 8 + i * 18));
			}
			this.addSlot(new Slot(this.assistantEquipmentFallback, 4, 77, 62));
		}
		this.addStandardInventorySlots(assistantInventory, 8, 84);

		// ------------------------------------------------------------------
		// 右面板：玩家自己（合成网格/结果槽直接用基类原版方法，仅 x 偏移）
		// ------------------------------------------------------------------
		this.addResultSlot(this.owner, 154 + RIGHT_PANEL_X, 28);
		this.addCraftingGridSlots(98 + RIGHT_PANEL_X, 18);
		this.addPlayerEquipmentSlots(playerInventory, RIGHT_PANEL_X);
		this.addStandardInventorySlots(playerInventory, 8 + RIGHT_PANEL_X, 84);
	}

	/**
	 * 玩家装备槽（护甲 4 + 副手）：复刻原版 {@code InventoryMenu} 的构造——
	 * 绑定 {@link Inventory} 容器的原版装备索引（护甲 39-i、副手 40），双端对称。
	 */
	private void addPlayerEquipmentSlots(Inventory inventory, int xOffset) {
		for (int i = 0; i < ARMOR_SLOTS.length; i++) {
			EquipmentSlot equipmentSlot = ARMOR_SLOTS[i];
			this.addSlot(new PlayerEquipmentSlot(inventory, inventory.player, equipmentSlot,
					39 - i, 8 + xOffset, 8 + i * 18, emptyArmorIcon(equipmentSlot)));
		}
		this.addSlot(new PlayerEquipmentSlot(inventory, inventory.player, EquipmentSlot.OFFHAND,
				Inventory.SLOT_OFFHAND, 77 + xOffset, 62, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));
	}

	/** 原版空护甲槽占位图标（InventoryMenu 的公开常量）。 */
	private static net.minecraft.resources.Identifier emptyArmorIcon(EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
			case CHEST -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
			case LEGS -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
			case FEET -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
			default -> null;
		};
	}

	/**
	 * 两套合成网格的配方更新：都走原版 {@link CraftingMenu#slotChangedCraftingGrid}
	 * （配方匹配、结果槽填充、取出时消耗材料的逻辑全部原版）。
	 */
	@Override
	public void slotsChanged(Container container) {
		if (this.owner.level() instanceof ServerLevel serverLevel) {
			if (container == this.assistantCraftSlots) {
				CraftingMenu.slotChangedCraftingGrid(this, serverLevel, this.owner,
						this.assistantCraftSlots, this.assistantResultSlots, null);
			} else if (container == this.craftSlots) {
				CraftingMenu.slotChangedCraftingGrid(this, serverLevel, this.owner,
						this.craftSlots, this.resultSlots, null);
			}
		}
	}

	/**
	 * Shift + 点击：左右面板互转（只进对方的主背包 + 快捷栏，不动护甲/合成）；
	 * 结果槽与原版 {@code InventoryMenu} 同款处理（onTake 消耗材料）。
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (slot == null || !slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack original = slot.getItem();
		ItemStack result = original.copy();
		if (index < RIGHT_RESULT) {
			// 左（助手）→ 右（玩家）：优先玩家快捷栏
			if (!moveItemStackTo(original, RIGHT_INV_START, TOTAL_SLOTS, true)) {
				return ItemStack.EMPTY;
			}
		} else {
			// 右（玩家）→ 左（助手）
			if (!moveItemStackTo(original, LEFT_INV_START, RIGHT_RESULT, false)) {
				return ItemStack.EMPTY;
			}
		}
		if (original.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY, result);
		} else {
			slot.setChanged();
		}
		if (original.getCount() == result.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(player, original);
		return result;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.valid.getAsBoolean();
	}

	/** 关闭时清空两套合成区（材料退回，原版 InventoryMenu.removed 的双份版本）。 */
	@Override
	public void removed(Player player) {
		super.removed(player);
		this.resultSlots.clearContent();
		this.assistantResultSlots.clearContent();
		if (!player.level().isClientSide()) {
			this.clearContainer(player, this.craftSlots);
			this.clearContainer(player, this.assistantCraftSlots);
			this.assistantInventory.stopOpen(player);
		}
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot.container != this.resultSlots
				&& slot.container != this.assistantResultSlots
				&& super.canTakeItemForPickAll(stack, slot);
	}

	// —— RecipeBookMenu / AbstractCraftingMenu 要求的实现（指向玩家侧合成网格） ——

	@Override
	public Slot getResultSlot() {
		return this.slots.get(RIGHT_RESULT);
	}

	@Override
	public List<Slot> getInputGridSlots() {
		return this.slots.subList(RIGHT_CRAFT_START, RIGHT_CRAFT_START + 4);
	}

	@Override
	public RecipeBookType getRecipeBookType() {
		return RecipeBookType.CRAFTING;
	}

	@Override
	protected Player owner() {
		return this.owner;
	}

	// ------------------------------------------------------------------
	// 玩家装备槽：复刻原版 InventoryMenu.ArmorSlot / 副手槽（容器绑定、双端对称）
	// ------------------------------------------------------------------

	/**
	 * 原版 {@code InventoryMenu} 护甲/副手槽的复刻（原版类是包私有，无法直接用）：
	 * 绑定玩家 {@code Inventory} 容器的原版装备索引，客户端同步只写容器——
	 * 与原版 E 背包的行为一致（部位校验 {@code isEquippableInSlot}、堆叠上限 1、
	 * 穿戴回调 {@code onEquipItem} 仅服务端执行、空槽占位图标）。
	 */
	public static class PlayerEquipmentSlot extends Slot {
		private final Player owner;
		private final EquipmentSlot equipmentSlot;
		private final net.minecraft.resources.Identifier emptyIcon;

		public PlayerEquipmentSlot(Inventory inventory, Player owner, EquipmentSlot equipmentSlot,
		                           int index, int x, int y,
		                           net.minecraft.resources.Identifier emptyIcon) {
			super(inventory, index, x, y);
			this.owner = owner;
			this.equipmentSlot = equipmentSlot;
			this.emptyIcon = emptyIcon;
		}

		@Override
		public void setByPlayer(ItemStack stack, ItemStack oldStack) {
			// 原版 ArmorSlot 同款穿戴回调；仅服务端执行（客户端同步没有实体语义）
			if (!this.owner.level().isClientSide()) {
				this.owner.onEquipItem(this.equipmentSlot, oldStack, stack);
			}
			super.setByPlayer(stack, oldStack);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			// 原版规则：护甲槽只收对应部位的可穿戴物；副手槽不限
			return this.equipmentSlot == EquipmentSlot.OFFHAND
					|| this.owner.isEquippableInSlot(stack, this.equipmentSlot);
		}

		@Override
		public boolean isActive() {
			return this.owner.canUseSlot(this.equipmentSlot);
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public net.minecraft.resources.Identifier getNoItemIcon() {
			return this.emptyIcon;
		}
	}
}
