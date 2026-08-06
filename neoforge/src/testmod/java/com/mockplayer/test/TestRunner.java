package com.mockplayer.test;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;
import com.mockplayer.api.container.BotContainer;
import com.mockplayer.api.container.BotMerchantMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.MinecraftServer.MultiplayerScope;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 游戏内自动化测试执行器（主线程 tick 驱动，状态机逐步推进）。
 *
 * 流程：等单机世界就绪 → 自动开局域网 → 创建 bot（owner="test"）→ 按套件断言 →
 * 结果写 runs/client/test-results/&lt;suite&gt;.json → Minecraft.stop() 主动退出（不卡 CI）。
 * 断言异常全部 catch 记录，绝不崩游戏（崩了反而退不干净）。
 */
public final class TestRunner {

    private static final long TIMEOUT_MS = 45_000;

    /** 全部套件（suite=all / IDE 默认入口时按序连续跑） */
    private static final List<String> ALL_SUITES = List.of("api-smoke", "containers", "merchant");

    private enum Phase { WAIT_TITLE, WAIT_WORLD, RUN, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static long phaseStart;
    private static boolean worldCreationStarted;
    private static String suite = "";
    private static List<String> suiteQueue;
    private static int suiteIndex;
    private static Bot bot;

    /** 断言记录（最终写入 JSON） */
    private static final List<Record> records = new ArrayList<>();
    /** 套件内步骤计数器 */
    private static int step;
    /** 等待帧计数（实体同步等） */
    private static int waitTicks;

    private TestRunner() {
    }

    /** 每个 suite 的断言记录 */
    private record Record(String name, boolean passed, String detail) {
    }

    public static void tick(Minecraft mc, String suiteName) {
        if (phase == Phase.DONE) {
            return;
        }
        if (suiteQueue == null) {
            // 首次 tick：初始化套件队列（"all" 展开为全部套件，逐个连续跑）
            suiteQueue = "all".equals(suiteName) ? new ArrayList<>(ALL_SUITES) : List.of(suiteName);
            suiteIndex = 0;
            suite = suiteQueue.get(0);
            phase = Phase.WAIT_TITLE;
            phaseStart = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        advance(mc);
        if (phase != Phase.DONE && now - phaseStart > TIMEOUT_MS) {
            fail("timeout @phase=" + phase + " step=" + step);
            finishSuite();
        }
    }

    /** 阶段推进：主菜单 → 自动建世界 → 单机就绪+开局域网 → 跑套件 */
    private static void advance(Minecraft mc) {
        long now = System.currentTimeMillis();
        switch (phase) {
            case WAIT_TITLE -> {
                // 已进世界则跳过建世界
                if (mc.level != null) {
                    phase = Phase.WAIT_WORLD;
                    phaseStart = now;
                } else if (!worldCreationStarted) {
                    worldCreationStarted = true;
                    phaseStart = now;
                    createWorld(mc);
                }
            }
            case WAIT_WORLD -> {
                if (mc.getSingleplayerServer() != null && mc.level != null && mc.player != null) {
                    if (!mc.getSingleplayerServer().isPublished()) {
                        // 显式端口（26.2 port=0 不自动分配，getPort() 会返回 0）
                        mc.getSingleplayerServer().publishServer(MultiplayerScope.LAN, GameType.SURVIVAL, false, 25565);
                    }
                    if (mc.getSingleplayerServer().isPublished() && mc.getSingleplayerServer().getPort() > 0) {
                        phase = Phase.RUN;
                        phaseStart = now;
                    }
                }
            }
            case RUN -> run(mc);
        }
    }

    /** 创建单机测试世界（只调一次）：用默认超平坦?不，正常主世界 preset */
    private static void createWorld(Minecraft mc) {
        System.out.println("[mocktest] creating singleplayer world 'mocktest'");
        mc.createWorldOpenFlows().createFreshLevel(
                "mocktest",
                new LevelSettings("mocktest", GameType.SURVIVAL,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                new WorldOptions(0L, false, false),
                WorldPresets::createNormalWorldDimensions,
                null);
    }

    private static void run(Minecraft mc) {
        switch (suite) {
            case "api-smoke" -> runApiSmoke(mc);
            case "containers" -> runContainers(mc);
            case "merchant" -> runMerchant(mc);
            default -> {
                fail("unknown suite: " + suite);
                finishSuite();
            }
        }
    }

    // ===== api-smoke：创建/生命周期/世界信息/动作原语/owner 删除 =====

    private static void runApiSmoke(Minecraft mc) {
        switch (step) {
            case 0 -> {
                bot = MockplayerApi.bots().createBot(BotProfile.of("tbot", "test"));
                check("createBot non-null", bot != null);
                if (bot == null) {
                    finishSuite();
                    return;
                }
                step = 1;
            }
            case 1 -> {
                if (bot.getLifecycle() == BotLifecycle.PLAYING) {
                    check("lifecycle PLAYING", true);
                    check("getLocalPlayer != null", bot.getLocalPlayer() != null);
                    check("getLevel != null", bot.getLevel() != null);
                    check("getGameMode != null", bot.getGameMode() != null);
                    check("getOwner == test", "test".equals(bot.getOwner()));
                    step = 2;
                }
            }
            case 2 -> {
                bot.actions().look(0.0F, 0.0F);
                check("look yRot", Math.abs(bot.getLocalPlayer().getYRot() - 0.0F) < 1.0F);
                // turn：相对叠加（90 水平 + 30 垂直）
                bot.actions().turn(90.0F, 30.0F);
                check("turn yRot+90", Math.abs(((bot.getLocalPlayer().getYRot() % 360) + 360) % 360 - 90.0F) < 1.0F);
                check("turn xRot+30", Math.abs(bot.getLocalPlayer().getXRot() - 30.0F) < 1.0F);
                bot.actions().setForward(1.0F);
                bot.actions().setSneak(true);
                step = 3;
            }
            case 3 -> {
                // keyPresses 由 BotImpl.tick 的 applyInput 应用（需下一帧生效）
                check("forward keyPresses", bot.getLocalPlayer().input.keyPresses.forward());
                check("sneak keyPresses", bot.getLocalPlayer().input.keyPresses.shift());
                bot.actions().stop();
                step = 4;
            }
            case 4 -> {
                check("stop resets input", !bot.getLocalPlayer().input.keyPresses.forward()
                        && !bot.getLocalPlayer().input.keyPresses.shift());
                step = 5;
            }
            case 5 -> {
                // 实体/区块同步有延迟：等假人 level 有区块 + 周围出现主玩家实体（neoforge 端更慢，等 300 tick）
                if ((bot.isBlockLoaded(bot.getLocalPlayer().blockPosition())
                        && !bot.getEntitiesNear(64).isEmpty()) || ++waitTicks > 300) {
                    check("getEntitiesNear", !bot.getEntitiesNear(64).isEmpty());
                    check("isBlockLoaded", bot.isBlockLoaded(bot.getLocalPlayer().blockPosition()));
                    check("getBlockState air check", bot.getBlockState(bot.getLocalPlayer().blockPosition()) != null);
                    step = 6;
                }
            }
            case 6 -> {
                check("getContainer empty (no menu open)", bot.getContainer().isEmpty());
                // 新原语冒烟：无环境空操作不崩（drop/mount/dismount/持续攻击使用）
                bot.actions().drop(0, false);
                bot.actions().mount(true);
                bot.actions().dismount();
                bot.actions().sustainedAttack(null);
                bot.actions().sustainedUse(null);
                bot.actions().stopSustained();
                check("new primitives no-crash", true);
                check("removeBot own owner", MockplayerApi.bots().removeBot("tbot", "test") == RemoveResult.REMOVED);
                check("removeBot not found", MockplayerApi.bots().removeBot("tbot", "test") == RemoveResult.NOT_FOUND);
                finishSuite();
            }
        }
    }

    // ===== containers：服务端开箱 → 假人客户端容器会话断言 → 关闭 =====

    private static BlockPos containerPos;
    private static boolean openIssued;

    private static void runContainers(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                if (bot == null) {
                    bot = MockplayerApi.bots().createBot(BotProfile.of("tbot", "test"));
                }
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                // 箱子放假人旁边 3 格
                containerPos = bot.getLocalPlayer().blockPosition().offset(3, 0, 0);
                server.getLevel(Level.OVERWORLD).setBlock(containerPos, Blocks.CHEST.defaultBlockState(), 3);
                step = 2;
            }
            case 2 -> {
                // 等假人客户端 level 同步到箱子（服务端 setBlock 后经区块更新包到达）
                if (bot.getBlockState(containerPos).is(Blocks.CHEST)) {
                    step = 3;
                }
            }
            case 3 -> {
                // 等服务端玩家可交互：awaitingPositionFromClient 清除（收到位置包）后原版才接受交互
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName("tbot");
                if (sp != null && !isAwaitingPosition(sp)) {
                    step = 4;
                }
            }
            case 4 -> {
                // 假人真实交互开箱：useItemOn 走 gameMode → 假人 connection → 服务端（loaded 修复后应被接受）
                if (!openIssued) {
                    openIssued = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(containerPos));
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(containerPos), Direction.WEST, containerPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                    check("useItemOn chest issued", true);
                }
                step = 5;
            }
            case 5 -> {
                Optional<BotContainer> container = bot.getContainer();
                if (container.isPresent()) {
                    check("getContainer present", true);
                    BotContainer c = container.get();
                    check("menuType is chest", c.getMenuType() == MenuType.GENERIC_9x3);
                    // ChestMenu 完整槽 = 27 容器 + 27 背包 + 9 热栏 = 63
                    check("container total slots == 63", c.getSize() == 63);
                    check("containerId > 0", c.getContainerId() > 0);
                    c.close();
                    step = 6;
                }
            }
            case 6 -> {
                if (bot.getContainer().isEmpty()) {
                    check("container closed", true);
                    MockplayerApi.bots().removeBot("tbot", "test");
                    finishSuite();
                }
            }
        }
    }

    /** 反射读服务端玩家连接是否仍在等待位置确认（26.2 awaitingPositionFromClient 是 Vec3，非 null = 等待中） */
    private static boolean isAwaitingPosition(net.minecraft.server.level.ServerPlayer sp) {
        try {
            java.lang.reflect.Field f = sp.connection.getClass().getDeclaredField("awaitingPositionFromClient");
            f.setAccessible(true);
            return f.get(sp.connection) != null;
        } catch (Exception ignored) {
            return true; // 读不到按等待处理（宁等勿早）
        }
    }

    // ===== merchant：服务端开交易菜单（ClientSideMerchant，无实体依赖）→ 假人交易会话断言 =====

    private static boolean merchantOpened;

    private static void runMerchant(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                if (bot == null) {
                    bot = MockplayerApi.bots().createBot(BotProfile.of("tbot", "test"));
                }
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                // 服务端线程开交易菜单：用 ClientSideMerchant（内存 merchant，stillValid 恒 true，
                // 无实体/无 AI/无距离问题——村民实体会走开导致 MerchantMenu.stillValid=false 服务端关菜单）
                if (!merchantOpened) {
                    merchantOpened = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName("tbot");
                        if (sp != null) {
                            try {
                                net.minecraft.world.entity.npc.ClientSideMerchant clientMerchant = new net.minecraft.world.entity.npc.ClientSideMerchant(sp);
                                net.minecraft.world.item.trading.MerchantOffers offers = new net.minecraft.world.item.trading.MerchantOffers();
                                offers.add(new net.minecraft.world.item.trading.MerchantOffer(
                                        new net.minecraft.world.item.trading.ItemCost(net.minecraft.world.item.Items.EMERALD, 1),
                                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 2),
                                        12, 5, 0.05F));
                                clientMerchant.overrideOffers(offers);
                                net.minecraft.world.SimpleMenuProvider provider = new net.minecraft.world.SimpleMenuProvider(
                                        (id, inv, p) -> new net.minecraft.world.inventory.MerchantMenu(id, inv, clientMerchant),
                                        net.minecraft.network.chat.Component.literal("Villager"));
                                java.util.OptionalInt r = sp.openMenu(provider);
                                // 26.2 openMenu 不自动发 offers 包（sendMerchantOffers 是村民交互流程单独调用）
                                if (r.isPresent()) {
                                    sp.sendMerchantOffers(r.getAsInt(), clientMerchant.getOffers(), 0, 1, true, true);
                                }
                            } catch (Exception ex) {
                                System.out.println("[mocktest] open merchant err " + ex);
                            }
                        }
                    });
                }
                step = 2;
            }
            case 2 -> {
                Optional<BotMerchantMenu> merchant = bot.getMerchant();
                if (merchant.isPresent()) {
                    check("getMerchant present", true);
                    MerchantOffers offers = merchant.get().getOffers();
                    check("offers non-empty", offers != null && !offers.isEmpty());
                    merchant.get().selectOffer(0);
                    check("selectOffer issued", true);
                    step = 3;
                }
            }
            case 3 -> {
                MockplayerApi.bots().removeBot("tbot", "test");
                finishSuite();
            }
        }
    }

    // ===== 断言与结果 =====

    private static void check(String name, boolean ok) {
        records.add(new Record(name, ok, ok ? "" : "assertion failed"));
        log(name, ok);
    }

    private static void fail(String name) {
        records.add(new Record(name, false, "failure"));
        log(name, false);
    }

    private static void log(String name, boolean ok) {
        System.out.println("[mocktest] " + (ok ? "PASS " : "FAIL ") + name);
    }

    /** 当前套件收尾：写结果 JSON → 推进下一个套件（world 已在，直接 RUN）或全部完成退出 */
    private static void finishSuite() {
        writeResultJson();
        boolean passed = records.stream().allMatch(Record::passed);
        System.out.println("[mocktest] suite " + suite + " " + (passed ? "PASSED" : "FAILED")
                + " (" + records.size() + " checks)");
        suiteIndex++;
        if (suiteIndex < suiteQueue.size()) {
            suite = suiteQueue.get(suiteIndex);
            resetSuiteState();
            phase = Phase.RUN;
            phaseStart = System.currentTimeMillis();
        } else {
            phase = Phase.DONE;
            // 全部套件跑完：主动退出游戏，保证 gradlew 返回、bash 不被卡死
            Minecraft.getInstance().stop();
        }
    }

    /** 套件间重置（世界不重建，只重置套件状态 + 清理上一个假人） */
    private static void resetSuiteState() {
        step = 0;
        waitTicks = 0;
        records.clear();
        for (Bot b : MockplayerApi.bots().getBots()) {
            MockplayerApi.bots().removeBot(b.getName(), "test");
        }
        bot = null;
        containerPos = null;
        openIssued = false;
        merchantOpened = false;
    }

    /** 写当前套件结果 JSON（runs/client/test-results/<suite>.json） */
    private static void writeResultJson() {
        boolean passed = records.stream().allMatch(Record::passed);
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"suite\": \"").append(suite).append("\",\n");
        json.append("  \"passed\": ").append(passed).append(",\n");
        json.append("  \"results\": [\n");
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            json.append("    {\"name\": \"").append(r.name().replace("\"", "\\\""))
                    .append("\", \"status\": \"").append(r.passed() ? "PASS" : "FAIL")
                    .append("\", \"detail\": \"").append(r.detail().replace("\"", "\\\""))
                    .append("\"}");
            if (i < records.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n}\n");
        try {
            File dir = new File("test-results");
            if (!dir.exists() && !dir.mkdirs()) {
                System.out.println("[mocktest] cannot create test-results dir");
            }
            File out = new File(dir, suite + ".json");
            try (FileWriter w = new FileWriter(out, StandardCharsets.UTF_8)) {
                w.write(json.toString());
            }
            System.out.println("[mocktest] wrote " + out.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[mocktest] failed to write result: " + e);
        }
    }
}
