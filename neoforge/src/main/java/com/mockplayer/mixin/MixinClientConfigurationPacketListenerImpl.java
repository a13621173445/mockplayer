package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakePlayListener;
import com.mockplayer.session.accessor.MockplayerClientCommonListenerAccessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拦截 ClientConfigurationPacketListenerImpl 创建 ClientPacketListener 的瞬间。
 *
 * 假人连接 → 换成 FakePlayListener（无头但完整：独立 world/player/物理，不污染主玩家）。
 * 主玩家连接 → 原逻辑（new ClientPacketListener）。
 *
 * 单机 registry 对齐：原版配置结束用 collectGameRegistries + filterRegistries 重建最终 registry，
 * 假人（TCP 第二个连接）走网络收集构建的是"新实例"（与服务端不同源）→ 矛 Holder 不同源 → 服务端编码崩。
 * 这里假人单机直接跳过收集（runWithResources 返回服务端完整实例），后续 filterRegistries 基于它的
 * 完整 keys 从服务端取 → 假人编解码 = 服务端实例（含 spear/dimension，同源）。
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
            return new FakePlayListener(
                    FakeConnectionRegistry.getSession(connection),
                    minecraft,
                    connection,
                    cookie
            );
        }
        return new ClientPacketListener(minecraft, connection, cookie);
    }

    @WrapOperation(
            method = "handleConfigurationFinished",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl;runWithResources(Ljava/util/function/Function;)Ljava/lang/Object;"),
            require = 1
    )
    private Object mockplayer$useServerRegistry(
            ClientConfigurationPacketListenerImpl self,
            java.util.function.Function<net.minecraft.server.packs.resources.ResourceProvider, ?> fn,
            Operation<Object> original
    ) {
        Connection conn = ((MockplayerClientCommonListenerAccessor) (Object) self).connection();
        if (FakeConnectionRegistry.isFake(conn) && Minecraft.getInstance().getSingleplayerServer() != null) {
            return Minecraft.getInstance().getSingleplayerServer().registryAccess();
        }
        return original.call(self, fn);
    }
}
