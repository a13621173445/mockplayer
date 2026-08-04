package com.mockplayer.session;

import com.mockplayer.Constants;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.LoginProtocols;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 假人会话：持有独立的网络连接，负责离线登录到当前服务器并保持在线。
 *
 * P0 目标：假人能登录上线（服务端 Tab 可见、客户端能看到实体）。
 * 连接流程复用 MC 自带类：Connection + 登录协议，不重写网络层。
 */
public class FakeSession {

    public static final Logger LOG = LoggerFactory.getLogger("Mockplayer/FakeSession");

    /** 假人名字 */
    private final String name;
    /** 独立网络连接 */
    private Connection connection;
    /** 当前是否已连接（TCP 层面） */
    private boolean connected;
    /** 服务端下发的登录 profile */
    private com.mojang.authlib.GameProfile profile;
    /** 假人 play 阶段 listener（进 play 后设置） */
    private FakePlayListener playListener;
    /** 假人完整状态（无头但完整感知） */
    private final FakePlayerState state = new FakePlayerState();
    /** 假人自己的 LocalPlayer（复用物理），进 play 后创建 */
    private net.minecraft.client.player.LocalPlayer fakePlayer;

    public FakeSession(String name) {
        this.name = name;
    }

    /** 登录成功后由 listener 回调设置 */
    void setProfile(com.mojang.authlib.GameProfile profile) {
        this.profile = profile;
    }

    /** play 阶段 listener 设置（进 play 后由配置 Mixin 回调） */
    void setPlayListener(FakePlayListener playListener) {
        this.playListener = playListener;
    }

    /** 假人 LocalPlayer 设置（由 Mixin 在假人 handleLogin 时创建） */
    public void setFakePlayer(net.minecraft.client.player.LocalPlayer fakePlayer) {
        this.fakePlayer = fakePlayer;
    }

    public net.minecraft.client.player.LocalPlayer getFakePlayer() {
        return this.fakePlayer;
    }

    public FakePlayerState getState() {
        return this.state;
    }

    public FakePlayListener getPlayListener() {
        return this.playListener;
    }

    /**
     * 发起离线登录：连接当前客户端所在的服务器，用假人名字登录。
     * 在独立线程执行（网络阻塞），登录成功后回调到渲染线程。
     */
    public void connect() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LOG.warn("[{}] 当前不在服务器中，无法创建假人", name);
            return;
        }

        // 从当前连接拿到服务器地址。优先用当前 ClientPacketListener 的连接。
        String host = "127.0.0.1";
        int port = 25565;
        Connection main = mc.getConnection() != null ? mc.getConnection().getConnection() : null;
        if (main != null) {
            java.net.SocketAddress addr = main.getRemoteAddress();
            if (addr instanceof java.net.InetSocketAddress inet) {
                host = inet.getHostString();
                port = inet.getPort();
            }
        }

        final String fHost = host;
        final int fPort = port;
        Thread thread = new Thread(() -> doConnect(fHost, fPort), "Mockplayer Connector #" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** 实际连接逻辑（网络线程） */
    private void doConnect(String host, int port) {
        try {
            Optional<InetSocketAddress> resolved = ServerNameResolver.DEFAULT
                    .resolveAddress(new ServerAddress(host, port))
                    .map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isEmpty()) {
                LOG.warn("[{}] 无法解析服务器地址 {}", name, host);
                return;
            }

            InetSocketAddress address = resolved.get();
            Connection conn = new Connection(PacketFlow.CLIENTBOUND);
            conn.setBandwidthLogger(Minecraft.getInstance().getDebugOverlay().getBandwidthLogger());
            this.connection = conn;

            // 标记为假人连接（供平台 Mixin 识别，创建 FakePlayListener）
            FakeConnectionRegistry.markFake(conn, this);

            // 建连（阻塞直到 TCP 建立）
            Connection.connect(address, net.minecraft.server.network.EventLoopGroupHolder.remote(Minecraft.getInstance().options.useNativeTransport()), conn)
                    .syncUninterruptibly();

            // 发起登录握手，用一个自定义登录 listener 处理登录阶段
            FakeLoginListener loginListener = new FakeLoginListener(this, conn, name);
            conn.initiateServerboundPlayConnection(
                    address.getHostName(),
                    address.getPort(),
                    LoginProtocols.SERVERBOUND,
                    LoginProtocols.CLIENTBOUND,
                    loginListener,
                    false
            );

            // 离线登录：发 hello 包，带名字和离线 UUID
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.send(new ServerboundHelloPacket(name, offlineUuid));

            connected = true;
            LOG.info("[{}] 假人连接已建立，等待登录完成", name);
        } catch (Exception e) {
            LOG.error("[{}] 假人连接失败", name, e);
            disconnect();
        }
    }

    /**
     * 驱动连接 tick（必须在渲染线程每 tick 调用），保持 keepalive/收发包。
     * 同时驱动假人 LocalPlayer 物理（重力/移动/碰撞 + 发移动包），反作弊合规。
     */
    public void tick() {
        if (connection != null && connection.isConnected()) {
            connection.tick();
        } else if (connection != null && !connected) {
            // 已断开
            connection = null;
        }

        // 驱动假人物理（独立 LocalPlayer，完整物理）
        if (this.fakePlayer != null) {
            try {
                this.fakePlayer.tick();
            } catch (Exception e) {
                LOG.error("[{}] 假人物理 tick 出错", name, e);
            }
        }
    }

    /**
     * 断开假人连接。
     */
    public void disconnect() {
        if (connection != null) {
            FakeConnectionRegistry.unmarkFake(connection);
            connection.disconnect(net.minecraft.network.chat.Component.literal("Fake player removed"));
            connection = null;
        }
        connected = false;
        LOG.info("[{}] 假人已移除", name);
    }

    public String getName() {
        return name;
    }

    public boolean isConnected() {
        return connected && connection != null && connection.isConnected();
    }
}
