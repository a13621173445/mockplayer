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
        // 会话可能已在连接完成前被删除：放弃登录，不进入配置阶段（防孤儿 level 泄漏）
        if (session.isDisposed()) {
            FakeSession.LOG.warn("[{}] 会话已删除，放弃登录", name);
            this.connection.disconnect(
                    Component.translatable("disconnect.mockplayer.fake_player_removed"));
            return;
        }
        GameProfile profile = packet.gameProfile();
        session.setProfile(profile);
        FakeSession.LOG.info("[{}] 登录完成，profile={}", name, profile.name());
        // 进入配置阶段：neoforge RegistryManager.applySnapshot 会覆盖 BuiltInRegistries.BLOCK，
        // 配置阶段置位让 neoforge Mixin 跳过假人的 registry snapshot（见 FakeConnectionRegistry.configuringFake）
        FakeConnectionRegistry.setConfiguringFake(true);

        // 切到配置阶段（复用 MC 自带 listener）
        Minecraft mc = Minecraft.getInstance();
        ServerData serverData = new ServerData(name, "mockplayer", ServerData.Type.OTHER);
        // 跟随传送（transfer）时带上原版一致的 cookies / seenPlayers / seenInsecureChatWarning
        net.minecraft.client.multiplayer.TransferState transfer = session.getPendingTransfer();
        java.util.Map<net.minecraft.resources.Identifier, byte[]> cookies =
                transfer != null ? transfer.cookies() : Map.of();
        java.util.Map<java.util.UUID, PlayerInfo> seenPlayers =
                transfer != null ? transfer.seenPlayers() : Map.of();
        boolean seenInsecureWarning =
                transfer != null && transfer.seenInsecureChatWarning();
        // 假人 cookie 必须复用主玩家 play registryAccess（单机/局域网），不能用 ClientRegistryLayer
        // 基础层。原因：neoforge 服务端对假人（TCP）发 RegistrySnapshot 包，服务端按假人 cookie 的
        // registry 打包 snapshot，客户端 ClientPayloadHandler → RegistryManager.applySnapshot 会把
        // BuiltInRegistries.BLOCK 的 tags 覆盖成 snapshot 内容。若 cookie 是 ClientRegistryLayer
        // （block 基础态 16 tags），服务端打包 16 → applySnapshot 覆盖 395 → 原版配置阶段
        // loadNewElementsAndTags 解析数据包缺 tag（infiniburn_overworld 等）→ Registry Loading 崩。
        // 主玩家（内存连接）不走 neoforge 网络同步所以不受影响。多人远程保持原版默认（后续阶段）。
        net.minecraft.core.RegistryAccess.Frozen registryAccess =
                mc.getSingleplayerServer() != null && mc.getConnection() != null
                        ? mc.getConnection().registryAccess()
                        : ClientRegistryLayer.createRegistryAccess().compositeAccess();
        CommonListenerCookie cookie = new CommonListenerCookie(
                new LevelLoadTracker(),
                profile,
                new WorldSessionTelemetryManager(net.minecraft.client.telemetry.TelemetryEventSender.DISABLED, false, Duration.ZERO, ""),
                registryAccess,
                FeatureFlags.DEFAULT_FLAGS,
                null,
                serverData,
                null,
                cookies,
                null,
                Map.of(),
                ServerLinks.EMPTY,
                seenPlayers,
                seenInsecureWarning
        );

        this.connection.setupInboundProtocol(
                ConfigurationProtocols.CLIENTBOUND,
                new ClientConfigurationPacketListenerImpl(mc, this.connection, cookie)
        );
        this.connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
        this.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
        this.connection.send(new ServerboundCustomPayloadPacket(new BrandPayload(ClientBrandRetriever.getClientModName())));
        // 假人用配置默认区块加载半径（默认 2，节约性能），不再直接继承主玩家 options 的渲染距离
        net.minecraft.server.level.ClientInformation base = mc.options.buildPlayerInformation();
        int chunkRadius = com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius();
        net.minecraft.server.level.ClientInformation info = new net.minecraft.server.level.ClientInformation(
                base.language(), chunkRadius, base.chatVisibility(), base.chatColors(),
                base.modelCustomisation(), base.mainHand(),
                base.textFilteringEnabled(), base.allowsListing(), base.particleStatus());
        session.setClientInformation(info);
        this.connection.send(new ServerboundClientInformationPacket(info));
    }

    @Override
    public void handleDisconnect(ClientboundLoginDisconnectPacket packet) {
        FakeSession.LOG.warn("[{}] 登录被拒绝: {}", name, packet.reason().getString());
        session.disconnect();
        if (!session.isReconnecting()) {
            com.mockplayer.session.SessionManager.getInstance().removeFakePlayer(name);
        }
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
        if (!session.isReconnecting()) {
            com.mockplayer.session.SessionManager.getInstance().removeFakePlayer(name);
        }
    }

    @Override
    public void fillListenerSpecificCrashDetails(CrashReport report, CrashReportCategory category) {
    }
}
