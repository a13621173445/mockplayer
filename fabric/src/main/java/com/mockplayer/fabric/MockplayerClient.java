package com.mockplayer.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.mockplayer.config.CommandTreeReloader;
import com.mockplayer.config.ModCommands;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.FakePlayerNameArgument;
import com.mockplayer.session.SessionManager;
import com.mockplayer.session.ControlCommands;
import com.mockplayer.session.QueryCommands;
import com.mockplayer.session.CommandSupport;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.HashSet;
import java.util.Set;

/**
 * Fabric 客户端入口：注册假人命令 + 驱动假人连接 tick。
 */
public class MockplayerClient implements ClientModInitializer {

    /** 当前会话的命令 dispatcher（Fabric 每次连接重建，保存引用供热重载）。 */
    private static CommandDispatcher<FabricClientCommandSource> lastDispatcher;
    /** 本 mod 当前已注册的根命令名（热重载时先移除旧的）。 */
    private static final Set<String> registeredRoots = new HashSet<>();

    @Override
    public void onInitializeClient() {
        // 配置保存/重载后立即重建命令树（GUI 保存即热重载）
        MockplayerConfig.onReload(MockplayerClient::reloadCommands);
        registerCommands();
        registerTick();
        registerDisconnect();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            lastDispatcher = dispatcher;
            registerAllCommands(dispatcher);
        });
    }

    /** 按当前配置注册全部根命令（可重入：首次注册与热重载共用）。 */
    private static void registerAllCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        registeredRoots.clear();
        ModConfig cfg = MockplayerConfig.get();
        registerRoot(dispatcher, cfg.getCommandName(ModCommands.CONTROL),
                ControlCommands.buildControlTree(factory(), cfg.getCommandName(ModCommands.CONTROL)));
        registerRoot(dispatcher, cfg.getCommandName(ModCommands.QUERY),
                QueryCommands.buildQueryTree(factory(), cfg.getCommandName(ModCommands.QUERY)));
        registerRoot(dispatcher, cfg.getCommandName(ModCommands.NEWPLAYER),
                newPlayerTree(cfg.getCommandName(ModCommands.NEWPLAYER)));
        registerRoot(dispatcher, cfg.getCommandName(ModCommands.DELPLAYER),
                delPlayerTree(cfg.getCommandName(ModCommands.DELPLAYER)));
        registerRoot(dispatcher, cfg.getCommandName(ModCommands.CONNECT),
                connectTree(cfg.getCommandName(ModCommands.CONNECT)));
    }

    /** 注册单个根命令；禁用（空名）则跳过。 */
    private static void registerRoot(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                     String name, LiteralArgumentBuilder<FabricClientCommandSource> tree) {
        if (ModCommands.isDisabled(name)) {
            return;
        }
        dispatcher.register(tree);
        registeredRoots.add(name);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> newPlayerTree(String rootName) {
        return literal(rootName)
                .then(argument("name", FakePlayerNameArgument.fakePlayerName())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendFeedback(FakePlayerCommands.newPlayer(name));
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> delPlayerTree(String rootName) {
        return literal(rootName)
                .then(argument("name", FakePlayerNameArgument.fakePlayerName())
                        .suggests(FakePlayerCommands.fakePlayerNames())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendFeedback(FakePlayerCommands.delPlayer(name));
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> connectTree(String rootName) {
        return literal(rootName)
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
                                        }))));
    }

    /**
     * 配置热重载：移除旧根 → 按新配置注册 → 同步执行层。
     * Fabric 客户端命令分两层：activeDispatcher（注册层）与 ClientPacketListener.commands（执行层），
     * 两层都要移除旧根，再调 ClientCommandInternals.addCommands 把新树拷进执行层。
     */
    private static void reloadCommands() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = lastDispatcher;
        if (dispatcher == null) {
            return;
        }
        Set<String> oldRoots = new HashSet<>(registeredRoots);
        CommandTreeReloader.removeRoots(dispatcher, oldRoots);
        registerAllCommands(dispatcher);
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            @SuppressWarnings("rawtypes")
            CommandDispatcher exec = connection.getCommands();
            CommandTreeReloader.removeRoots(exec, oldRoots);
            // impl 类（fabric-command-api-v2 内部）：把 activeDispatcher 拷贝进执行层，
            // 与 Fabric 每次进世界时的同步路径一致（@ApiStatus.Internal，版本升级时留意）
            ClientCommandInternals.addCommands(
                    exec,
                    (FabricClientCommandSource) connection.getSuggestionsProvider());
        }
    }

    /** 双端共用命令树工厂：Fabric 提供 literal/argument/反馈函数。 */
    private static CommandSupport.CommandFactory<FabricClientCommandSource> factory() {
        return new CommandSupport.CommandFactory<FabricClientCommandSource>() {
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
        };
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
