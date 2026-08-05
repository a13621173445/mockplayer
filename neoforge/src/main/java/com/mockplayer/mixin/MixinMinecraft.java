package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.SessionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主玩家断开时决定是否清空假人。
 *
 * 主玩家所有断开路径（真退出 / 被踢 / transfer）都汇聚到 Minecraft.disconnect(Screen, boolean, boolean)。
 * 若 transferring 标志为 true（主玩家正在 server transfer，将重连子服）→ 跳过 clearAll（假人保留）；
 * 否则（真退出 / 被踢）→ 全部假人下线。
 *
 * 注意：SessionManager.clearAll 原本挂在 Fabric DISCONNECT / NeoForge LoggingOut 事件，
 * 那些事件在 transfer 时也会触发（导致误清）。此 Mixin 在 disconnect 源头拦截，替代事件里的判断。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void mockplayer$onMainPlayerDisconnect(Screen screen, boolean clearResourcePacks, boolean keepSavingScreen, CallbackInfo ci) {
        // 读取并复位 transfer 标志：true = 主玩家正在 transfer（空窗），跳过清空
        if (FakeConnectionRegistry.takeTransferring()) {
            return;
        }
        SessionManager.getInstance().clearAll();
    }
}
