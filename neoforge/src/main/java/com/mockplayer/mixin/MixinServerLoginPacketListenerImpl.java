package com.mockplayer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 假人局域网登录短路：跳过服务端 hasJoined HTTP 鉴权。
 *
 * <p>集成服务器（单人/局域网）对 TCP 假人连接仍会执行
 * {@code MinecraftSessionService.hasJoinedServer}（请求 Mojang），网络差时单请求
 * 可挂几分钟，导致假人（尤其批量创建）长时间卡在 AUTHENTICATING。
 *
 * <p>这里对「单机服务器 + 用户名是本 mod 正在连接的假人」把
 * {@code MinecraftServer.usesAuthentication()} 改成 false，handleHello 走
 * 原版离线分支（createOfflineProfile），不再发加密握手、不再请求 Mojang，
 * 与主玩家（memory 连接）等价。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow
    private String requestedUsername;

    @WrapOperation(
            method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z")
    )
    private boolean mockplayer$skipAuthForFakeBots(MinecraftServer server, Operation<Boolean> original) {
        if (server.isSingleplayer() && isOurConnectingBot(this.requestedUsername)) {
            return false;
        }
        return original.call(server);
    }

    private static boolean isOurConnectingBot(String name) {
        if (name == null) {
            return false;
        }
        com.mockplayer.session.FakeSession session =
                com.mockplayer.session.SessionManager.getInstance().getSession(name);
        com.mockplayer.api.Bot bot = session != null ? session.getBot() : null;
        // CONNECTING = TCP 活跃但未进入 PLAY 阶段（登录/配置中）
        return bot != null && bot.getLifecycle() == com.mockplayer.api.BotLifecycle.CONNECTING;
    }
}
