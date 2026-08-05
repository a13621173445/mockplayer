package com.mockplayer.mixin;

import com.mockplayer.session.ControlManager;
import com.mockplayer.session.FakeLocalPlayer;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 LocalPlayer：
 *  1. aiStep 里 MultiPlayerGameMode.isSpectator() —— 假人用假人自己的 gameMode，主玩家原逻辑
 *  2. isControlledCamera() —— /control 控制假人期间，主玩家强制 true（保持物理发包，防反作弊踢）
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer {

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;isSpectator()Z"
            ),
            require = 2
    )
    private boolean mockplayer$isSpectator(MultiPlayerGameMode instance) {
        // 若是假人，用假人自己的模式判断；否则原逻辑（读主玩家 gameMode）
        if ((Object) this instanceof FakeLocalPlayer fakePlayer) {
            return fakePlayer.isFakeSpectator();
        }
        return instance.isSpectator();
    }

    @Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
    private void mockplayer$keepMainPlayerSending(CallbackInfoReturnable<Boolean> cir) {
        // /control 期间，被换下保活的主玩家强制视为受控相机 → 继续发位置包（防反作弊踢）
        if (ControlManager.isControlling()
                && !((Object) this instanceof FakeLocalPlayer)
                && (Object) this == ControlManager.getMainPlayer()) {
            cir.setReturnValue(true);
        }
    }
}
