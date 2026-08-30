package com.swaydy.opencraft.client.mixin;

import com.mojang.authlib.GameProfile;
import com.swaydy.opencraft.client.skin.AssistantSkinState;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * 玩家形态助手的自定义皮肤（路线 A：客户端 Mixin 改道皮肤解析）。
 *
 * 1.21.9+ 皮肤解析全在客户端：PlayerInfo.createSkinLookup 用 GameProfile 的
 * textures 属性建 lookup，且对非本地玩家强制 Mojang 签名（secure 过滤），假玩家
 * 的 profile 没有签名贴图，永远只显示按 UUID 哈希的默认皮肤。
 * 这里在 lookup 创建前改道：是同步过皮肤 id 的助手 bot → 返回 AssistantSkinState
 * 的动态 Supplier（内置贴图，secure=true）；其余玩家不受影响。
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
	@Inject(method = "createSkinLookup", at = @At("HEAD"), cancellable = true)
	private static void opencraft$assistantSkinOverride(
			GameProfile profile, CallbackInfoReturnable<Supplier<PlayerSkin>> cir) {
		Supplier<PlayerSkin> override = AssistantSkinState.overrideLookup(profile);
		if (override != null) {
			cir.setReturnValue(override);
		}
	}
}
