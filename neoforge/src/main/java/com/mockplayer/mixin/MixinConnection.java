package com.mockplayer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.Packet;

import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakeLoginListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.ChannelHandlerContext;

/**
 * 假人连接的所有收包统一路由到渲染线程（主玩家 Minecraft.packetProcessor）再处理。
 *
 * 原版 Connection.channelRead0 在 Netty IO 线程直接 packet.handle(listener)，只有 handler 内
 * ensureRunningOnSameThread 的包才转渲染线程。若假人 handleLogin 在 Netty 线程创建假人 level，
 * neoforge ModelDataManager 绑定 Netty 线程，渲染线程操作假人 level 崩；若 login 转渲染线程而后续包
 * （难度等）仍在 Netty 线程读 levelData 又崩（线程不一致）。统一全包转渲染线程，保证假人 level 生命周期
 * 与全部 handler 同线程（渲染线程）、顺序 FIFO，双端稳定。路由范围是配置 + play 阶段
 * （登录阶段除外：handleCompression 等原版在 Netty 线程直接操作 pipeline，转渲染线程会
 * 和 Netty 并发改 pipeline 崩 splitter 找不到）：配置阶段 neoforge 的
 * ModdedNetworkQuery 处理若在 Netty 线程
 * 立即回包，而渲染线程的 setupOutboundProtocol 还没执行 → "Pipeline has no outbound protocol
 * configured" 断连（Linux/Epoll 必现）——全部转渲染线程后，handleLoginFinished
 * （ack + 出站配置）先完成，配置包后处理，无竞态。
 */
@Mixin(Connection.class)
public abstract class MixinConnection {

    @Inject(method = "channelRead0", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void mockplayer$routeFakeToRenderThread(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        PacketListener listener = self.getPacketListener();
        if (FakeConnectionRegistry.isFake(self) && !(listener instanceof FakeLoginListener)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && !mc.packetProcessor().isSameThread()) {
                ((PacketProcessor) mc.packetProcessor()).scheduleIfPossible(listener, (Packet) packet);
                ci.cancel();
            }
        }
    }

    /**
     * 假人连接出站 mod payload 记录（只记录，不阻止发送，服务端无感知）。
     *
     * 入站拦截（FakePlayListener.handleCustomPayload）与出站记录（本注入）构成完整观测面：
     * mod 客户端 tick 自动发的心跳/报到/状态上报也能查到（Bot.getSentModPayloads）。
     *
     * 只注入单参 send(Packet)（mod 发送链路的主入口；双参重载由它内部委托，避免重复记录）。
     * minecraft: namespace（brand 等 vanilla payload）不记录。
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void mockplayer$recordFakeOutboundModPayload(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket custom)) {
            return;
        }
        Connection self = (Connection) (Object) this;
        com.mockplayer.session.FakeSession session = FakeConnectionRegistry.getSession(self);
        if (session == null || !self.isConnected()
                || !com.mockplayer.config.MockplayerConfig.get().isPayloadSendLogEnabled()) {
            return;
        }
        net.minecraft.network.protocol.common.custom.CustomPacketPayload payload = custom.payload();
        net.minecraft.resources.Identifier id = payload.type().id();
        if ("minecraft".equals(id.getNamespace())) {
            return;
        }
        long tick = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
        session.getState().recordSentModPayload(new com.mockplayer.api.ModPayloadInfo(
                id,
                id.getNamespace(),
                com.mockplayer.platform.Services.PLATFORM.getModDisplayName(id.getNamespace()),
                tick,
                com.mockplayer.session.PayloadInspector.estimateSize(payload)));
    }
}
