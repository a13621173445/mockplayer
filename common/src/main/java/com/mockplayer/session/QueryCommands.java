package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotMemoryInfo;
import com.mockplayer.api.container.BotContainer;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /query 命令集：假人状态查询 + 事件监听（与 /control 动作命令完全分离）。
 *
 * 设计约束（主人拍板 2026-08-09）：
 * - 查询不改变假人/服务端状态（listen on/off 只挂摘本地监听器）；
 * - i18n 全部走 commands.mockplayer.query.*，不污染 control 命名空间；
 * - 监听器惰性：listen on 才挂到目标 bot 私有总线，off 摘除，未监听零开销；
 * - memory 的 JVM 堆是真实值，per-bot 是 Mod 侧估算（口径见 BotMemoryInfo）。
 */
public class QueryCommands {

    /** 当前开启的事件监听器（bot 名 → recorder），listen off 时摘除并清除。 */
    private static final Map<String, EventRecorder> RECORDERS = new ConcurrentHashMap<>();

    private QueryCommands() {
    }

    // ===== 查询命令 =====

    public static Component list() {
        // 管理边界：只列本 mod 命令创建的假人（CORE），API/附属创建的不可见
        List<Bot> bots = com.mockplayer.api.MockplayerApi.bots().getBots().stream()
                .filter(b -> b.source() == com.mockplayer.api.BotSource.CORE)
                .toList();
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.list.header", bots.size());
        for (Bot bot : bots) {
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.list.entry",
                    CommandSupport.playerName(bot.getName()),
                    bot.getOwner(),
                    Component.translatable(lifecycleKey(bot.getLifecycle())),
                    bot.getUUID().toString().substring(0, 8)));
        }
        return out;
    }

    private static String lifecycleKey(BotLifecycle lifecycle) {
        return switch (lifecycle) {
            case CONNECTING -> "commands.mockplayer.query.lifecycle.connecting";
            case PLAYING -> "commands.mockplayer.query.lifecycle.playing";
            case DISCONNECTED -> "commands.mockplayer.query.lifecycle.disconnected";
        };
    }

    public static Component botInfo(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.info.header", CommandSupport.playerName(name));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.uuid",
                bot.getUUID().toString()));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.owner", bot.getOwner()));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.lifecycle",
                Component.translatable(lifecycleKey(bot.getLifecycle()))));
        if (bot.getLocalPlayer() != null) {
            var p = bot.getLocalPlayer();
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.position",
                    String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ())));
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.health",
                    bot.getLocalPlayer().getHealth()));
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.food",
                    bot.getLocalPlayer().getFoodData().getFoodLevel()));
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.xp",
                    bot.getLocalPlayer().experienceLevel));
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.dimension",
                    bot.getLevel().dimension().identifier().toString()));
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.info.online_count",
                    bot.getOnlinePlayers().size()));
        }
        return out;
    }

    public static Component inventory(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        if (bot.getLocalPlayer() == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_playing", CommandSupport.playerName(name));
        }
        net.minecraft.world.entity.player.Inventory inv = bot.getLocalPlayer().getInventory();
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.inventory.header");
        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.inventory.entry",
                        i < 9 ? "[" + i + "]" : String.valueOf(i),
                        stack.getHoverName(),
                        stack.getCount()));
            }
        }
        return out;
    }

    /** 查询假人当前区块加载半径。 */
    public static Component chunk(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        return CommandSupport.info("commands.mockplayer.query.chunk",
                CommandSupport.playerName(name), bot.getChunkRadius());
    }

    public static Component container(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            // 模板含 %s（假人名字），必须传参数，否则输出残留字面 %s
            return CommandSupport.info("commands.mockplayer.query.container.none", CommandSupport.playerName(name));
        }
        BotContainer container = c.get();
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.container.header",
                container.getTitle(),
                container.getContainerId(),
                container.getSize());
        for (int i = 0; i < container.getSize(); i++) {
            net.minecraft.world.item.ItemStack stack = container.getSlot(i);
            if (!stack.isEmpty()) {
                out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.container.entry",
                        i, stack.getHoverName(), stack.getCount()));
            }
        }
        if (!container.getCarried().isEmpty()) {
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.container.carried",
                    container.getCarried().getHoverName(), container.getCarried().getCount()));
        }
        return out;
    }

    public static Component near(String name, double radius) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        if (bot.getLocalPlayer() == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_playing", CommandSupport.playerName(name));
        }
        List<net.minecraft.world.entity.Entity> entities = bot.getEntitiesNear(radius);
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.near.header", entities.size(), radius);
        for (net.minecraft.world.entity.Entity e : entities) {
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.near.entry",
                    e.getName(),
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                    String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(e.distanceToSqr(bot.getLocalPlayer())))));
        }
        return out;
    }

    public static Component blockAt(String name, int x, int y, int z) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        BlockPos pos = new BlockPos(x, y, z);
        return CommandSupport.info("commands.mockplayer.query.block.entry",
                x + " " + y + " " + z,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bot.getBlockState(pos).getBlock()).toString(),
                bot.isBlockLoaded(pos));
    }

    public static Component online(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        List<PlayerInfo> players = bot.getOnlinePlayers();
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.online.header", players.size());
        for (PlayerInfo info : players) {
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.online.entry",
                    info.getProfile().name(),
                    info.getProfile().id().toString().substring(0, 8)));
        }
        return out;
    }

    public static Component chatHistory(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        List<Component> history = bot instanceof BotImpl impl ? impl.session().getState().getChatHistory() : List.of();
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.chat.header", history.size());
        for (int i = Math.max(0, history.size() - 10); i < history.size(); i++) {
            out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.chat.entry", history.get(i)));
        }
        return out;
    }

    public static Component listen(String name, boolean on) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        if (!(bot instanceof BotImpl impl)) {
            return CommandSupport.fail("commands.mockplayer.query.not_playing", CommandSupport.playerName(name));
        }
        if (on) {
            EventRecorder recorder = RECORDERS.computeIfAbsent(name, n -> new EventRecorder(name));
            impl.events().addListener(recorder);
            return CommandSupport.info("commands.mockplayer.query.listen.on", CommandSupport.playerName(name));
        }
        EventRecorder recorder = RECORDERS.remove(name);
        if (recorder != null) {
            impl.events().removeListener(recorder);
        }
        return CommandSupport.info("commands.mockplayer.query.listen.off", CommandSupport.playerName(name));
    }

    public static Component events(String name, int count) {
        EventRecorder recorder = RECORDERS.get(name);
        if (recorder == null) {
            return CommandSupport.info("commands.mockplayer.query.events.not_listening", CommandSupport.playerName(name));
        }
        return recorder.formatEvents(count);
    }

    /** 当前监听的 recorder（查询/测试用；未监听返回 null）。 */
    public static EventRecorder getRecorder(String name) {
        return RECORDERS.get(name);
    }

    /** 假人内存占用查询：JVM 堆真实值 + Mod 侧精确记账分解 + level 实体/区块数。 */
    public static Component memory(String name) {
        Bot bot = CommandSupport.findBot(name);
        if (bot == null) {
            return CommandSupport.fail("commands.mockplayer.query.not_found", CommandSupport.playerName(name));
        }
        BotMemoryInfo m = bot.memoryInfo();
        MutableComponent out = CommandSupport.info("commands.mockplayer.query.memory.header", CommandSupport.playerName(name));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.jvm",
                CommandSupport.formatBytes(m.jvmUsedBytes()), CommandSupport.formatBytes(m.jvmCommittedBytes()), CommandSupport.formatBytes(m.jvmMaxBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.bot_count", m.botCount()));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.tracked",
                CommandSupport.formatBytes(m.trackedBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.chat",
                CommandSupport.formatBytes(m.chatBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.sound",
                CommandSupport.formatBytes(m.soundBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.particle",
                CommandSupport.formatBytes(m.particleBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.packets",
                m.packetCount()));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.online",
                CommandSupport.formatBytes(m.onlinePlayersBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.events",
                CommandSupport.formatBytes(m.eventCacheBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.container",
                CommandSupport.formatBytes(m.containerBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.inventory",
                CommandSupport.formatBytes(m.inventoryBytes())));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.level",
                m.entityCount(), m.chunkCount()));
        out.append(Component.literal("\n")).append(CommandSupport.info("commands.mockplayer.query.memory.note"));
        return out;
    }

    // ===== Tab 补全 =====

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> onOff() {
        return CommandSupport.fixed("on", "off");
    }

    // ===== 命令树构建（双端共用，平台只提供 literal/argument/反馈函数） =====

    public static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> buildQueryTree(
            CommandSupport.CommandFactory<S> f, String rootName) {
        LiteralArgumentBuilder<S> root = f.literal(rootName);
        root.then(f.literal("list").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), list());
            return 1;
        }));

        RequiredArgumentBuilder<S, ?> player = f.argument("player", FakePlayerNameArgument.fakePlayerName())
                .suggests(CommandSupport.botNames());

        player.then(f.literal("info").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), botInfo(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("inventory").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), inventory(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("chunk").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), chunk(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("container").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), container(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("near")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), near(StringArgumentType.getString(ctx, "player"), 10.0));
                    return 1;
                })
                .then(f.argument("radius", DoubleArgumentType.doubleArg(0.0))
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), near(
                                    StringArgumentType.getString(ctx, "player"),
                                    DoubleArgumentType.getDouble(ctx, "radius")));
                            return 1;
                        })));
        player.then(f.literal("block")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .suggests(CommandSupport.coordX("commands.mockplayer.query.suggest.x"))
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .suggests(CommandSupport.coordY("commands.mockplayer.query.suggest.y"))
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .suggests(CommandSupport.coordZ("commands.mockplayer.query.suggest.z"))
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            f.sendFeedback(ctx.getSource(), blockAt(name,
                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                    IntegerArgumentType.getInteger(ctx, "z")));
                                            return 1;
                                        })))));
        player.then(f.literal("online").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), online(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("chatlog").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), chatHistory(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("listen")
                .then(f.argument("mode", StringArgumentType.word())
                        .suggests(onOff())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            f.sendFeedback(ctx.getSource(), listen(name,
                                    "on".equals(StringArgumentType.getString(ctx, "mode"))));
                            return 1;
                        })));
        player.then(f.literal("events")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), events(StringArgumentType.getString(ctx, "player"), 10));
                    return 1;
                })
                .then(f.argument("count", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), events(
                                    StringArgumentType.getString(ctx, "player"),
                                    IntegerArgumentType.getInteger(ctx, "count")));
                            return 1;
                        })));
        player.then(f.literal("memory").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), memory(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));

        root.then(player);
        return root;
    }
}
