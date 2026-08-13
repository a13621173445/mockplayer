package com.mockplayer.test.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CI headless 专用（仅 testmod 加载，不进生产 jar）：
 * 当系统属性 mockplayer.norender=1 时跳过 renderFrame —— no-op 渲染，
 * 等价于 HeadlessMC 的 -lwjgl（GLFW/GL 调用全部空掉），让 MC 在 xvfb/llvmpipe
 * 软件渲染跑不动的情况下（GL ERROR X11 cursor / 新渲染管线）仍能推进 tick 跑 mocktest。
 * 本地不设置该属性 → 渲染完全正常，不影响调试。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftRender {

    @Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true)
    private void mockplayerTest$skipRender(CallbackInfo ci) {
        if ("1".equals(System.getProperty("mockplayer.norender"))) {
            ci.cancel();
        }
    }
}
