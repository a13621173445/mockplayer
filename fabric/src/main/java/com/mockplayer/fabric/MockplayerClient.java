package com.mockplayer.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.FakePlayListener;
import com.mockplayer.session.SessionManager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.commands.CommandBuildContext;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Fabric 客户端入口：注册假人命令 + 驱动假人连接 tick。
 */
public class MockplayerClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        registerCommands();
        registerTick();
        registerDisconnect();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("newplayer")
                    .then(argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                ctx.getSource().sendFeedback(FakePlayerCommands.newPlayer(name));
                                return 1;
                            })));
            dispatcher.register(literal("delplayer")
                    .then(argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                ctx.getSource().sendFeedback(FakePlayerCommands.delPlayer(name));
                                return 1;
                            })));
            dispatcher.register(literal("control")
                    .then(argument("name", StringArgumentType.word())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                ctx.getSource().sendFeedback(FakePlayerCommands.control(name));
                                return 1;
                            })));
            dispatcher.register(literal("fakelist")
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(FakePlayerCommands.listPlayers());
                        return 1;
                    }));
        });
    }

    private void registerTick() {
        // 每 tick 驱动假人连接，保持在线
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            SessionManager.getInstance().tick();
        });
    }

    private void registerDisconnect() {
        // 主玩家断开服务器 → 全部假人下线。
        // 注意：FakePlayListener extends ClientPacketListener，假人连接断开也会触发本事件，
        // 必须过滤掉假人自己的 listener——否则踢一个假人会误清所有假人（假人各自独立清理）。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            if (handler instanceof FakePlayListener) {
                return;
            }
            SessionManager.getInstance().clearAll();
        });
    }
}
