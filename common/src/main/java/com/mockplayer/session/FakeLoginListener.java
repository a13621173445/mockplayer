package com.mockplayer.session;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

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
import net.minecraft.network.PacketSendListener;
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
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.ServerLinks;
import net.minecraft.util.Crypt;
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
        // 集成服务器（单人/局域网）usesAuthentication()=true 且假人连接非 memory → 服务端强制加密握手。
        // 复刻原版 ClientHandshakePacketListenerImpl.handleHello 的加密通道部分：
        //  1) 生成随机 AES 密钥，用服务端 RSA 公钥加密后回 ServerboundKeyPacket（构造器内部完成加密 + challenge 回应）
        //  2) 发包成功后切 AES 加解密（PacketSendListener.thenRun，channel 回调线程执行，与原版一致）
        // 离线/LAN 下 packet.shouldAuthenticate()=true，但假人无正版会话，跳过 Mojang joinServer 认证；
        // 服务端认证线程 hasJoinedServer 失败时 isSingleplayer()==true 豁免 → createOfflineProfile 放行。
        try {
            SecretKey secretKey = Crypt.generateSecretKey();
            PublicKey publicKey = packet.getPublicKey();
            Cipher cipherIn = Crypt.getCipher(2, secretKey);
            Cipher cipherOut = Crypt.getCipher(1, secretKey);
            ServerboundKeyPacket keyPacket = new ServerboundKeyPacket(secretKey, publicKey, packet.getChallenge());
            this.connection.send(keyPacket, PacketSendListener.thenRun(
                    () -> this.connection.setEncryptionKey(cipherIn, cipherOut)));
        } catch (Exception e) {
            FakeSession.LOG.warn("[{}] 加密握手失败: {}", name, e.toString());
            session.disconnect();
        }
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
        // 原版会回一个空答案，避免服务端等待自定义查询（mod 登录握手）而阻塞登录。
        this.connection.send(new ServerboundCustomQueryAnswerPacket(packet.transactionId(), null));
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
