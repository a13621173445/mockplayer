package com.mockplayer.mixin;

import com.mockplayer.session.FakeLocalPlayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 LocalPlayer.aiStep 里对 MultiPlayerGameMode.isSpectator() 的调用。
 *
 * 原版 aiStep 用 this.minecraft.gameMode.isSpectator() 判断——读的是主玩家的游戏模式。
 * 对假人（FakeLocalPlayer）改用假人自己的 gameMode 判断，彻底与主玩家解耦；
 * 主玩家走原逻辑。
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

    /**
     * 假人传送门过场：跳过主玩家 Minecraft.setScreen。
     *
     * 原版 handlePortalTransitionEffect 在传传送门时会 this.minecraft.setScreen(null)
     * （关闭当前屏幕），假人传传送门不应关掉主玩家的暂停/容器界面。
     */
    @Redirect(
            method = "handlePortalTransitionEffect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"
            ),
            require = 1
    )
    private void mockplayer$portalSetScreen(Minecraft instance, Screen screen) {
        if (!((Object) this instanceof FakeLocalPlayer)) {
            instance.setScreen(screen);
        }
    }
}
