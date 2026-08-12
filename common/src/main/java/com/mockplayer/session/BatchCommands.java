package com.mockplayer.session;

import com.mockplayer.api.Bot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量假人生成 / 移除命令（性能测试用）。
 *
 * <p>批量添加：tick 驱动队列（复用客户端 tick），支持 interval（每 N tick 发起）
 * 与 concurrency（同时在途登录上限）；名字 = 前缀 + "_" + 序号，跳过已存在名字；
 * 全部到 PLAYING 或超时后产出 {@link BatchSummary}（性能报告数据源）。
 *
 * <p>批量删除：只遍历 source == CORE 的假人（管理边界）；支持 dry-run 只列不删。
 *
 * <p>与主玩家完全隔离：只调 SessionManager 内部核心创建路径 / BotManager 删除，
 * 不碰主玩家 level / options。
 */
public final class BatchCommands {

    private BatchCommands() {
    }

    /** 批量创建汇总（测试与报告共用；时间单位 ms，字节单位 byte）。 */
    public record BatchSummary(
            int total,
            int created,
            int skipped,
            int failed,
            long durationMs,
            long maxSingleMs,
            long jvmDeltaBytes,
            long trackedBytes) {
    }

    // ===== 批量创建状态（tick 驱动） =====
    private static boolean spawnActive;
    private static String spawnPrefix;
    private static int spawnTotal;
    private static int spawnInterval;
    private static int spawnConcurrency;
    /** 下一次批量发起的时间点（墙钟，interval 按 50ms/tick 折算）。 */
    private static long spawnNextBurstNanos;
    private static int spawnIndex;
    private static int spawnCreated;
    private static int spawnSkipped;
    private static int spawnFailed;
    private static int spawnInFlight;
    private static final List<Bot> spawnBots = new ArrayList<>();
    private static final Map<String, Long> spawnStartNanos = new HashMap<>();
    private static long spawnMaxSingleNanos;
    private static long spawnStartWallNanos;
    private static long jvmBeforeBytes;
    private static long spawnTrackedBytes;
    private static volatile BatchSummary lastSummary;

    /** 最近一次批量创建汇总（未开始/进行中为 null；测试与后续 GUI 共用）。 */
    public static BatchSummary lastSummary() {
        return lastSummary;
    }

    /** 批量创建是否进行中（测试与状态展示用）。 */
    public static boolean isActive() {
        return spawnActive;
    }

    /**
     * 停止批量创建（主玩家退出服务器/游戏时由 SessionManager.clearAll 调用；幂等）。
     *
     * 复位全部状态；已创建/在途的假人由 clearAll 统一下线，本方法不碰假人本身。
     */
    public static void cancel() {
        if (!spawnActive) {
            return;
        }
        spawnActive = false;
        spawnPrefix = null;
        spawnTotal = 0;
        spawnInterval = 0;
        spawnConcurrency = 1;
        spawnIndex = 0;
        spawnCreated = 0;
        spawnSkipped = 0;
        spawnFailed = 0;
        spawnInFlight = 0;
        spawnBots.clear();
        spawnStartNanos.clear();
        spawnMaxSingleNanos = 0;
        spawnTrackedBytes = 0;
        lastSummary = null;
    }

    /** 每 tick 驱动批量创建队列（SessionManager.tick 调用）。 */
    public static void tick() {
        if (!spawnActive) {
            return;
        }
        long now = System.nanoTime();
        // 发起本批创建（interval=0 每帧尝试；否则按墙钟 50ms/tick 间隔批量发起）
        if (spawnInterval == 0 || now >= spawnNextBurstNanos) {
            if (spawnInterval > 0) {
                spawnNextBurstNanos = now + spawnInterval * 50_000_000L;
            }
            int issued = 0;
            while (spawnIndex <= spawnTotal && spawnInFlight < spawnConcurrency && issued < spawnConcurrency) {
                String name = spawnPrefix + "_" + spawnIndex;
                spawnIndex++;
                issued++;
                if (nameTaken(name)) {
                    spawnSkipped++;
                    continue;
                }
                Bot bot = ((BotManagerImpl) com.mockplayer.api.MockplayerApi.bots())
                        .createCoreBot(com.mockplayer.api.BotProfile.of(name, BotManagerImpl.COMMAND_OWNER));
                if (bot == null) {
                    spawnFailed++;
                    continue;
                }
                spawnBots.add(bot);
                spawnInFlight++;
                spawnStartNanos.put(name, now);
            }
        }
        // 推进在途 bot：PLAYING → 记时长/内存；断开或超时 → 记失败
        java.util.Iterator<Bot> it = spawnBots.iterator();
        while (it.hasNext()) {
            Bot b = it.next();
            long start = spawnStartNanos.getOrDefault(b.getName(), now);
            long ageMs = (now - start) / 1_000_000L;
            if (b.getLifecycle() == com.mockplayer.api.BotLifecycle.PLAYING) {
                long singleMs = ageMs;
                spawnMaxSingleNanos = Math.max(spawnMaxSingleNanos, singleMs * 1_000_000L);
                spawnCreated++;
                spawnTrackedBytes += b.memoryInfo().trackedBytes();
                spawnInFlight--;
                it.remove();
                spawnStartNanos.remove(b.getName());
            } else {
                // 宽限 2 秒：创建瞬间会话可能还是 DISCONNECTED（连接在 Netty 线程异步建立）；
                // 创建后超过 150 秒仍未 PLAYING 按失败处理（离线 hasJoined 网络慢时可到 1-2 分钟）
                if ((b.getLifecycle() == com.mockplayer.api.BotLifecycle.DISCONNECTED && ageMs >= 2000)
                        || ageMs > 150_000) {
                    spawnFailed++;
                    spawnInFlight--;
                    it.remove();
                    spawnStartNanos.remove(b.getName());
                }
            }
        }
        if (spawnIndex > spawnTotal && spawnInFlight == 0) {
            spawnActive = false;
            long durationMs = (System.nanoTime() - spawnStartWallNanos) / 1_000_000L;
            lastSummary = new BatchSummary(
                    spawnTotal, spawnCreated, spawnSkipped, spawnFailed,
                    durationMs, spawnMaxSingleNanos / 1_000_000L,
                    jvmUsedBytes() - jvmBeforeBytes, spawnTrackedBytes);
            CommandSupport.pushToChat(Component.translatable("commands.mockplayer.newplayer.batch.done",
                    spawnCreated, spawnSkipped, spawnFailed,
                    durationMs,
                    spawnTotal == 0 ? 0 : durationMs / Math.max(1, spawnCreated + spawnFailed),
                    spawnMaxSingleNanos / 1_000_000L,
                    CommandSupport.formatBytes(jvmUsedBytes() - jvmBeforeBytes),
                    CommandSupport.formatBytes(spawnTrackedBytes)));
        }
    }

    /** 批量创建入口（命令调用）：校验后启动 tick 队列，返回开始反馈。 */
    public static Component newPlayerBatch(String prefix, int count, int intervalTicks, int concurrency) {
        com.mockplayer.config.ModConfig cfg = com.mockplayer.config.MockplayerConfig.get();
        if (count < 1 || count > cfg.getBatchMaxCount()) {
            return CommandSupport.fail("commands.mockplayer.newplayer.batch.too_many",
                    count, cfg.getBatchMaxCount());
        }
        if (intervalTicks < 0) {
            intervalTicks = 0;
        }
        if (concurrency < 1) {
            concurrency = 1;
        }
        if (concurrency > 64) {
            concurrency = 64;
        }
        if (!validNames(prefix, count)) {
            return CommandSupport.fail("commands.mockplayer.newplayer.batch.invalid_prefix",
                    prefix == null ? "" : prefix);
        }
        spawnPrefix = prefix;
        spawnTotal = count;
        spawnInterval = intervalTicks;
        spawnConcurrency = concurrency;
        spawnIndex = 1;
        spawnCreated = 0;
        spawnSkipped = 0;
        spawnFailed = 0;
        spawnInFlight = 0;
        spawnBots.clear();
        spawnStartNanos.clear();
        spawnMaxSingleNanos = 0;
        spawnStartWallNanos = System.nanoTime();
        spawnNextBurstNanos = spawnStartWallNanos; // 立即发起第一批
        jvmBeforeBytes = jvmUsedBytes();
        spawnTrackedBytes = 0;
        spawnActive = true;
        lastSummary = null;
        return CommandSupport.info("commands.mockplayer.newplayer.batch.started", count, prefix);
    }

    /** 批量删除（命令调用）：只管理 CORE 假人；dry=true 只列不删。 */
    public static Component delPlayerBatch(String prefixOrAll, boolean dry) {
        boolean all = "all".equalsIgnoreCase(prefixOrAll);
        List<Bot> targets = com.mockplayer.api.MockplayerApi.bots().getBots().stream()
                .filter(b -> b.source() == com.mockplayer.api.BotSource.CORE)
                .filter(b -> all || b.getName().startsWith(prefixOrAll))
                .sorted(Comparator.comparing(Bot::getName))
                .toList();
        if (targets.isEmpty()) {
            return CommandSupport.info("commands.mockplayer.delplayer.batch.empty");
        }
        if (dry) {
            String names = targets.stream().map(Bot::getName)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            return CommandSupport.info("commands.mockplayer.delplayer.batch.dry", targets.size(), names);
        }
        long start = System.nanoTime();
        int failed = 0;
        for (Bot b : targets) {
            if (com.mockplayer.api.MockplayerApi.bots().removeBot(b.getName(), BotManagerImpl.COMMAND_OWNER)
                    != com.mockplayer.api.RemoveResult.REMOVED) {
                failed++;
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        return CommandSupport.info("commands.mockplayer.delplayer.batch.done",
                targets.size() - failed, failed, ms);
    }

    // ===== 命令树节点（双端共用） =====

    /** {@code /newplayer batch <prefix> <count> [interval [concurrency]]} 子树。 */
    public static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> newPlayerBatchNode(
            CommandSupport.CommandFactory<S> f) {
        return f.literal("batch")
                .then(f.argument("prefix", StringArgumentType.word())
                        .then(f.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    f.sendFeedback(ctx.getSource(), newPlayerBatch(
                                            getString(ctx, "prefix"), getInt(ctx, "count"), 0, 1));
                                    return 1;
                                })
                                .then(f.argument("interval", IntegerArgumentType.integer(0, 1200))
                                        .executes(ctx -> {
                                            f.sendFeedback(ctx.getSource(), newPlayerBatch(
                                                    getString(ctx, "prefix"), getInt(ctx, "count"),
                                                    getInt(ctx, "interval"), 1));
                                            return 1;
                                        })
                                        .then(f.argument("concurrency", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> {
                                                    f.sendFeedback(ctx.getSource(), newPlayerBatch(
                                                            getString(ctx, "prefix"), getInt(ctx, "count"),
                                                            getInt(ctx, "interval"), getInt(ctx, "concurrency")));
                                                    return 1;
                                                })))));
    }

    /** {@code /delplayer batch <all|prefix> [--dry]} 子树。 */
    public static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> delPlayerBatchNode(
            CommandSupport.CommandFactory<S> f) {
        return f.literal("batch")
                .then(f.literal("all")
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), delPlayerBatch("all", false));
                            return 1;
                        })
                        .then(f.literal("--dry").executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), delPlayerBatch("all", true));
                            return 1;
                        })))
                .then(f.argument("prefix", StringArgumentType.word())
                        .suggests(coreBotNames())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), delPlayerBatch(getString(ctx, "prefix"), false));
                            return 1;
                        })
                        .then(f.literal("--dry").executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), delPlayerBatch(getString(ctx, "prefix"), true));
                            return 1;
                        })));
    }

    // ===== 辅助 =====

    private static String getString(CommandContext<?> ctx, String key) {
        return StringArgumentType.getString(ctx, key);
    }

    private static int getInt(CommandContext<?> ctx, String key) {
        return IntegerArgumentType.getInteger(ctx, key);
    }

    /** Tab 补全：只补本 mod 命令创建的假人（CORE）。 */
    private static <S extends SharedSuggestionProvider> SuggestionProvider<S> coreBotNames() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(
                com.mockplayer.api.MockplayerApi.bots().getBots().stream()
                        .filter(b -> b.source() == com.mockplayer.api.BotSource.CORE)
                        .map(Bot::getName)
                        .toList(), builder);
    }

    /** 生成的全部名字必须满足 MC 玩家名规则（1-16 字符，字母/数字/下划线）。 */
    private static boolean validNames(String prefix, int count) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        for (int i = 1; i <= count; i++) {
            if (!validName(prefix + "_" + i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validName(String name) {
        if (name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** 名字是否已被占用：本 mod 会话已存在，或单机/局域网服务端已有同名玩家。 */
    private static boolean nameTaken(String name) {
        if (com.mockplayer.api.MockplayerApi.bots().getBot(name).isPresent()) {
            return true;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
        return server != null && server.getPlayerList().getPlayerByName(name) != null;
    }

    private static long jvmUsedBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

}
