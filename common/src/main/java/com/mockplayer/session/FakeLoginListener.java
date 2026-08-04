package com.mockplayer.session;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;

import com.mojang.authlib.GameProfile;

/**
 * 假人登录阶段 listener：处理握手/登录阶段的服务端包。
 * 离线模式下服务端 shouldAuthenticate()=false 跳过 Mojang 认证。
 *
 * P0：登录成功后切换到配置阶段（复用 MC 自带 listener），配置完成后进入 play 阶段保持在线。
 * 注意：P0 阶段假人会短暂接触全局单例，P1 用 Mixin fakeMode 挂锁修复。
 */
public class FakeLoginListener implements ClientLoginPacketListener {

    private final FakeSession session;
    private final Connection connection;
    private final String name;

    public FakeLoginListener(FakeSession session, Connection connection, String name) {
        this.session = session;
        this.connection = connection;
        this.name = name;
    }

    @Override
    public void handleHello(ClientboundHelloPacket packet) {
        // 离线模式：服务端 shouldAuthenticate()=false 时跳过 Mojang 认证，
        // 直接接受 hello。离线服通常直接发 LoginFinished。
        // （完整加密握手留到后续阶段；离线服一般不需要。）
    }

    @Override
    public void handleLoginFinished(ClientboundLoginFinishedPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, Minecraft.getInstance().packetProcessor());
        GameProfile profile = packet.gameProfile();
        session.setProfile(profile);
        FakeSession.LOG.info("[{}] 登录完成，profile={}", name, profile.name());

        // 切到配置阶段（复用 MC 自带 listener）
        Minecraft mc = Minecraft.getInstance();
        ServerData serverData = new ServerData(name, "mockplayer", ServerData.Type.OTHER);
        CommonListenerCookie cookie = new CommonListenerCookie(
                new LevelLoadTracker(),
                profile,
                new WorldSessionTelemetryManager(net.minecraft.client.telemetry.TelemetryEventSender.DISABLED, false, Duration.ZERO, "", UUID.randomUUID()),
                ClientRegistryLayer.createRegistryAccess().compositeAccess(),
                FeatureFlags.DEFAULT_FLAGS,
                null,
                serverData,
                null,
                Map.of(),
                null,
                Map.of(),
                ServerLinks.EMPTY,
                Map.<UUID, PlayerInfo>of(),
                false
        );

        this.connection.setupInboundProtocol(
                ConfigurationProtocols.CLIENTBOUND,
                new ClientConfigurationPacketListenerImpl(mc, this.connection, cookie)
        );
        this.connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
        this.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
        this.connection.send(new ServerboundCustomPayloadPacket(new BrandPayload(ClientBrandRetriever.getClientModName())));
        this.connection.send(new ServerboundClientInformationPacket(mc.options.buildPlayerInformation()));
    }

    @Override
    public void handleDisconnect(ClientboundLoginDisconnectPacket packet) {
        FakeSession.LOG.warn("[{}] 登录被拒绝: {}", name, packet.reason().getString());
        session.disconnect();
    }

    @Override
    public void handleCompression(ClientboundLoginCompressionPacket packet) {
        this.connection.setupCompression(packet.getCompressionThreshold(), false);
    }

    @Override
    public void handleCustomQuery(ClientboundCustomQueryPacket packet) {
        // 自定义查询（mod 握手）P0 不处理，直接忽略
    }

    @Override
    public boolean isAcceptingMessages() {
        return true;
    }

    @Override
    public void handleRequestCookie(ClientboundCookieRequestPacket packet) {
        // 忽略 cookie 请求
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.CLIENTBOUND;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        FakeSession.LOG.warn("[{}] 连接断开: {}", name, details.reason().getString());
        session.disconnect();
    }

    @Override
    public void fillListenerSpecificCrashDetails(CrashReport report, CrashReportCategory category) {
    }
}
