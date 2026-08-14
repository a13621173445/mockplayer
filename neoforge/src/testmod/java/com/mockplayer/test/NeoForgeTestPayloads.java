package com.mockplayer.test;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * neoforge 测试 payload 注册与发送（仅 testmod 环境）。
 *
 * RegisterPayloadHandlersEvent 在 mod 加载期触发（客户端物理端，集成服务器同 JVM）；
 * playBidirectional 一次注册双向 codec + 双端 handler。
 */
public final class NeoForgeTestPayloads {

    /** 客户端 handler 触发标志（主玩家污染检测：拦截生效时应为 false）。 */
    private static final AtomicBoolean CLIENT_FIRED = new AtomicBoolean();
    /** 服务端 handler 触发标志（sendModPayload 出站链路验证）。 */
    private static final AtomicBoolean SERVER_FIRED = new AtomicBoolean();

    private NeoForgeTestPayloads() {
    }

    /** 注册双向 payload（RegisterPayloadHandlersEvent 回调，双端 handler 一并注册）。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("mockplayer_test");
        // playBidirectional 参数顺序 = (serverboundHandler, clientboundHandler)（字节码实证）
        registrar.playBidirectional(
                TestPayloads.PayloadA.TYPE, TestPayloads.PayloadA.STREAM_CODEC,
                (payload, context) -> SERVER_FIRED.set(true),
                (payload, context) -> CLIENT_FIRED.set(true));
        registrar.playBidirectional(
                TestPayloads.PayloadB.TYPE, TestPayloads.PayloadB.STREAM_CODEC,
                (payload, context) -> SERVER_FIRED.set(true),
                (payload, context) -> CLIENT_FIRED.set(true));
    }

    /** 服务端向指定假人发送 payload_a（须在服务端线程调用，套件经 ctx.server 执行）。 */
    public static void sendToBot(String botName) {
        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer sp = srv.getPlayerList().getPlayerByName(botName);
        if (sp != null) {
            PacketDistributor.sendToPlayer(sp, new TestPayloads.PayloadA(
                    42, "hello", new TestPayloads.Nested(7, "deep"), List.of("a", "b")));
        }
    }

    /** 服务端向主玩家发送 payload_a（对照组）。 */
    public static void sendToMainPlayer() {
        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer sp = srv.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
        if (sp != null) {
            PacketDistributor.sendToPlayer(sp, new TestPayloads.PayloadA(
                    1, "main", new TestPayloads.Nested(2, "m"), List.of("x")));
        }
    }

    /** 服务端向指定假人一次发送 count 个 payload_b（count 字段递增，上限截断用例用）。 */
    public static void sendBToBot(String botName, int count) {
        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer sp = srv.getPlayerList().getPlayerByName(botName);
        if (sp != null) {
            for (int i = 0; i < count; i++) {
                try {
                    PacketDistributor.sendToPlayer(sp, new TestPayloads.PayloadB(i % 2 == 0, i));
                } catch (Exception e) {
                    System.out.println("[mocktest] sendB[" + i + "] failed: " + e);
                    break;
                }
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
