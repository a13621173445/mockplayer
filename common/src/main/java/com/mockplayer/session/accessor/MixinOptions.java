package com.mockplayer.session.accessor;

import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.gui.BotControlScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BotControlScreen 打开期间只读改写模糊强度（不再写主玩家 options）。
 *
 * 原版 GameRenderer 每帧从 {@code options.getMenuBackgroundBlurriness()} 读模糊半径，
 * 旧实现打开 GUI 时直接 set 主玩家选项、关闭时恢复——会污染主玩家设置（改完变回默认等）。
 * 这里在 GUI 打开时按配置返回 guiBlur，关闭后自然回到主玩家原值，全程零写入。
 */
@Mixin(Options.class)
public abstract class MixinOptions {

    @Inject(method = "getMenuBackgroundBlurriness", at = @At("HEAD"), cancellable = true)
    private void mockplayer$guiBlurOverride(CallbackInfoReturnable<Integer> cir) {
        if (Minecraft.getInstance().screen instanceof BotControlScreen) {
            cir.setReturnValue(MockplayerConfig.get().getGuiBlur());
        }
    }
}
