package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;

import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakePlayListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 ClientConfigurationPacketListenerImpl 创建 ClientPacketListener 的瞬间。
 *
 * 假人连接 → 换成 FakePlayListener（无头但完整：独立 world/player/物理，不污染主玩家），
 * 并清除 neoforge applySnapshot 跳过标志（配置阶段结束，进入 play）。
 * 主玩家连接 → 原逻辑（new ClientPacketListener）。
 */
@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class MixinClientConfigurationPacketListenerImpl {

    @Redirect(
            method = "handleConfigurationFinished",
            at = @At(value = "NEW", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;"),
            require = 1
    )
    private ClientPacketListener mockplayer$createListener(
            Minecraft minecraft,
            Connection connection,
            CommonListenerCookie cookie
    ) {
        // 假人连接 → FakePlayListener；否则原版 ClientPacketListener
        if (FakeConnectionRegistry.isFake(connection)) {
            FakeConnectionRegistry.setConfiguringFake(false);
            return new FakePlayListener(
                    FakeConnectionRegistry.getSession(connection),
                    minecraft,
                    connection,
                    cookie
            );
        }
        return new ClientPacketListener(minecraft, connection, cookie);
    }
}
