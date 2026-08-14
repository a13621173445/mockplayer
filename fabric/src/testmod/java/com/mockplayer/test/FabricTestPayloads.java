package com.mockplayer.test;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * fabric 测试 payload 注册与发送（仅 testmod 环境）。
 *
 * 集成服务器与客户端同 JVM：客户端注册（解码 clientbound + 编码 serverbound + handler）
 * 与服务端注册（编码 clientbound 发送 + 接收 serverbound）都在客户端初始化时完成。
 */
public final class FabricTestPayloads {

    /** 客户端 handler 触发标志（主玩家污染检测：拦截生效时应为 false）。 */
    private static final AtomicBoolean CLIENT_FIRED = new AtomicBoolean();
    /** 服务端 handler 触发标志（sendModPayload 出站链路验证）。 */
    private static final AtomicBoolean SERVER_FIRED = new AtomicBoolean();

    private FabricTestPayloads() {
    }

    /** 注册双向 codec + 双端 handler（在 onInitializeClient 调用，早于任何连接）。 */
    public static void register() {
        // 客户端侧：解码 clientbound + 接收 handler
        PayloadTypeRegistry.clientboundPlay().register(
                TestPayloads.PayloadA.TYPE, TestPayloads.PayloadA.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                TestPayloads.PayloadB.TYPE, TestPayloads.PayloadB.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(
                TestPayloads.PayloadA.TYPE, (payload, context) -> CLIENT_FIRED.set(true));
        ClientPlayNetworking.registerGlobalReceiver(
                TestPayloads.PayloadB.TYPE, (payload, context) -> CLIENT_FIRED.set(true));
        // 客户端侧：编码 serverbound（sendModPayload 发送链路）
        PayloadTypeRegistry.serverboundPlay().register(
                TestPayloads.PayloadA.TYPE, TestPayloads.PayloadA.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                TestPayloads.PayloadB.TYPE, TestPayloads.PayloadB.STREAM_CODEC);
        // 服务端侧（集成服务器，同 JVM）：接收 serverbound（clientbound codec 已在上方注册，
        // fabric 的 PayloadTypeRegistry 是全局单例，服务端发送复用同一注册）
        ServerPlayNetworking.registerGlobalReceiver(
                TestPayloads.PayloadA.TYPE, (payload, context) -> SERVER_FIRED.set(true));
        ServerPlayNetworking.registerGlobalReceiver(
                TestPayloads.PayloadB.TYPE, (payload, context) -> SERVER_FIRED.set(true));
    }

    /** 服务端向指定假人发送 payload_a（须在服务端线程调用，套件经 ctx.server 执行）。 */
    public static void sendToBot(String botName) {
        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer sp = srv.getPlayerList().getPlayerByName(botName);
        if (sp != null) {
            ServerPlayNetworking.send(sp, new TestPayloads.PayloadA(
                    42, "hello", new TestPayloads.Nested(7, "deep"), List.of("a", "b")));
        }
    }

    /** 服务端向主玩家发送 payload_a（对照组）。 */
    public static void sendToMainPlayer() {
        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer sp = srv.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
        if (sp != null) {
            ServerPlayNetworking.send(sp, new TestPayloads.PayloadA(
                    1, "main", new TestPayloads.Nested(2, "m"), List.of("x")));
        }
    }

    /** 服务端向指定假人一次发送 count 个 payload_b（count 字段递增，上限截断用例用）。 */
    public static void sendBToBot(String botName, int count) {
        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer sp = srv.getPlayerList().getPlayerByName(botName);
        if (sp != null) {
            for (int i = 0; i < count; i++) {
                ServerPlayNetworking.send(sp, new TestPayloads.PayloadB(i % 2 == 0, i));
            }
        }
    }

    public static boolean isClientFired() {
        return CLIENT_FIRED.get();
    }

    public static boolean isServerFired() {
        return SERVER_FIRED.get();
    }

    public static void reset() {
        CLIENT_FIRED.set(false);
        SERVER_FIRED.set(false);
    }
}
