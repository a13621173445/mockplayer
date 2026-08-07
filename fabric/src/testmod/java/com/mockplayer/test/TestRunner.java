package com.mockplayer.test;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;
import com.mockplayer.api.container.BotContainer;

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
    private static final List<String> ALL_SUITES = List.of(
            "api-smoke", "containers", "crafting", "furnace",
            "combat-stab", "combat-sprint", "enchanting", "merchant");

    private enum Phase { WAIT_TITLE, WAIT_WORLD, RUN, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static long phaseStart;
    /** 当前套件开始时间（finishSuite 算耗时） */
    private static long suiteStart;
    private static boolean worldCreationStarted;
    private static boolean gamerulesApplied;
    private static String suite = "";
    /**
     * 当前套件假人唯一名（MC 玩家名最长 16 字符，套件名截短映射；唯一名避免同名玩家数据继承）
     */
    private static String botName;

    /** 套件 → 假人短名（离线登录假人，名字过长服务端拒绝登录） */
    private static String botNameFor(String suite) {
        return switch (suite) {
            case "api-smoke" -> "tbot-smoke";
            case "containers" -> "tbot-cont";
            case "crafting" -> "tbot-craft";
            case "furnace" -> "tbot-furn";
            case "combat-stab" -> "tbot-stab";
            case "combat-sprint" -> "tbot-spr";
            case "enchanting" -> "tbot-enc";
            case "merchant" -> "tbot-merk";
            default -> "tbot";
        };
    }
    private static List<String> suiteQueue;
    private static int suiteIndex;
    private static Bot bot;

    /** 断言记录（最终写入 JSON） */
    private static final List<Record> records = new ArrayList<>();
    /** 套件内步骤计数器 */
    private static int step;
    /** 等待帧计数（实体同步等） */
    private static int waitTicks;
    /** 套件间冷却（tick）：让上一套件残留状态过期（假人断开 / lastDamageSource 40 tick 等），避免干扰下一套件 */
    private static int suiteCooldown;

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
            suiteStart = phaseStart;
        }
        long now = System.currentTimeMillis();
        advance(mc);
        if (phase != Phase.DONE && now - phaseStart > TIMEOUT_MS) {
            fail("timeout @phase=" + phase + " step=" + step);
            finishSuite();
        }
    }

    /** 阶段推进：主菜单 → 清旧档+建超平坦世界 → 单机就绪+开局域网+锁 gamerules → 跑套件 */
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
                    deleteOldWorld(mc);
                    createWorld(mc);
                }
            }
            case WAIT_WORLD -> {
                if (mc.getSingleplayerServer() != null && mc.level != null && mc.player != null) {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (!gamerulesApplied) {
                        gamerulesApplied = true;
                        applyTestGameRules(server);
                    }
                    if (!server.isPublished()) {
                        // 显式端口（26.2 port=0 不自动分配，getPort() 会返回 0）
                        server.publishServer(MultiplayerScope.LAN, GameType.SURVIVAL, false, 25565);
                    }
                    if (server.isPublished() && server.getPort() > 0) {
                        phase = Phase.RUN;
                        phaseStart = now;
                    }
                }
            }
            case RUN -> run(mc);
        }
    }

    /** 删除旧测试存档（世界未加载时调用），保证每次从干净世界开始 */
    private static void deleteOldWorld(Minecraft mc) {
        try {
            net.minecraft.world.level.storage.LevelStorageSource source = mc.getLevelSource();
            if (source.levelExists("mocktest")) {
                java.nio.file.Path levelPath = source.getLevelPath("mocktest");
                System.out.println("[mocktest] deleting old world: " + levelPath);
                try (var walk = java.nio.file.Files.walk(levelPath)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            java.nio.file.Files.delete(p);
                        } catch (java.io.IOException ignored) {
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.out.println("[mocktest] failed to delete old world: " + e);
        }
    }

    /** 锁测试 gamerules：不昼夜循环 / 不更新天气 / 不生成生物（26.2 注册制 GameRule<Boolean>） */
    private static void applyTestGameRules(MinecraftServer server) {
        var rules = server.getGameRules();
        rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
        rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
        rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
        System.out.println("[mocktest] test gamerules applied (advance_time/advance_weather/spawn_mobs = false)");
    }

    /** 创建单机测试世界（只调一次）：原版官方测试 preset（FLAT_ALL_DIMENSIONS 三维度超平坦，加载快/环境干净） */
    private static void createWorld(Minecraft mc) {
        System.out.println("[mocktest] creating singleplayer world 'mocktest' (flat)");
        mc.createWorldOpenFlows().createFreshLevel(
                "mocktest",
                new LevelSettings("mocktest", GameType.SURVIVAL,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                new WorldOptions(0L, false, false),
                WorldPresets::createTestWorldDimensions,
                null);
    }

    private static void run(Minecraft mc) {
        switch (suite) {
            case "api-smoke" -> runApiSmoke(mc);
            case "containers" -> runContainers(mc);
            case "crafting" -> runCrafting(mc);
            case "furnace" -> runFurnace(mc);
            case "combat-stab" -> runCombatStab(mc);
            case "combat-sprint" -> runCombatSprint(mc);
            case "enchanting" -> runEnchanting(mc);
            case "merchant" -> runMerchant(mc);
            default -> {
                fail("unknown suite: " + suite);
                finishSuite();
            }
        }
    }

    // ===== api-smoke：创建/生命周期/世界信息/动作原语（含移动/跳跃端到端）/owner 删除 =====

    private static volatile double fakeSx;
    private static volatile double fakeSz;
    private static volatile double fakeSy;
    private static volatile double fakeJumpStartY;
    private static volatile double fakeMoveBaseX;
    private static volatile double fakeMoveBaseZ;

    /**
     * 准备 bot：等旧假人从服务端 PlayerList 完全消失再创建。
     * 每个套件用唯一假人名（botName = "tbot-" + suite）——离线登录假人同名 = 同一离线 UUID =
     * 服务端按 playerdata/<uuid>.dat 加载同一个玩家存档，物品栏会跨套件继承（实测 crafting 的
     * 木棍跑到 furnace 假人背包）。唯一名 = 不同 UUID = 空玩家数据。返回 null 表示还在等旧假人消失。
     */
    private static Bot prepareBot(MinecraftServer server) {
        if (bot != null) {
            return bot;
        }
        if (botName == null) {
            botName = botNameFor(suite);
        }
        if (suiteCooldown > 0) {
            // 套件间冷却：等上一套件假人断开 + 残留 lastDamageSource（40 tick）过期，避免干扰
            suiteCooldown--;
            return null;
        }
        if (server.getPlayerList().getPlayerByName(botName) != null) {
            MockplayerApi.bots().removeBot(botName, "test");
            return null;
        }
        bot = MockplayerApi.bots().createBot(BotProfile.of(botName, "test"));
        return bot;
    }

    private static void runApiSmoke(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        switch (step) {
            case 0 -> {
                prepareBot(mc.getSingleplayerServer());
                if (bot != null) {
                    check("createBot non-null", true);
                    step = 1;
                }
                // bot null = 套件间冷却中，继续等
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
                // 记录假人初始服务端位置（移动端到端验证基线）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        fakeSx = sp.getX();
                        fakeSz = sp.getZ();
                        fakeSy = sp.getY();
                        fakeMoveBaseX = sp.getX();
                        fakeMoveBaseZ = sp.getZ();
                    }
                });
                step = 4;
            }
            case 4 -> {
                // 移动端到端：setForward(1) 后假人应真的移动（服务端位置 x/z 变化，不只是 input 位）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        fakeSx = sp.getX();
                        fakeSz = sp.getZ();
                        fakeSy = sp.getY();
                    }
                });
                if (Math.abs(fakeSx - fakeMoveBaseX) > 0.5 || Math.abs(fakeSz - fakeMoveBaseZ) > 0.5) {
                    check("fake moved on server (dx/dz changed)", true);
                    bot.actions().stop();
                    // 跳跃端到端：setForward + jump → 服务端 Y 上升
                    bot.actions().setForward(1.0F);
                    bot.actions().jump();
                    fakeMoveBaseX = fakeSx;
                    fakeMoveBaseZ = fakeSz;
                    fakeJumpStartY = fakeSy;
                    waitTicks = 0;
                    step = 5;
                } else if (++waitTicks > 200) {
                    fail("fake movement timeout");
                    bot.actions().stop();
                    step = 5;
                }
            }
            case 5 -> {
                // 跳跃端到端：服务端 Y 应高于起跳 Y + 0.3（假人真的跳起来了）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        fakeSy = sp.getY();
                    }
                });
                if (fakeSy > fakeJumpStartY + 0.3) {
                    check("fake jumped on server (y +" + String.format("%.2f", fakeSy - fakeJumpStartY) + ")", true);
                    bot.actions().stop();
                    step = 6;
                } else if (++waitTicks > 100) {
                    fail("fake jump timeout");
                    bot.actions().stop();
                    step = 6;
                }
            }
            case 6 -> {
                check("stop resets input", !bot.getLocalPlayer().input.keyPresses.forward()
                        && !bot.getLocalPlayer().input.keyPresses.shift());
                step = 7;
            }
            case 7 -> {
                // 实体/区块同步有延迟：等假人 level 有区块 + 周围出现主玩家实体（neoforge 端更慢，等 300 tick）
                if ((bot.isBlockLoaded(bot.getLocalPlayer().blockPosition())
                        && !bot.getEntitiesNear(64).isEmpty()) || ++waitTicks > 300) {
                    check("getEntitiesNear", !bot.getEntitiesNear(64).isEmpty());
                    check("isBlockLoaded", bot.isBlockLoaded(bot.getLocalPlayer().blockPosition()));
                    check("getBlockState air check", bot.getBlockState(bot.getLocalPlayer().blockPosition()) != null);
                    step = 8;
                }
            }
            case 8 -> {
                check("getContainer empty (no menu open)", bot.getContainer().isEmpty());
                // 新原语冒烟：无环境空操作不崩（drop/mount/dismount/持续攻击使用）
                bot.actions().drop(0, false);
                bot.actions().mount(true);
                bot.actions().dismount();
                bot.actions().sustainedAttack(null);
                bot.actions().sustainedUse(null);
                bot.actions().stopSustained();
                check("new primitives no-crash", true);
                check("removeBot own owner", MockplayerApi.bots().removeBot(botName, "test") == RemoveResult.REMOVED);
                check("removeBot not found", MockplayerApi.bots().removeBot(botName, "test") == RemoveResult.NOT_FOUND);
                finishSuite();
            }
        }
    }

    // ===== containers：服务端开箱 → 假人客户端容器会话断言 → 关闭 =====

    private static BlockPos containerPos;
    private static boolean openIssued;
    private static boolean openIssued2;
    private static boolean chestGiveDone;
    private static volatile boolean chestSlotHasStone;
    private static volatile boolean chestSlotEmpty;

    private static void runContainers(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
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
                net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
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
                    step = 7;
                }
            }
            case 7 -> {
                // give 石头 → 假人背包同步后放/取箱子物品（端到端：click → 服务端箱子状态变化）
                if (!chestGiveDone) {
                    chestGiveDone = true;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give " + botName + " minecraft:stone 1");
                    });
                }
                step = 8;
            }
            case 8 -> {
                if (bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.STONE) > 0) {
                    check("client has stone", true);
                    step = 9;
                } else if (++waitTicks > 200) {
                    // give 与假人刚登录背包初始化竞态可能丢失 → 重试 give
                    chestGiveDone = false;
                    waitTicks = 0;
                    step = 7;
                }
            }
            case 9 -> {
                if (!openIssued2) {
                    openIssued2 = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(containerPos));
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(containerPos), Direction.WEST, containerPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                }
                step = 10;
            }
            case 10 -> {
                Optional<BotContainer> container = bot.getContainer();
                if (container.isPresent()) {
                    // 假人刚登录背包空：give 的石头在快捷栏槽0 = ChestMenu(9x3) 槽54（0-26 容器 / 27-53 背包 / 54-62 快捷栏）
                    container.get().click(54, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 石头→鼠标
                    container.get().click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 石头→箱子槽0
                    check("put stone into chest issued", true);
                    step = 11;
                }
            }
            case 11 -> {
                server.execute(() -> {
                    net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                            (net.minecraft.world.level.block.entity.ChestBlockEntity) server.getLevel(Level.OVERWORLD).getBlockEntity(containerPos);
                    chestSlotHasStone = chest != null && chest.getItem(0).is(net.minecraft.world.item.Items.STONE);
                });
                if (chestSlotHasStone) {
                    check("chest slot0 has stone (server)", true);
                    bot.getContainer().ifPresent(c -> c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP)); // 取回
                    step = 12;
                } else if (++waitTicks > 100) {
                    fail("put stone into chest timeout");
                    step = 12;
                }
            }
            case 12 -> {
                server.execute(() -> {
                    net.minecraft.world.level.block.entity.ChestBlockEntity chest =
                            (net.minecraft.world.level.block.entity.ChestBlockEntity) server.getLevel(Level.OVERWORLD).getBlockEntity(containerPos);
                    chestSlotEmpty = chest != null && chest.getItem(0).isEmpty();
                });
                if (chestSlotEmpty) {
                    check("chest slot0 empty after pickup (server)", true);
                    step = 13;
                } else if (++waitTicks > 100) {
                    fail("pickup stone from chest timeout");
                    step = 13;
                }
            }
            case 13 -> {
                bot.getContainer().ifPresent(c -> c.close());
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
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

    // ===== 矛 combat 端到端（d0535eb 矛 DAMAGE_TYPE 编码崩回归——服务端不崩 + SPEAR 伤害生效） =====
    // 矛两种用法：
    //   1) 蓄力戳刺（combat-stab）：普通 attack，需蓄力满（MINIMUM_ATTACK_CHARGE=1.0）+ 距离 AttackRange 2.0~4.5
    //   2) 冲刺戳刺（combat-sprint）：使用矛（startUsingItem）+ 冲刺（KineticWeapon 相对速度 >= 4.6）→ 服务端 onUseTick
    // 用 /summon 命令 spawn 无 AI 尸壳（Husk 免疫阳光自燃，排除"掉血=自燃"假阳性）；断言受伤攻击类型 = SPEAR。

    private static boolean stabSummoned;
    private static boolean stabSpearGiven;
    private static boolean stabAttacked;
    private static boolean sprintSummoned;
    private static boolean sprintSpearGiven;
    private static boolean sprintThrustIssued;
    private static volatile boolean combatLastDamageIsSpear;
    private static volatile float combatServerScale = -1;
    private static volatile boolean sprintFacingOk;
    private static volatile boolean sprintUsingOk;
    private static volatile float combatHuskHealth = -1;
    private static volatile boolean encSwordEnchanted;

    /** 服务端 /summon 无 AI 尸壳在假人前方 ahead 格（Husk 免疫阳光自燃） */
    private static void summonHusk(MinecraftServer server, double ahead) {
        server.execute(() -> {
            net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
            if (sp != null) {
                String cmd = String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                        sp.getX() + ahead, sp.getY(), sp.getZ());
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            }
        });
    }

    /** 服务端给假人铁矛 + 客户端选快捷栏 0 */
    private static void giveSpear(MinecraftServer server) {
        server.execute(() -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give " + botName + " minecraft:iron_spear 1");
        });
        bot.getLocalPlayer().getInventory().setSelectedSlot(0);
    }

    /** combat 收尾：清理服务端 Husk（避免残留 lastDamageSource 干扰下一套件） */
    private static void removeHusks(MinecraftServer server) {
        server.execute(() -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=minecraft:husk]");
        });
    }

    /** 读服务端 Husk 最近伤害来源 → combatLastDamageIsSpear（40 tick 内有效）+ 服务端蓄力 */
    private static void readSpearDamage(MinecraftServer server) {
        server.execute(() -> {
            net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
            if (sp != null) {
                net.minecraft.server.level.ServerLevel level = server.getLevel(Level.OVERWORLD);
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        sp.getX() - 12, sp.getY() - 12, sp.getZ() - 12,
                        sp.getX() + 12, sp.getY() + 12, sp.getZ() + 12);
                java.util.List<net.minecraft.world.entity.monster.zombie.Zombie> zombies =
                        java.util.List.copyOf(level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, box));
                net.minecraft.world.damagesource.DamageSource ds = zombies.isEmpty() ? null : zombies.get(0).getLastDamageSource();
                combatLastDamageIsSpear = ds != null && ds.is(net.minecraft.world.damagesource.DamageTypes.SPEAR);
                combatServerScale = sp.getAttackStrengthScale(1.0F);
                combatHuskHealth = zombies.isEmpty() ? -1 : zombies.get(0).getHealth();
            }
        });
    }

    // ===== combat-stab：蓄力戳刺（普通 attack，站着也能戳） =====

    private static void runCombatStab(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                // Husk 前 3 格（矛 AttackRange 2.0~4.5 内，避开 minReach 太近打不到）
                if (!stabSummoned) {
                    stabSummoned = true;
                    summonHusk(server, 3.0);
                }
                step = 2;
            }
            case 2 -> {
                if (bot.getEntitiesNear(64).stream().anyMatch(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)) {
                    check("client sees husk", true);
                    step = 3;
                }
            }
            case 3 -> {
                if (!stabSpearGiven) {
                    stabSpearGiven = true;
                    giveSpear(server);
                }
                step = 4;
            }
            case 4 -> {
                if (bot.getLocalPlayer().getMainHandItem().is(net.minecraft.world.item.Items.IRON_SPEAR)) {
                    check("client holds spear", true);
                    step = 5;
                } else if (++waitTicks > 200) {
                    stabSpearGiven = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 3;
                }
            }
            case 5 -> {
                // 左键戳刺：矛是 PIERCING_WEAPON，普通 attack 被服务端 handleAttack 跳过；
                // 戳刺走 ServerboundPlayerActionPacket(STAB) → 服务端 PiercingWeapon.attack（SPEAR 伤害）。
                // 需要攻击蓄力满（MINIMUM_ATTACK_CHARGE，服务端 cannotAttackWithItem 检查）。
                readSpearDamage(server);
                if (combatLastDamageIsSpear && combatHuskHealth >= 0 && combatHuskHealth < 20) {
                    check("husk hurt by SPEAR (left-click stab)", true);
                    check("fake still PLAYING (no server crash)", bot.getLifecycle() == BotLifecycle.PLAYING);
                    step = 6;
                } else if (combatServerScale >= 1.0F) {
                    // 戳刺射线沿视线方向（ProjectileUtil.getHitEntitiesAlong），必须先面向僵尸
                    net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                            .findFirst().orElse(null);
                    if (target != null) {
                        bot.actions().lookAt(target);
                        bot.actions().stab();
                        stabAttacked = true;
                        check("left-click STAB issued", true);
                    }
                } else if (++waitTicks > 400) {
                    fail("stab timeout (no SPEAR damage)");
                    step = 6;
                }
            }
            case 6 -> {
                removeHusks(server);
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== combat-sprint：冲刺戳刺（使用矛 + 疾跑 → KineticWeapon 相对速度伤害） =====

    private static void runCombatSprint(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                // Husk 前 6 格（留冲刺距离；冲刺时相对速度 ~5.6 >= KineticWeapon 条件 4.6）
                if (!sprintSummoned) {
                    sprintSummoned = true;
                    summonHusk(server, 6.0);
                }
                step = 2;
            }
            case 2 -> {
                if (bot.getEntitiesNear(64).stream().anyMatch(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)) {
                    check("client sees husk", true);
                    step = 3;
                }
            }
            case 3 -> {
                if (!sprintSpearGiven) {
                    sprintSpearGiven = true;
                    giveSpear(server);
                }
                step = 4;
            }
            case 4 -> {
                if (bot.getLocalPlayer().getMainHandItem().is(net.minecraft.world.item.Items.IRON_SPEAR)) {
                    check("client holds spear", true);
                    step = 5;
                } else if (++waitTicks > 200) {
                    sprintSpearGiven = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 3;
                }
            }
            case 5 -> {
                if (!sprintThrustIssued) {
                    sprintThrustIssued = true;
                    net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                            .findFirst().orElse(null);
                    if (target != null) {
                        bot.actions().lookAt(target);
                    }
                    bot.actions().setForward(1.0F);
                    bot.actions().setSprint(true);
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                    // 记录冲刺移动基线（服务端位置，验证假人真的冲出去）
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            fakeMoveBaseX = sp.getX();
                            fakeMoveBaseZ = sp.getZ();
                            fakeSx = sp.getX();
                            fakeSz = sp.getZ();
                        }
                    });
                    check("sprint-thrust issued (sprint + use)", true);
                }
                step = 6;
            }
            case 6 -> {
                // 冲刺移动：KineticWeapon 相对速度伤害的前提（假人真的冲出去）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        fakeSx = sp.getX();
                        fakeSz = sp.getZ();
                    }
                });
                if (Math.abs(fakeSx - fakeMoveBaseX) > 0.5 || Math.abs(fakeSz - fakeMoveBaseZ) > 0.5) {
                    check("fake sprinted on server (moved)", true);
                    waitTicks = 0;
                    step = 7;
                } else if (++waitTicks > 300) {
                    fail("sprint movement timeout (fake didn't move)");
                    step = 7;
                }
            }
            case 7 -> {
                // 面向 Husk + 正在使用矛（戳刺射线沿视线 + onUseTick 前提）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        net.minecraft.server.level.ServerLevel level = server.getLevel(Level.OVERWORLD);
                        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                                sp.getX() - 12, sp.getY() - 12, sp.getZ() - 12,
                                sp.getX() + 12, sp.getY() + 12, sp.getZ() + 12);
                                java.util.List<net.minecraft.world.entity.monster.zombie.Zombie> zombies =
                                        java.util.List.copyOf(level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, box));
                        if (!zombies.isEmpty()) {
                            net.minecraft.world.entity.Entity husk = zombies.get(0);
                            // 假人 yRot 朝向 Husk 的夹角（MC yRot：0=南，顺时针）
                            double dx = husk.getX() - sp.getX();
                            double dz = husk.getZ() - sp.getZ();
                            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
                            double yaw = sp.getYRot() % 360.0;
                            if (yaw < 0) {
                                yaw += 360.0;
                            }
                            double diff = Math.abs(yaw - targetYaw);
                            if (diff > 180.0) {
                                diff = 360.0 - diff;
                            }
                            sprintFacingOk = diff < 45.0;
                            sprintUsingOk = sp.isUsingItem();
                        }
                    }
                });
                if (sprintFacingOk && sprintUsingOk) {
                    check("fake facing husk", true);
                    check("fake using spear", true);
                    waitTicks = 0;
                    step = 8;
                } else if (++waitTicks > 200) {
                    fail("sprint facing/using timeout (facing=" + sprintFacingOk + " using=" + sprintUsingOk + ")");
                    step = 8;
                }
            }
            case 8 -> {
                readSpearDamage(server);
                if (combatLastDamageIsSpear && combatHuskHealth >= 0 && combatHuskHealth < 20) {
                    check("husk hurt by SPEAR (sprint-thrust)", true);
                    check("fake still PLAYING (no server crash)", bot.getLifecycle() == BotLifecycle.PLAYING);
                    step = 9;
                } else if (++waitTicks > 300) {
                    fail("sprint-thrust timeout (no SPEAR damage)");
                    step = 9;
                }
            }
            case 9 -> {
                removeHusks(server);
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== crafting：工作台合成（give 木板 → 放合成格 → 取 4 木棍，服务端验证） =====

    private static BlockPos craftPos;
    private static boolean craftPlanksGiven;
    private static boolean craftClicked;
    private static volatile int craftSticksCount;

    private static void runCrafting(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                if (craftPos == null) {
                    craftPos = bot.getLocalPlayer().blockPosition().offset(3, 0, 0);
                    BlockPos p = craftPos;
                    server.execute(() -> server.getLevel(Level.OVERWORLD).setBlock(p, Blocks.CRAFTING_TABLE.defaultBlockState(), 3));
                }
                step = 2;
            }
            case 2 -> {
                if (bot.getBlockState(craftPos).is(Blocks.CRAFTING_TABLE)) {
                    check("client sees crafting table", true);
                    step = 3;
                }
            }
            case 3 -> {
                if (!openIssued) {
                    openIssued = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(craftPos));
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(craftPos), Direction.WEST, craftPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                    check("useItemOn crafting table issued", true);
                }
                step = 4;
            }
            case 4 -> {
                Optional<BotContainer> container = bot.getContainer();
                if (container.isPresent()) {
                    check("getContainer present", true);
                    check("menuType is crafting", container.get().getMenuType() == MenuType.CRAFTING);
                    step = 5;
                }
            }
            case 5 -> {
                if (!craftPlanksGiven) {
                    craftPlanksGiven = true;
                    // 2 木板（竖排 → 4 木棍；1 木板单独是橡木按钮，别踩坑）
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give " + botName + " minecraft:oak_planks 2");
                    });
                }
                step = 6;
            }
            case 6 -> {
                if (bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.OAK_PLANKS) >= 2) {
                    check("client has 2 planks", true);
                    step = 7;
                } else if (++waitTicks > 200) {
                    craftPlanksGiven = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 5;
                }
            }
            case 7 -> {
                if (!craftClicked) {
                    craftClicked = true;
                    // give 2 木板叠在快捷栏0 = CraftingMenu 槽37（0 结果 / 1-9 合成格 / 10-36 主背包 / 37-45 快捷栏）
                    // click(37) 一次拿走 2 个，PICKUP 放合成格每次 1 个；合成格1（左上）+4（左中）= 竖排 2 木板 → 4 木棍
                    bot.getContainer().ifPresent(c -> {
                        c.click(37, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 左键拿 2 木板→鼠标
                        c.click(1, 1, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 右键放 1→合成格1
                        c.click(4, 1, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 右键放 1→合成格4（竖排）
                        c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 左键取 4 木棍→鼠标
                        c.click(37, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 木棍放回快捷栏0
                    });
                    check("crafting clicks issued", true);
                }
                step = 8;
            }
            case 8 -> {
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    craftSticksCount = sp != null ? sp.getInventory().countItem(net.minecraft.world.item.Items.STICK) : 0;
                });
                if (craftSticksCount > 0) {
                    check("crafted 4 sticks on server", craftSticksCount >= 4, "count=" + craftSticksCount);
                    step = 9;
                } else if (++waitTicks > 200) {
                    fail("crafting timeout (sticks=" + craftSticksCount + ")");
                    step = 9;
                }
            }
            case 9 -> {
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== furnace：熔炉烧制（give 原木+煤炭 → 放原料/燃料 → 取木炭产物） =====

    private static BlockPos furnacePos;
    private static boolean furnaceGiven;
    private static boolean furnaceClicked;
    private static volatile boolean furnaceCharcoal;

    private static void runFurnace(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                if (furnacePos == null) {
                    furnacePos = bot.getLocalPlayer().blockPosition().offset(3, 0, 0);
                    BlockPos p = furnacePos;
                    server.execute(() -> server.getLevel(Level.OVERWORLD).setBlock(p, Blocks.FURNACE.defaultBlockState(), 3));
                }
                step = 2;
            }
            case 2 -> {
                if (bot.getBlockState(furnacePos).is(Blocks.FURNACE)) {
                    check("client sees furnace", true);
                    step = 3;
                }
            }
            case 3 -> {
                if (!openIssued) {
                    openIssued = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(furnacePos));
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(furnacePos), Direction.WEST, furnacePos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                    check("useItemOn furnace issued", true);
                }
                step = 4;
            }
            case 4 -> {
                Optional<BotContainer> container = bot.getContainer();
                if (container.isPresent()) {
                    check("getContainer present", true);
                    check("menuType is furnace", container.get().getMenuType() == MenuType.FURNACE);
                    step = 5;
                }
            }
            case 5 -> {
                if (!furnaceGiven) {
                    furnaceGiven = true;
                    server.execute(() -> {
                        var source = server.createCommandSourceStack();
                        server.getCommands().performPrefixedCommand(source, "give " + botName + " minecraft:oak_log 1");
                        server.getCommands().performPrefixedCommand(source, "give " + botName + " minecraft:coal 1");
                    });
                }
                step = 6;
            }
            case 6 -> {
                if (bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.OAK_LOG) > 0
                        && bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.COAL) > 0) {
                    check("client has log + coal", true);
                    step = 7;
                } else if (++waitTicks > 200) {
                    furnaceGiven = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 5;
                }
            }
            case 7 -> {
                if (!furnaceClicked) {
                    furnaceClicked = true;
                    // 快捷栏槽0=原木、槽1=煤炭（FurnaceMenu：0 原料 / 1 燃料 / 2 产物 / 3-29 主背包 / 30-38 快捷栏）
                    bot.getContainer().ifPresent(c -> {
                        c.click(30, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 原木→鼠标
                        c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 原木→原料槽
                        c.click(31, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 煤炭→鼠标
                        c.click(1, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 煤炭→燃料槽
                    });
                    check("furnace load clicks issued", true);
                }
                step = 8;
            }
            case 8 -> {
                // 等烧制完成：用服务端熔炉 BlockEntity 产物槽（比客户端菜单同步可靠，flaky 更稳）
                server.execute(() -> {
                    net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity be =
                            (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) server.getLevel(Level.OVERWORLD).getBlockEntity(furnacePos);
                    furnaceCharcoal = be != null && be.getItem(2).is(net.minecraft.world.item.Items.CHARCOAL);
                });
                if (furnaceCharcoal) {
                    check("furnace produced charcoal", true);
                    step = 9;
                } else if (++waitTicks > 300) {
                    fail("furnace timeout (no charcoal)");
                    step = 9;
                }
            }
            case 9 -> {
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== enchanting：附魔台（验证 GUI 通用抽象 getEnchantment + 附魔执行） =====

    private static BlockPos encPos;
    private static boolean encOpened;
    private static boolean encGiveDone;
    private static boolean encLoaded;
    private static volatile int encCost0;

    private static void runEnchanting(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                if (encPos == null) {
                    encPos = bot.getLocalPlayer().blockPosition().offset(3, 0, 0);
                    BlockPos p = encPos;
                    server.execute(() -> server.getLevel(Level.OVERWORLD).setBlock(p, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3));
                }
                step = 2;
            }
            case 2 -> {
                if (bot.getBlockState(encPos).is(Blocks.ENCHANTING_TABLE)) {
                    check("client sees enchanting table", true);
                    step = 3;
                }
            }
            case 3 -> {
                if (!encOpened) {
                    encOpened = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(encPos));
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(encPos), Direction.WEST, encPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                    check("useItemOn enchanting table issued", true);
                }
                step = 4;
            }
            case 4 -> {
                Optional<BotContainer> enc = bot.getContainer();
                if (enc.isPresent()) {
                    check("getScreen present", bot.getScreen().isPresent());
                    check("getContainer present (enchanting)", true);
                    check("menuType is enchanting", enc.get().getMenuType() == MenuType.ENCHANTMENT);
                    step = 5;
                }
            }
            case 5 -> {
                if (!encGiveDone) {
                    encGiveDone = true;
                    server.execute(() -> {
                        var source = server.createCommandSourceStack();
                        server.getCommands().performPrefixedCommand(source, "give " + botName + " minecraft:diamond_sword 1");
                        server.getCommands().performPrefixedCommand(source, "give " + botName + " minecraft:lapis_lazuli 1");
                        server.getCommands().performPrefixedCommand(source, "experience set " + botName + " 30 levels");
                    });
                }
                step = 6;
            }
            case 6 -> {
                if (bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.DIAMOND_SWORD) > 0
                        && bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.LAPIS_LAZULI) > 0) {
                    check("client has sword + lapis", true);
                    step = 7;
                } else if (++waitTicks > 200) {
                    encGiveDone = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 5;
                }
            }
            case 7 -> {
                if (!encLoaded) {
                    encLoaded = true;
                    // give 的剑/青金石在快捷栏0/1 = EnchantmentMenu 槽29/30（0 物品 / 1 青金石 / 2-28 主背包 / 29-37 快捷栏）
                    bot.getContainer().ifPresent(c -> {
                        c.click(29, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 剑→鼠标
                        c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 剑→附魔槽0
                        c.click(30, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 青金石→鼠标
                        c.click(1, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 青金石→槽1
                    });
                    check("enchantment load clicks issued", true);
                }
                step = 8;
            }
            case 8 -> {
                // 等服务端算出附魔成本（物品+青金石放入后 slotsChanged 更新 costs；raw() cast 原版菜单读）
                bot.getContainer().ifPresent(c -> {
                    if (c.getMenuType() == MenuType.ENCHANTMENT) {
                        encCost0 = ((net.minecraft.world.inventory.EnchantmentMenu) c.raw()).costs[0];
                    }
                });
                if (encCost0 > 0) {
                    check("enchantment costs available (cost0=" + encCost0 + ")", true);
                    step = 9;
                } else if (++waitTicks > 200) {
                    fail("enchantment costs timeout (cost0=" + encCost0 + ")");
                    step = 9;
                }
            }
            case 9 -> {
                // 执行附魔（通用 clickButton，服务端校验材料/经验）→ 断言附魔槽0 的剑有 ENCHANTMENTS
                bot.getContainer().ifPresent(c -> c.clickButton(0));
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        net.minecraft.world.item.ItemStack sword = sp.containerMenu.getSlot(0).getItem();
                        encSwordEnchanted = sword.is(net.minecraft.world.item.Items.DIAMOND_SWORD)
                                && sword.has(net.minecraft.core.component.DataComponents.ENCHANTMENTS);
                    }
                });
                if (encSwordEnchanted) {
                    check("sword enchanted (ENCHANTMENTS component)", true);
                    step = 10;
                } else if (++waitTicks > 200) {
                    fail("enchant timeout (sword not enchanted)");
                    step = 10;
                }
            }
            case 10 -> {
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== merchant：服务端开交易菜单（ClientSideMerchant，无实体依赖）→ 假人交易会话断言 =====

    private static boolean merchantSummoned;
    private static boolean merchantInteracted;
    private static boolean merchantGiveDone;
    private static boolean merchantTraded;
    private static volatile boolean merchantGotDiamond;

    private static void runMerchant(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                prepareBot(server);
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> {
                // /summon 无 AI 村民（NoAI 不走动，交易菜单 stillValid 恒 true）+ 特殊交易覆盖原版报价
                // 26.2 交易 NBT：MerchantOffers.CODEC = MerchantOffer.CODEC.listOf().optionalFieldOf("Recipes") →
                // Offers 是 map（带 Recipes 键），Recipes 是 list（实测 "Not a map" 后确认格式）
                if (!merchantSummoned) {
                    merchantSummoned = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            String cmd = String.format(
                                    "summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:diamond\",count:1},maxUses:99,xp:1}]}}",
                                    sp.getX() + 3.0, sp.getY(), sp.getZ());
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                        }
                    });
                }
                step = 2;
            }
            case 2 -> {
                if (bot.getEntitiesNear(64).stream().anyMatch(e -> e instanceof net.minecraft.world.entity.npc.villager.Villager)) {
                    check("client sees villager", true);
                    step = 3;
                }
            }
            case 3 -> {
                if (!merchantInteracted) {
                    merchantInteracted = true;
                    net.minecraft.world.entity.Entity villager = bot.getEntitiesNear(64).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.npc.villager.Villager)
                            .findFirst().orElse(null);
                    if (villager != null) {
                        bot.actions().lookAt(villager);
                        bot.actions().interact(villager);
                        check("interact villager issued", true);
                    }
                }
                step = 4;
            }
            case 4 -> {
                Optional<BotContainer> merchant = bot.getContainer();
                if (merchant.isPresent() && merchant.get().getMenuType() == MenuType.MERCHANT) {
                    check("getContainer present (real villager merchant)", true);
                    check("menuType is merchant", true);
                    step = 5;
                }
            }
            case 5 -> {
                if (!merchantGiveDone) {
                    merchantGiveDone = true;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "give " + botName + " minecraft:emerald 1");
                    });
                }
                step = 6;
            }
            case 6 -> {
                if (bot.getLocalPlayer().getInventory().countItem(net.minecraft.world.item.Items.EMERALD) > 0) {
                    check("client has emerald", true);
                    step = 7;
                } else if (++waitTicks > 200) {
                    merchantGiveDone = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 5;
                }
            }
            case 7 -> {
                if (!merchantTraded) {
                    merchantTraded = true;
                    // give 的 emerald 在快捷栏0 = MerchantMenu 槽30（0/1 买入 / 2 结果 / 3-29 主背包 / 30-38 快捷栏）
                    bot.getContainer().ifPresent(c -> {
                        c.click(30, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // emerald→鼠标
                        c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // emerald→交易槽0
                        c.selectTrade(0);                                                    // 选中特殊交易
                        c.click(2, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 点结果槽交易 → 钻石到鼠标
                        c.click(30, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 钻石放回快捷栏0
                    });
                    check("merchant trade clicks issued", true);
                }
                step = 8;
            }
            case 8 -> {
                // 服务端验证：假人背包出现钻石（交易真的执行）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        merchantGotDiamond = sp.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) > 0;
                    }
                });
                if (merchantGotDiamond) {
                    check("traded emerald → diamond (server)", true);
                    step = 9;
                } else if (++waitTicks > 300) {
                    fail("merchant trade timeout (no diamond)");
                    step = 9;
                }
            }
            case 9 -> {
                // 清理村民（避免残留干扰后续套件）
                server.execute(() -> {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=minecraft:villager]");
                });
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== 断言与结果 =====

    private static void check(String name, boolean ok) {
        check(name, ok, ok ? "" : "assertion failed");
    }

    /** 断言（可带失败详情：实际值/期望值，FAIL 时打印具体原因） */
    private static void check(String name, boolean ok, String detail) {
        records.add(new Record(name, ok, ok ? "" : detail));
        log(name, ok, detail);
    }

    private static void fail(String name) {
        records.add(new Record(name, false, "failure"));
        log(name, false, "failure");
    }

    private static void log(String name, boolean ok, String detail) {
        String suffix = (ok || detail == null || detail.isEmpty()) ? "" : " <" + detail + ">";
        System.out.println("[mocktest] " + (ok ? "PASS " : "FAIL ") + name + suffix);
    }

    /** 当前套件收尾：写结果 JSON → 推进下一个套件（world 已在，直接 RUN）或全部完成退出 */
    private static void finishSuite() {
        long elapsed = System.currentTimeMillis() - suiteStart;
        writeResultJson(elapsed);
        boolean passed = records.stream().allMatch(Record::passed);
        System.out.println("[mocktest] suite " + suite + " " + (passed ? "PASSED" : "FAILED")
                + " (" + records.size() + " checks) in " + elapsed + "ms");
        suiteIndex++;
        if (suiteIndex < suiteQueue.size()) {
            suite = suiteQueue.get(suiteIndex);
            resetSuiteState();
            phase = Phase.RUN;
            phaseStart = System.currentTimeMillis();
            suiteStart = phaseStart;
        } else {
            phase = Phase.DONE;
            // 全部套件跑完：主动退出游戏，保证 gradlew 返回、bash 不被卡死
            Minecraft.getInstance().stop();
        }
    }

    /** 套件间重置（世界不重建，只重置套件状态 + 清理上一个假人；假人名用套件唯一名） */
    private static void resetSuiteState() {
        step = 0;
        waitTicks = 0;
        suiteCooldown = 40; // 套件间冷却 2 秒：假人断开完成 + 残留 lastDamageSource 过期
        records.clear();
        botName = botNameFor(suite);
        for (Bot b : MockplayerApi.bots().getBots()) {
            MockplayerApi.bots().removeBot(b.getName(), "test");
        }
        bot = null;
        containerPos = null;
        openIssued = false;
        openIssued2 = false;
        chestGiveDone = false;
        chestSlotHasStone = false;
        chestSlotEmpty = false;
        merchantSummoned = false;
        merchantInteracted = false;
        merchantGiveDone = false;
        merchantTraded = false;
        merchantGotDiamond = false;
        stabSummoned = false;
        stabSpearGiven = false;
        stabAttacked = false;
        sprintSummoned = false;
        sprintSpearGiven = false;
        sprintThrustIssued = false;
        combatLastDamageIsSpear = false;
        combatServerScale = -1;
        combatHuskHealth = -1;
        encPos = null;
        encOpened = false;
        encGiveDone = false;
        encLoaded = false;
        encCost0 = 0;
        encSwordEnchanted = false;
        sprintFacingOk = false;
        sprintUsingOk = false;
        craftPos = null;
        craftPlanksGiven = false;
        craftClicked = false;
        craftSticksCount = 0;
        furnacePos = null;
        furnaceGiven = false;
        furnaceClicked = false;
        furnaceCharcoal = false;
    }

    /** 写当前套件结果 JSON（runs/client/test-results/<suite>.json），含耗时 */
    private static void writeResultJson(long durationMs) {
        boolean passed = records.stream().allMatch(Record::passed);
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"suite\": \"").append(suite).append("\",\n");
        json.append("  \"passed\": ").append(passed).append(",\n");
        json.append("  \"duration_ms\": ").append(durationMs).append(",\n");
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
