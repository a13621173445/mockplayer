package com.mockplayer.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.mockplayer.Constants;
import com.mockplayer.config.CommandTreeReloader;
import com.mockplayer.config.ModCommands;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.ModConfigScreenFactory;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.FakePlayerNameArgument;
import com.mockplayer.session.SessionManager;
import com.mockplayer.session.ControlCommands;
import com.mockplayer.session.QueryCommands;
import com.mockplayer.session.CommandSupport;

import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.CommandBuildContext;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashSet;
import java.util.Set;

/**
 * NeoForge 客户端专属入口：注册假人命令 + 驱动假人连接 tick。
 * 仅物理客户端加载（dist = Dist.CLIENT）。
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class MockplayerNeoForgeClient {

    /** 本 mod 当前已注册的根命令名（热重载时先移除旧的）。 */
    private static final Set<String> registeredRoots = new HashSet<>();

    public MockplayerNeoForgeClient(IEventBus modBus, ModContainer container) {
        // RegisterClientCommandsEvent / ClientTickEvent.Post / ClientPlayerNetworkEvent.LoggingOut 都是
        // GAME 事件（发在 NeoForge.EVENT_BUS），不是 IModBusEvent——注册到 mod bus 会抛
        // "This bus only accepts subclasses of IModBusEvent"。必须注册到 NeoForge.EVENT_BUS。
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::registerCommands);
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::onPlayerLogout);
        // 配置保存/重载后立即重建命令树（GUI 保存即热重载）
        MockplayerConfig.onReload(MockplayerNeoForgeClient::reloadCommands);
        // YACL 可选：缺 YACL 时模组列表不出现「配置」按钮，配置仍可手改 JSON（零崩溃）
        if (ModList.get().isLoaded("yet_another_config_lib_v3")) {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (java.util.function.Supplier<IConfigScreenFactory>)
                            () -> (modContainer, parent) -> {
                                // 反射桥：类加载验证不直接引用 YACL 类，缺 YACL 也不会 NoClassDefFoundError
                                net.minecraft.client.gui.screens.Screen screen =
                                        ModConfigScreenFactory.create(parent);
                                return screen != null ? screen : new MissingYaclScreen(parent);
                            });
        }
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        registerAllCommands(dispatcher);
    }

    /** 按当前配置注册全部根命令（可重入：事件注册与热重载共用）。 */
    private static void registerAllCommands(CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
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
    private static void registerRoot(CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
                                     String name, LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> tree) {
        if (ModCommands.isDisabled(name)) {
            return;
        }
        dispatcher.register(tree);
        registeredRoots.add(name);
    }

    private static LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> newPlayerTree(String rootName) {
        return Commands.literal(rootName)
                .then(Commands.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.newPlayer(name), false);
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> delPlayerTree(String rootName) {
        return Commands.literal(rootName)
                .then(Commands.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .suggests(FakePlayerCommands.fakePlayerNames())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            ctx.getSource().sendSuccess(() -> FakePlayerCommands.delPlayer(name), false);
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> connectTree(String rootName) {
        return Commands.literal(rootName)
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
                                        }))));
    }

    /**
     * 配置热重载：从执行层移除旧根 → 重建客户端命令并合并服务端命令写回。
     * NeoForge 的事件 dispatcher 每次重建是临时对象，正确入口是
     * ClientCommandHandler.mergeServerCommands（@ApiStatus.Internal，版本升级时留意）：
     * 它重新触发 RegisterClientCommandsEvent（自动用新配置注册）并返回合并后的执行 dispatcher。
     */
    private static void reloadCommands() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        Set<String> oldRoots = new HashSet<>(registeredRoots);
        @SuppressWarnings("rawtypes")
        CommandDispatcher exec = connection.getCommands();
        CommandTreeReloader.removeRoots(exec, oldRoots);
        CommandDispatcher<ClientSuggestionProvider> merged = ClientCommandHandler.mergeServerCommands(
                exec,
                CommandBuildContext.simple(connection.registryAccess(), connection.enabledFeatures()));
        connection.commands = merged;
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
