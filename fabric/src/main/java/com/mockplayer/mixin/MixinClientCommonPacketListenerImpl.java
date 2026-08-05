package com.mockplayer.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;

import com.mockplayer.session.FakeConnectionRegistry;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主玩家 server transfer 置位标志。
 *
 * 主玩家收到 ClientboundTransferPacket（被传送到子服）时，置 transferring 标志，
 * 让 MixinMinecraft 在 disconnect 时跳过 SessionManager.clearAll()——主玩家 transfer 不误清假人。
 *
 * 假人（FakePlayListener）override handleTransfer 不调父类 → 本注入对假人不生效，
 * 只有主玩家（走父类实现）触发标志，天然隔离。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListenerImpl {

    @Inject(method = "handleTransfer", at = @At("HEAD"))
    private void mockplayer$markTransferring(ClientboundTransferPacket packet, CallbackInfo ci) {
        FakeConnectionRegistry.setTransferring(true);
    }
}
