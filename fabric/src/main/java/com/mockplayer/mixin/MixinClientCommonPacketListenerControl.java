package com.mockplayer.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;

import com.mockplayer.session.ControlManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * /control 期间主玩家 listener 的 common 层显示类隔离。
 *
 * 这些 handler（对话框/资源包提示）声明在 ClientCommonPacketListenerImpl，主玩家 listener（ClientPacketListener
 * 子类）继承后走原版实现会弹主玩家 GUI（污染 bot 视角）。假人 listener（FakePlayListener）已 override 记录到 state，
 * 不走原版 → 触发本注入的必然是主玩家 listener，故只需判断 ControlManager.isControlling()。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListenerControl {

    private boolean mockplayer$isMainControlled() {
        return ControlManager.isControlling();
    }

    @Inject(method = "handleShowDialog", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateShowDialog(ClientboundShowDialogPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleShowDialog", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleClearDialog", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateClearDialog(ClientboundClearDialogPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleClearDialog", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateResourcePackPush(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleResourcePackPush", packet);
            ci.cancel();
        }
    }

    @Inject(method = "handleResourcePackPop", at = @At("HEAD"), cancellable = true)
    private void mockplayer$isolateResourcePackPop(ClientboundResourcePackPopPacket packet, CallbackInfo ci) {
        if (mockplayer$isMainControlled()) {
            ControlManager.getMainState().recordPacket("handleResourcePackPop", packet);
            ci.cancel();
        }
    }
}
