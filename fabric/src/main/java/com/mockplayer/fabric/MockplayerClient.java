package com.mockplayer.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.FakePlayerNameArgument;
import com.mockplayer.session.SessionManager;
import com.mockplayer.session.ControlCommands;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
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
            dispatcher.register(ControlCommands.buildCommandTree(new ControlCommands.CommandFactory<FabricClientCommandSource>() {
                @Override
                public com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
                    return ClientCommands.literal(name);
                }

                @Override
                public com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, ?> argument(
                        String name, com.mojang.brigadier.arguments.ArgumentType<?> type) {
                    return ClientCommands.argument(name, type);
                }

                @Override
                public void sendFeedback(FabricClientCommandSource source, net.minecraft.network.chat.Component message) {
                    source.sendFeedback(message);
                }
            }));
            dispatcher.register(literal("newplayer")
                    .then(argument("name", FakePlayerNameArgument.fakePlayerName())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                ctx.getSource().sendFeedback(FakePlayerCommands.newPlayer(name));
                                return 1;
                            })));
            dispatcher.register(literal("delplayer")
                    .then(argument("name", FakePlayerNameArgument.fakePlayerName())
                            .suggests(FakePlayerCommands.fakePlayerNames())
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "name");
                                ctx.getSource().sendFeedback(FakePlayerCommands.delPlayer(name));
                                return 1;
                            })));
            dispatcher.register(literal("connect")
                    .then(argument("name", FakePlayerNameArgument.fakePlayerName())
                            .suggests(FakePlayerCommands.fakePlayerNames())
                            .then(argument("host", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name");
                                        String host = StringArgumentType.getString(ctx, "host");
                                        ctx.getSource().sendFeedback(FakePlayerCommands.connectPlayer(name, host, 25565));
                                        return 1;
                                    })
                                    .then(argument("port", IntegerArgumentType.integer(1, 65535))
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "name");
                                                String host = StringArgumentType.getString(ctx, "host");
                                                int port = IntegerArgumentType.getInteger(ctx, "port");
                                                ctx.getSource().sendFeedback(FakePlayerCommands.connectPlayer(name, host, port));
                                                return 1;
                                            })))));
        });
    }

    private void registerTick() {
        // 每 tick 驱动假人连接，保持在线
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            SessionManager.getInstance().tick();
        });
    }

    private void registerDisconnect() {
        // 主玩家断线 → 假人清理已由 MixinMinecraft 在 Minecraft.disconnect 统一处理
        // （能区分 transfer 空窗 vs 真退出，避免主玩家被传送到子服时误清假人）。
        // 这里仅过滤假人自己的断开（假人各自独立清理，无需事件处理）。
        // 保留监听以防 Mixin 未生效场景，但不再直接 clearAll。
    }
}
