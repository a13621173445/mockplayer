package com.mockplayer.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;

import com.mockplayer.Constants;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.SessionManager;

import net.minecraft.commands.Commands;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * NeoForge 客户端专属入口：注册假人命令 + 驱动假人连接 tick。
 * 仅物理客户端加载（dist = Dist.CLIENT）。
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class MockplayerNeoForgeClient {

    public MockplayerNeoForgeClient(IEventBus eventBus) {
        eventBus.addListener(MockplayerNeoForgeClient::registerCommands);
        eventBus.addListener(MockplayerNeoForgeClient::onClientTick);
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("newplayer")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.newPlayer(name), false);
                            return 1;
                        })));
        dispatcher.register(Commands.literal("delplayer")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.delPlayer(name), false);
                            return 1;
                        })));
        dispatcher.register(Commands.literal("control")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.control(name), false);
                            return 1;
                        })));
        dispatcher.register(Commands.literal("fakelist")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> FakePlayerCommands.listPlayers(), false);
                    return 1;
                }));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        // 每 tick 驱动假人连接，保持在线
        SessionManager.getInstance().tick();
    }
}
