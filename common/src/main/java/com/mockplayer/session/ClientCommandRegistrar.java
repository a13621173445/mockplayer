package com.mockplayer.session;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.SharedSuggestionProvider;

import java.util.Set;

/**
 * 双端共用的客户端命令树注册器。
 *
 * 输入：平台提供的 {@link CommandSupport.CommandFactory}（literal/argument/反馈函数）
 * 输出：按当前配置注册 control/query/newplayer/delplayer/connect 全部根命令。
 *
 * Fabric/NeoForge 入口只保留「注册时机 + 热重载 + factory 实现」，
 * 根树构建与注册循环在 common 只写一份，杜绝双端逐字复制。
 */
public final class ClientCommandRegistrar {

    private ClientCommandRegistrar() {
    }

    /**
     * 按当前配置注册全部根命令（可重入：首次注册与热重载共用）。
     *
     * @param dispatcher     平台命令 dispatcher
     * @param f              平台命令工厂
     * @param registeredRoots 已注册根名集合（热重载时先移除旧根）
     */
    public static <S extends SharedSuggestionProvider> void registerAll(
            CommandDispatcher<S> dispatcher,
            CommandSupport.CommandFactory<S> f,
            Set<String> registeredRoots) {
        registeredRoots.clear();
        com.mockplayer.config.ModConfig cfg = com.mockplayer.config.MockplayerConfig.get();
        registerRoot(dispatcher,
                cfg.getCommandName(com.mockplayer.config.ModCommands.CONTROL),
                ControlCommands.buildControlTree(f, cfg.getCommandName(com.mockplayer.config.ModCommands.CONTROL)),
                registeredRoots);
        registerRoot(dispatcher,
                cfg.getCommandName(com.mockplayer.config.ModCommands.QUERY),
                QueryCommands.buildQueryTree(f, cfg.getCommandName(com.mockplayer.config.ModCommands.QUERY)),
                registeredRoots);
        registerRoot(dispatcher,
                cfg.getCommandName(com.mockplayer.config.ModCommands.NEWPLAYER),
                newPlayerTree(f, cfg.getCommandName(com.mockplayer.config.ModCommands.NEWPLAYER)),
                registeredRoots);
        registerRoot(dispatcher,
                cfg.getCommandName(com.mockplayer.config.ModCommands.DELPLAYER),
                delPlayerTree(f, cfg.getCommandName(com.mockplayer.config.ModCommands.DELPLAYER)),
                registeredRoots);
        registerRoot(dispatcher,
                cfg.getCommandName(com.mockplayer.config.ModCommands.CONNECT),
                connectTree(f, cfg.getCommandName(com.mockplayer.config.ModCommands.CONNECT)),
                registeredRoots);
    }

    /** 注册单个根命令；禁用（空名）则跳过。 */
    private static <S extends SharedSuggestionProvider> void registerRoot(
            CommandDispatcher<S> dispatcher,
            String name,
            LiteralArgumentBuilder<S> tree,
            Set<String> registeredRoots) {
        if (com.mockplayer.config.ModCommands.isDisabled(name)) {
            return;
        }
        dispatcher.register(tree);
        registeredRoots.add(name);
    }

    /** {@code /newplayer <name> [batch ...]} 子树。 */
    private static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> newPlayerTree(
            CommandSupport.CommandFactory<S> f, String rootName) {
        return f.literal(rootName)
                .then(f.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            f.sendFeedback(ctx.getSource(), FakePlayerCommands.newPlayer(name));
                            return 1;
                        }))
                .then(BatchCommands.newPlayerBatchNode(f));
    }

    /** {@code /delplayer <name> [batch ...]} 子树。 */
    private static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> delPlayerTree(
            CommandSupport.CommandFactory<S> f, String rootName) {
        return f.literal(rootName)
                .then(f.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .suggests(FakePlayerCommands.fakePlayerNames())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            f.sendFeedback(ctx.getSource(), FakePlayerCommands.delPlayer(name));
                            return 1;
                        }))
                .then(BatchCommands.delPlayerBatchNode(f));
    }

    /** {@code /connect <name> <host> [port]} 子树。 */
    private static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> connectTree(
            CommandSupport.CommandFactory<S> f, String rootName) {
        return f.literal(rootName)
                .then(f.argument("name", FakePlayerNameArgument.fakePlayerName())
                        .suggests(FakePlayerCommands.fakePlayerNames())
                        .then(f.argument("host", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String host = StringArgumentType.getString(ctx, "host");
                                    f.sendFeedback(ctx.getSource(), FakePlayerCommands.connectPlayer(name, host, 25565));
                                    return 1;
                                })
                                .then(f.argument("port", IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String host = StringArgumentType.getString(ctx, "host");
                                            int port = IntegerArgumentType.getInteger(ctx, "port");
                                            f.sendFeedback(ctx.getSource(), FakePlayerCommands.connectPlayer(name, host, port));
                                            return 1;
                                        }))));
    }
}
