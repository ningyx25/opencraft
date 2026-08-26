package com.swaydy.opencraft.loop;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.loop.presets.HealAuraLoop;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 循环事件模块的 Minecraft 接线层：把纯 Java 的 {@link LoopEngine} 挂到服务端生命周期上。
 *
 * <ul>
 * <li>注册内置循环事件定义（{@code presets/HealAuraLoop}）到 {@link LoopRegistry};</li>
 * <li>{@code ServerTickEvents.END_SERVER_TICK} → {@link LoopEngine#tick(long)}
 *     （循环实例全部在服务端线程推进）;</li>
 * <li>{@code ServerLifecycleEvents.SERVER_STOPPING} → {@link LoopEngine#clear()};</li>
 * <li>持有当前 {@link MinecraftServer} 引用,供内置循环的条件/事件/监测函数
 *     在 lambda 闭包里实时查服务端状态（定义在 mod 初始化时注册,彼时还没有服务端）。</li>
 * </ul>
 *
 * <p>启动/停止循环实例的入口不在本类：绑定方块召唤助手时由
 * {@code PlayerAssistantService.summonFor} 调 {@code LoopEngine.start},送走/方块被拆时由
 * {@code PlayerAssistantService.dismiss} 调 {@code LoopEngine.stopAll}（见 CLAUDE.md 生命周期章节）。
 */
public final class LoopModule {
	/** 当前服务端引用（tick 时更新,服务端停止时清空;供内置循环闭包使用）。 */
	private static volatile MinecraftServer serverRef;

	private LoopModule() {
	}

	/** 在模组初始化时调用：注册内置定义（loop/presets/ 的 LoopPreset 预设）+ 服务端 tick / 生命周期回调。 */
	public static void init() {
		LoopRegistry.register(new HealAuraLoop());
		ServerTickEvents.END_SERVER_TICK.register(LoopModule::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(LoopModule::onServerStopping);
		OpenCraftMod.LOGGER.info("[OpenCraft] 循环事件模块已就绪（内置: {}）",
				HealAuraLoop.ID);
	}

	/** 当前服务端（未启动/已停止时为 null）;供内置循环闭包取实时状态。 */
	public static MinecraftServer server() {
		return serverRef;
	}

	private static void onServerTick(MinecraftServer server) {
		serverRef = server;
		LoopEngine.tick(server.getTickCount());
	}

	private static void onServerStopping(MinecraftServer server) {
		LoopEngine.clear();
		serverRef = null;
	}
}