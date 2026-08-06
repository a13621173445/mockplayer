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
 * 假人连接 → 换成 FakePlayListener（无头但完整：独立 world/player/物理，不污染主玩家），
 * 并清除 neoforge applySnapshot 跳过标志（配置阶段结束，进入 play）。
 * 主玩家连接 → 原逻辑（new ClientPacketListener）。
 *
 * ===== runWithResources hack（⚠️ 不要移除，否则矛戳刺服务端崩）=====
 * 单机 registry 对齐：假人（TCP 第二个连接）走网络收集构建的 registry 是"新实例"（与服务端不同源）→
 * 假人拿到矛的 DAMAGE_TYPE Holder 引用自己实例的 spear，服务端编码 ClientboundDamageEventPacket 时
 * 按 Holder 查服务端 registry 找不到 id → "Can't find id for .../damage_type/minecraft:spear" 崩。
 * 假人单机跳过收集（runWithResources 返回服务端完整实例），后续 filterRegistries 基于它的完整 keys
 * 从服务端取 → 假人编解码 = 服务端实例（含 spear/dimension，同源）。
 * 只在单机（集成服务器，同进程能拿到 server.registryAccess()）生效；多人远程走原逻辑。
 * 历史：d0535eb 最初修复；2026-08-06 曾误删（fabric 保留、neoforge 误删）→ 测试抓出矛戳刺崩，
 * 已恢复双端一致。改动本 Mixin 时务必保持双端对称，跑 combat-stab 回归。
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
