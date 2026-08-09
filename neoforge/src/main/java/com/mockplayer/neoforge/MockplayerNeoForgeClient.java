package com.mockplayer.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.mockplayer.Constants;
import com.mockplayer.config.ModConfigScreen;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.FakePlayerNameArgument;
import com.mockplayer.session.SessionManager;
import com.mockplayer.session.ControlCommands;
import com.mockplayer.session.QueryCommands;
import com.mockplayer.session.CommandSupport;

import net.minecraft.commands.Commands;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 客户端专属入口：注册假人命令 + 驱动假人连接 tick。
 * 仅物理客户端加载（dist = Dist.CLIENT）。
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class MockplayerNeoForgeClient {

    public MockplayerNeoForgeClient(IEventBus modBus, ModContainer container) {
        // RegisterClientCommandsEvent / ClientTickEvent.Post / ClientPlayerNetworkEvent.LoggingOut 都是
        // GAME 事件（发在 NeoForge.EVENT_BUS），不是 IModBusEvent——注册到 mod bus 会抛
        // "This bus only accepts subclasses of IModBusEvent"。必须注册到 NeoForge.EVENT_BUS。
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::registerCommands);
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::onPlayerLogout);
        // YACL 可选：缺 YACL 时模组列表不出现「配置」按钮，配置仍可手改 JSON（零崩溃）
        if (ModList.get().isLoaded("yet_another_config_lib_v3")) {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (java.util.function.Supplier<IConfigScreenFactory>)
                            () -> (modContainer, parent) -> new ModConfigScreen(parent));
        }
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        dispatcher.register(ControlCommands.buildControlTree(factory()));
        dispatcher.register(QueryCommands.buildQueryTree(factory()));
        dispatcher.register(Commands.literal("newplayer")
                .then(Commands.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.newPlayer(name), false);
                            return 1;
                        })));
        dispatcher.register(Commands.literal("delplayer")
                .then(Commands.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .suggests(FakePlayerCommands.fakePlayerNames())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.delPlayer(name), false);
                            return 1;
                        })));
        dispatcher.register(Commands.literal("connect")
                .then(Commands.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .suggests(FakePlayerCommands.fakePlayerNames())
                        .then(Commands.argument("host", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String host = StringArgumentType.getString(ctx, "host");
                                    ctx.getSource().sendSuccess(() -> FakePlayerCommands.connectPlayer(name, host, 25565), false);
                                    return 1;
                                })
                                .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String host = StringArgumentType.getString(ctx, "host");
                                            int port = IntegerArgumentType.getInteger(ctx, "port");
                                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.connectPlayer(name, host, port), false);
                                            return 1;
                                        })))));
    }

    /** 双端共用命令树工厂：NeoForge 提供 literal/argument/反馈函数。 */
    private static CommandSupport.CommandFactory<net.minecraft.commands.CommandSourceStack> factory() {
        return new CommandSupport.CommandFactory<net.minecraft.commands.CommandSourceStack>() {
            @Override
            public com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> literal(String name) {
                return Commands.literal(name);
            }

            @Override
            public com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, ?> argument(
                    String name, com.mojang.brigadier.arguments.ArgumentType<?> type) {
                return Commands.argument(name, type);
            }

            @Override
            public void sendFeedback(net.minecraft.commands.CommandSourceStack source, net.minecraft.network.chat.Component message) {
                source.sendSuccess(() -> message, false);
            }
        };
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        // 每 tick 驱动假人连接，保持在线
        SessionManager.getInstance().tick();
    }

    private static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // 主玩家断线 → 假人清理已由 MixinMinecraft 在 Minecraft.disconnect 统一处理
        // （能区分 transfer 空窗 vs 真退出，避免主玩家被传送到子服时误清假人）。
        // 假人连接断开也触发本事件，但假人各自独立清理（cleanupOnKick），这里无需处理。
    }
}
