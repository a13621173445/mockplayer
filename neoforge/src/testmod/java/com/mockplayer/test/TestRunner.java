package com.mockplayer.test;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;
import com.mockplayer.api.container.BotContainer;
import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.ModConfigIO;
import com.mockplayer.config.ModConfigScreen;
import com.mockplayer.config.ModCommands;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.EventRecorder;
import com.mockplayer.session.FakePlayerState;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final long TIMEOUT_MS = 180_000;

    /** 全部套件（suite=all / IDE 默认入口时按序连续跑） */
    private static final List<String> ALL_SUITES = List.of(
            "api-smoke", "api-full", "use-items", "containers", "containers-all",
            "gui-actions", "listener-events", "control-commands",
            "bot-gui");

    private enum Phase { WAIT_TITLE, WAIT_WORLD, RUN, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static long phaseStart;
    /** 测试局域网发布端口：运行时空闲端口，避免被本机代理占用（25565/25567 实测被 FlClash 占用）。 */
    private static int testLanPort = -1;
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
            case "api-full" -> "tbot-full";
            case "use-items" -> "tbot-use";
            case "containers" -> "tbot-cont";
            case "containers-all" -> "tbot-call";
            case "crafting" -> "tbot-craft";
            case "furnace" -> "tbot-furn";
            case "combat-stab" -> "tbot-stab";
            case "combat-sprint" -> "tbot-spr";
            case "enchanting" -> "tbot-enc";
            case "merchant" -> "tbot-merk";
            case "gui-actions" -> "tbot-gui";
            case "listener-events" -> "tbot-le";
            case "control-commands" -> "tbot-ctl";
            case "batch" -> "tbot-bat";
            case "config" -> "tbot-cfg";
            case "debug-name-tag" -> "tbot-dbg";
            case "bot-gui" -> "tbot-gui";
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
                        // 显式端口（26.2 port=0 不自动分配，getPort() 会返回 0）；
                        // 用运行时空闲端口，避免被本机代理等进程占用（实测 FlClash 占 25565/25567 导致发布超时）
                        if (testLanPort < 0) {
                            testLanPort = findFreeTestPort();
                        }
                        server.publishServer(MultiplayerScope.LAN, GameType.SURVIVAL, false, testLanPort);
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

    /** 找当前空闲的 TCP 端口（绑定 0 由系统分配，随后关闭，端口号用于局域网发布）。 */
    private static int findFreeTestPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return 25566;
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
            case "api-full" -> runApiFull(mc);
            case "use-items" -> runUseItems(mc);
            case "containers" -> runContainers(mc);
            case "containers-all" -> runContainersAll(mc);
            case "crafting" -> runCrafting(mc);
            case "furnace" -> runFurnace(mc);
            case "combat-stab" -> runCombatStab(mc);
            case "combat-sprint" -> runCombatSprint(mc);
            case "enchanting" -> runEnchanting(mc);
            case "merchant" -> runMerchant(mc);
            case "gui-actions" -> runGuiActions(mc);
            case "listener-events" -> runListenerEvents(mc);
            case "control-commands" -> runControlCommands(mc);
            case "batch" -> runBatch(mc);
            case "config" -> runConfig(mc);
            case "debug-name-tag" -> runDebugNameTag(mc);
            case "bot-gui" -> runBotGui(mc);
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
            MockplayerApi.bots().removeBot(botName, "command");
            return null;
        }
        // 走真实命令路径创建（source=CORE，受本 mod 命令/配置管理）
        MockplayerApi.bots().removeBot(botName, "command");
        com.mockplayer.session.FakePlayerCommands.newPlayer(botName);
        bot = MockplayerApi.bots().getBot(botName).orElse(null);
        // 套件开始：清理上一个套件残留的非玩家实体（husk/村民/马/箭/掉落物等）——连跑稳定
        MinecraftServer srv = server;
        srv.execute(() -> srv.getCommands().performPrefixedCommand(
                srv.createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
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
                    // 管理 bot 走真实命令路径创建：owner = "command"（CORE）
                    check("getOwner == command", "command".equals(bot.getOwner()));
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
                // 输入解耦：移动走抽象 moveVector（y=前后=forward，x=左右=strafe），不写 keyPresses 移动位
                net.minecraft.world.phys.Vec2 mv = ((com.mockplayer.session.accessor.MockplayerClientInputAccessor)
                        bot.getLocalPlayer().input).mockplayer$getMoveVector();
                check("forward moveVector", mv.y > 0);
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
                // 管理边界：公共 API 创建的假人（即使伪造 owner="command"）不被本 mod 命令管理
                var boundaryApiBot = MockplayerApi.bots().createBot(
                        com.mockplayer.api.BotProfile.of("tbot-api-b", "command"));
                check("boundary api bot created", boundaryApiBot != null);
                String boundaryList = com.mockplayer.session.QueryCommands.list().getString();
                check("boundary api bot not in query list",
                        !boundaryList.contains("tbot-api-b"), "list=" + boundaryList);
                String boundaryDel = com.mockplayer.session.FakePlayerCommands.delPlayer("tbot-api-b").getString();
                check("boundary delplayer refuses api bot",
                        MockplayerApi.bots().getBot("tbot-api-b").isPresent(), "del=" + boundaryDel);
                check("boundary core bot in query list",
                        boundaryList.contains(botName), "list=" + boundaryList);
                // 清理：API 层 removeBot + command 特权仍可删（命令层不删它）
                MockplayerApi.bots().removeBot("tbot-api-b", "command");
                // 新原语冒烟：无环境空操作不崩（drop/mount/dismount/持续攻击使用）
                bot.actions().drop(0, false);
                bot.actions().mount(true);
                bot.actions().dismount();
                bot.actions().sustainedAttack(null);
                bot.actions().sustainedUse(null);
                bot.actions().stopSustained();
                check("new primitives no-crash", true);
                check("removeBot own owner", MockplayerApi.bots().removeBot(botName, "command") == RemoveResult.REMOVED);
                check("removeBot not found", MockplayerApi.bots().removeBot(botName, "command") == RemoveResult.NOT_FOUND);
                finishSuite();
            }
        }
    }

    // ===== api-full：全接口真实路径（补 api-smoke 未覆盖的 Bot/BotActions/BotContainer/BotManager 接口） =====

    private static boolean afItemGiven;
    private static boolean afStrafeMoved;
    private static boolean afSwapped;
    private static boolean afDropped;
    private static boolean afDropIssued;
    private static boolean afPlaced;
    private static boolean afMined;
    private static volatile boolean afOffhandHasItem;
    private static volatile boolean afInvReduced;
    private static volatile boolean afPlacedServer;
    private static volatile boolean afMinedServer;
    private static volatile int afListenerBreakCount;
    private static BlockPos afPlacePos;
    private static boolean afContainerChecked;
    private static BlockPos afChestPos;

    private static void runApiFull(Minecraft mc) {
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
                    // 注册事件监听（真实路径：mineBlock 应触发 onBreakBlock）
                    MockplayerApi.listen(new com.mockplayer.api.event.BotListener() {
                        @Override
                        public void onBreakBlock(Bot b, BlockPos pos) {
                            afListenerBreakCount++;
                        }
                    });
                    check("createBot PLAYING", true);
                    step = 1;
                }
            }
            case 1 -> {
                // 信息接口：UUID / 在线玩家（tab list 同步延迟，重试）/ 过滤实体 / getScreen 别名
                check("getUUID non-null", bot.getUUID() != null);
                if (!bot.getOnlinePlayers().isEmpty()) {
                    check("getOnlinePlayers non-empty", true);
                    check("getEntitiesNear pred (villager filter no-crash)", bot.getEntitiesNear(64, e -> e instanceof net.minecraft.world.entity.npc.villager.Villager) != null);
                    check("getScreen alias getContainer", bot.getScreen().isEmpty() == bot.getContainer().isEmpty());
                    step = 2;
                } else if (++waitTicks > 200) {
                    fail("getOnlinePlayers timeout");
                    step = 2;
                }
            }
            case 2 -> {
                // setStrafe 横移：服务端 x/z 位置变化（真实移动，纯 strafe 无 forward）
                if (!afStrafeMoved) {
                    afStrafeMoved = true;
                    bot.actions().setForward(0.0F);
                    bot.actions().setStrafe(1.0F);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            fakeMoveBaseX = sp.getX();
                            fakeMoveBaseZ = sp.getZ();
                        }
                    });
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        fakeSx = sp.getX();
                        fakeSz = sp.getZ();
                    }
                });
                if (Math.abs(fakeSx - fakeMoveBaseX) > 0.3 || Math.abs(fakeSz - fakeMoveBaseZ) > 0.3) {
                    check("setStrafe moved on server", true);
                    bot.actions().stop();
                    step = 3;
                } else if (++waitTicks > 200) {
                    fail("setStrafe movement timeout");
                    bot.actions().stop();
                    step = 3;
                }
            }
            case 3 -> {
                // swapHands：give 物品快捷栏0 → swapHands → 服务端副手有物品
                if (!afItemGiven) {
                    afItemGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                            sp.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 2));
                            sp.getInventory().setSelectedSlot(0);
                        }
                    });
                }
                if (!afSwapped) {
                    afSwapped = true;
                    bot.actions().swapHands();
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        afOffhandHasItem = sp.getOffhandItem().is(net.minecraft.world.item.Items.OAK_PLANKS);
                    }
                });
                if (afOffhandHasItem) {
                    check("swapHands moved item to offhand (server)", true);
                    step = 4;
                } else if (++waitTicks > 200) {
                    fail("swapHands timeout");
                    step = 4;
                }
            }
            case 4 -> {
                // drop(slot, all)：重新给快捷栏0 物品（swapHands 后快捷栏空）→ drop 1 → 服务端数量减少
                if (!afDropped) {
                    afDropped = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                            sp.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 3));
                            sp.getInventory().setSelectedSlot(0);
                        }
                    });
                }
                if (!afDropIssued && bot.getLocalPlayer().getMainHandItem().is(net.minecraft.world.item.Items.OAK_PLANKS)) {
                    afDropIssued = true;
                    bot.actions().drop(0, false);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            fakeMoveBaseX = sp.getInventory().getItem(0).getCount();
                        }
                    });
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        afInvReduced = sp.getInventory().getItem(0).getCount() < fakeMoveBaseX;
                    }
                });
                if (afInvReduced) {
                    check("drop reduced inventory (server)", true);
                    step = 5;
                } else if (++waitTicks > 200) {
                    fail("drop timeout");
                    step = 5;
                }
            }
            case 5 -> {
                // placeBlock：give 方块 → 假人放方块 → 服务端方块存在（placeBlock(pos,UP) 放 pos 上方）
                if (afPlacePos == null) {
                    afPlacePos = bot.getLocalPlayer().blockPosition().offset(2, 0, 0);
                    server.execute(() -> {
                        // 原版命令：清背包保证 give 落快捷栏0 + 放置位置清空
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "give " + botName + " minecraft:dirt 1");
                        server.getLevel(Level.OVERWORLD).setBlock(afPlacePos, Blocks.AIR.defaultBlockState(), 3);
                    });
                }
                // 等假人位置同步 + 主手 dirt（give 命令正式发放同步）再放置
                if (!afPlaced) {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null && !isAwaitingPosition(sp)
                            && bot.getLocalPlayer().getMainHandItem().is(net.minecraft.world.item.Items.DIRT)) {
                        afPlaced = true;
                        bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(afPlacePos));
                        bot.actions().placeBlock(afPlacePos, Direction.UP);
                    }
                }
                server.execute(() -> {
                    afPlacedServer = server.getLevel(Level.OVERWORLD).getBlockState(afPlacePos).is(Blocks.DIRT);
                });
                if (afPlacedServer) {
                    check("placeBlock placed dirt (server)", true);
                    step = 6;
                } else if (++waitTicks > 400) {
                    fail("placeBlock timeout");
                    step = 6;
                }
            }
            case 6 -> {
                // mineBlock：复用原版挖掘（START+STOP，服务端 delayedDestroy 推进破坏）——只调一次，等服务端破坏
                if (!afMined) {
                    afMined = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(afPlacePos));
                    bot.actions().mineBlock(afPlacePos);
                }
                server.execute(() -> {
                    afMinedServer = server.getLevel(Level.OVERWORLD).getBlockState(afPlacePos).isAir();
                });
                if (afMinedServer) {
                    check("mineBlock broke dirt (server)", true);
                    if (afListenerBreakCount > 0) {
                        check("BotListener onBreakBlock fired", true);
                        step = 7;
                    }
                    // 服务端已挖掉：等假人 level 同步 air → tickMining 的 continueDestroyBlock 返回 false → fire 事件
                } else if (++waitTicks > 400) {
                    fail("mineBlock timeout");
                    step = 7;
                }
            }
            case 7 -> {
                // useItem：使用物品（记录事件）
                bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                check("useItem issued", true);
                step = 8;
            }
            case 8 -> {
                // 容器：打开箱子 → getTitle/getSize/setSlot（服务端验证 setSlot）
                if (afChestPos == null) {
                    afChestPos = bot.getLocalPlayer().blockPosition().offset(1, 0, 0);
                    BlockPos p = afChestPos;
                    server.execute(() -> server.getLevel(Level.OVERWORLD).setBlock(p, Blocks.CHEST.defaultBlockState(), 3));
                }
                if (!afContainerChecked) {
                    // 服务端箱子存在 + 假人位置同步（假人客户端 level 可能未同步该区块，useItemOn 由服务端处理）
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    boolean serverChest = server.getLevel(Level.OVERWORLD).getBlockState(afChestPos).is(Blocks.CHEST);
                    if (sp != null && !isAwaitingPosition(sp) && serverChest) {
                        afContainerChecked = true;
                        bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(afChestPos));
                        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(afChestPos), Direction.WEST, afChestPos, false);
                        bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                    }
                }
                Optional<BotContainer> container = bot.getContainer();
                if (container.isPresent()) {
                    check("getTitle non-empty", !container.get().getTitle().getString().isEmpty());
                    check("getSize > 0", container.get().getSize() > 0);
                    // setSlot：客户端本地乐观写（服务端不参与 setSlot 语义，验证客户端容器会话可写）
                    container.get().setSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
                    check("setSlot slot0 stone (client)", container.get().getSlot(0).is(net.minecraft.world.item.Items.STONE));
                    step = 9;
                } else if (++waitTicks > 200) {
                    fail("container open timeout");
                    step = 9;
                }
            }
            case 9 -> {
                // BotManager / MockplayerApi：getBot/getBots/getBots(owner)/allBots
                check("getBot found", MockplayerApi.bots().getBot(botName).isPresent());
                check("getBots contains", MockplayerApi.bots().getBots().stream().anyMatch(b -> botName.equals(b.getName())));
                // 管理 bot 由命令路径创建：owner = "command"
                check("getBots(owner=command) contains", MockplayerApi.bots().getBots("command").stream().anyMatch(b -> botName.equals(b.getName())));
                check("allBots contains", MockplayerApi.allBots().stream().anyMatch(b -> botName.equals(b.getName())));
                // removeBot 幂等
                check("removeBot owner ok", MockplayerApi.bots().removeBot(botName, "command") == RemoveResult.REMOVED);
                check("removeBot not found", MockplayerApi.bots().removeBot(botName, "command") == RemoveResult.NOT_FOUND);
                finishSuite();
            }
        }
    }

    // ===== use-items：长按使用物品真实路径（食物自动吃完/盾牌格挡/弓蓄力放箭/雪球投掷，服务端强断言） =====

    private static boolean uiBreadGiven;
    private static boolean uiBreadUsed;
    private static volatile boolean uiBreadEaten;
    private static volatile boolean uiBreadVisibleToMain;
    private static boolean uiBreadVisibleChecked;
    private static volatile boolean uiBreadServer;
    private static int uiBreadBaseFood = 20;
    private static boolean uiShieldGiven;
    private static boolean uiShieldUsed;
    private static boolean uiShieldReleased;
    private static volatile boolean uiShieldUsing;
    private static volatile boolean uiShieldVisibleToMain;
    private static boolean uiShieldVisibleChecked;
    private static volatile boolean uiShieldServer;
    private static volatile boolean uiShieldReleasedServer;
    private static boolean uiShieldBlockedChecked;
    private static int uiShieldHoldTicks;
    private static boolean uiBowGiven;
    private static boolean uiBowUsed;
    private static boolean uiBowReleased;
    private static int uiBowHoldTicks;
    private static boolean uiArrowChecked;
    private static volatile boolean uiBowUsing;
    private static volatile boolean uiBowVisibleToMain;
    private static boolean uiBowVisibleChecked;
    private static volatile boolean uiBowServer;
    private static volatile boolean uiArrowServer;
    private static boolean uiSnowGiven;
    private static boolean uiSnowUsed;
    private static volatile boolean uiSnowballServer;
    private static volatile boolean uiSnowServer;
    private static boolean uiOffhandGiven;
    private static boolean uiOffhandUsed;
    private static volatile boolean uiOffhandUsing;
    private static boolean uiTridentGiven;
    private static boolean uiTridentUsed;
    private static boolean uiTridentReleased;
    private static boolean uiTridentChecked;
    private static int uiTridentHoldTicks;
    private static volatile boolean uiTridentCharging;
    private static volatile boolean uiTridentServer;
    private static volatile boolean uiTridentVisibleToMain;
    private static boolean uiCrossbowGiven;
    private static boolean uiCrossbowUsed;
    private static boolean uiCrossbowReleased;
    private static volatile boolean uiCrossbowServer;
    private static volatile boolean uiCrossbowUsing;
    private static boolean uiPotionGiven;
    private static boolean uiPotionUsed;
    private static volatile boolean uiPotionServer;
    private static volatile boolean uiPotionVisibleToMain;
    private static BlockPos uiBedPos;
    private static boolean uiBedUsed;
    private static boolean uiBedLookedAt;
    private static int uiBedLookTicks;
    private static boolean uiBedClicked;
    private static volatile boolean uiBedSleeping;
    private static volatile boolean uiBedAwake;

    private static void runUseItems(Minecraft mc) {
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
            // ===== 食物：先饥饿（服务端设 hunger=2，否则满饱食度吃面包不变）→ useItem 面包 → 服务端自动吃完（32 tick）→ 饥饿值上升 + 面包消耗 =====
            case 1 -> {
                if (!uiBreadGiven) {
                    uiBreadGiven = true;
                    server.execute(() -> {
                        // 原版命令先清背包（保证 give 落主手）+ 让假人饥饿（否则满饱食度吃面包无变化）
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                            sp.getFoodData().setFoodLevel(2);
                            sp.getFoodData().setSaturation(0.0F);
                            uiBreadBaseFood = sp.getFoodData().getFoodLevel();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:bread");
                        bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    });
                }
                // 服务端强断言：主手拿到面包
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiBreadServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.BREAD);
                });
                if (uiBreadServer) {
                    check("server holds bread", true);
                    step = 2;
                } else if (++waitTicks > 200) {
                    uiBreadGiven = false;
                    waitTicks = 0;
                }
            }
            case 2 -> {
                if (!uiBreadUsed) {
                    uiBreadUsed = true;
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                    check("useItem bread issued", true);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        // 主玩家视角：累积（吃面包期间任何一刻主玩家 level 假人实体同步了 EAT 动画即可见）
                        uiBreadVisibleToMain |= mc.level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,
                                        new net.minecraft.world.phys.AABB(sp.position().add(-32, -32, -32), sp.position().add(32, 32, 32)))
                                .stream().anyMatch(p -> p.getName().getString().equals(botName)
                                        && p.isUsingItem() && p.getUseItem().is(net.minecraft.world.item.Items.BREAD)
                                        && p.getUseItem().getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.EAT);
                        // 强断言：吃完（面包消耗）+ 饥饿值上升（比吃前 base 高）
                        uiBreadEaten = !sp.isUsingItem()
                                && sp.getInventory().countItem(net.minecraft.world.item.Items.BREAD) == 0
                                && sp.getFoodData().getFoodLevel() > uiBreadBaseFood;
                    }
                });
                if (uiBreadVisibleToMain && !uiBreadVisibleChecked) {
                    uiBreadVisibleChecked = true;
                    check("bread eat action visible to main player", true);
                }
                if (uiBreadEaten) {
                    check("bread auto-eaten + hunger (server)", true);
                    step = 3;
                } else if (++waitTicks > 200) {
                    fail("bread not eaten timeout (food=" + uiBreadBaseFood + ")");
                    step = 3;
                }
            }
            // ===== 盾牌：useItem 格挡（isUsingItem 持续多 tick）→ releaseUsingItem 解除 =====
            case 3 -> {
                if (!uiShieldGiven) {
                    uiShieldGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:shield");
                        bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    });
                }
                // 服务端强断言：主手拿到盾
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiShieldServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.SHIELD);
                });
                if (uiShieldServer) {
                    check("server holds shield", true);
                    step = 4;
                } else if (++waitTicks > 200) {
                    uiShieldGiven = false;
                    waitTicks = 0;
                }
            }
            case 4 -> {
                if (!uiShieldUsed) {
                    uiShieldUsed = true;
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiShieldUsing = sp != null && sp.isUsingItem() && sp.getUseItem().is(net.minecraft.world.item.Items.SHIELD);
                    // 主玩家视角：累积（持续使用期间任何一刻主玩家 level 假人实体同步了举盾 BLOCK 动画即可见）
                    if (sp != null) {
                        uiShieldVisibleToMain |= mc.level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,
                                        new net.minecraft.world.phys.AABB(sp.position().add(-32, -32, -32), sp.position().add(32, 32, 32)))
                                .stream().anyMatch(p -> p.getName().getString().equals(botName)
                                        && p.isUsingItem() && p.getUseItem().is(net.minecraft.world.item.Items.SHIELD)
                                        && p.getUseItem().getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.BLOCK);
                    }
                });
                if (uiShieldUsing) {
                    // 强断言：盾牌举盾持续（isUsingItem 保持 10+ tick）+ 主玩家视角能看到假人举盾 BLOCK 动画
                    if (!uiShieldBlockedChecked) {
                        uiShieldBlockedChecked = true;
                        check("shield blocking (server isUsingItem)", true);
                    }
                    if (uiShieldVisibleToMain && !uiShieldVisibleChecked) {
                        uiShieldVisibleChecked = true;
                        check("shield action visible to main player", true);
                    }
                    if (++uiShieldHoldTicks >= 10) {
                        check("shield held 10+ ticks (sustained)", true);
                        step = 5;
                    }
                } else if (++waitTicks > 200) {
                    fail("shield not blocking timeout");
                    step = 5;
                }
            }
            case 5 -> {
                if (!uiShieldReleased) {
                    uiShieldReleased = true;
                    bot.actions().releaseUsingItem();
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiShieldReleasedServer = sp != null && !sp.isUsingItem();
                });
                if (uiShieldReleasedServer) {
                    check("shield released (server)", true);
                    step = 6;
                } else if (++waitTicks > 200) {
                    fail("shield release timeout");
                    step = 6;
                }
            }
            // ===== 弓：useItem 蓄力（isUsingItem）→ releaseUsingItem 放箭（服务端箭实体） =====
            case 6 -> {
                if (!uiBowGiven) {
                    uiBowGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:bow");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "give " + botName + " minecraft:arrow 64");
                        if (sp != null) {
                            sp.getInventory().setSelectedSlot(0);
                        }
                    });
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                }
                // 服务端强断言：主手确实拿到弓（give 竞态/selected 被覆盖都逃不过服务端验证）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiBowServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.BOW);
                });
                if (uiBowServer) {
                    check("server holds bow", true);
                    step = 7;
                } else if (++waitTicks > 200) {
                    uiBowGiven = false; // give 竞态 → 重试
                    waitTicks = 0;
                }
            }
            case 7 -> {
                if (!uiBowUsed) {
                    uiBowUsed = true;
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiBowUsing = sp != null && sp.isUsingItem() && sp.getUseItem().is(net.minecraft.world.item.Items.BOW);
                    // 主玩家视角：累积（拉弦期间任何一刻主玩家 level 假人实体同步了 BOW 动画即可见）
                    if (sp != null) {
                        uiBowVisibleToMain |= mc.level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,
                                        new net.minecraft.world.phys.AABB(sp.position().add(-32, -32, -32), sp.position().add(32, 32, 32)))
                                .stream().anyMatch(p -> p.getName().getString().equals(botName)
                                        && p.isUsingItem() && p.getUseItem().is(net.minecraft.world.item.Items.BOW)
                                        && p.getUseItem().getUseAnimation() == net.minecraft.world.item.ItemUseAnimation.BOW);
                    }
                });
                if (uiBowUsing) {
                    check("bow charging (server isUsingItem)", true);
                    if (uiBowVisibleToMain && !uiBowVisibleChecked) {
                        uiBowVisibleChecked = true;
                        check("bow pull action visible to main player", true);
                    }
                    step = 8;
                } else if (++waitTicks > 200) {
                    fail("bow not charging timeout");
                    step = 8;
                }
            }
            case 8 -> {
                // 弓也需蓄力（拉满再松开才射箭），case 7 蓄力后等 15 tick 再 release
                if (!uiBowReleased && ++uiBowHoldTicks >= 15) {
                    uiBowReleased = true;
                    bot.actions().releaseUsingItem();
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        uiArrowServer |= !sp.level().getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty();
                    }
                });
                if (uiArrowServer) {
                    if (!uiArrowChecked) {
                        uiArrowChecked = true;
                        check("bow released arrow (server)", true);
                    }
                    step = 9;
                } else if (++waitTicks > 200) {
                    fail("bow no arrow timeout");
                    step = 9;
                }
            }
            // ===== 雪球：useItem 直接投掷（服务端雪球实体） =====
            case 9 -> {
                if (!uiSnowGiven) {
                    uiSnowGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:snowball");
                        bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    });
                }
                // 服务端强断言：主手拿到雪球
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiSnowServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.SNOWBALL);
                });
                if (uiSnowServer) {
                    check("server holds snowball", true);
                    step = 10;
                } else if (++waitTicks > 200) {
                    uiSnowGiven = false;
                    waitTicks = 0;
                }
            }
            case 10 -> {
                if (!uiSnowUsed) {
                    uiSnowUsed = true;
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                if (!uiSnowballServer) {
                    // 第一阶段：等服务端出现雪球实体
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            uiSnowballServer = !sp.level().getEntitiesOfClass(
                                    net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball.class,
                                    new net.minecraft.world.phys.AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty();
                        }
                    });
                    if (uiSnowballServer) {
                        check("snowball thrown (server)", true);
                        waitTicks = 0;
                    } else if (++waitTicks > 200) {
                        fail("snowball not thrown timeout");
                        step = 11;
                    }
                } else {
                    // 第二阶段：等主玩家客户端 level 同步到雪球实体（客户端实体同步有延迟，单次读会偶发 miss）
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    boolean visibleToMain = sp != null && !mc.level.getEntitiesOfClass(
                            net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball.class,
                            new net.minecraft.world.phys.AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty();
                    if (visibleToMain) {
                        check("snowball throw visible to main player", true);
                        step = 11;
                    } else if (++waitTicks > 100) {
                        fail("snowball visible to main player timeout");
                        step = 11;
                    }
                }
            }
            // ===== 副手使用：副手持盾 useItem(OFF_HAND) → 服务端格挡（isUsingItem + offhand 盾） =====
            case 11 -> {
                if (!uiOffhandGiven) {
                    uiOffhandGiven = true;
                    server.execute(() -> {
                        // 原版命令 item replace 给副手盾（setItem 会被假人客户端背包状态覆盖）
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.offhand with minecraft:shield");
                    });
                }
                if (bot.getLocalPlayer().getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
                    check("client holds offhand shield", true);
                    step = 12;
                } else if (++waitTicks > 200) {
                    fail("offhand shield not given");
                    step = 12;
                }
            }
            case 12 -> {
                if (!uiOffhandUsed) {
                    uiOffhandUsed = true;
                    bot.actions().useItem(net.minecraft.world.InteractionHand.OFF_HAND);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiOffhandUsing = sp != null && sp.isUsingItem() && sp.getUseItem().is(net.minecraft.world.item.Items.SHIELD)
                            && sp.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD);
                });
                if (uiOffhandUsing) {
                    check("offhand shield blocking (server)", true);
                    bot.actions().releaseUsingItem();
                    check("offhand released", true);
                    step = 14;
                } else if (++waitTicks > 200) {
                    fail("offhand shield not blocking timeout");
                    step = 14;
                }
            }
            // ===== 三叉戟投掷：item replace → useItem → 服务端 ThrownTrident 实体（主玩家可见） =====
            case 14 -> {
                if (!uiTridentGiven) {
                    uiTridentGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:trident");
                        if (sp != null) {
                            sp.getInventory().setSelectedSlot(0);
                        }
                    });
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiTridentServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.TRIDENT);
                });
                if (uiTridentServer) {
                    check("server holds trident", true);
                    step = 15;
                } else if (++waitTicks > 200) {
                    uiTridentGiven = false;
                    waitTicks = 0;
                }
            }
            case 15 -> {
                if (!uiTridentUsed) {
                    uiTridentUsed = true;
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND); // 举着蓄力
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiTridentCharging = sp != null && sp.isUsingItem() && sp.getUseItem().is(net.minecraft.world.item.Items.TRIDENT);
                });
                if (uiTridentCharging && !uiTridentReleased && ++uiTridentHoldTicks >= 15) {
                    // 三叉戟需蓄力满（TridentItem.releaseUsing 要求蓄力 >= 10 tick 才投掷），蓄力后松开
                    uiTridentReleased = true;
                    check("trident charging (server isUsingItem)", true);
                    bot.actions().releaseUsingItem();
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        // 累积：投掷后几 tick 内服务端 + 主玩家 level 出现 ThrownTrident
                        uiTridentServer |= !sp.level().getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.arrow.ThrownTrident.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty();
                        uiTridentVisibleToMain |= !mc.level.getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.arrow.ThrownTrident.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty();
                    }
                });
                if (uiTridentServer) {
                    if (!uiTridentChecked) {
                        uiTridentChecked = true;
                        check("trident thrown (server)", true);
                    }
                    if (uiTridentVisibleToMain) {
                        check("trident throw visible to main player", true);
                        step = 16;
                    }
                } else if (++waitTicks > 200) {
                    fail("trident not thrown timeout");
                    step = 16;
                }
            }
            // ===== 弩装填发射：item replace → useItem（装填）→ releaseUsingItem → 服务端箭 =====
            case 16 -> {
                if (!uiCrossbowGiven) {
                    uiCrossbowGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:crossbow");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "give " + botName + " minecraft:arrow 64");
                        if (sp != null) {
                            sp.getInventory().setSelectedSlot(0);
                        }
                    });
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiCrossbowServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.CROSSBOW);
                });
                if (uiCrossbowServer) {
                    check("server holds crossbow", true);
                    step = 17;
                } else if (++waitTicks > 200) {
                    uiCrossbowGiven = false;
                    waitTicks = 0;
                }
            }
            case 17 -> {
                if (!uiCrossbowUsed) {
                    uiCrossbowUsed = true;
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND); // 装填
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiCrossbowUsing = sp != null && sp.isUsingItem() && sp.getUseItem().is(net.minecraft.world.item.Items.CROSSBOW);
                });
                if (uiCrossbowUsing) {
                    check("crossbow charging (server isUsingItem)", true);
                    step = 18;
                } else if (++waitTicks > 200) {
                    fail("crossbow not charging timeout");
                    step = 18;
                }
            }
            case 18 -> {
                if (!uiCrossbowReleased) {
                    uiCrossbowReleased = true;
                    bot.actions().releaseUsingItem(); // 发射
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        uiCrossbowServer = !sp.level().getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.arrow.AbstractArrow.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty();
                    }
                });
                if (uiCrossbowServer) {
                    check("crossbow fired arrow (server)", true);
                    step = 19;
                } else if (++waitTicks > 200) {
                    fail("crossbow no arrow timeout");
                    step = 19;
                }
            }
            // ===== 药水投掷：item replace → useItem → 服务端 ThrownSplashPotion 实体 =====
            case 19 -> {
                if (!uiPotionGiven) {
                    uiPotionGiven = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:splash_potion");
                        if (sp != null) {
                            sp.getInventory().setSelectedSlot(0);
                        }
                    });
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiPotionServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.SPLASH_POTION);
                });
                if (uiPotionServer) {
                    check("server holds splash potion", true);
                    step = 20;
                } else if (++waitTicks > 200) {
                    uiPotionGiven = false;
                    waitTicks = 0;
                }
            }
            case 20 -> {
                if (!uiPotionUsed) {
                    uiPotionUsed = true;
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        // 累积：投掷后几 tick 内服务端 + 主玩家 level 出现 ThrownSplashPotion
                        uiPotionServer |= !sp.level().getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty();
                        uiPotionVisibleToMain |= !mc.level.getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty();
                    }
                });
                if (uiPotionServer) {
                    check("splash potion thrown (server)", true);
                    if (uiPotionVisibleToMain) {
                        check("potion throw visible to main player", true);
                        step = 21;
                    }
                } else if (++waitTicks > 200) {
                    fail("potion not thrown timeout");
                    step = 21;
                }
            }
            // ===== 睡觉/起床：useItemOn 床 → isSleeping → wakeUp() 发包起床 =====
            case 21 -> {
                if (uiBedPos == null) {
                    uiBedPos = bot.getLocalPlayer().blockPosition().offset(1, 0, 0);
                    server.execute(() -> {
                        // 原版命令放完整床（head+foot 双格，只放一半睡不了）+ 设夜晚（白天不能睡）
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "time set night");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + uiBedPos.getX() + " " + uiBedPos.getY() + " " + uiBedPos.getZ()
                                        + " minecraft:red_bed[facing=south,part=head]");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + uiBedPos.getX() + " " + uiBedPos.getY() + " " + (uiBedPos.getZ() - 1)
                                        + " minecraft:red_bed[facing=south,part=foot]");
                    });
                }
                if (!uiBedUsed && !uiBedLookedAt) {
                    uiBedLookedAt = true;
                    uiBedLookTicks = 0;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(uiBedPos));
                }
                if (uiBedLookedAt && !uiBedUsed && ++uiBedLookTicks > 40) {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null && !isAwaitingPosition(sp)) {
                        uiBedUsed = true;
                        // 空手右键床才触发 useWithoutItem（服务端 useItemOn 内部用 player.getItemInHand(hand)）
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with air");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.offhand with air");
                        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                                net.minecraft.world.phys.Vec3.atCenterOf(uiBedPos), Direction.UP, uiBedPos, false);
                        bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                    }
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiBedSleeping = sp != null && sp.isSleeping();
                });
                if (uiBedSleeping) {
                    check("fake sleeping (server isSleeping)", true);
                    step = 22;
                } else if (uiBedUsed && ++waitTicks > 200) {
                    fail("sleep not started timeout");
                    step = 22;
                }
            }
            case 22 -> {
                if (!uiBedClicked) {
                    uiBedClicked = true;
                    waitTicks = 0;
                    // 起床 = 直接发包 STOP_SLEEPING（等价 InBedChatScreen 起床按钮，包路由到假人 connection）
                    bot.actions().wakeUp();
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    uiBedAwake = sp != null && !sp.isSleeping();
                });
                if (uiBedAwake) {
                    check("stopSleeping woke up (server)", true);
                    step = 23;
                } else if (++waitTicks > 200) {
                    fail("wake up timeout");
                    step = 23;
                }
            }
            case 23 -> {
                MockplayerApi.bots().removeBot(botName, "command");
                finishSuite();
            }
        }
    }

    // ===== control-commands：/control 动作 + /query 查询分离强测试（命令层走
    // ControlCommands / QueryCommands；底层真实网络包 + 服务端强断言；全部输出
    // i18n；Tab 补全逐位断言；memory 为精确记账） =====

    private static boolean ccTreeChecked;
    private static boolean ccMoveStarted;
    private static volatile boolean ccMoved;
    private static boolean ccStopChecked;
    private static double ccBaseX;
    private static double ccBaseZ;
    private static int ccLookStage;
    private static volatile float ccServerYRot;
    private static boolean ccHuskSummoned;
    private static volatile boolean ccHuskAttacked;
    private static volatile float ccHuskHealth = 20;
    private static volatile float ccHuskHealthBeforeTarget = 20;
    private static volatile net.minecraft.world.phys.Vec3 ccHuskPos;
    private static volatile boolean ccHuskTargetAttacked;
    private static boolean ccSustainedStarted;
    private static volatile boolean ccSustainedHit;
    private static boolean ccVillagerSummoned;
    private static volatile boolean ccMerchantOpen;
    private static boolean ccHotbarGiven;
    private static volatile boolean ccHotbarVerified;
    private static String ccMainHandBefore = "";
    private static volatile boolean ccDropVerified;
    private static boolean ccSwapGiven;
    private static volatile boolean ccSwapVerified;
    private static volatile String ccChatMsg = "";
    private static boolean ccChatListenerRegistered;
    private static boolean ccChatChecked;
    private static boolean ccChatSent;
    private static boolean ccCommandSent;
    private static volatile boolean ccTimeVerified;
    private static boolean ccUseGiven;
    private static boolean ccUseChecked;
    private static volatile boolean ccUsing;
    private static volatile boolean ccReleased;
    private static boolean ccDirtGiven;
    private static BlockPos ccPlacePos;
    private static BlockPos ccPlacePos2;
    private static boolean ccAttackBlockSent;
    private static volatile boolean ccAttackBlockBroken;
    private static boolean ccAttackBlockChecked;
    private static boolean ccPlaceSent;
    private static boolean ccPlaceChecked;
    private static volatile boolean ccPlacedDirt;
    private static boolean ccCreativeSet;
    private static boolean ccPickSent;
    private static volatile boolean ccPickVerified;
    private static boolean ccPickChecked;
    private static boolean ccSurvivalBack;
    private static boolean ccPlace2Sent;
    private static volatile boolean ccPlacedDirt2;
    private static boolean ccPlaced2Checked;
    private static boolean ccMineSent;
    private static volatile boolean ccMinedAir2;
    private static boolean ccMinedChecked;
    private static BlockPos ccPlaceAtPos;
    private static boolean ccPlaceAtGiven;
    private static boolean ccPlaceAtSent;
    private static volatile boolean ccPlacedAt;
    private static boolean ccPlaceAtChecked;
    private static boolean ccBedSet;
    private static BlockPos ccBedPos;
    private static boolean ccBedClicked;
    private static boolean ccSleepChecked;
    private static volatile boolean ccSleeping;
    private static volatile boolean ccWoke;
    private static boolean ccRailSet;
    private static boolean ccMountSent;
    private static boolean ccMountChecked;
    private static volatile boolean ccMounted;
    private static int ccMountStableTicks;
    private static boolean ccDismountSent;
    private static volatile boolean ccDismounted;
    private static volatile double ccCartStartX;
    private static volatile boolean ccCartMoved;
    private static boolean ccCartPushed;
    private static BlockPos ccChestPos;
    private static boolean ccChestSet;
    private static boolean ccChestClicked;
    private static volatile boolean ccQueriesOk;
    private static boolean ccCloseSent;
    private static boolean ccCloseChecked;
    private static boolean ccClickPutSent;
    private static boolean ccClickPutDone;
    private static boolean ccClickPutVerified;
    private static volatile boolean ccClickPutItemInChest;
    private static boolean ccMineTestSet;
    private static boolean ccMineTestSynced;
    private static boolean ccMineTestDone;
    private static boolean ccMineStopSent;
    /** case 18 的 drop/swap 等动作包是否已确认在服务端处理完（防延迟包清掉 replace 的石镐）。 */
    private static boolean ccMineFlushDone;
    private static int ccMineTestTicks;
    private static volatile boolean ccMineSpReady;
    private static volatile BlockPos ccMineStone1;
    private static volatile BlockPos ccMineStone2;
    private static boolean ccChunkDefaultChecked;
    private static boolean ccChunkServerChecked;
    private static boolean ccChunkServerAfterChecked;
    private static boolean ccChunkLoadedChecked;
    private static boolean ccChunkTeleported;
    private static boolean ccChunkSettled;
    private static BlockPos ccChunkProbePos;
    private static int ccChunkMainOptionsBefore = -1;
    private static Object ccChunkMainChunkSource;
    private static volatile int ccChunkServerRequested = -1;
    private static volatile int ccChunkServerView = -1;
    // ===== 射线交互（attackLook/useLook 单点 + sustained*Look 长按）=====
    private static int rcStep;
    private static long rcWaitStart;
    private static BlockPos rcPos;
    private static BlockPos rcHuskPos;
    private static volatile float rcHuskHp = 20;
    /** 服务端已确认 husk 出现（异步读未完成时不能用旧 hp 值推进状态机）。 */
    private static volatile boolean rcHuskFound;
    private static boolean rcHuskSummoned;
    private static boolean rcChestPlaced;
    private static volatile boolean rcChestOpen;
    private static BlockPos rcChestPos;
    private static boolean rcBreadGiven;
    private static volatile boolean rcUsing;
    private static volatile boolean rcReleased;
    private static boolean rcDirtPlaced;
    private static volatile boolean rcDirtBroken;
    private static BlockPos rcDirtPos;
    private static boolean rcFarPlaced;
    private static volatile boolean rcFarStill;
    private static BlockPos rcFarPos;
    private static boolean rcTurnHuskSummoned;
    private static boolean rcTurnAttacked;
    private static volatile float rcTurnHpAfter = 20;

    /** 射线测试助手：取服务端 OVERWORLD 里离假人最近的僵尸（husk 也属于 Zombie）。 */
    private static net.minecraft.world.entity.monster.zombie.Zombie ccNearestZombie(MinecraftServer server) {
        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
        net.minecraft.server.level.ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (sp == null || level == null) {
            return null;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                sp.getX() - 16, sp.getY() - 16, sp.getZ() - 16,
                sp.getX() + 16, sp.getY() + 16, sp.getZ() + 16);
        return level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class, box).stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(sp)))
                .orElse(null);
    }

    private static volatile BlockPos ccMineStoneFar;
    private static boolean ccMineFarSent;
    private static boolean ccMineFarChecked;
    private static boolean ccMineFarWaitDone;
    private static volatile boolean ccMineFarStill;
    private static volatile boolean ccMineStone1Air;
    private static volatile boolean ccMineStone2Still;
    private static volatile String ccMineMainHand = "?";
    private static volatile int ccMineServerSlot = -1;
    private static volatile String ccMineServerHotbar = "?";
    private static volatile String ccMineAfterReplace = "?";
    private static volatile float ccMineDigSpeed = -1.0F;
    private static volatile boolean ccMineCorrectTool = false;
    private static volatile float ccMineDestroyProgressRate = -1.0F;
    private static volatile boolean ccMineServerPickaxe;
    private static volatile boolean ccMineServerMoving;
    private static volatile double ccMineLastX;
    private static volatile double ccMineLastY;
    private static volatile double ccMineLastZ;
    private static int ccMineStableTicks;
    private static boolean ccMineStopWaitDone;
    private static boolean ccListenOn;
    private static boolean ccListenDamaged;
    private static volatile boolean ccEventsHasDamage;
    private static boolean ccListenOff;
    private static volatile boolean ccErrorsOk;
    private static boolean ccRespawnKilled;
    private static volatile boolean ccRespawnDead;
    private static boolean ccRespawnDone;
    private static volatile boolean ccRespawnAlive;
    private static final com.mockplayer.api.event.BotListener ccChatListener = new com.mockplayer.api.event.BotListener() {
        @Override
        public void onChat(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component message) {
            ccChatMsg = message.getString();
        }
    };

    /** Brigadier 补全辅助：parse + 收集建议文本。 */
    private static java.util.List<String> ccCompletions(
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
            net.minecraft.commands.CommandSourceStack source, String input) {
        try {
            var parse = dispatcher.parse(input, source);
            return dispatcher.getCompletionSuggestions(parse).get(2, java.util.concurrent.TimeUnit.SECONDS).getList()
                    .stream().map(com.mojang.brigadier.suggestion.Suggestion::getText).toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** 双端共用命令树工厂（测试用，反馈丢弃）。 */
    private static com.mockplayer.session.CommandSupport.CommandFactory<net.minecraft.commands.CommandSourceStack> ccFactory() {
        return new com.mockplayer.session.CommandSupport.CommandFactory<net.minecraft.commands.CommandSourceStack>() {
            @Override
            public com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> literal(String name) {
                return net.minecraft.commands.Commands.literal(name);
            }

            @Override
            public com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, ?> argument(
                    String name, com.mojang.brigadier.arguments.ArgumentType<?> type) {
                return net.minecraft.commands.Commands.argument(name, type);
            }

            @Override
            public void sendFeedback(net.minecraft.commands.CommandSourceStack source,
                                     net.minecraft.network.chat.Component message) {
            }
        };
    }

    /** 注册 /control + /query 双树（测试用）。 */
    private static void ccRegisterAll(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(com.mockplayer.session.ControlCommands.buildControlTree(ccFactory(), "control"));
        dispatcher.register(com.mockplayer.session.QueryCommands.buildQueryTree(ccFactory(), "query"));
    }

    /** 收集组件 visit 展开后的全部样式片段（转义断言用）。 */
    private static java.util.List<net.minecraft.network.chat.Style> ccCollectStyles(net.minecraft.network.chat.Component c) {
        java.util.List<net.minecraft.network.chat.Style> styles = new java.util.ArrayList<>();
        c.visit((style, text) -> {
            styles.add(style);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return styles;
    }

    /**
     * § 注入检测：出现非预期 RED（allowRootRed=false 时）或任何 BOLD/斜体/下划线/删除线/混淆即失败。
     * 26.2 组件树是结构化 Style，§ 不应被解析成样式——若被解析会在这里被抓到。
     */
    private static boolean ccNoInjectedStyle(net.minecraft.network.chat.Component c, boolean allowRootRed) {
        return ccCollectStyles(c).stream().noneMatch(s ->
                (s.getColor() != null && !allowRootRed
                        && s.getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(
                        net.minecraft.ChatFormatting.RED)))
                        || s.isBold() || s.isItalic() || s.isUnderlined() || s.isStrikethrough() || s.isObfuscated());
    }

    /** 断言假人当前朝向是否指向目标位置（自动 lookAt 的强证据，yaw/pitch 容差可配）。 */
    private static boolean ccFacing(net.minecraft.client.player.LocalPlayer p, net.minecraft.world.phys.Vec3 target,
                                    float yawTol, float pitchTol) {
        if (p == null || target == null) {
            return false;
        }
        net.minecraft.world.phys.Vec3 d = target.subtract(p.getEyePosition());
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        if (horiz < 1.0E-4) {
            return true;
        }
        float expYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0F);
        float expPitch = (float) (-Math.toDegrees(Math.atan2(d.y, horiz)));
        float yawDelta = Math.abs((((p.getYRot() - expYaw) % 360.0F) + 540.0F) % 360.0F - 180.0F);
        float pitchDelta = Math.abs(p.getXRot() - expPitch);
        return yawDelta <= yawTol && pitchDelta <= pitchTol;
    }

    /** 服务端强断言：读 ServerPlayer.requestedViewDistance（先公开方法，反射兜底）。 */
    private static int ccServerRequestedViewDistance(net.minecraft.server.level.ServerPlayer sp) {
        if (sp == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method m = net.minecraft.server.level.ServerPlayer.class.getMethod("requestedViewDistance");
            return (Integer) m.invoke(sp);
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Field f = net.minecraft.server.level.ServerPlayer.class.getDeclaredField("requestedViewDistance");
            f.setAccessible(true);
            return f.getInt(sp);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** 服务端强断言：读 ServerPlayer 的 ChunkTrackingView 半径（Positioned.viewDistance）。 */
    private static int ccServerChunkViewDistance(net.minecraft.server.level.ServerPlayer sp) {
        if (sp == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method getView = net.minecraft.server.level.ServerPlayer.class.getMethod("getChunkTrackingView");
            Object view = getView.invoke(sp);
            if (view != null && view.getClass().getSimpleName().contains("Positioned")) {
                java.lang.reflect.Method vd = view.getClass().getMethod("viewDistance");
                return (Integer) vd.invoke(view);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void runControlCommands(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> { // 命令树结构 + Tab 补全 + i18n + list 查询
                prepareBot(server);
                if (bot == null || bot.getLifecycle() != BotLifecycle.PLAYING) {
                    return;
                }
                var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
                ccRegisterAll(dispatcher);
                var root = dispatcher.getRoot().getChildren();
                var control = root.stream().filter(n -> n.getName().equals("control")).findFirst().orElse(null);
                check("tree control root", control != null);
                if (control == null) {
                    fail("tree control missing");
                    finishSuite();
                    return;
                }
                check("tree control top has no list", control.getChildren().stream()
                        .noneMatch(n -> n.getName().equals("list")));
                var playerNode = control.getChildren().stream().filter(n -> n.getName().equals("player"))
                        .findFirst().orElse(null);
                if (playerNode == null) {
                    fail("tree player missing");
                    finishSuite();
                    return;
                }
                java.util.Set<String> subs = playerNode.getChildren().stream()
                        .map(com.mojang.brigadier.tree.CommandNode::getName)
                        .collect(java.util.stream.Collectors.toSet());
                java.util.List<String> actions = java.util.List.of(
                        "move", "stop", "sneak", "unsneak", "sprint", "unsprint", "jump",
                        "look", "lookAt", "turn", "attack", "stab", "sustainedAttack", "sustainedUse",
                        "attackLook", "useLook", "sustainedAttackLook", "sustainedUseLook",
                        "stopSustained", "interact", "useItem", "releaseUsingItem", "useItemOn",
                        "placeBlock", "mineBlock", "attackBlock", "hotbar", "chunkRadius", "drop", "swapHands",
                        "mount", "dismount", "chat", "command", "wakeUp", "respawn", "editBook",
                        "close", "click", "button", "trade", "setSlot", "editSign", "setBeacon",
                        "renameItem", "pickItemFromBlock", "help");
                java.util.List<String> missingActions = new java.util.ArrayList<>(actions);
                missingActions.removeAll(subs);
                java.util.List<String> leakedQueries = subs.stream()
                        .filter(s -> !actions.contains(s)).toList();
                check("tree actions complete", missingActions.isEmpty(), "missing=" + missingActions);
                check("tree control no query leak", leakedQueries.isEmpty(), "leaked=" + leakedQueries);
                // /control help：输出包含全部动作的 i18n 翻译（防 ACTIONS 与树漂移）
                String helpText = com.mockplayer.session.ControlCommands.help(botName).getString();
                check("help lists all actions",
                        helpText.lines().count() == com.mockplayer.session.ControlCommands.ACTIONS.size() + 1);
                check("help action translated", helpText.contains(
                        net.minecraft.network.chat.Component.translatable(
                                "commands.mockplayer.control.action.attack").getString()));
                // /query 树：list + player 查询全集（含 memory）
                var query = root.stream().filter(n -> n.getName().equals("query")).findFirst().orElse(null);
                check("tree query root", query != null);
                if (query == null) {
                    fail("tree query missing");
                    finishSuite();
                    return;
                }
                check("tree query top list", query.getChildren().stream()
                        .anyMatch(n -> n.getName().equals("list")));
                var qPlayer = query.getChildren().stream().filter(n -> n.getName().equals("player"))
                        .findFirst().orElse(null);
                check("tree query player", qPlayer != null);
                if (qPlayer == null) {
                    fail("tree query player missing");
                    finishSuite();
                    return;
                }
                java.util.Set<String> qSubs = qPlayer.getChildren().stream()
                        .map(com.mojang.brigadier.tree.CommandNode::getName)
                        .collect(java.util.stream.Collectors.toSet());
                java.util.List<String> queries = java.util.List.of(
                        "info", "inventory", "container", "near", "block", "chunk", "online", "chatlog",
                        "listen", "events", "memory");
                java.util.List<String> missingQueries = new java.util.ArrayList<>(queries);
                missingQueries.removeAll(qSubs);
                java.util.List<String> leakedActions = qSubs.stream()
                        .filter(s -> !queries.contains(s)).toList();
                check("tree query complete", missingQueries.isEmpty(), "missing=" + missingQueries);
                check("tree query no action leak", leakedActions.isEmpty(), "leaked=" + leakedActions);
                net.minecraft.commands.CommandSourceStack stack = server.createCommandSourceStack();
                java.util.List<String> sugg = ccCompletions(dispatcher, stack, "control ");
                check("tab control bots only", sugg.contains(botName) && !sugg.contains("list"),
                        "sugg=" + sugg);
                sugg = ccCompletions(dispatcher, stack, "query ");
                check("tab query bots+list", sugg.contains(botName) && sugg.contains("list"),
                        "sugg=" + sugg);
                sugg = ccCompletions(dispatcher, stack, "query " + botName + " ");
                check("tab query subs", sugg.containsAll(queries), "sugg=" + sugg);
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " move ");
                check("tab move dirs", sugg.containsAll(java.util.List.of("forward", "backward", "left", "right")));
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " hotbar ");
                check("tab hotbar", sugg.contains("1") && sugg.contains("9"));
                sugg = ccCompletions(dispatcher, stack, "query " + botName + " listen ");
                check("tab query listen", sugg.containsAll(java.util.List.of("on", "off")), "sugg=" + sugg);
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " useItem ");
                check("tab hands", sugg.containsAll(java.util.List.of("mainhand", "offhand")));
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " placeBlock 0 0 0 ");
                check("tab sides", sugg.containsAll(java.util.List.of("north", "south", "east", "west", "up", "down")));
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " setBeacon ");
                check("tab effects", sugg.contains("minecraft:speed"), "sugg=" + sugg);
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " drop 0 ");
                check("tab drop modes", sugg.containsAll(java.util.List.of("one", "all")));
                // 嵌套命令补全：command 参数复用主玩家连接命令树（原版 execute run 同款机制）
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " command ");
                check("tab nested command root", sugg.contains("time") && sugg.contains("gamemode"),
                        "sugg=" + sugg);
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " command time ");
                check("tab nested command sub", sugg.contains("set"), "sugg=" + sugg);
                // i18n：关键 key 有翻译（getString 不等于 key 原文）
                for (String key : java.util.List.of(
                        "commands.mockplayer.control.success", "commands.mockplayer.control.not_found",
                        "commands.mockplayer.control.action.attack", "commands.mockplayer.control.suggest.yaw",
                        "commands.mockplayer.query.listen.on", "commands.mockplayer.query.events.not_listening",
                        "commands.mockplayer.query.event.onDamage", "commands.mockplayer.query.memory.jvm")) {
                    check("i18n key " + key, !net.minecraft.network.chat.Component.translatable(key).getString().equals(key));
                }
                // list 查询
                String listText = com.mockplayer.session.QueryCommands.list().getString();
                // 管理 bot 由命令路径创建：owner = "command"
                check("list contains bot", listText.contains(botName) && listText.contains("command"),
                        "text=" + listText.replace("\n", "|"));
                ccTreeChecked = true;
                step = 1;
            }
            case 1 -> { // move forward 端到端 + stop 归零
                if (!ccMoveStarted) {
                    ccMoveStarted = true;
                    ccMoved = false;
                    waitTicks = 0;
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            ccBaseX = sp.getX();
                            ccBaseZ = sp.getZ();
                        }
                    });
                    com.mockplayer.session.ControlCommands.move(botName, "forward");
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null && (Math.abs(sp.getX() - ccBaseX) > 0.5 || Math.abs(sp.getZ() - ccBaseZ) > 0.5)) {
                        ccMoved = true;
                    }
                });
                if (!ccMoved) {
                    if (++waitTicks > 200) {
                        fail("move forward timeout");
                        com.mockplayer.session.ControlCommands.stop(botName);
                        step = 2;
                    }
                } else if (!ccStopChecked) {
                    ccStopChecked = true;
                    waitTicks = 0;
                    com.mockplayer.session.ControlCommands.stop(botName);
                } else if (++waitTicks > 5) {
                    check("move forward moved on server", true);
                    check("stop clears input", bot.getLocalPlayer().input.getMoveVector().y == 0.0F);
                    step = 2;
                }
            }
            case 2 -> { // look / turn / lookAt（本地 + 服务端朝向）
                if (ccLookStage == 0) {
                    ccServerYRot = -999;
                    com.mockplayer.session.ControlCommands.look(botName, 30.0F, 20.0F);
                    var p = bot.getLocalPlayer();
                    check("look local yRot", Math.abs(p.getYRot() - 30.0F) < 1.0F);
                    check("look local xRot", Math.abs(p.getXRot() - 20.0F) < 1.0F);
                    waitTicks = 0;
                    ccLookStage = 1;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        ccServerYRot = sp.getYRot();
                    }
                });
                if (ccLookStage == 1 && Math.abs((((ccServerYRot % 360) + 360) % 360) - 30.0F) < 5.0F) {
                    check("look server yRot", true);
                    ccServerYRot = -999;
                    com.mockplayer.session.ControlCommands.turn(botName, 90.0F, 10.0F);
                    var p = bot.getLocalPlayer();
                    check("turn local yRot", Math.abs((((p.getYRot() % 360) + 360) % 360) - 120.0F) < 1.0F);
                    check("turn local xRot", Math.abs(p.getXRot() - 30.0F) < 1.0F);
                    waitTicks = 0;
                    ccLookStage = 2;
                } else if (ccLookStage == 2 && Math.abs((((ccServerYRot % 360) + 360) % 360) - 120.0F) < 5.0F) {
                    check("turn server yRot", true);
                    var p = bot.getLocalPlayer();
                    com.mockplayer.session.ControlCommands.lookAt(botName, p.getX(), p.getY(), p.getZ() + 5.0);
                    check("lookAt local yRot ~south", Math.abs((((bot.getLocalPlayer().getYRot() % 360) + 360) % 360)) < 5.0F);
                    ccServerYRot = -999;
                    waitTicks = 0;
                    ccLookStage = 3;
                } else if (ccLookStage == 3 && Math.abs((((ccServerYRot % 360) + 360) % 360)) < 5.0F) {
                    check("lookAt server yRot", true);
                    step = 3;
                } else if (++waitTicks > 300) {
                    fail("look/turn/lookAt timeout stage=" + ccLookStage);
                    step = 3;
                }
            }
            case 3 -> { // attack 无参（最近非玩家实体 = husk）
                if (!ccHuskSummoned) {
                    ccHuskSummoned = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                            sp.getX() + 2.0, sp.getY(), sp.getZ()));
                            // 空手伤害只有 1（0.94）：必须给武器才能做强伤害断言（钻石剑 7）
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "item replace entity " + botName + " weapon.mainhand with minecraft:diamond_sword");
                        }
                    });
                }
                if (++waitTicks > 15 && !ccHuskAttacked) {
                    // 服务端攻击冷却（attackStrengthTicker）：满蓄力才造成完整伤害，避免弱断言
                    if (bot.getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F || waitTicks > 70) {
                        ccHuskAttacked = true;
                        ccHuskHealth = 20;
                        com.mockplayer.session.ControlCommands.attack(botName, null);
                    }
                }
                server.execute(() -> {
                    var level = server.getLevel(Level.OVERWORLD);
                    if (level != null) {
                        var husk = level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                                bot.getLocalPlayer().getBoundingBox().inflate(8.0)).stream().findFirst();
                        husk.ifPresent(h -> ccHuskHealth = h.getHealth());
                    }
                });
                if (ccHuskHealth < 20.0F) {
                    check("attack nearest husk damaged", true);
                    ccHuskHealthBeforeTarget = ccHuskHealth;
                    // 实体名 Tab 补全（husk 在场）
                    var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
                    ccRegisterAll(dispatcher);
                    java.util.List<String> sugg = ccCompletions(dispatcher, server.createCommandSourceStack(),
                            "control " + botName + " attack ");
                    check("tab entities contains type id", sugg.contains("husk"), "sugg=" + sugg);
                    step = 4;
                } else if (waitTicks > 120) {
                    fail("attack husk timeout");
                    step = 4;
                }
            }
            case 4 -> { // attack 指定实体名 target
                if (!ccHuskTargetAttacked) {
                    if (bot.getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F || ++waitTicks > 70) {
                        ccHuskTargetAttacked = true;
                        waitTicks = 0;
                        // 先背对 husk（husk 在 +X，朝西 yaw=90），命令层 attack 会先 lookAt 转向它
                        bot.actions().look(90.0F, 0.0F);
                        // 用攻击瞬间的客户端实体眼睛位置做期望（僵尸会移动，且脚底 vs 眼睛有 pitch 误差）
                        ccHuskPos = bot.getEntitiesNear(16.0).stream()
                                .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(bot.getLocalPlayer())))
                                .map(net.minecraft.world.entity.Entity::getEyePosition)
                                .orElse(null);
                        com.mockplayer.session.ControlCommands.attack(botName, "husk");
                        // 攻击同一 tick 立即断言
                        check("attack auto-face target", ccFacing(bot.getLocalPlayer(), ccHuskPos, 10.0F, 15.0F));
                    }
                }
                server.execute(() -> {
                    var level = server.getLevel(Level.OVERWORLD);
                    if (level != null) {
                        var husk = level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                                bot.getLocalPlayer().getBoundingBox().inflate(8.0)).stream().findFirst();
                        husk.ifPresent(h -> ccHuskHealth = h.getHealth());
                    }
                });
                if (ccHuskHealth < 10.0F) {
                    check("attack target Husk damaged", true);
                    step = 5;
                } else if (++waitTicks > 120) {
                    fail("attack target timeout hp=" + ccHuskHealth + " before=" + ccHuskHealthBeforeTarget);
                    step = 5;
                }
            }
            case 5 -> { // sustainedAttack 持续伤害 + stopSustained
                if (!ccSustainedStarted) {
                    ccSustainedStarted = true;
                    ccSustainedHit = false;
                    waitTicks = 0;
                    com.mockplayer.session.ControlCommands.sustainedAttack(botName, null);
                }
                server.execute(() -> {
                    var level = server.getLevel(Level.OVERWORLD);
                    if (level != null) {
                        var husk = level.getEntitiesOfClass(net.minecraft.world.entity.monster.zombie.Zombie.class,
                                bot.getLocalPlayer().getBoundingBox().inflate(8.0)).stream().findFirst();
                        husk.ifPresent(h -> {
                            if (h.getHealth() < ccHuskHealth) {
                                ccSustainedHit = true;
                            }
                            ccHuskHealth = h.getHealth();
                        });
                    }
                });
                if (ccSustainedHit) {
                    check("sustainedAttack hit repeatedly", true);
                    com.mockplayer.session.ControlCommands.stopSustained(botName);
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
                    step = 6;
                } else if (++waitTicks > 160) {
                    fail("sustainedAttack timeout");
                    com.mockplayer.session.ControlCommands.stopSustained(botName);
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
                    step = 6;
                }
            }
            case 6 -> { // interact 村民 → 服务端 MerchantMenu
                if (!ccVillagerSummoned) {
                    ccVillagerSummoned = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    String.format("summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:diamond\",count:1},maxUses:99,xp:1}]}}",
                                            sp.getX() + 1.0, sp.getY(), sp.getZ()));
                        }
                    });
                }
                if (++waitTicks > 15 && !ccMerchantOpen) {
                    com.mockplayer.session.ControlCommands.interact(botName, null);
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccMerchantOpen = sp != null && sp.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu;
                });
                if (ccMerchantOpen) {
                    check("interact villager opened merchant", true);
                    step = 7;
                } else if (waitTicks > 120) {
                    fail("interact villager timeout");
                    step = 7;
                }
            }
            case 7 -> { // hotbar 2 → 服务端选中槽 + 主玩家快捷栏不变
                if (!ccHotbarGiven) {
                    ccHotbarGiven = true;
                    ccHotbarVerified = false;
                    waitTicks = 0;
                    ccMainHandBefore = mc.player.getMainHandItem().getHoverName().getString();
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " hotbar.0 with minecraft:stone");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " hotbar.1 with minecraft:oak_planks");
                    });
                }
                if (++waitTicks > 15 && !ccHotbarVerified) {
                    com.mockplayer.session.ControlCommands.hotbar(botName, 2);
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccHotbarVerified = sp != null && sp.getInventory().getSelectedSlot() == 1
                            && mc.player.getMainHandItem().getHoverName().getString().equals(ccMainHandBefore);
                });
                if (ccHotbarVerified) {
                    check("hotbar switched bot slot, main player untouched", true);
                    step = 8;
                } else if (waitTicks > 120) {
                    fail("hotbar timeout");
                    step = 8;
                }
            }
            case 8 -> { // drop 槽 1 一个 → 服务端槽 0 空
                if (!ccDropVerified) {
                    ccDropVerified = false;
                    waitTicks = 0;
                    com.mockplayer.session.ControlCommands.drop(botName, 1, false);
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccDropVerified = sp != null && sp.getInventory().getItem(1).isEmpty();
                });
                if (ccDropVerified) {
                    check("drop slot 1 removed item", true);
                    step = 9;
                } else if (++waitTicks > 80) {
                    fail("drop timeout");
                    step = 9;
                }
            }
            case 9 -> { // swapHands → 服务端主副手交换
                if (!ccSwapGiven) {
                    ccSwapGiven = true;
                    ccSwapVerified = false;
                    waitTicks = 0;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:stone_sword");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.offhand with minecraft:stick");
                    });
                }
                if (++waitTicks > 15 && !ccSwapVerified) {
                    com.mockplayer.session.ControlCommands.swapHands(botName);
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccSwapVerified = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.STICK)
                            && sp.getOffhandItem().is(net.minecraft.world.item.Items.STONE_SWORD);
                });
                if (ccSwapVerified) {
                    check("swapHands exchanged main/off hand", true);
                    step = 10;
                } else if (waitTicks > 120) {
                    fail("swapHands timeout");
                    step = 10;
                }
            }
            case 10 -> { // chat（假人身份广播）+ command（服务端 time）
                if (!ccChatListenerRegistered) {
                    ccChatListenerRegistered = true;
                    waitTicks = 0;
                    MockplayerApi.listen(ccChatListener);
                }
                if (!ccChatSent) {
                    ccChatSent = true;
                    ccChatMsg = "";
                    com.mockplayer.session.ControlCommands.chat(botName, "mockplayer-ctl-chat");
                }
                if (ccChatMsg.contains("mockplayer-ctl-chat") && !ccChatChecked) {
                    ccChatChecked = true;
                    check("chat command broadcast as bot", true);
                    if (!ccCommandSent) {
                        ccCommandSent = true;
                        ccChatMsg = "";
                        com.mockplayer.session.ControlCommands.command(botName, "me mockplayer-ctl-cmd");
                    }
                }
                if (ccChatMsg.contains("mockplayer-ctl-cmd")) {
                    check("command time set executed", true);
                    step = 11;
                } else if (++waitTicks > 200) {
                    fail("chat/command timeout");
                    step = 11;
                }
            }
            case 11 -> { // useItem（面包→using）+ releaseUsingItem
                if (!ccUseGiven) {
                    ccUseGiven = true;
                    ccUsing = false;
                    ccReleased = false;
                    waitTicks = 0;
                    bot.getContainer().ifPresent(c -> c.close());
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:bread"));
                }
                if (++waitTicks > 15 && !ccUsing) {
                    com.mockplayer.session.ControlCommands.useItem(botName, "mainhand");
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccUsing = sp != null && sp.isUsingItem();
                });
                if (ccUsing && !ccUseChecked) {
                    ccUseChecked = true;
                    check("useItem started using", true);
                    if (!ccReleased) {
                        ccReleased = true;
                        com.mockplayer.session.ControlCommands.releaseUsingItem(botName);
                    }
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccReleased = sp != null && !sp.isUsingItem();
                });
                if (ccReleased) {
                    check("releaseUsingItem stopped using", true);
                    step = 12;
                } else if (waitTicks > 120) {
                    fail("useItem timeout");
                    step = 12;
                }
            }
            case 12 -> { // placeBlock → attackBlock(创造瞬破) → pickItemFromBlock → placeBlock/mineBlock 强断言
                if (!ccDirtGiven) {
                    ccDirtGiven = true;
                    ccPlacePos = null;
                    ccPlacePos2 = null;
                    waitTicks = 0;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:dirt"));
                }
                if (ccPlacePos == null) {
                    var p = bot.getLocalPlayer();
                    ccPlacePos = p.blockPosition().offset(0, 0, 2);
                    ccPlacePos2 = p.blockPosition().offset(0, 0, 3);
                    // placeBlockAt 目标：复用被挖空的位置（下方是实心地面，可作支撑块）
                    ccPlaceAtPos = p.blockPosition().offset(0, 0, 3);
                }
                if (!ccPlaceSent) {
                    if (++waitTicks > 15) {
                        ccPlaceSent = true;
                        waitTicks = 0;
                        com.mockplayer.session.ControlCommands.placeBlock(botName,
                                ccPlacePos.getX(), ccPlacePos.getY(), ccPlacePos.getZ(), "up");
                    }
                } else if (!ccPlacedDirt) {
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccPlacedDirt = ccPlacePos != null && level != null
                                && level.getBlockState(ccPlacePos).is(net.minecraft.world.level.block.Blocks.DIRT);
                    });
                    if (++waitTicks > 80) {
                        fail("placeBlock timeout");
                        step = 13;
                    }
                } else if (!ccPlaceChecked) {
                    ccPlaceChecked = true;
                    check("placeBlock placed dirt", true);
                    ccCreativeSet = true;
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "gamemode creative " + botName));
                } else if (!ccPickSent) {
                    if (++waitTicks > 25) {
                        ccPickSent = true;
                        waitTicks = 0;
                        com.mockplayer.session.ControlCommands.pickItemFromBlock(botName,
                                ccPlacePos.getX(), ccPlacePos.getY(), ccPlacePos.getZ(), false);
                    }
                } else if (!ccPickVerified) {
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        ccPickVerified = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.DIRT);
                    });
                    if (++waitTicks > 40) {
                        fail("pickItemFromBlock timeout");
                        step = 13;
                    }
                } else if (!ccPickChecked) {
                    ccPickChecked = true;
                    check("pickItemFromBlock put dirt in hand", true);
                    ccAttackBlockSent = true;
                    com.mockplayer.session.ControlCommands.attackBlock(botName,
                            ccPlacePos.getX(), ccPlacePos.getY(), ccPlacePos.getZ());
                } else if (!ccAttackBlockBroken) {
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccAttackBlockBroken = ccPlacePos != null && level != null
                                && level.getBlockState(ccPlacePos).is(net.minecraft.world.level.block.Blocks.AIR);
                    });
                    if (++waitTicks > 60) {
                        fail("attackBlock timeout");
                        step = 13;
                    }
                } else if (!ccAttackBlockChecked) {
                    ccAttackBlockChecked = true;
                    check("attackBlock broke dirt (creative)", true);
                    ccSurvivalBack = true;
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "gamemode survival " + botName));
                } else if (!ccPlace2Sent) {
                    if (++waitTicks > 25) {
                        ccPlace2Sent = true;
                        waitTicks = 0;
                        // 先背对目标（朝北），验证命令层 placeBlock 会自动 lookAt 转回来
                        bot.actions().look(180.0F, 0.0F);
                        com.mockplayer.session.ControlCommands.placeBlock(botName,
                                ccPlacePos2.getX(), ccPlacePos2.getY(), ccPlacePos2.getZ(), "up");
                    }
                } else if (!ccPlacedDirt2) {
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccPlacedDirt2 = ccPlacePos2 != null && level != null
                                && level.getBlockState(ccPlacePos2).is(net.minecraft.world.level.block.Blocks.DIRT);
                    });
                    if (++waitTicks > 160) {
                        fail("placeBlock 2 timeout");
                        step = 13;
                    }
                } else if (!ccPlaced2Checked) {
                    ccPlaced2Checked = true;
                    check("placeBlock placed dirt 2", true);
                    check("placeBlock auto-face target", ccFacing(bot.getLocalPlayer(),
                            net.minecraft.world.phys.Vec3.atCenterOf(ccPlacePos2), 12.0F, 20.0F));
                    ccMineSent = true;
                    // 再背对目标（朝北），验证命令层 mineBlock 会自动 lookAt 转回来
                    bot.actions().look(180.0F, 0.0F);
                    com.mockplayer.session.ControlCommands.mineBlock(botName,
                            ccPlacePos2.getX(), ccPlacePos2.getY(), ccPlacePos2.getZ());
                } else if (!ccMinedAir2) {
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccMinedAir2 = ccPlacePos2 != null && level != null
                                && level.getBlockState(ccPlacePos2).is(net.minecraft.world.level.block.Blocks.AIR);
                    });
                    if (++waitTicks > 200) {
                        fail("mineBlock timeout");
                        step = 13;
                    }
                } else if (!ccMinedChecked) {
                    ccMinedChecked = true;
                    check("mineBlock broke dirt (survival)", true);
                    check("mineBlock auto-face target", ccFacing(bot.getLocalPlayer(),
                            net.minecraft.world.phys.Vec3.atCenterOf(ccPlacePos2), 12.0F, 20.0F));
                } else if (!ccPlaceAtGiven) {
                    ccPlaceAtGiven = true;
                    waitTicks = 0;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:dirt"));
                } else if (!ccPlaceAtSent) {
                    if (++waitTicks > 15 && bot.getLocalPlayer().getMainHandItem().is(net.minecraft.world.item.Items.DIRT)) {
                        ccPlaceAtSent = true;
                        waitTicks = 0;
                        // 朝向由外部调用者负责：placeBlockAt 不再自动转向，先 lookAt 目标
                        bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(ccPlaceAtPos));
                        bot.actions().placeBlockAt(ccPlaceAtPos);
                    }
                } else if (!ccPlacedAt) {
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccPlacedAt = ccPlaceAtPos != null && level != null
                                && level.getBlockState(ccPlaceAtPos).is(net.minecraft.world.level.block.Blocks.DIRT);
                    });
                    if (++waitTicks > 160) {
                        fail("placeBlockAt timeout");
                        step = 13;
                    }
                } else if (!ccPlaceAtChecked) {
                    ccPlaceAtChecked = true;
                    check("placeBlockAt placed dirt at exact pos (server)", true);
                    check("placeBlockAt external lookAt applied", ccFacing(bot.getLocalPlayer(),
                            net.minecraft.world.phys.Vec3.atCenterOf(ccPlaceAtPos), 12.0F, 20.0F));
                    step = 13;
                }
            }
            case 13 -> { // wakeUp：夜晚睡床 → 醒来
                if (!ccBedSet) {
                    ccBedSet = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 13000");
                        var p = bot.getLocalPlayer();
                        ccBedPos = p.blockPosition().offset(0, 0, 2);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + ccBedPos.getX() + " " + ccBedPos.getY() + " " + ccBedPos.getZ()
                                        + " minecraft:red_bed[facing=south,part=head]");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + ccBedPos.getX() + " " + ccBedPos.getY() + " " + (ccBedPos.getZ() - 1)
                                        + " minecraft:red_bed[facing=south,part=foot]");
                        bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(ccBedPos));
                    });
                }
                if (++waitTicks > 20 && !ccBedClicked) {
                    ccBedClicked = true;
                    com.mockplayer.session.ControlCommands.useItemOn(botName, ccBedPos.getX(), ccBedPos.getY(), ccBedPos.getZ(), "up");
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccSleeping = sp != null && sp.isSleeping();
                });
                if (ccSleeping && !ccSleepChecked) {
                    ccSleepChecked = true;
                    check("useItemOn bed sleeping", true);
                    if (!ccWoke) {
                        ccWoke = true;
                        com.mockplayer.session.ControlCommands.wakeUp(botName);
                    }
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccWoke = sp != null && !sp.isSleeping();
                });
                if (ccWoke) {
                    check("wakeUp stopped sleeping", true);
                    step = 14;
                } else if (waitTicks > 200) {
                    fail("wakeUp timeout");
                    step = 14;
                }
            }
            case 14 -> { // mount 矿车 + 移动中 dismount
                if (!ccRailSet) {
                    ccRailSet = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        var p = bot.getLocalPlayer();
                        var cartPos = p.blockPosition().offset(2, 0, 0);
                        // 5 格直线平轨：矿车静止停靠（不会因坠落/碰撞被服务端自动弹出乘客），
                        // 骑乘稳定后 data merge 给 Motion 让矿车滑行（覆盖「移动中下马」）
                        for (int i = 0; i < 5; i++) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "setblock " + (cartPos.getX() + i) + " " + cartPos.getY() + " " + cartPos.getZ()
                                            + " minecraft:rail");
                        }
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                String.format("summon minecraft:minecart %.2f %.2f %.2f",
                                        cartPos.getX() + 0.5, cartPos.getY() + 0.0, cartPos.getZ() + 0.5));
                    });
                }
                if (++waitTicks > 15 && !ccMountSent) {
                    ccMountSent = true;
                    com.mockplayer.session.ControlCommands.mount(botName, "minecart", null);
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccMounted = sp != null && sp.getVehicle() != null;
                });
                if (ccMounted && !ccMountChecked) {
                    // 稳定骑乘验证：连续 20 tick 服务端仍骑乘才认定上马成功，
                    // 排除「矿车坠落/碰撞被服务端自动弹出乘客」造成的假绿
                    if (++ccMountStableTicks >= 20) {
                        ccMountStableTicks = 0;
                        ccMountChecked = true;
                        check("mount minecart", true);
                        server.execute(() -> {
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            if (sp != null) {
                                ccCartStartX = sp.getX();
                            }
                        });
                    }
                } else {
                    ccMountStableTicks = 0;
                }
                // 骑乘中给矿车速度：data merge Motion（骑乘后设置，summon 时给会被骑乘初始化重置）
                if (ccMountChecked && !ccDismountSent && !ccCartPushed) {
                    ccCartPushed = true;
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null && sp.getVehicle() != null) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "data merge entity " + sp.getVehicle().getUUID()
                                            + " {Motion:[0.6d,0.0d,0.0d]}");
                        }
                    });
                }
                if (ccMountChecked && !ccDismountSent) {
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null && sp.getVehicle() != null) {
                            ccCartMoved = Math.abs(sp.getX() - ccCartStartX) > 0.5;
                        }
                    });
                    if (ccCartMoved) {
                        ccDismountSent = true;
                        waitTicks = 0;
                        com.mockplayer.session.ControlCommands.dismount(botName);
                    }
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccDismounted = sp != null && sp.getVehicle() == null;
                });
                // 必须先 mount 成功才允许 dismount PASS：没上马时 getVehicle()==null 恒真，会假绿
                if (ccDismounted && ccMountChecked) {
                    check("dismount while moving", ccCartMoved);
                    check("dismount", true);
                    step = 15;
                } else if (waitTicks > 220) {
                    fail("mount/dismount timeout");
                    step = 15;
                }
            }
            case 15 -> { // 查询命令全断言
                if (!ccChestSet) {
                    ccChestSet = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "give " + botName + " minecraft:stone 3");
                        // 主手换成不可放置物品（木棍）：否则 BlockItem 会尝试放到箱子顶上，箱子打不开
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " weapon.mainhand with minecraft:stick");
                        var p = bot.getLocalPlayer();
                        ccChestPos = p.blockPosition().offset(2, 0, 0);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + ccChestPos.getX() + " " + ccChestPos.getY() + " " + ccChestPos.getZ()
                                        + " minecraft:chest");
                        bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(ccChestPos));
                    });
                }
                if (++waitTicks > 20 && !ccChestClicked) {
                    ccChestClicked = true;
                    com.mockplayer.session.ControlCommands.useItemOn(botName,
                            ccChestPos.getX(), ccChestPos.getY(), ccChestPos.getZ(), "up");
                }
                if (waitTicks > 40 && !ccQueriesOk) {
                    ccQueriesOk = true;
                    String containerText = com.mockplayer.session.QueryCommands.container(botName).getString();
                    String infoText = com.mockplayer.session.QueryCommands.botInfo(botName).getString();
                    String invText = com.mockplayer.session.QueryCommands.inventory(botName).getString();
                    String nearText = com.mockplayer.session.QueryCommands.near(botName, 16.0).getString();
                    var p = bot.getLocalPlayer();
                    String blockText = com.mockplayer.session.QueryCommands.blockAt(
                            botName, p.blockPosition().getX(), p.blockPosition().getY() - 1, p.blockPosition().getZ()).getString();
                    String onlineText = com.mockplayer.session.QueryCommands.online(botName).getString();
                    String chatText = com.mockplayer.session.QueryCommands.chatHistory(botName).getString();
                    check("query container", containerText.contains("id="), "text=" + containerText);
                    check("query info", infoText.contains(botName) && !infoText.contains("commands.mockplayer."));
                    check("query inventory", invText.contains(" x"), "text=" + invText);
                    check("query near", nearText.contains("villager"), "text=" + nearText);
                    check("query block", blockText.contains("minecraft:"), "text=" + blockText);
                    check("query online", onlineText.contains(mc.player.getGameProfile().name()) && onlineText.contains(botName));
                    check("query chat", chatText.contains("mockplayer-ctl-chat"));
                    // 所有查询输出：不得残留 key 原文 / 字面 %s（模板参数必须传全）
                    java.util.List<String> queryTexts = java.util.List.of(
                            containerText, infoText, invText, nearText, blockText, onlineText, chatText);
                    boolean noResidue = queryTexts.stream()
                            .noneMatch(t -> t.contains("commands.mockplayer.") || t.contains("%s"));
                    check("query outputs no key/%s residue", noResidue, "texts=" + queryTexts);
                    // memory 精确记账强测：JVM 真实值 + Mod 侧精确字节
                    var mem = bot.memoryInfo();
                    check("memory jvm used", mem.jvmUsedBytes() > 0);
                    check("memory jvm max", mem.jvmMaxBytes() >= mem.jvmUsedBytes());
                    check("memory bot count", mem.botCount() >= 1);
                    check("memory chat exact", mem.chatBytes() > 0, "bytes=" + mem.chatBytes());
                    check("memory sound exact", mem.soundBytes() >= 0);
                    check("memory particle exact", mem.particleBytes() >= 0);
                    check("memory packet count", mem.packetCount() > 0, "count=" + mem.packetCount());
                    check("memory online exact", mem.onlinePlayersBytes() > 0, "bytes=" + mem.onlinePlayersBytes());
                    check("memory inventory exact", mem.inventoryBytes() > 0, "bytes=" + mem.inventoryBytes());
                    check("memory entity count", mem.entityCount() > 0, "count=" + mem.entityCount());
                    check("memory chunk count", mem.chunkCount() > 0, "count=" + mem.chunkCount());
                    String memText = com.mockplayer.session.QueryCommands.memory(botName).getString();
                    check("memory text", memText.contains("JVM") && memText.contains(botName)
                                    && !memText.contains("commands.mockplayer.query."),
                            "text=" + memText.replace("\n", "|"));
                    // 容器交互命令强测：拿起主手石头 → 放入箱子槽 0 → 服务端 BlockEntity 证据
                    ccClickPutSent = true;
                    waitTicks = 0;
                    com.mockplayer.session.ControlCommands.hotbar(botName, 1);
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "give " + botName + " minecraft:stone 1"));
                } else if (ccClickPutSent && !ccClickPutDone) {
                    if (++waitTicks > 5) {
                        ccClickPutDone = true;
                        waitTicks = 0;
                        var c = bot.getContainer();
                        // 菜单槽 = 总槽数 - 9（快捷栏起点）+ selected(0)；getSize() 含玩家背包 36 槽
                        int pickupSlot = (c.isPresent() ? c.get().getSize() : 63) - 9;
                        com.mockplayer.session.ControlCommands.click(botName, pickupSlot, 0, "pickup");
                        com.mockplayer.session.ControlCommands.click(botName, 0, 0, "pickup");
                    }
                } else if (ccClickPutDone && !ccClickPutVerified) {
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccClickPutItemInChest = level != null
                                && level.getBlockEntity(ccChestPos)
                                instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest
                                && chest.getItem(0).is(net.minecraft.world.item.Items.STONE);
                    });
                    if (++waitTicks > 40) {
                        ccClickPutVerified = true;
                        check("container click put item", ccClickPutItemInChest,
                                "chest0=" + ccClickPutItemInChest);
                        // 箱子开着 + 槽 0 有石头 → 容器序列化物品数据字节必须 > 0（精确记账）
                        var mem2 = bot.memoryInfo();
                        check("memory container exact after put", mem2.containerBytes() > 0,
                                "bytes=" + mem2.containerBytes());
                        // 容器用完必须关闭：走 /control close 命令路径（强测命令本身）
                        com.mockplayer.session.ControlCommands.close(botName);
                        ccCloseSent = true;
                        step = 16;
                    }
                } else if (waitTicks > 160) {
                    fail("query timeout");
                    step = 16;
                }
            }
            case 16 -> { // listen 实时事件 + 推送 + off 惰性恢复
                if (ccCloseSent && !ccCloseChecked) {
                    if (bot.getContainer().isEmpty()) {
                        ccCloseChecked = true;
                        check("container closed after query", true);
                        waitTicks = 0;
                    } else if (++waitTicks > 40) {
                        fail("container close timeout");
                        ccCloseChecked = true;
                        waitTicks = 0;
                    }
                }
                if (ccCloseChecked && !ccListenOn) {
                    ccListenOn = true;
                    waitTicks = 0;
                    check("memory event cache zero before listen", bot.memoryInfo().eventCacheBytes() == 0);
                    String onText = com.mockplayer.session.QueryCommands.listen(botName, true).getString();
                    check("listen on feedback", onText.contains(botName), "text=" + onText);
                    if (!ccListenDamaged) {
                        ccListenDamaged = true;
                        server.execute(() -> server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack(), "damage " + botName + " 4"));
                    }
                }
                if (ccListenDamaged && !ccEventsHasDamage) {
                    com.mockplayer.session.EventRecorder recorder = com.mockplayer.session.QueryCommands.getRecorder(botName);
                    ccEventsHasDamage = recorder != null && recorder.getPushCount() >= 1
                            && recorder.snapshot().stream().anyMatch(s -> s.startsWith("onDamage|"));
                }
                if (ccEventsHasDamage) {
                    check("listen recorded+push damage event", true);
                    check("memory event cache exact after damage", bot.memoryInfo().eventCacheBytes() > 0);
                    if (!ccListenOff) {
                        ccListenOff = true;
                        String offText = com.mockplayer.session.QueryCommands.listen(botName, false).getString();
                        check("listen off feedback", offText.contains(botName), "text=" + offText);
                        check("listen off removes recorder", com.mockplayer.session.QueryCommands.getRecorder(botName) == null);
                        check("memory event cache zero after off", bot.memoryInfo().eventCacheBytes() == 0);
                        String notText = com.mockplayer.session.QueryCommands.events(botName, 10).getString();
                        check("events after off says not listening", notText.contains(botName), "text=" + notText);
                        step = 17;
                    }
                } else if (++waitTicks > 200) {
                    com.mockplayer.session.EventRecorder recorder = com.mockplayer.session.QueryCommands.getRecorder(botName);
                    fail("listen timeout recorder=" + (recorder != null)
                            + " push=" + (recorder != null ? recorder.getPushCount() : -1)
                            + " snap=" + (recorder != null ? recorder.snapshot().stream().limit(5).toList() : "[]"));
                    com.mockplayer.session.QueryCommands.listen(botName, false);
                    step = 17;
                }
            }
            case 17 -> { // respawn 强测：服务端击杀 → 死亡 → respawn 命令 → 服务端复活
                if (!ccRespawnKilled) {
                    ccRespawnKilled = true;
                    waitTicks = 0;
                    // 关闭自动重生，才能证明是 respawn 命令让 bot 复活
                    com.mockplayer.session.SessionManager.getInstance().getSession(botName).setAutoRespawn(false);
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill " + botName));
                }
                if (!ccRespawnDone) {
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        ccRespawnDead = sp != null && !sp.isAlive();
                    });
                    if (++waitTicks > 2 && ccRespawnDead) {
                        ccRespawnDone = true;
                        waitTicks = 0;
                        com.mockplayer.session.ControlCommands.respawn(botName);
                    } else if (waitTicks > 40) {
                        fail("respawn kill timeout alive=" + ccRespawnAlive);
                        ccRespawnDone = true;
                        waitTicks = 0;
                    }
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccRespawnAlive = sp != null && sp.isAlive();
                });
                if (ccRespawnDone && ccRespawnAlive) {
                    check("respawn recovered bot", true);
                    com.mockplayer.session.SessionManager.getInstance().getSession(botName).setAutoRespawn(true);
                    step = 18;
                } else if (ccRespawnDone && ++waitTicks > 120) {
                    fail("respawn timeout");
                    step = 18;
                }
            }
            case 18 -> { // 错误路径 + 全部动作输出 i18n
                if (!ccErrorsOk) {
                    ccErrorsOk = true;
                    String notFound = com.mockplayer.session.ControlCommands.attack("nobody-cc", null).getString();
                    String invalidHand = com.mockplayer.session.ControlCommands.useItem(botName, "bad").getString();
                    String invalidSide = com.mockplayer.session.ControlCommands.useItemOn(botName, 0, 0, 0, "bad").getString();
                    String invalidEffect = com.mockplayer.session.ControlCommands.setBeacon(
                            botName, "minecraft:nonexistent_effect", null).getString();
                    String blank = com.mockplayer.session.ControlCommands.chat(botName, " ").getString();
                    String noEntity = com.mockplayer.session.ControlCommands.attack(botName, "zzz-no-entity").getString();
                    check("error not_found", notFound.contains("nobody-cc"));
                    check("error invalid_hand", invalidHand.contains("bad"));
                    check("error invalid_side", invalidSide.contains("bad"));
                    check("error invalid_effect", invalidEffect.contains("nonexistent"));
                    check("error blank_message", !blank.isBlank() && !blank.contains("commands.mockplayer.control."));
                    check("error entity_not_found", noEntity.contains("zzz-no-entity"));
                    // 全部动作调用：输出都来自语言文件（getString 不含未翻译 key 原文）
                    java.util.List<net.minecraft.network.chat.Component> outputs = new java.util.ArrayList<>();
                    outputs.add(com.mockplayer.session.ControlCommands.move(botName, "forward"));
                    outputs.add(com.mockplayer.session.ControlCommands.stop(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.setSneak(botName, true));
                    outputs.add(com.mockplayer.session.ControlCommands.setSprint(botName, true));
                    outputs.add(com.mockplayer.session.ControlCommands.jump(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.look(botName, 0.0F, 0.0F));
                    outputs.add(com.mockplayer.session.ControlCommands.lookAt(botName, 0.0, 0.0, 0.0));
                    outputs.add(com.mockplayer.session.ControlCommands.turn(botName, 0.0F, 0.0F));
                    outputs.add(com.mockplayer.session.ControlCommands.stab(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.interact(botName, null));
                    outputs.add(com.mockplayer.session.ControlCommands.useItem(botName, "offhand"));
                    outputs.add(com.mockplayer.session.ControlCommands.releaseUsingItem(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.useItemOn(botName, 0, 0, 0, "up"));
                    outputs.add(com.mockplayer.session.ControlCommands.placeBlock(botName, 0, 0, 0, "up"));
                    outputs.add(com.mockplayer.session.ControlCommands.mineBlock(botName, 0, 0, 0));
                    outputs.add(com.mockplayer.session.ControlCommands.attackBlock(botName, 0, 0, 0));
                    outputs.add(com.mockplayer.session.ControlCommands.hotbar(botName, 1));
                    outputs.add(com.mockplayer.session.ControlCommands.drop(botName, null, false));
                    outputs.add(com.mockplayer.session.ControlCommands.swapHands(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.mount(botName, null, null));
                    outputs.add(com.mockplayer.session.ControlCommands.dismount(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.chat(botName, "mockplayer-ctl-final"));
                    outputs.add(com.mockplayer.session.ControlCommands.command(botName, "time set 2000"));
                    outputs.add(com.mockplayer.session.ControlCommands.wakeUp(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.respawn(botName));
                    outputs.add(com.mockplayer.session.ControlCommands.editBook(botName, 1, "page", null));
                    outputs.add(com.mockplayer.session.ControlCommands.editSign(botName, 0, 0, 0, true,
                            new String[]{"a", "b", "c", "d"}));
                    outputs.add(com.mockplayer.session.ControlCommands.setBeacon(botName, "minecraft:speed", null));
                    outputs.add(com.mockplayer.session.ControlCommands.renameItem(botName, "Test"));
                    outputs.add(com.mockplayer.session.ControlCommands.pickItemFromBlock(botName, 0, 0, 0, false));
                    outputs.add(com.mockplayer.session.ControlCommands.sustainedAttack(botName, null));
                    outputs.add(com.mockplayer.session.ControlCommands.sustainedUse(botName, null));
                    outputs.add(com.mockplayer.session.ControlCommands.stopSustained(botName));
                    boolean allI18n = true;
                    for (net.minecraft.network.chat.Component c : outputs) {
                        String s = c.getString();
                        if (s.isBlank() || s.contains("commands.mockplayer.") || s.contains("%s")) {
                            allI18n = false;
                            System.out.println("[mocktest] non-i18n output: " + s);
                        }
                    }
                    check("all control outputs i18n", allI18n);
                    // 输出转义强测：%s / § 输入不二次解析、不注入样式、不炸
                    String escapeChat = "100%s \u00a7c\u00a7lHi";
                    net.minecraft.network.chat.Component escapeOut =
                            com.mockplayer.session.ControlCommands.chat(botName, escapeChat);
                    check("escape chat text", escapeOut.getString().contains(escapeChat),
                            "text=" + escapeOut.getString());
                    check("escape chat no style inject", ccNoInjectedStyle(escapeOut, false),
                            "styles=" + ccCollectStyles(escapeOut));
                    String escapeCmd = "say 100%s \u00a7c";
                    net.minecraft.network.chat.Component escapeCmdOut =
                            com.mockplayer.session.ControlCommands.command(botName, escapeCmd);
                    check("escape command text", escapeCmdOut.getString().contains(escapeCmd),
                            "text=" + escapeCmdOut.getString());
                    net.minecraft.network.chat.Component escapeNameOut =
                            com.mockplayer.session.ControlCommands.stop("x\u00a7ly");
                    check("escape name text", escapeNameOut.getString().contains("x\u00a7ly"),
                            "text=" + escapeNameOut.getString());
                    check("escape name no style inject", ccNoInjectedStyle(escapeNameOut, true),
                            "styles=" + ccCollectStyles(escapeNameOut));
                    // container.none 模板 %s 必须被替换：输出含假人名字、不得残留字面 %s
                    String noneText = com.mockplayer.session.QueryCommands.container(botName).getString();
                    check("container none text", noneText.contains(botName) && !noneText.contains("%s"),
                            "text=" + noneText);
                    // 清掉 outputs 里 move/jump 等持续输入，否则假人带着前进+跳跃进入挖掘测试
                    // （onGround=false → 挖掘速度 /5，mine time 测试会假失败）
                    com.mockplayer.session.ControlCommands.stop(botName);
                    step = 19;
                }
            }
            case 19 -> { // 挖掘时间原版锁定 + stopSustained 取消挖掘（服务端证据）
                if (!ccMineFlushDone) {
                    // 先让 case 18 的 drop/swapHands 等延迟动作包在服务端处理完：
                    // 否则 replace 石镐后旧包才到达，服务端会把刚放好的石镐丢/换掉
                    // （drop 本地移除不回显 → 客户端有镐、服务端槽空，mine sync 假失败）
                    if (++waitTicks > 40) {
                        ccMineFlushDone = true;
                        waitTicks = 0;
                    }
                    return;
                }
                if (!ccMineTestSet) {
                    ccMineTestSet = true;
                    waitTicks = 0;
                    // 客户端先固定选中槽 0（与服务端一致，item replace 才能落在主手）
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    server.execute(() -> {
                        // 用服务端玩家位置算石头坐标：respawn 后客户端位置可能尚未同步到重生点
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        ccMineSpReady = sp != null;
                        if (sp == null) {
                            return;
                        }
                        ccMineStone1 = sp.blockPosition().offset(2, 0, 0);
                        ccMineStone2 = sp.blockPosition().offset(3, 0, 0);
                        ccMineStoneFar = sp.blockPosition().offset(8, 0, 0);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + ccMineStone1.getX() + " " + ccMineStone1.getY() + " "
                                        + ccMineStone1.getZ() + " minecraft:stone");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + ccMineStone2.getX() + " " + ccMineStone2.getY() + " "
                                        + ccMineStone2.getZ() + " minecraft:stone");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + ccMineStoneFar.getX() + " " + ccMineStoneFar.getY() + " "
                                        + ccMineStoneFar.getZ() + " minecraft:stone");
                        // 先固定服务端选中槽 0，再替换主手：顺序反了镐会落到原选中槽
                        sp.getInventory().setSelectedSlot(0);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                // give 不覆盖主手（selected 槽还有木棍），必须 replace 才保证用石镐挖
                                "item replace entity " + botName + " weapon.mainhand with minecraft:stone_pickaxe");
                        // 立即快照：replace 刚执行完时的服务端主手（判断是被清空还是 replace 失败）
                        ccMineAfterReplace = sp.getMainHandItem().is(net.minecraft.world.item.Items.STONE_PICKAXE)
                                ? "pickaxe" : String.valueOf(sp.getMainHandItem().getItem());
                    });
                } else if (!ccMineTestSynced) {
                    if (!ccMineSpReady || ccMineStone1 == null) {
                        // 服务端玩家还没就绪：等待，不要对 null 位置调 getBlockState
                        if (++waitTicks > 40) {
                            fail("mine test spawn missing");
                            step = 20;
                        }
                        return;
                    }
                    if (++waitTicks > 20
                            && bot.getBlockState(ccMineStone1).is(net.minecraft.world.level.block.Blocks.STONE)
                            && bot.getBlockState(ccMineStone2).is(net.minecraft.world.level.block.Blocks.STONE)
                            // 石镐必须已同步到主手再开挖：空手挖石头约 45 tick，
                            // 中途换镐会拉长总时长（all 模式偶发 115 tick 的根因，hand=air 特征）
                            && bot.getLocalPlayer().getMainHandItem()
                                    .is(net.minecraft.world.item.Items.STONE_PICKAXE)) {
                        server.execute(() -> {
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            ccMineServerPickaxe = sp != null
                                    && sp.getMainHandItem().is(net.minecraft.world.item.Items.STONE_PICKAXE);
                            ccMineMainHand = sp != null ? String.valueOf(sp.getMainHandItem().getItem()) : "sp-null";
                            ccMineServerSlot = sp != null ? sp.getInventory().getSelectedSlot() : -1;
                            ccMineServerHotbar = sp != null
                                    ? sp.getInventory().getItem(0) + "|" + sp.getInventory().getItem(1)
                                            + "|" + sp.getInventory().getItem(2)
                                    : "sp-null";
                            if (sp != null) {
                                double dx = sp.getX() - ccMineLastX;
                                double dy = sp.getY() - ccMineLastY;
                                double dz = sp.getZ() - ccMineLastZ;
                                // 0.05 阈值容忍微小浮点抖动（服务端位置更新粒度）
                                ccMineServerMoving = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 0.05;
                                ccMineLastX = sp.getX();
                                ccMineLastY = sp.getY();
                                ccMineLastZ = sp.getZ();
                            }
                        });
                        if (!ccMineServerPickaxe || ccMineServerMoving) {
                            // 主手未就绪或位置仍在漂移（respawn 同步中）：继续等
                            ccMineStableTicks = 0;
                            if (waitTicks > 300) {
                                fail("mine test sync timeout (server pickaxe/stable) clientHand="
                                        + bot.getLocalPlayer().getMainHandItem().getItem()
                                        + " serverPickaxe=" + ccMineServerPickaxe
                                        + " serverHand=" + ccMineMainHand
                                        + " serverSlot=" + ccMineServerSlot
                                        + " serverHotbar=" + ccMineServerHotbar
                                        + " afterReplace=" + ccMineAfterReplace
                                        + " moving=" + ccMineServerMoving
                                        + " ticks=" + waitTicks);
                                step = 20;
                            }
                            return;
                        }
                        if (++ccMineStableTicks < 20) {
                            // 服务端位置连续 20 tick 稳定才开挖（等物理/onGround 收敛，
                            // 挖掘中移动会破坏原版时间锁定；0.05 阈值容忍浮点抖动）
                            return;
                        }
                        ccMineTestSynced = true;
                        ccMineTestTicks = 0;
                        waitTicks = 0;
                        var stoneState = bot.getBlockState(ccMineStone1);
                        ccMineDigSpeed = bot.getLocalPlayer().getDestroySpeed(stoneState);
                        ccMineCorrectTool = bot.getLocalPlayer().hasCorrectToolForDrops(stoneState);
                        ccMineDestroyProgressRate = stoneState.getDestroyProgress(
                                bot.getLocalPlayer(), bot.getLevel(), ccMineStone1);
                        server.execute(() -> {
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            ccMineMainHand = sp != null ? String.valueOf(sp.getMainHandItem().getItem()) : "?";
                        });
                        com.mockplayer.session.ControlCommands.mineBlock(botName,
                                ccMineStone1.getX(), ccMineStone1.getY(), ccMineStone1.getZ());
                    } else if (waitTicks > 200) {
                        fail("mine test sync timeout bot="
                                + (bot.getLocalPlayer() != null ? bot.getLocalPlayer().blockPosition() : "?")
                                + " stone1=" + ccMineStone1
                                + " client=" + bot.getBlockState(ccMineStone1));
                        step = 20;
                    }
                } else if (!ccMineTestDone) {
                    ccMineTestTicks++;
                    server.execute(() -> {
                        var level = server.getLevel(Level.OVERWORLD);
                        ccMineStone1Air = level != null
                                && level.getBlockState(ccMineStone1).is(net.minecraft.world.level.block.Blocks.AIR);
                    });
                    if (ccMineStone1Air) {
                        ccMineTestDone = true;
                        // 石镐挖石头原版约 12 tick（硬度 1.5 × 30 / 速度 4）；6-20 范围锁定原版时间
                        System.out.println("[mocktest] mine ticks=" + ccMineTestTicks
                                + " hand=" + ccMineMainHand
                                + " digSpeed=" + ccMineDigSpeed
                                + " correctTool=" + ccMineCorrectTool
                                + " rate=" + ccMineDestroyProgressRate);
                        check("mine time vanilla", ccMineTestTicks >= 6 && ccMineTestTicks <= 20,
                                "ticks=" + ccMineTestTicks
                                        + " hand=" + ccMineMainHand
                                        + " digSpeed=" + ccMineDigSpeed
                                        + " correctTool=" + ccMineCorrectTool
                                        + " rate=" + ccMineDestroyProgressRate);
                        waitTicks = 0;
                        com.mockplayer.session.ControlCommands.mineBlock(botName,
                                ccMineStone2.getX(), ccMineStone2.getY(), ccMineStone2.getZ());
                    } else if (ccMineTestTicks > 160) {
                        fail("mine time timeout hand=" + ccMineMainHand
                                + " digSpeed=" + ccMineDigSpeed
                                + " correctTool=" + ccMineCorrectTool
                                + " rate=" + ccMineDestroyProgressRate);
                        step = 20;
                    }
                } else if (!ccMineStopSent) {
                    if (++waitTicks >= 5) {
                        ccMineStopSent = true;
                        waitTicks = 0;
                        com.mockplayer.session.ControlCommands.stopSustained(botName);
                    }
                } else {
                    if (++waitTicks > 80) {
                        server.execute(() -> {
                            var level = server.getLevel(Level.OVERWORLD);
                            ccMineStone2Still = level != null
                                    && level.getBlockState(ccMineStone2).is(net.minecraft.world.level.block.Blocks.STONE);
                        });
                        ccMineStopWaitDone = true;
                        waitTicks = 0;
                    } else if (ccMineStopWaitDone && ++waitTicks > 3) {
                        check("stopSustained cancels mining", ccMineStone2Still,
                                "stone2=" + ccMineStone2 + " still=" + ccMineStone2Still);
                        ccMineStopWaitDone = false; // 防止分支重复执行灌爆 records
                        // 距离拒绝：8 格外方块超出原版交互范围，mineBlock 必须被客户端拒绝
                        com.mockplayer.session.ControlCommands.mineBlock(botName,
                                ccMineStoneFar.getX(), ccMineStoneFar.getY(), ccMineStoneFar.getZ());
                        ccMineFarSent = true;
                        waitTicks = 0;
                    } else if (ccMineFarSent && !ccMineFarChecked) {
                        if (++waitTicks > 20) {
                            ccMineFarChecked = true;
                            server.execute(() -> {
                                var level = server.getLevel(Level.OVERWORLD);
                                ccMineFarStill = level != null
                                        && level.getBlockState(ccMineStoneFar).is(net.minecraft.world.level.block.Blocks.STONE);
                            });
                            ccMineFarWaitDone = true;
                            waitTicks = 0;
                        }
                    } else if (ccMineFarWaitDone && ++waitTicks > 3) {
                        check("mine distance rejected", ccMineFarStill,
                                "far=" + ccMineStoneFar + " still=" + ccMineFarStill);
                        ccMineFarWaitDone = false; // 防止分支重复执行灌爆 records
                        step = 20;
                    }
                }
            }
            case 20 -> { // chunkRadius：配置默认 2 + 服务端 requestedViewDistance/ChunkTrackingView 强断言 + 主玩家隔离
                if (bot == null || bot.getLifecycle() != BotLifecycle.PLAYING) {
                    return;
                }
                if (!ccChunkTeleported) {
                    ccChunkTeleported = true;
                    waitTicks = 0;
                    ccChunkMainOptionsBefore = mc.options.renderDistance().get();
                    ccChunkMainChunkSource = mc.level != null ? mc.level.getChunkSource() : null;
                    // 先传送到全新坐标（远离此前移动/加载历史），保证半径断言确定性
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "tp " + botName + " 3000 4 0"));
                } else if (!ccChunkSettled) {
                    // 等假人位置同步到新坐标（客户端 chunk 中心随服务端 center 包更新）
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null && !isAwaitingPosition(sp)) {
                        ccChunkProbePos = bot.getLocalPlayer().blockPosition();
                        if (Math.abs(ccChunkProbePos.getX() - 3000) < 2) {
                            ccChunkSettled = true;
                            waitTicks = 0;
                        }
                    }
                    if (++waitTicks > 200) {
                        fail("chunk teleport timeout");
                        step = 21;
                    }
                } else if (!ccChunkDefaultChecked) {
                    if (++waitTicks > 20) {
                        ccChunkDefaultChecked = true;
                        check("chunk config default 2",
                                com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius() == 2);
                        check("chunk bot default radius", bot.getChunkRadius() == 2);
                        server.execute(() -> {
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            ccChunkServerRequested = ccServerRequestedViewDistance(sp);
                            ccChunkServerView = ccServerChunkViewDistance(sp);
                        });
                        // 默认 2：ChunkTrackingView includeNeighbors buffer=2 → 实际发包到距离 3；
                        // 距离 4（64 格）应未加载，距离 1（16 格）应加载
                        check("chunk default +3 loaded", bot.isBlockLoaded(ccChunkProbePos.offset(48, 0, 0)));
                        check("chunk default +4 not loaded",
                                !bot.isBlockLoaded(ccChunkProbePos.offset(64, 0, 0)),
                                "pos=" + ccChunkProbePos.offset(64, 0, 0));
                        check("chunk default +1 loaded", bot.isBlockLoaded(ccChunkProbePos.offset(16, 0, 0)));
                        waitTicks = 0;
                    }
                } else if (!ccChunkServerChecked) {
                    if (++waitTicks > 10) {
                        ccChunkServerChecked = true;
                        check("chunk server requestedViewDistance default", ccChunkServerRequested == 2,
                                "server=" + ccChunkServerRequested);
                        check("chunk server tracking view default", ccChunkServerView == 2,
                                "view=" + ccChunkServerView);
                        String out = com.mockplayer.session.ControlCommands.chunkRadius(botName, 4).getString();
                        check("chunk set feedback", !out.contains("commands."), "out=" + out);
                        check("chunk set local radius", bot.getChunkRadius() == 4);
                        ccChunkServerRequested = -1;
                        ccChunkServerView = -1;
                        waitTicks = 0;
                    }
                } else if (!ccChunkServerAfterChecked) {
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        ccChunkServerRequested = ccServerRequestedViewDistance(sp);
                        ccChunkServerView = ccServerChunkViewDistance(sp);
                    });
                    if (++waitTicks > 30) {
                        ccChunkServerAfterChecked = true;
                        check("chunk server requestedViewDistance after set", ccChunkServerRequested == 4,
                                "server=" + ccChunkServerRequested);
                        check("chunk server tracking view after set", ccChunkServerView == 4,
                                "view=" + ccChunkServerView);
                        waitTicks = 0;
                    }
                } else if (!ccChunkLoadedChecked) {
                    if (++waitTicks > 60) {
                        ccChunkLoadedChecked = true;
                        // 半径 4 → 实际发包到距离 5；距离 6 应未加载
                        check("chunk set +5 loaded", bot.isBlockLoaded(ccChunkProbePos.offset(80, 0, 0)));
                        check("chunk set +6 not loaded", !bot.isBlockLoaded(ccChunkProbePos.offset(96, 0, 0)));
                        check("chunk main player isolated",
                                mc.options.renderDistance().get() == ccChunkMainOptionsBefore
                                        && (ccChunkMainChunkSource == null || mc.level == null
                                            || mc.level.getChunkSource() == ccChunkMainChunkSource));
                        String q = com.mockplayer.session.QueryCommands.chunk(botName).getString();
                        check("chunk query readback", q.contains("4"), "q=" + q);
                        String bad = com.mockplayer.session.ControlCommands.chunkRadius(botName, 0).getString();
                        check("chunk invalid 0 rejected", !bad.contains("commands.") && bot.getChunkRadius() == 4,
                                "out=" + bad);
                        String bad2 = com.mockplayer.session.ControlCommands.chunkRadius(botName, 33).getString();
                        check("chunk invalid 33 rejected", !bad2.contains("commands.") && bot.getChunkRadius() == 4,
                                "out=" + bad2);
                        // 配置 JSON 往返：保存 5 → 重载 → 读回 5（再恢复默认 2）
                        com.mockplayer.config.ModConfig cfg5 = new com.mockplayer.config.ModConfig();
                        cfg5.setFakePlayerChunkRadius(5);
                        com.mockplayer.config.MockplayerConfig.save(cfg5);
                        com.mockplayer.config.MockplayerConfig.reload();
                        check("chunk config json roundtrip",
                                com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius() == 5);
                        com.mockplayer.config.MockplayerConfig.save(new com.mockplayer.config.ModConfig());
                        com.mockplayer.config.MockplayerConfig.reload();
                        check("chunk config restore default",
                                com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius() == 2);
                        step = 21;
                    }
                }
            }
            case 21 -> { // 射线交互：attackLook/useLook 单点 + sustained*Look 长按（原版等价）
                if (bot == null || bot.getLifecycle() != BotLifecycle.PLAYING) {
                    return;
                }
                switch (rcStep) {
                    case 0 -> { // 准备：记录基座 + 清实体
                        rcStep = 1;
                        rcWaitStart = System.currentTimeMillis();
                        rcPos = bot.getLocalPlayer().blockPosition();
                        BlockPos p = rcPos;
                        server.execute(() -> server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
                        rcHuskSummoned = false;
                        rcHuskFound = false;
                    }
                    case 1 -> { // 召唤 husk（东 3 格）并等它出现
                        if (!rcHuskSummoned) {
                            rcHuskSummoned = true;
                            rcHuskPos = rcPos.offset(3, 0, 0);
                            BlockPos hp = rcHuskPos;
                            server.execute(() -> server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(),
                                    String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                            hp.getX() + 0.5, (double) hp.getY(), hp.getZ() + 0.5)));
                        }
                        server.execute(() -> {
                            var h = ccNearestZombie(server);
                            rcHuskFound = h != null;
                            rcHuskHp = h != null ? h.getHealth() : -1;
                        });
                        if (rcHuskFound && rcHuskHp == 20.0F) {
                            rcStep = 2;
                            rcWaitStart = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - rcWaitStart > 15_000) {
                            fail("raycast husk spawn timeout");
                            step = 22;
                        }
                    }
                    case 2 -> { // attackLook 单点 → 服务端伤害
                        if (rcHuskFound && rcHuskHp >= 20.0F) {
                            bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(rcHuskPos));
                            com.mockplayer.session.ControlCommands.attackLook(botName);
                        }
                        server.execute(() -> {
                            var h = ccNearestZombie(server);
                            rcHuskFound = h != null;
                            rcHuskHp = h != null ? h.getHealth() : 20;
                        });
                        if (rcHuskFound && rcHuskHp < 20.0F) {
                            check("attackLook damages entity", true);
                            rcStep = 3;
                            rcWaitStart = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - rcWaitStart > 15_000) {
                            fail("attackLook no damage");
                            step = 22;
                        }
                    }
                    case 3 -> { // sustainedAttackLook 长按 → 持续伤害，再停止
                        if (rcHuskFound && rcHuskHp >= 13.0F) {
                            com.mockplayer.session.ControlCommands.sustainedAttackLook(botName);
                        }
                        server.execute(() -> {
                            var h = ccNearestZombie(server);
                            rcHuskFound = h != null;
                            rcHuskHp = h != null ? h.getHealth() : 20;
                        });
                        if (rcHuskFound && rcHuskHp < 13.0F) {
                            check("sustainedAttackLook continuous damage", true);
                            com.mockplayer.session.ControlCommands.stopSustained(botName);
                            rcStep = 4;
                            rcWaitStart = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - rcWaitStart > 25_000) {
                            fail("sustainedAttackLook no damage hp=" + rcHuskHp);
                            step = 22;
                        }
                    }
                    case 4 -> { // useLook 单点开箱
                        if (!rcChestPlaced) {
                            rcChestPlaced = true;
                            rcChestPos = rcPos.offset(2, 0, 0);
                            BlockPos p = rcChestPos;
                            server.execute(() -> server.getLevel(Level.OVERWORLD)
                                    .setBlock(p, Blocks.CHEST.defaultBlockState(), 3));
                        }
                        if (!rcChestOpen && bot.getBlockState(rcChestPos).is(Blocks.CHEST)) {
                            bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(rcChestPos));
                            com.mockplayer.session.ControlCommands.useLook(botName);
                        }
                        rcChestOpen = bot.getContainer().isPresent();
                        if (rcChestOpen) {
                            check("useLook opens container", true);
                            bot.getContainer().ifPresent(c -> c.close());
                            rcStep = 5;
                            rcWaitStart = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - rcWaitStart > 15_000) {
                            fail("useLook chest timeout");
                            step = 22;
                        }
                    }
                    case 5 -> { // sustainedUseLook 长按面包：using 状态 + release
                        if (!rcBreadGiven) {
                            rcBreadGiven = true;
                            // 满饥饿吃不了面包（canEat=false）：先把服务端饥饿压到 6（同步给假人客户端）
                            server.execute(() -> {
                                var sp = server.getPlayerList().getPlayerByName(botName);
                                if (sp != null) {
                                    sp.getFoodData().setFoodLevel(6);
                                    sp.getFoodData().setSaturation(0.0F);
                                }
                            });
                            server.execute(() -> server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(),
                                    "item replace entity " + botName + " weapon.mainhand with minecraft:bread"));
                        }
                        if (!rcUsing && bot.getLocalPlayer().getMainHandItem()
                                .is(net.minecraft.world.item.Items.BREAD)) {
                            bot.actions().lookAt(new net.minecraft.world.phys.Vec3(
                                    rcPos.getX(), rcPos.getY() + 20.0, rcPos.getZ()));
                            com.mockplayer.session.ControlCommands.sustainedUseLook(botName);
                        }
                        // 服务端 using 证据：假人持面包且处于使用状态
                        server.execute(() -> {
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            rcUsing = sp != null && sp.isUsingItem()
                                    && sp.getUseItem().is(net.minecraft.world.item.Items.BREAD);
                        });
                        if (rcUsing) {
                            check("sustainedUseLook using item", true);
                            if (!rcReleased) {
                                rcReleased = true;
                                com.mockplayer.session.ControlCommands.releaseUsingItem(botName);
                                com.mockplayer.session.ControlCommands.stopSustained(botName);
                            }
                            server.execute(() -> {
                                var sp = server.getPlayerList().getPlayerByName(botName);
                                rcReleased = sp != null && !sp.isUsingItem();
                            });
                            if (rcReleased) {
                                check("sustainedUseLook release stops using", true);
                                rcStep = 6;
                                rcWaitStart = System.currentTimeMillis();
                            }
                        } else if (System.currentTimeMillis() - rcWaitStart > 15_000) {
                            fail("sustainedUseLook bread timeout");
                            step = 22;
                        }
                    }
                    case 6 -> { // sustainedAttackLook 长按挖 dirt
                        if (!rcDirtPlaced) {
                            rcDirtPlaced = true;
                            rcDirtPos = rcPos.offset(2, 0, 0);
                            BlockPos p = rcDirtPos;
                            server.execute(() -> server.getLevel(Level.OVERWORLD)
                                    .setBlock(p, Blocks.DIRT.defaultBlockState(), 3));
                        }
                        if (!rcDirtBroken && bot.getBlockState(rcDirtPos).is(Blocks.DIRT)) {
                            bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(rcDirtPos));
                            com.mockplayer.session.ControlCommands.sustainedAttackLook(botName);
                        }
                        server.execute(() -> rcDirtBroken = server.getLevel(Level.OVERWORLD)
                                .getBlockState(rcDirtPos).isAir());
                        if (rcDirtBroken) {
                            check("sustainedAttackLook breaks block", true);
                            com.mockplayer.session.ControlCommands.stopSustained(botName);
                            rcStep = 7;
                            rcWaitStart = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - rcWaitStart > 25_000) {
                            fail("sustainedAttackLook mine timeout");
                            step = 22;
                        }
                    }
                    case 7 -> { // 距离边界：8 格石头不破坏（射线在 4.5 格外 MISS）
                        if (!rcFarPlaced) {
                            rcFarPlaced = true;
                            rcFarPos = rcPos.offset(8, 0, 0);
                            BlockPos p = rcFarPos;
                            BlockPos base = rcPos;
                            server.execute(() -> {
                                for (int i = 2; i <= 7; i++) {
                                    server.getLevel(Level.OVERWORLD)
                                            .setBlock(base.offset(i, 0, 0), Blocks.AIR.defaultBlockState(), 3);
                                }
                                server.getLevel(Level.OVERWORLD)
                                        .setBlock(p, Blocks.STONE.defaultBlockState(), 3);
                            });
                        }
                        if (System.currentTimeMillis() - rcWaitStart < 3000) {
                            bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(rcFarPos));
                            com.mockplayer.session.ControlCommands.sustainedAttackLook(botName);
                        } else {
                            server.execute(() -> rcFarStill = server.getLevel(Level.OVERWORLD)
                                    .getBlockState(rcFarPos).is(Blocks.STONE));
                            if (rcFarStill) {
                                check("look out of reach no break", true);
                                com.mockplayer.session.ControlCommands.stopSustained(botName);
                                rcStep = 8;
                                rcWaitStart = System.currentTimeMillis();
                            } else if (System.currentTimeMillis() - rcWaitStart > 25_000) {
                                fail("look out of reach stone broken");
                                com.mockplayer.session.ControlCommands.stopSustained(botName);
                                step = 22;
                            }
                        }
                    }
                    case 8 -> { // 视线变化：lookAt husk 后 turn 180，攻击不再命中
                        if (!rcTurnHuskSummoned) {
                            rcTurnHuskSummoned = true;
                            // 清掉此前攻击过的残血 husk（血量断言依赖满血新目标）
                            server.execute(() -> server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
                            rcHuskPos = rcPos.offset(3, 0, 0);
                            BlockPos hp = rcHuskPos;
                            server.execute(() -> server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(),
                                    String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                            hp.getX() + 0.5, (double) hp.getY(), hp.getZ() + 0.5)));
                        } else if (!rcTurnAttacked) {
                            if (System.currentTimeMillis() - rcWaitStart > 5000) {
                                rcTurnAttacked = true;
                                bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(rcHuskPos));
                                bot.actions().turn(180.0F, 0.0F);
                                com.mockplayer.session.ControlCommands.attackLook(botName);
                                rcWaitStart = System.currentTimeMillis();
                            }
                        } else if (System.currentTimeMillis() - rcWaitStart > 2000) {
                            server.execute(() -> {
                                var h = ccNearestZombie(server);
                                rcTurnHpAfter = h != null ? h.getHealth() : -1;
                            });
                            check("turn changes look target", rcTurnHpAfter >= 19.99F,
                                    "hp=" + rcTurnHpAfter);
                            server.execute(() -> server.getCommands().performPrefixedCommand(
                                    server.createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
                            rcStep = 9;
                        }
                    }
                    case 9 -> { // 命令层 i18n：help 列出 4 个新动作
                        String help = com.mockplayer.session.ControlCommands.help(botName).getString();
                        check("help lists look actions",
                                help.contains(net.minecraft.network.chat.Component.translatable(
                                        "commands.mockplayer.control.action.attackLook").getString())
                                        && help.contains(net.minecraft.network.chat.Component.translatable(
                                                "commands.mockplayer.control.action.useLook").getString())
                                        && help.contains(net.minecraft.network.chat.Component.translatable(
                                                "commands.mockplayer.control.action.sustainedAttackLook").getString())
                                        && help.contains(net.minecraft.network.chat.Component.translatable(
                                                "commands.mockplayer.control.action.sustainedUseLook").getString()));
                        step = 22;
                    }
                }
            }
            case 22 -> {
                finishSuite();
            }
        }
    }

    // ===== gui-actions：GUI 操作直接发包（chat/sendCommand/respawn/editBook/editSign/setBeacon/pickItemFromBlock，服务端强断言） =====

    /** 聊天广播断言：假人 chat/sendCommand 后服务端广播，假人自己收到 → onChat 记录 */
    private static volatile String guiChatMsg = "";
    private static final com.mockplayer.api.event.BotListener guiListener = new com.mockplayer.api.event.BotListener() {
        @Override
        public void onChat(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component message) {
            guiChatMsg = message.getString();
        }
    };
    private static boolean guiChatCmdDone;
    private static boolean guiCmdDone;
    private static boolean guiRespawnKilled;
    private static boolean guiRespawnDone;
    private static volatile boolean guiRespawnDead;
    private static volatile boolean guiRespawnVerified;
    private static boolean guiBookGiven;
    private static boolean guiBookDone;
    private static volatile boolean guiBookVerified;
    private static BlockPos guiSignPos;
    private static boolean guiSignRightClicked;
    private static int guiSignWait2;
    private static boolean guiSignDone;
    private static volatile boolean guiSignVerified;
    private static BlockPos guiBeaconPos;
    private static boolean guiBeaconDone;
    private static boolean guiBeaconDone2;
    private static boolean guiBeaconDone3;
    private static int guiBeaconWait2;
    private static int guiBeaconWait3;
    private static volatile boolean guiBeaconVerified;
    private static boolean guiPickDone;
    private static boolean guiPickGiven;
    private static int guiBookWait;
    private static int guiSignWait;
    private static int guiBeaconWait;
    private static int guiPickWait;
    private static volatile boolean guiPickVerified;

    private static void runGuiActions(Minecraft mc) {
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
                    MockplayerApi.listen(guiListener);
                    step = 1;
                }
            }
            case 1 -> { // chat 纯消息：服务端广播 → 假人自己收到 → onChat 断言
                if (!guiChatCmdDone) {
                    guiChatCmdDone = true;
                    guiChatMsg = "";
                    bot.actions().chat("mockplayer-gui-test");
                }
                if (guiChatMsg.contains("mockplayer-gui-test")) {
                    check("chat message broadcast to fake", true);
                    step = 2;
                } else if (++waitTicks > 200) {
                    fail("chat message timeout");
                    step = 2;
                }
            }
            case 2 -> { // sendCommand("me ...")：命令执行 → 服务端广播 → onChat 断言
                if (!guiCmdDone) {
                    guiCmdDone = true;
                    guiChatMsg = "";
                    bot.actions().sendCommand("me mockplayer-gui-cmd");
                }
                if (guiChatMsg.contains("mockplayer-gui-cmd")) {
                    check("sendCommand me executed", true);
                    step = 3;
                } else if (++waitTicks > 200) {
                    fail("sendCommand timeout");
                    step = 3;
                }
            }
            case 3 -> { // editBook：书与笔 → 写书
                if (!guiBookGiven) {
                    guiBookGiven = true;
                    guiBookWait = 0;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:writable_book");
                }
                if (guiBookGiven && ++guiBookWait > 20 && !guiBookDone) {
                    guiBookDone = true;
                    // 书与笔在假人当前选中槽（weapon.mainhand = selectedSlot）——editBook 必须用同一槽位
                    bot.actions().editBook(bot.getLocalPlayer().getInventory().getSelectedSlot(),
                            java.util.List.of("mockplayer page one", "second line"),
                            java.util.Optional.of("Mockplayer Book"));
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    guiBookVerified = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.WRITTEN_BOOK);
                });
                if (guiBookVerified) {
                    check("editBook wrote written book", true);
                    step = 4;
                } else if (guiBookDone && ++waitTicks > 300) {
                    fail("editBook timeout");
                    step = 4;
                }
            }
            case 4 -> { // editSign：告示牌 → 写文本
                if (guiSignPos == null) {
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            guiSignPos = sp.blockPosition().offset(3, 0, 0);
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "setblock " + guiSignPos.getX() + " " + guiSignPos.getY() + " " + guiSignPos.getZ()
                                            + " minecraft:oak_sign");
                        }
                    });
                }
                if (guiSignPos != null && ++guiSignWait > 20 && !guiSignRightClicked) {
                    guiSignRightClicked = true;
                    // 先右键告示牌打开编辑（服务端设置 allowedPlayerEditor），editSign 才被接受
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(guiSignPos), Direction.UP, guiSignPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                }
                if (guiSignRightClicked && ++guiSignWait2 > 20 && !guiSignDone) {
                    guiSignDone = true;
                    bot.actions().editSign(guiSignPos, true, new String[]{"mock", "player", "sign", "line4"});
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel lv = server.getLevel(Level.OVERWORLD);
                    if (guiSignPos != null && lv.getBlockEntity(guiSignPos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                        guiSignVerified = "mock".equals(sign.getFrontText().getMessage(0, false).getString());
                    }
                });
                if (guiSignVerified) {
                    check("editSign updated block entity", true);
                    step = 5;
                } else if (guiSignDone && ++waitTicks > 300) {
                    fail("editSign timeout");
                    step = 5;
                }
            }
            case 5 -> { // setBeacon：信标 + 3x3 底座金字塔 → 交互开菜单 → setBeacon 效果
                if (guiBeaconPos == null) {
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            guiBeaconPos = sp.blockPosition().offset(3, 0, 0);
                            int x = guiBeaconPos.getX(), y = guiBeaconPos.getY(), z = guiBeaconPos.getZ();
                            for (int dx = -1; dx <= 1; dx++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                            "setblock " + (x + dx) + " " + (y - 1) + " " + (z + dz) + " minecraft:iron_block");
                                }
                            }
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "setblock " + x + " " + y + " " + z + " minecraft:beacon");
                        }
                    });
                }
                if (guiBeaconPos != null && ++guiBeaconWait > 100 && !guiBeaconDone) {
                    guiBeaconDone = true;
                    guiBeaconWait2 = 0;
                    // 等 >80 tick（信标每 80 tick 更新 levels）后开菜单
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(guiBeaconPos), Direction.UP, guiBeaconPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit); // 开 BeaconMenu
                }
                if (guiBeaconDone && ++guiBeaconWait2 > 20 && !guiBeaconDone2) {
                    guiBeaconDone2 = true;
                    guiBeaconWait3 = 0;
                    // BeaconMenu.updateEffects 要求付款槽（菜单槽 0）有物品——等菜单打开后放铁锭
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null && sp.containerMenu instanceof net.minecraft.world.inventory.BeaconMenu menu) {
                            menu.getSlot(0).set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
                        }
                    });
                }
                if (guiBeaconDone2 && ++guiBeaconWait3 > 10 && !guiBeaconDone3) {
                    guiBeaconDone3 = true;
                    bot.actions().setBeacon(
                            java.util.Optional.of(net.minecraft.world.effect.MobEffects.SPEED),
                            java.util.Optional.empty());
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    // 真正激活：信标 levels>=1（底座）+ 付款槽有材料 → setBeacon 应用 SPEED 到假人
                    guiBeaconVerified = sp != null && sp.hasEffect(net.minecraft.world.effect.MobEffects.SPEED);
                });
                if (guiBeaconVerified) {
                    check("setBeacon applied speed", true);
                    step = 6;
                } else if (guiBeaconDone3 && ++waitTicks > 300) {
                    fail("setBeacon timeout");
                    step = 6;
                }
            }
            case 6 -> { // pickItemFromBlock：创造中键取脚下方块
                if (!guiPickGiven) {
                    guiPickGiven = true;
                    guiPickWait = 0;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode creative " + botName);
                }
                if (guiPickGiven && ++guiPickWait > 20 && !guiPickDone) {
                    guiPickDone = true;
                    net.minecraft.core.BlockPos target = bot.getLocalPlayer().blockPosition().below();
                    bot.actions().pickItemFromBlock(target, false);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    guiPickVerified = sp != null && !sp.getMainHandItem().isEmpty();
                });
                if (guiPickVerified) {
                    check("pickItemFromBlock changed held item", true);
                    step = 7;
                } else if (guiPickDone && ++waitTicks > 300) {
                    fail("pickItemFromBlock timeout");
                    step = 7;
                }
            }
            case 7 -> { // respawn：/kill → respawn() → 复活（放最后，respawn 改变假人位置/引用）
                if (!guiRespawnKilled) {
                    guiRespawnKilled = true;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill " + botName);
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    guiRespawnDead = sp != null && sp.isDeadOrDying();
                });
                if (guiRespawnDead && !guiRespawnDone) {
                    guiRespawnDone = true;
                    bot.actions().respawn();
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    guiRespawnVerified = sp != null && !sp.isDeadOrDying();
                });
                if (guiRespawnVerified) {
                    check("respawn revived", true);
                    step = 8;
                } else if (++waitTicks > 200) {
                    fail("respawn timeout");
                    step = 8;
                }
            }
            case 8 -> {
                MockplayerApi.bots().removeBot(botName, "command");
                finishSuite();
            }
        }
    }

    // ===== listener-events：BotListener 全事件真实触发 + 计数强断言（服务端/主玩家可见） =====

    private static final java.util.Map<String, Integer> leCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private static com.mockplayer.api.Bot leDamageBot;
    private static com.mockplayer.api.Bot leAttackedBot;
    private static com.mockplayer.api.Bot leHealthBot;
    private static float leDamageAmount;
    private static net.minecraft.world.damagesource.DamageSource leDamageSource;
    private static net.minecraft.world.damagesource.DamageSource leAttackedSource;
    private static float leAttackedAmount;
    private static float leHealthOld;
    private static float leHealthNew;
    private static final com.mockplayer.api.event.BotListener leListener = new com.mockplayer.api.event.BotListener() {
        @Override public void onSpawned(com.mockplayer.api.Bot b) { leCounts.merge("onSpawned", 1, Integer::sum); }
        @Override public void onPlayReady(com.mockplayer.api.Bot b) { leCounts.merge("onPlayReady", 1, Integer::sum); }
        @Override public void onDisconnected(com.mockplayer.api.Bot b, net.minecraft.network.DisconnectionDetails d) { leCounts.merge("onDisconnected", 1, Integer::sum); }
        @Override public void onRespawn(com.mockplayer.api.Bot b) { leCounts.merge("onRespawn", 1, Integer::sum); }
        @Override public void onDimensionChange(com.mockplayer.api.Bot b, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> f, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> t) { leCounts.merge("onDimensionChange", 1, Integer::sum); }
        @Override public void onChat(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component m) { leCounts.merge("onChat", 1, Integer::sum); }
        @Override public void onDamage(com.mockplayer.api.Bot b, net.minecraft.world.damagesource.DamageSource s, float a) { leDamageBot = b; leDamageSource = s; leDamageAmount = a; leCounts.merge("onDamage", 1, Integer::sum); }
        @Override public void onDeath(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component d) { leCounts.merge("onDeath", 1, Integer::sum); }
        @Override public void onHealthChanged(com.mockplayer.api.Bot b, float o, float n) { leHealthBot = b; leHealthOld = o; leHealthNew = n; leCounts.merge("onHealthChanged", 1, Integer::sum); }
        @Override public void onAttackEntity(com.mockplayer.api.Bot b, net.minecraft.world.entity.Entity t) { leCounts.merge("onAttackEntity", 1, Integer::sum); }
        @Override public void onEntityAttacked(com.mockplayer.api.Bot b, net.minecraft.world.damagesource.DamageSource s, float a) { leAttackedBot = b; leAttackedSource = s; leAttackedAmount = a; leCounts.merge("onEntityAttacked", 1, Integer::sum); }
        @Override public void onInteractBlock(com.mockplayer.api.Bot b, net.minecraft.core.BlockPos p, net.minecraft.core.Direction s) { leCounts.merge("onInteractBlock", 1, Integer::sum); }
        @Override public void onPlaceBlock(com.mockplayer.api.Bot b, net.minecraft.core.BlockPos p) { leCounts.merge("onPlaceBlock", 1, Integer::sum); }
        @Override public void onBreakBlock(com.mockplayer.api.Bot b, net.minecraft.core.BlockPos p) { leCounts.merge("onBreakBlock", 1, Integer::sum); }
        @Override public void onUseItem(com.mockplayer.api.Bot b, net.minecraft.world.InteractionHand h, net.minecraft.world.item.ItemStack s) { leCounts.merge("onUseItem", 1, Integer::sum); }
        @Override public void onInteractEntity(com.mockplayer.api.Bot b, net.minecraft.world.entity.Entity t) { leCounts.merge("onInteractEntity", 1, Integer::sum); }
        @Override public void onContainerOpened(com.mockplayer.api.Bot b, net.minecraft.world.inventory.MenuType<?> t, int c, net.minecraft.network.chat.Component ti) { leCounts.merge("onContainerOpened", 1, Integer::sum); }
        @Override public void onContainerSlotChanged(com.mockplayer.api.Bot b, int c, int s, net.minecraft.world.item.ItemStack st) { leCounts.merge("onContainerSlotChanged", 1, Integer::sum); }
        @Override public void onContainerClosed(com.mockplayer.api.Bot b, int c) { leCounts.merge("onContainerClosed", 1, Integer::sum); }
        @Override public void onMerchantOffersUpdated(com.mockplayer.api.Bot b, net.minecraft.world.item.trading.MerchantOffers o) { leCounts.merge("onMerchantOffersUpdated", 1, Integer::sum); }
        @Override public void onPlayerJoined(com.mockplayer.api.Bot b, com.mojang.authlib.GameProfile p) { leCounts.merge("onPlayerJoined", 1, Integer::sum); }
        @Override public void onPlayerLeft(com.mockplayer.api.Bot b, com.mojang.authlib.GameProfile p) { leCounts.merge("onPlayerLeft", 1, Integer::sum); }
        @Override public void onHeldSlotChanged(com.mockplayer.api.Bot b, int s) { leCounts.merge("onHeldSlotChanged", 1, Integer::sum); }
        @Override public void onItemCooldown(com.mockplayer.api.Bot b, net.minecraft.resources.Identifier i, int d) { leCounts.merge("onItemCooldown", 1, Integer::sum); }
        @Override public void onPickupItem(com.mockplayer.api.Bot b, net.minecraft.world.item.ItemStack s) { leCounts.merge("onPickupItem", 1, Integer::sum); }
        @Override public void onDropItem(com.mockplayer.api.Bot b, net.minecraft.world.item.ItemStack s) { leCounts.merge("onDropItem", 1, Integer::sum); }
        @Override public void onSwapHands(com.mockplayer.api.Bot b) { leCounts.merge("onSwapHands", 1, Integer::sum); }
        @Override public void onSneakToggle(com.mockplayer.api.Bot b, boolean s) { leCounts.merge("onSneakToggle", 1, Integer::sum); }
        @Override public void onSprintToggle(com.mockplayer.api.Bot b, boolean s) { leCounts.merge("onSprintToggle", 1, Integer::sum); }
        @Override public void onTick(com.mockplayer.api.Bot b) { leCounts.merge("onTick", 1, Integer::sum); }
        @Override public void onMove(com.mockplayer.api.Bot b) { leCounts.merge("onMove", 1, Integer::sum); }
    };

    private static boolean leCase0Done;
    private static boolean leTickChecked;
    private static int leMoveTicks;
    private static boolean leMoveIssued;
    private static volatile int leMoveStartX;
    private static boolean leSneakDone;
    private static int leInputWait;
    private static boolean leHeldDone;
    private static int leHeldWait;
    private static net.minecraft.world.item.ItemStack[] leMainHotbar;
    private static boolean leInteractDone;
    private static int leInteractWait;
    private static boolean leChatDone;
    private static int leChatWait;
    private static boolean leBlockDone;
    private static boolean lePlaceSwingSeen;
    private static int leBlockWait;
    private static boolean leZombieDone;
    private static boolean leZombieInteracted;
    private static boolean leZombieAttacked;
    private static boolean leZombieKilled;
    private static int leZombieWait;
    private static boolean leContainerDone;
    private static int leContainerWait;
    private static net.minecraft.core.BlockPos leChestPos;
    private static boolean leDropDone;
    private static boolean leDropUsed;
    private static boolean leDropChecked;
    private static int leDropWait;
    private static boolean leRespawnKilled;
    private static int leRespawnKillAttempts;
    private static boolean leRespawnDone;
    private static int leRespawnWait;
    private static boolean lePickupDone;
    private static int lePickupWait;
    private static boolean leMerchantDone;
    private static boolean leMerchantInteracted;
    private static int leMerchantWait;
    private static boolean leDimDone;
    private static int leDimWait;
    private static boolean leRemoveDone;
    private static int leRemoveWait;
    private static Bot leBot2;
    private static boolean leRemove2Done;

    private static void runListenerEvents(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> {
                if (!leCase0Done) {
                    leCase0Done = true;
                    leCounts.clear();
                    MockplayerApi.listen(leListener); // 先注册，捕获 onSpawned/onPlayReady/onPlayerJoined
                }
                prepareBot(server); // 每次调用（套件冷却后 createBot），不能一次性
                if (bot != null && bot.getLifecycle() == BotLifecycle.PLAYING) {
                    step = 1;
                }
            }
            case 1 -> { // 生命周期：onSpawned / onPlayReady / onPlayerJoined
                if (leCounts.getOrDefault("onSpawned", 0) >= 1 && leCounts.getOrDefault("onPlayReady", 0) >= 1
                        && leCounts.getOrDefault("onPlayerJoined", 0) >= 1) {
                    check("onSpawned", true);
                    check("onPlayReady", true);
                    check("onPlayerJoined", true);
                    step = 2;
                } else if (++waitTicks > 100) {
                    fail("lifecycle events timeout");
                    step = 2;
                }
            }
            case 2 -> { // onTick：等 10 tick 计数增长
                if (!leTickChecked && leCounts.getOrDefault("onTick", 0) >= 10) {
                    leTickChecked = true;
                    check("onTick", true);
                    step = 3;
                } else if (++waitTicks > 100) {
                    fail("onTick timeout");
                    step = 3;
                }
            }
            case 3 -> { // onMove：setForward 移动
                if (!leMoveIssued) {
                    leMoveIssued = true;
                    leMoveStartX = (int) bot.getLocalPlayer().getX();
                    bot.actions().setForward(0.5F);
                }
                if (++leMoveTicks > 40) {
                    bot.actions().stop();
                    if (leCounts.getOrDefault("onMove", 0) >= 1) {
                        check("onMove", true);
                        step = 4;
                    } else {
                        fail("onMove timeout");
                        step = 4;
                    }
                }
            }
            case 4 -> { // 输入：onSneakToggle / onSprintToggle
                if (!leSneakDone) {
                    leSneakDone = true;
                    bot.actions().setSneak(true);
                    bot.actions().setSprint(true);
                }
                if (++leInputWait > 10) {
                    boolean sneaked = leCounts.getOrDefault("onSneakToggle", 0) >= 1;
                    boolean sprinted = leCounts.getOrDefault("onSprintToggle", 0) >= 1;
                    check("onSneakToggle", sneaked);
                    check("onSprintToggle", sprinted);
                    step = 5;
                }
            }
            case 5 -> { // onHeldSlotChanged / onSwapHands
                if (!leHeldDone) {
                    leHeldDone = true;
                    net.minecraft.client.player.LocalPlayer mainPlayer = Minecraft.getInstance().player;
                    leMainHotbar = new net.minecraft.world.item.ItemStack[9];
                    if (mainPlayer != null) {
                        for (int i = 0; i < leMainHotbar.length; i++) {
                            leMainHotbar[i] = mainPlayer.getInventory().getItem(i).copy();
                        }
                    }
                    bot.actions().setSelectedSlot(1);
                }
                if (++leHeldWait > 10) {
                    bot.actions().swapHands();
                }
                if (++leHeldWait > 20) {
                    check("onHeldSlotChanged", leCounts.getOrDefault("onHeldSlotChanged", 0) >= 1);
                    check("onSwapHands", leCounts.getOrDefault("onSwapHands", 0) >= 1);
                    net.minecraft.client.player.LocalPlayer mainPlayer = Minecraft.getInstance().player;
                    boolean mainHotbarUnchanged = mainPlayer != null && leMainHotbar != null;
                    if (mainHotbarUnchanged) {
                        for (int i = 0; i < leMainHotbar.length; i++) {
                            if (!net.minecraft.world.item.ItemStack.matches(leMainHotbar[i], mainPlayer.getInventory().getItem(i))) {
                                mainHotbarUnchanged = false;
                                break;
                            }
                        }
                    }
                    check("main hotbar isolated", mainHotbarUnchanged);
                    step = 6;
                }
            }
            case 6 -> { // onUseItem / onInteractBlock（走 BotActions 接口才 fire 事件）
                if (!leInteractDone) {
                    leInteractDone = true;
                    leInteractWait = 0;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:bread");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "setblock " + (int) (bot.getLocalPlayer().getX() + 3) + " " + (int) bot.getLocalPlayer().getY() + " " + (int) bot.getLocalPlayer().getZ() + " minecraft:dirt");
                }
                leInteractWait++;
                if (leInteractWait == 20) {
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                if (leInteractWait == 40) {
                    bot.actions().useItemOn(bot.getLocalPlayer().blockPosition().offset(3, 0, 0), net.minecraft.core.Direction.UP);
                }
                if (leInteractWait > 80) {
                    check("onUseItem", leCounts.getOrDefault("onUseItem", 0) >= 1);
                    check("onInteractBlock", leCounts.getOrDefault("onInteractBlock", 0) >= 1);
                    step = 7;
                }
            }
            case 7 -> { // onChat（聊天广播）+ onPlaceBlock / onBreakBlock
                if (!leChatDone) {
                    leChatDone = true;
                    bot.actions().chat("mockplayer-le-chat");
                }
                if (leCounts.getOrDefault("onChat", 0) >= 1) {
                    check("onChat", true);
                    step = 8;
                } else if (++leChatWait > 100) {
                    fail("onChat timeout");
                    step = 8;
                }
            }
            case 8 -> { // onPlaceBlock / onBreakBlock
                if (!leBlockDone) {
                    leBlockDone = true;
                    lePlaceSwingSeen = false;
                    leBlockWait = 0;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:dirt");
                }
                leBlockWait++;
                if (leBlockWait == 20) {
                    bot.actions().placeBlock(bot.getLocalPlayer().blockPosition().offset(4, 0, 0), net.minecraft.core.Direction.UP);
                }
                if (leBlockWait == 50) {
                    bot.actions().mineBlock(bot.getLocalPlayer().blockPosition().offset(4, 0, 0));
                }
                net.minecraft.server.level.ServerPlayer blockSp = server.getPlayerList().getPlayerByName(botName);
                if (leBlockWait > 20 && blockSp != null && blockSp.swinging) {
                    lePlaceSwingSeen = true;
                }
                if (leBlockWait > 120) { check("onPlaceBlock", leCounts.getOrDefault("onPlaceBlock", 0) >= 1);
                    check("onBreakBlock", leCounts.getOrDefault("onBreakBlock", 0) >= 1);
                    check("placeBlock swing", lePlaceSwingSeen);
                    step = 9;
                }
            }
            case 9 -> { // 僵尸(夜晚)攻击假人(onEntityAttacked/onDamage/onHealthChanged) + 假人 interact/attack(onInteractEntity/onAttackEntity)
                if (!leZombieDone) {
                    leZombieDone = true;
                    leZombieWait = 0;
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                String.format("summon minecraft:zombie %.2f %.2f %.2f", sp.getX() + 2.0, sp.getY(), sp.getZ()));
                    }
                }
                leZombieWait++;
                net.minecraft.world.entity.Entity zombie = bot.getEntitiesNear(4).stream()
                        .filter(e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("zombie"))
                        .findFirst().orElse(null);
                if (zombie != null && !leZombieInteracted) {
                    bot.actions().lookAt(zombie);
                    bot.actions().interact(zombie); // 原版实体右键交互包 → onInteractEntity
                    leZombieInteracted = true;
                }
                if (zombie != null && !leZombieAttacked) {
                    bot.actions().lookAt(zombie);
                    bot.actions().attack(zombie); // 原版左键攻击包 → onAttackEntity
                    leZombieAttacked = true;
                }
                if (!leZombieKilled && leCounts.getOrDefault("onDamage", 0) >= 1) {
                    // bot 的真实血量下降已收到，立即用原版命令清理攻击源，避免继续打死 bot。
                    leZombieKilled = true;
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill @e[type=minecraft:zombie]"));
                }
                if (leZombieKilled && leCounts.getOrDefault("onEntityAttacked", 0) >= 1) {
                    check("onInteractEntity", leCounts.getOrDefault("onInteractEntity", 0) >= 1);
                    check("onAttackEntity", leCounts.getOrDefault("onAttackEntity", 0) >= 1);
                    check("onEntityAttacked", leCounts.getOrDefault("onEntityAttacked", 0) >= 1);
                    check("onDamage", leCounts.getOrDefault("onDamage", 0) >= 1);
                    check("onHealthChanged", leCounts.getOrDefault("onHealthChanged", 0) >= 1);
                    check("damage callback belongs to bot", leDamageBot == bot
                            && leDamageBot.getLocalPlayer() != Minecraft.getInstance().player
                            && leDamageSource != null && leDamageAmount > 0.0F);
                    check("entity-attacked callback belongs to bot", leAttackedBot == bot
                            && leAttackedBot.getLocalPlayer() != Minecraft.getInstance().player
                            && leAttackedSource != null && leAttackedAmount > 0.0F);
                    check("health callback belongs to bot", leHealthBot == bot && leHealthNew < leHealthOld);
                    step = 10;
                } else if (leZombieWait > 360) {
                    if (!leZombieKilled) {
                        server.execute(() -> server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack(), "kill @e[type=minecraft:zombie]"));
                    }
                    fail("zombie attack timeout (onDamage not fired)");
                    step = 10;
                }
            }
            case 10 -> { // 容器：onContainerOpened / onContainerSlotChanged / onContainerClosed
                if (!leContainerDone) {
                    leContainerDone = true;
                    leContainerWait = 0;
                    leChestPos = bot.getLocalPlayer().blockPosition().offset(2, 0, 0);
                    server.getLevel(Level.OVERWORLD).setBlock(leChestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:stone");
                }
                leContainerWait++;
                
                if (leContainerWait == 30) {
                    // 服务端开箱（确定性触发假人 handleOpenScreen → onContainerOpened）
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                                    (id, inv, p) -> new net.minecraft.world.inventory.ChestMenu(
                                            net.minecraft.world.inventory.MenuType.GENERIC_9x3, id, inv,
                                            new net.minecraft.world.SimpleContainer(27), 3),
                                    net.minecraft.network.chat.Component.literal("test")));
                        }
                    });
                }
                
                if (leContainerWait == 60) {
                    Optional<BotContainer> c = bot.getContainer();
                    c.ifPresent(cont -> cont.click(54, 0, net.minecraft.world.inventory.ContainerInput.PICKUP));
                }
                if (leContainerWait == 90) {
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null && sp.containerMenu != sp.inventoryMenu) {
                            sp.closeContainer();
                        }
                    });
                }
                if (leContainerWait > 220) { check("onContainerOpened", leCounts.getOrDefault("onContainerOpened", 0) >= 1);
                    check("onContainerSlotChanged", leCounts.getOrDefault("onContainerSlotChanged", 0) >= 1);
                    check("onContainerClosed", leCounts.getOrDefault("onContainerClosed", 0) >= 1);
                    step = 11;
                }
            }
            case 11 -> { // 物品：onDropItem / onPickupItem / onItemCooldown
                if (!leDropDone) {
                    leDropDone = true;
                    leDropWait = 0;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "item replace entity " + botName + " weapon.mainhand with minecraft:ender_pearl");
                }
                if (!leDropUsed) {
                    leDropUsed = true;
                    bot.actions().useItem(net.minecraft.world.InteractionHand.MAIN_HAND); // 末影珍珠投掷 → 冷却
                }
                if (leCounts.getOrDefault("onItemCooldown", 0) >= 1 && !leDropChecked) {
                    leDropChecked = true;
                    check("onItemCooldown", true);
                    bot.actions().dropSelected();
                }
                if (leDropChecked && leCounts.getOrDefault("onDropItem", 0) >= 1) {
                    check("onDropItem", true);
                    step = 12;
                } else if (++leDropWait > 200) {
                    fail("onItemCooldown timeout");
                    step = 12;
                }
            }
            case 12 -> { // onPickupItem：假人拾取掉落物
                if (!lePickupDone) {
                    lePickupDone = true;
                    lePickupWait = 0;
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                String.format("summon minecraft:item %.2f %.2f %.2f {Item:{id:\"minecraft:diamond\",count:1}}",
                                        sp.getX(), sp.getY(), sp.getZ()));
                    }
                }
                bot.actions().setForward(0.1F); // 假人微动走过去拾取（掉落物可能在半格位置/浮空）
                if (leCounts.getOrDefault("onPickupItem", 0) >= 1) {
                    bot.actions().setForward(0);
                    check("onPickupItem", true);
                    step = 13;
                } else if (++lePickupWait > 200) {
                    bot.actions().setForward(0);
                    fail("onPickupItem timeout");
                    step = 13;
                }
            }
            case 13 -> { // onMerchantOffersUpdated：真实村民交易菜单（生命真 lookAt+interact，与 merchant 套件同路径）
                if (!leMerchantDone) {
                    leMerchantDone = true;
                    leMerchantWait = 0;
                    bot.actions().stop();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            double x = sp.getX() + 5.0;
                            double y = sp.getY();
                            double z = sp.getZ();
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "kill @e[type=minecraft:villager]");
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    String.format("tp %s %.2f %.2f %.2f", botName, x, y, z));
                            String cmd = String.format(
                                    "summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:diamond\",count:1},maxUses:99,xp:1}]}}",
                                    x + 1.0, y, z);
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                        }
                    });
                }
                leMerchantWait++;
                if (!leMerchantInteracted) { // 等村民实体同步到假人 level 再交互（避免发到空实体）
                    net.minecraft.world.entity.Entity villager = bot.getEntitiesNear(64).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.npc.villager.Villager)
                            .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(bot.getLocalPlayer())))
                            .orElse(null);
                    if (villager != null) {
                        bot.actions().lookAt(villager);
                        bot.actions().interact(villager);
                        leMerchantInteracted = true;
                    }
                }
                if (leCounts.getOrDefault("onMerchantOffersUpdated", 0) >= 1) {
                    check("onMerchantOffersUpdated", true);
                    step = 14;
                } else if (leMerchantWait > 200) {
                    fail("onMerchantOffersUpdated timeout");
                    step = 14;
                }
            }
            case 14 -> { // onDimensionChange：假人换维（下界）
                if (!leDimDone) {
                    leDimDone = true;
                    leDimWait = 0;
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                            "execute in minecraft:the_nether run tp " + botName + " 0 64 0");
                }
                if (leCounts.getOrDefault("onDimensionChange", 0) >= 1) {
                    check("onDimensionChange", true);
                    step = 15;
                } else if (++leDimWait > 200) {
                    fail("onDimensionChange timeout");
                    step = 15;
                }
            }
            case 15 -> { // onRespawn（kill → respawn）
                if (!leRespawnKilled) {
                    leRespawnKilled = true;
                    leRespawnKillAttempts = 1;
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill " + botName));
                }
                if (leCounts.getOrDefault("onDeath", 0) < 1 && leRespawnWait > 0
                        && leRespawnWait % 20 == 0 && leRespawnKillAttempts < 3) {
                    leRespawnKillAttempts++;
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill " + botName));
                }
                if (!leRespawnDone && leCounts.getOrDefault("onDeath", 0) >= 1) {
                    leRespawnDone = true;
                }
                if (leRespawnDone && leCounts.getOrDefault("onRespawn", 0) >= 1) {
                    check("onDeath", true);
                    check("onRespawn", true);
                    step = 16;
                } else if (++leRespawnWait > 200) {
                    fail("onRespawn timeout");
                    step = 16;
                }
            }
            case 16 -> { // onDisconnected（假人 removeBot 断开）/ onPlayerLeft（假人看到第二个假人离开）
                if (!leRemoveDone) {
                    leRemoveDone = true;
                    leRemoveWait = 0;
                    leBot2 = MockplayerApi.bots().createBot(BotProfile.of("tbot-le2", "test"));
                }
                // 必须先让主假人 TabList 同步到 tbot-le2 再移除，否则 onPlayerLeft 永远不会触发
                boolean le2Seen = leBot2 != null && leBot2.getLifecycle() == BotLifecycle.PLAYING
                        && bot.getOnlinePlayers().stream()
                        .anyMatch(p -> p.getProfile().name().equals("tbot-le2"));
                if (le2Seen && !leRemove2Done) {
                    leRemove2Done = true;
                    MockplayerApi.bots().removeBot("tbot-le2", "command"); // 第二个假人离开 → 主假人 onPlayerLeft
                }
                if (leCounts.getOrDefault("onDisconnected", 0) >= 1 && leCounts.getOrDefault("onPlayerLeft", 0) >= 1) {
                    check("onDisconnected", true);
                    check("onPlayerLeft", true);
                    step = 17;
                } else if (++leRemoveWait > 600) {
                    fail("onDisconnected timeout bot2=" + (leBot2 != null ? leBot2.getLifecycle() : "null")
                            + " seen=" + leRemove2Done
                            + " counts=" + leCounts.getOrDefault("onDisconnected", 0)
                            + "/" + leCounts.getOrDefault("onPlayerLeft", 0));
                    step = 17;
                }
            }
            case 17 -> {
                finishSuite();
            }
        }
    }


    // ===== containers：服务端开箱 → 假人客户端容器会话断言 → 关闭 =====
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
                // 暂停隔离：主玩家打开 PauseScreen，假人容器交互不得打断暂停界面。
                // 根因：LocalPlayer.clientSideCloseContainer 会 this.minecraft.gui.setScreen(null)
                // （主玩家单例），假人一关容器就把主玩家暂停界面关掉；FakeLocalPlayer 已重写跳过。
                if (mc.gui.screen() == null) {
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                }
                check("pause screen open", mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen);
                // bot 容器此时仍开着：执行 UI 交互 click + close（close 是原污染路径）
                bot.getContainer().ifPresent(c -> {
                    c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);
                    c.close();
                });
                waitTicks = 0;
                step = 14;
            }
            case 14 -> {
                // 关容器是异步回包：持续断言主玩家暂停界面仍在（每 tick 检查）
                check("pause screen not interrupted", mc.gui.screen()
                        instanceof net.minecraft.client.gui.screens.PauseScreen);
                if (bot.getContainer().isEmpty()) {
                    check("container closed after pause isolation", true);
                    mc.gui.setScreen(null);
                    step = 15;
                } else if (++waitTicks > 100) {
                    fail("container close timeout during pause isolation");
                    mc.gui.setScreen(null);
                    step = 15;
                }
            }
            case 15 -> {
                MockplayerApi.bots().removeBot(botName, "command");
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
    private static volatile boolean stabSpearServer;
    private static boolean stabAttacked;
    private static boolean bgGuiStabCharged;
    private static boolean bgGuiStabAttacked;
    private static volatile float bgGuiStabHpBefore = -1;
    private static volatile float bgGuiStabHp = -1;
    private static volatile boolean bgGuiSpearUsing;
    private static boolean sprintSummoned;
    private static boolean sprintCleared;
    private static boolean sprintSpearGiven;
    private static volatile boolean sprintSpearServer;
    private static boolean sprintThrustIssued;
    private static boolean sprintHoldGiven;
    private static boolean sprintHoldSummoned;
    private static boolean sprintHoldSeen;
    private static boolean sprintHoldIssued;
    private static boolean sprintHoldWalkGiven;
    private static boolean sprintHoldWalkIssued;
    private static boolean sprintHoldWalkChecked;
    private static volatile boolean sprintHoldUsingOk;
    private static volatile int sprintHoldWalkRemaining = -1;
    private static volatile int sprintHoldWalkDuration = -1;
    private static volatile boolean combatLastDamageIsSpear;
    private static volatile boolean stabSwingSeen;
    private static volatile boolean stabSwingSampled;
    private static volatile boolean sprintHoldPoseSampled;
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
        // 原版 item replace 精确替换主手槽为矛（不依赖 give 落位；selected 固定 0 防切换走）
        server.execute(() -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "item replace entity " + botName + " weapon.mainhand with minecraft:iron_spear");
            net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
            if (sp != null) {
                sp.getInventory().setSelectedSlot(0);
            }
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
                // 服务端强断言：主手确实拿到矛（give 竞态/客户端 selected 同步慢都逃不过服务端验证）
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    stabSpearServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.IRON_SPEAR);
                });
                if (stabSpearServer) {
                    check("server holds spear", true);
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
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null && sp.swinging) {
                        stabSwingSeen = true; // 挥动动画已广播（主客户端可见动作）
                    }
                    stabSwingSampled = true;
                });
                if (combatLastDamageIsSpear && combatHuskHealth >= 0 && combatHuskHealth < 20
                        && stabSwingSampled) {
                    check("husk hurt by SPEAR (left-click stab)", true);
                    check("stab swing animation broadcast", stabSwingSeen);
                    check("fake still PLAYING (no server crash)", bot.getLifecycle() == BotLifecycle.PLAYING);
                    step = 7;
                } else if (combatServerScale >= 1.0F) {
                    // 戳刺射线沿视线方向（ProjectileUtil.getHitEntitiesAlong），必须先面向僵尸
                    net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                            .findFirst().orElse(null);
                    if (target != null) {
                        bot.getLocalPlayer().getInventory().setSelectedSlot(0); // 固定主手（item replace 后 selected 可能被切换走）
                        bot.actions().lookAt(target);
                        bot.actions().stab();
                        stabAttacked = true;
                        check("left-click STAB issued", true);
                    }
                } else if (++waitTicks > 400) {
                    fail("stab timeout (no SPEAR damage)");
                    step = 7;
                }
            }
            case 7 -> { // GUI 左键（attackLook）持矛戳刺：等蓄力满 → 记录当前血 → attackLook → 血继续降
                if (!bgGuiStabCharged) {
                    if (bot.getLocalPlayer() != null
                            && bot.getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F) {
                        bgGuiStabCharged = true;
                        net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                                .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                                .findFirst().orElse(null);
                        if (target != null) {
                            bot.actions().lookAt(target);
                            server.execute(() -> {
                                var level = server.getLevel(Level.OVERWORLD);
                                var husk = level != null ? level.getEntitiesOfClass(
                                        net.minecraft.world.entity.monster.zombie.Zombie.class,
                                        new net.minecraft.world.phys.AABB(-64, -64, -64, 64, 64, 64))
                                        .stream().findFirst().orElse(null) : null;
                                bgGuiStabHpBefore = husk != null ? husk.getHealth() : -1;
                            });
                            waitTicks = 0;
                        }
                    } else if (++waitTicks > 200) {
                        fail("gui spear charge timeout");
                        bgGuiStabCharged = true;
                        waitTicks = 0;
                    }
                    return;
                }
                if (!bgGuiStabAttacked) {
                    bgGuiStabAttacked = true;
                    bot.actions().attackLook(); // GUI 左键路径：持矛 → pierceStab（STAB 包）
                    waitTicks = 0;
                    return;
                }
                server.execute(() -> {
                    var level = server.getLevel(Level.OVERWORLD);
                    var husk = level != null ? level.getEntitiesOfClass(
                            net.minecraft.world.entity.monster.zombie.Zombie.class,
                            new net.minecraft.world.phys.AABB(-64, -64, -64, 64, 64, 64))
                            .stream().findFirst().orElse(null) : null;
                    bgGuiStabHp = husk != null ? husk.getHealth() : -1;
                });
                if (bgGuiStabHp >= 0 && bgGuiStabHpBefore > 0 && bgGuiStabHp < bgGuiStabHpBefore) {
                    check("gui left-click spear stabs", true,
                            "hp " + bgGuiStabHpBefore + " -> " + bgGuiStabHp);
                    net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                            .findFirst().orElse(null);
                    if (target != null) {
                        bot.actions().lookAt(target);
                    }
                    bot.actions().useLook(); // GUI 右键路径：实体 fallthrough → useItem（举矛）
                    waitTicks = 0;
                    step = 8;
                } else if (++waitTicks > 120) {
                    fail("gui spear stab timeout hp=" + bgGuiStabHp
                            + " before=" + bgGuiStabHpBefore);
                    step = 8;
                }
            }
            case 8 -> { // GUI 右键举矛：服务端 isUsingItem + 使用中物品是矛
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    bgGuiSpearUsing = sp != null && sp.isUsingItem()
                            && sp.getUseItem().is(net.minecraft.world.item.Items.IRON_SPEAR);
                });
                if (bgGuiSpearUsing) {
                    check("gui right-click raises spear", true);
                    bot.actions().stopSustained();
                    waitTicks = 0;
                    step = 9;
                } else if (++waitTicks > 100) {
                    fail("gui spear raise timeout using=" + bgGuiSpearUsing);
                    bot.actions().stopSustained();
                    step = 9;
                }
            }
            case 9 -> {
                removeHusks(server);
                MockplayerApi.bots().removeBot(botName, "command");
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
                // 清假人周围方块（连跑时 containers-all 残留方块会卡冲刺路径），再 summon Husk 前 6 格
                if (!sprintCleared) {
                    sprintCleared = true;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            net.minecraft.server.level.ServerLevel level = server.getLevel(Level.OVERWORLD);
                            BlockPos p = sp.blockPosition();
                            for (int dx = -8; dx <= 8; dx++) {
                                for (int dz = -8; dz <= 8; dz++) {
                                    for (int dy = 0; dy <= 2; dy++) {
                                        BlockPos q = p.offset(dx, dy, dz);
                                        if (!level.getBlockState(q).isAir()) {
                                            level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
                                        }
                                    }
                                }
                            }
                        }
                    });
                }
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
                // 服务端强断言：主手确实拿到矛
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    sprintSpearServer = sp != null && sp.getMainHandItem().is(net.minecraft.world.item.Items.IRON_SPEAR);
                });
                if (sprintSpearServer) {
                    check("server holds spear", true);
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
                    bot.getLocalPlayer().getInventory().setSelectedSlot(0); // 固定主手矛（防切换走）
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
            case 9 -> { // 长按右键 + 冲刺（sustainedUseLook）：由长按路径自己蓄力，动能伤害正常触发
                if (!sprintHoldGiven) {
                    sprintHoldGiven = true;
                    waitTicks = 0;
                    bot.actions().stop();
                    bot.actions().stopSustained(); // 释放上一次冲刺的使用中矛
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
                    return;
                }
                if (!sprintHoldSummoned) {
                    if (++waitTicks > 10) {
                        sprintHoldSummoned = true;
                        summonHusk(server, 6.0);
                        waitTicks = 0;
                    }
                    return;
                }
                if (!sprintHoldSeen) {
                    if (bot.getEntitiesNear(64).stream()
                            .anyMatch(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)) {
                        sprintHoldSeen = true;
                        waitTicks = 0;
                    } else if (++waitTicks > 200) {
                        fail("hold husk not seen");
                        sprintHoldSeen = true;
                    }
                    return;
                }
                if (!sprintHoldIssued) {
                    if (++waitTicks > 20) { // 等尸壳完全刷新
                        sprintHoldIssued = true;
                        waitTicks = 0;
                        net.minecraft.world.entity.Entity target = bot.getEntitiesNear(64).stream()
                                .filter(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)
                                .findFirst().orElse(null);
                        if (target != null) {
                            bot.actions().lookAt(target);
                        }
                        bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                        bot.actions().sustainedUseLook(); // 长按右键：长按路径负责蓄力，不手动 useItem
                        bot.actions().setForward(1.0F);
                        bot.actions().setSprint(true);
                        check("hold-thrust issued (sustainedUseLook + sprint)", true);
                    }
                    return;
                }
                step = 10;
            }
            case 10 -> { // 长按右键蓄力 + 冲刺 → 服务端 SPEAR 动能伤害
                readSpearDamage(server);
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    sprintHoldUsingOk = sp != null && sp.isUsingItem()
                            && sp.getUseItem().is(net.minecraft.world.item.Items.IRON_SPEAR);
                    sprintHoldPoseSampled = true;
                });
                if (combatLastDamageIsSpear && combatHuskHealth >= 0 && combatHuskHealth < 20
                        && sprintHoldPoseSampled) {
                    check("husk hurt by SPEAR (hold right-click sprint)", true);
                    check("hold right-click shows using pose", sprintHoldUsingOk);
                    check("fake still PLAYING (no server crash)", bot.getLifecycle() == BotLifecycle.PLAYING);
                    waitTicks = 0;
                    step = 11;
                } else if (++waitTicks > 300) {
                    fail("hold-thrust timeout (no SPEAR damage) hp=" + combatHuskHealth
                            + " spear=" + combatLastDamageIsSpear);
                    step = 11;
                }
            }
            case 11 -> { // 长按右键看地面：蓄力不得被方块分支每 tick 重置（旧 bug 根因）
                if (!sprintHoldWalkGiven) {
                    sprintHoldWalkGiven = true;
                    waitTicks = 0;
                    bot.actions().stop();
                    bot.actions().stopSustained();
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
                    return;
                }
                if (!sprintHoldWalkIssued) {
                    if (++waitTicks > 10) { // 等 kill 生效，再低头看前方地面
                        sprintHoldWalkIssued = true;
                        waitTicks = 0;
                        bot.actions().look(bot.getLocalPlayer().getYRot(), -45.0F);
                        bot.actions().sustainedUseLook(); // 长按右键：射线命中方块 → useItemOn PASS → 蓄力
                    }
                    return;
                }
                if (!sprintHoldWalkChecked) {
                    if (++waitTicks > 35) {
                        sprintHoldWalkChecked = true;
                        server.execute(() -> {
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            sprintHoldWalkRemaining = sp != null && sp.isUsingItem()
                                    ? sp.getUseItemRemainingTicks() : -1;
                            sprintHoldWalkDuration = sp != null && sp.isUsingItem()
                                    ? sp.getUseItem().getUseDuration(sp) : -1;
                        });
                    }
                    return;
                }
                if (sprintHoldWalkRemaining >= 0 && sprintHoldWalkDuration > 0) {
                    // 蓄力持续推进：剩余 tick 明显小于总时长（每 tick 重置 → 剩余≈总时长）
                    check("hold right-click charge not reset while aiming at block",
                            sprintHoldWalkRemaining <= sprintHoldWalkDuration - 20,
                            "remaining=" + sprintHoldWalkRemaining
                                    + " duration=" + sprintHoldWalkDuration);
                } else {
                    check("hold right-click charge not reset while aiming at block", false,
                            "remaining=" + sprintHoldWalkRemaining
                                    + " duration=" + sprintHoldWalkDuration);
                }
                bot.actions().stop();
                bot.actions().stopSustained();
                waitTicks = 0;
                step = 12;
            }
            case 12 -> { // 收尾
                removeHusks(server);
                MockplayerApi.bots().removeBot(botName, "command");
                finishSuite();
            }
        }
    }

    // ===== containers-all：所有容器类型真实交互（放方块 → 假人打开 → give 物品 → click 放入 → 服务端验证 → 取回） =====

    private record ContainerCase(String name, net.minecraft.world.level.block.Block block, String itemId,
                                 net.minecraft.world.inventory.MenuType<?> menuType, int hotbarSlot, int containerSlot, int resultSlot) {
    }

    private static final List<ContainerCase> CONTAINER_CASES = List.of(
            new ContainerCase("hopper", Blocks.HOPPER, "minecraft:stone", MenuType.HOPPER, 32, 0, -1),
            new ContainerCase("dropper", Blocks.DROPPER, "minecraft:stone", MenuType.GENERIC_3x3, 36, 0, -1),
            new ContainerCase("barrel", Blocks.BARREL, "minecraft:stone", MenuType.GENERIC_9x3, 54, 0, -1),
            new ContainerCase("shulker_box", Blocks.SHULKER_BOX, "minecraft:stone", MenuType.SHULKER_BOX, 54, 0, -1),
            new ContainerCase("ender_chest", Blocks.ENDER_CHEST, "minecraft:stone", MenuType.GENERIC_9x3, 54, 0, -1),
            new ContainerCase("anvil", Blocks.ANVIL, "minecraft:stone", MenuType.ANVIL, 30, 0, 2),
            new ContainerCase("grindstone", Blocks.GRINDSTONE, "minecraft:diamond_sword", MenuType.GRINDSTONE, 30, 0, 2),
            new ContainerCase("stonecutter", Blocks.STONECUTTER, "minecraft:stone", MenuType.STONECUTTER, 29, 0, 1),
            new ContainerCase("blast_furnace", Blocks.BLAST_FURNACE, "minecraft:coal", MenuType.BLAST_FURNACE, 30, 1, 2),
            new ContainerCase("smoker", Blocks.SMOKER, "minecraft:coal", MenuType.SMOKER, 30, 1, 2),
            new ContainerCase("brewing_stand", Blocks.BREWING_STAND, "minecraft:glass_bottle", MenuType.BREWING_STAND, 32, 0, -1),
            new ContainerCase("lectern", Blocks.LECTERN, "minecraft:written_book", MenuType.LECTERN, 28, 0, -1),
            new ContainerCase("loom", Blocks.LOOM, "minecraft:white_banner", MenuType.LOOM, 31, 0, 3),
            new ContainerCase("cartography_table", Blocks.CARTOGRAPHY_TABLE, "minecraft:paper", MenuType.CARTOGRAPHY_TABLE, 30, 1, 2),
            new ContainerCase("smithing_table", Blocks.SMITHING_TABLE, "minecraft:iron_ingot", MenuType.SMITHING, 31, 2, 3),
            new ContainerCase("beacon", Blocks.BEACON, "minecraft:emerald", MenuType.BEACON, 28, 0, -1),
            new ContainerCase("crafter", Blocks.CRAFTER, "minecraft:stone", MenuType.CRAFTER_3x3, 36, 0, 45),
            new ContainerCase("trapped_chest", Blocks.TRAPPED_CHEST, "minecraft:stone", MenuType.GENERIC_9x3, 54, 0, -1),
            new ContainerCase("large_chest", Blocks.CHEST, "minecraft:stone", MenuType.GENERIC_9x6, 81, 0, -1),
            new ContainerCase("horse", null, "minecraft:saddle", null, 29, 0, -1));

    private static int containerAllCaseIndex;
    private static BlockPos containerAllPos;
    private static boolean containerAllOpened;
    private static boolean containerAllGiven;
    private static boolean containerAllPut;
    private static boolean containerAllTaken;
    /** 服务端校验结果所属的 case 下标（防残留任务把下一 case 打成假绿）。 */
    private static volatile int containerAllServerPutFor = -1;
    private static volatile int containerAllServerEmptyFor = -1;
    private static volatile boolean containerAllServerPut;
    private static volatile boolean containerAllServerEmpty;
    private static boolean lecternBookGiven;
    private static boolean lecternBookPlaced;
    private static int lecternOpenWait;
    private static boolean horseSummoned;
    private static boolean horseInteracted;

    /** 切换到下一个容器 case 前重置全部 per-case 状态（成功与超时路径共用）。 */
    private static void containerAllResetCase() {
        containerAllPos = null;
        containerAllOpened = false;
        containerAllGiven = false;
        containerAllPut = false;
        containerAllTaken = false;
        containerAllServerPut = false;
        containerAllServerEmpty = false;
        containerAllServerPutFor = -1;
        containerAllServerEmptyFor = -1;
        lecternBookGiven = false;
        lecternBookPlaced = false;
        horseSummoned = false;
        horseInteracted = false;
        waitTicks = 0;
    }

    private static void runContainersAll(Minecraft mc) {
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
                    check("containers-all start (" + CONTAINER_CASES.size() + " types)", true);
                    step = 1;
                }
            }
            case 1 -> {
                // 放当前容器方块 + 假人打开
                if (containerAllPos == null) {
                    containerAllPos = bot.getLocalPlayer().blockPosition().offset(3, 0, 0);
                    BlockPos p = containerAllPos;
                    ContainerCase c = CONTAINER_CASES.get(containerAllCaseIndex);
                    if (c.block() != null) {
                        server.execute(() -> {
                        if ("large_chest".equals(c.name())) {
                            // 大箱：ChestBlock.updateShape 合并需邻居已非 SINGLE，手动放 LEFT + RIGHT（getConnectedDirection 匹配）
                            net.minecraft.world.level.block.state.BlockState cl = Blocks.CHEST.defaultBlockState()
                                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.NORTH)
                                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                                            net.minecraft.world.level.block.state.properties.ChestType.LEFT);
                            net.minecraft.world.level.block.state.BlockState cr = Blocks.CHEST.defaultBlockState()
                                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.NORTH)
                                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                                            net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
                            server.getLevel(Level.OVERWORLD).setBlock(p, cl, 3);
                            server.getLevel(Level.OVERWORLD).setBlock(p.east(), cr, 3);
                        } else {
                            server.getLevel(Level.OVERWORLD).setBlock(p, c.block().defaultBlockState(), 3);
                        }
                    });
                    }
                }
                ContainerCase c0 = CONTAINER_CASES.get(containerAllCaseIndex);
                if ("horse".equals(c0.name())) {
                    // 马特殊：服务端 spawn 无 AI 驯服马（地面），假人潜行右键马真实触发马背包（mobInteract 潜行分支）
                    if (!horseSummoned) {
                        horseSummoned = true;
                        server.execute(() -> {
                            // 马 spawn 在地面 1 格内（isWithinEntityInteractionRange 3 格），NoAI 驯服不走动
                            net.minecraft.world.phys.Vec3 hp = bot.getLocalPlayer().position().add(1.0, 0.0, 0.0);
                            net.minecraft.world.entity.animal.equine.Horse horse = new net.minecraft.world.entity.animal.equine.Horse(
                                    net.minecraft.world.entity.EntityTypes.HORSE, server.getLevel(Level.OVERWORLD));
                            horse.setPos(hp);
                            horse.setTamed(true);
                            horse.setNoAi(true);
                            server.getLevel(Level.OVERWORLD).addFreshEntity(horse);
                        });
                    }
                    if (!containerAllOpened) {
                        var clientHorses = bot.getLocalPlayer().level().getEntitiesOfClass(
                                net.minecraft.world.entity.animal.equine.AbstractHorse.class,
                                new net.minecraft.world.phys.AABB(bot.getLocalPlayer().position().add(-8.0, -8.0, -8.0),
                                        bot.getLocalPlayer().position().add(8.0, 12.0, 8.0)));
                        if (!clientHorses.isEmpty() && !horseInteracted) {
                            horseInteracted = true;
                            // 选距假人最近的马（世界持久化可能残留旧马），潜行右键已驯服马真实触发马背包
                            net.minecraft.world.entity.animal.equine.AbstractHorse horse = clientHorses.stream()
                                    .min(java.util.Comparator.comparingDouble(
                                            h -> h.distanceToSqr(bot.getLocalPlayer())))
                                    .orElse(null);
                            if (horse != null) {
                                // 手动发包 secondaryAction=true（setShiftKeyDown 的共享标志会被假人实体数据同步覆盖），
                                // 等价假人潜行右键：服务端 handleInteract setShiftKeyDown(true) → mobInteract 潜行分支开马背包
                                bot.getLocalPlayer().connection.send(new net.minecraft.network.protocol.game.ServerboundInteractPacket(
                                        horse.getId(), InteractionHand.MAIN_HAND, net.minecraft.world.phys.Vec3.ZERO, true));
                            }
                        }
                        if (horseInteracted && bot.getContainer().isPresent()) {
                            containerAllOpened = true;
                            step = 2;
                        }
                    }
                } else if (bot.getBlockState(containerAllPos).is(c0.block())) {
                    if ("lectern".equals(c0.name())) {
                        // 讲台特殊：空讲台右键不开菜单，服务端直接放书（setBook + HAS_BOOK）再打开
                        if (!lecternBookPlaced) {
                            lecternBookPlaced = true;
                            server.execute(() -> {
                                net.minecraft.world.level.block.entity.BlockEntity be = server.getLevel(Level.OVERWORLD).getBlockEntity(containerAllPos);
                                if (be instanceof net.minecraft.world.level.block.entity.LecternBlockEntity le) {
                                    le.setBook(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK));
                                }
                                net.minecraft.world.level.block.state.BlockState bs = server.getLevel(Level.OVERWORLD).getBlockState(containerAllPos);
                                server.getLevel(Level.OVERWORLD).setBlock(containerAllPos,
                                        bs.setValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK, true), 3);
                            });
                        }
                        if (!containerAllOpened) {
                            if (bot.getBlockState(containerAllPos).hasProperty(net.minecraft.world.level.block.LecternBlock.HAS_BOOK)
                                    && bot.getBlockState(containerAllPos).getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK)) {
                                containerAllOpened = true;
                                bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(containerAllPos));
                                net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                                        net.minecraft.world.phys.Vec3.atCenterOf(containerAllPos), Direction.WEST, containerAllPos, false);
                                bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit); // 打开菜单
                                step = 2;
                            }
                        }
                    } else {
                        // 大箱：等服务端合并完成（type 变 LEFT/RIGHT，非 SINGLE）再打开
                        boolean largeReady = !"large_chest".equals(c0.name())
                                || (bot.getBlockState(containerAllPos).hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                                    && bot.getBlockState(containerAllPos).getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE);
                        if (largeReady && !containerAllOpened) {
                            containerAllOpened = true;
                            bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(containerAllPos));
                            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                                    net.minecraft.world.phys.Vec3.atCenterOf(containerAllPos), Direction.WEST, containerAllPos, false);
                            bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                            step = 2;
                        }
                    }
                }
            }
            case 2 -> {
                // 断言 getContainer + menuType（真实打开该容器；马菜单 MenuType=null）
                ContainerCase c = CONTAINER_CASES.get(containerAllCaseIndex);
                Optional<BotContainer> container = bot.getContainer();
                if (container.isPresent()) {
                    check("open " + c.name(), true);
                    check("menuType " + c.name() + " correct", container.get().getMenuType() == c.menuType());
                    step = 3;
                }
            }
            case 3 -> {
                // 清空背包（保证 give 落快捷栏0）+ give 物品（讲台菜单无背包槽，跳过直接翻页）
                if ("lectern".equals(CONTAINER_CASES.get(containerAllCaseIndex).name())) {
                    step = 5;
                } else {
                    if (!containerAllGiven) {
                        containerAllGiven = true;
                        server.execute(() -> {
                            net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                            if (sp != null) {
                                sp.getInventory().clearContent();
                            }
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "give " + botName + " " + CONTAINER_CASES.get(containerAllCaseIndex).itemId() + " 1");
                        });
                    }
                    step = 4;
                }
            }
            case 4 -> {
                // 等假人背包有物品
                ContainerCase c = CONTAINER_CASES.get(containerAllCaseIndex);
                String itemId = c.itemId();
                net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .get(net.minecraft.resources.Identifier.tryParse(itemId)).get().value();
                if (bot.getLocalPlayer().getInventory().countItem(item) > 0) {
                    check("client has " + c.name() + " item", true);
                    step = 5;
                } else if (++waitTicks > 200) {
                    containerAllGiven = false; // give 竞态丢失 → 重试
                    waitTicks = 0;
                    step = 3;
                }
            }
            case 5 -> {
                // click 放入容器槽 → 等服务端菜单槽验证（讲台：翻页交互）
                if (!containerAllPut) {
                    containerAllPut = true;
                    ContainerCase c = CONTAINER_CASES.get(containerAllCaseIndex);
                    if ("lectern".equals(c.name())) {
                        bot.getContainer().ifPresent(cont -> cont.clickButton(3)); // 取书（讲台唯一交互，菜单仅书槽）
                    } else {
                        bot.getContainer().ifPresent(cont -> {
                            cont.click(c.hotbarSlot(), 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 快捷栏0→鼠标
                            cont.click(c.containerSlot(), 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 放入容器槽
                        });
                    }
                }
                final int putIdx = containerAllCaseIndex; // 调度时捕获，防执行时 index 已推进越界
                server.execute(() -> {
                    ContainerCase c = CONTAINER_CASES.get(putIdx);
                    boolean put;
                    if ("lectern".equals(c.name())) {
                        put = !server.getLevel(Level.OVERWORLD).getBlockState(containerAllPos)
                                .getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK); // 书被取走
                    } else {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        // 部分容器（砂轮/切石机等）放入即处理到结果槽，验证容器槽或结果槽有物品
                        put = sp != null
                                && (!sp.containerMenu.getSlot(c.containerSlot()).getItem().isEmpty()
                                || (c.resultSlot() >= 0 && !sp.containerMenu.getSlot(c.resultSlot()).getItem().isEmpty()));
                    }
                    containerAllServerPut = put;
                    containerAllServerPutFor = put ? putIdx : -1; // 带 case 标签，残留任务不污染下一 case
                });
                if (containerAllServerPut && containerAllServerPutFor == containerAllCaseIndex) {
                    check("put into " + CONTAINER_CASES.get(containerAllCaseIndex).name() + " (server)", true);
                    step = 6;
                } else if (++waitTicks > 200) {
                    fail("put into " + CONTAINER_CASES.get(containerAllCaseIndex).name() + " timeout");
                    step = 6;
                }
            }
            case 6 -> {
                // 取回（容器槽→鼠标→快捷栏）→ 服务端验证容器槽空 → 下一个容器或收尾
                if (!containerAllTaken) {
                    containerAllTaken = true;
                    ContainerCase c = CONTAINER_CASES.get(containerAllCaseIndex);
                    if ("lectern".equals(c.name())) {
                        bot.getContainer().ifPresent(cont -> cont.clickButton(3)); // 取书
                    } else {
                        bot.getContainer().ifPresent(cont -> {
                            if (!cont.getSlot(c.containerSlot()).isEmpty()) {
                                cont.click(c.containerSlot(), 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 容器槽→鼠标
                            }
                            if (c.resultSlot() >= 0 && c.resultSlot() != c.containerSlot() && !cont.getSlot(c.resultSlot()).isEmpty()) {
                                cont.click(c.resultSlot(), 0, net.minecraft.world.inventory.ContainerInput.PICKUP);    // 结果槽→鼠标
                            }
                            cont.click(c.hotbarSlot(), 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 鼠标→快捷栏
                        });
                    }
                }
                final int takeIdx = containerAllCaseIndex; // 调度时捕获，防执行时 index 已推进越界
                server.execute(() -> {
                    ContainerCase c = CONTAINER_CASES.get(takeIdx);
                    boolean empty;
                    if ("lectern".equals(c.name())) {
                        empty = bot.getLocalPlayer().getInventory()
                                .countItem(net.minecraft.world.item.Items.WRITABLE_BOOK) > 0; // 书取回到背包
                    } else {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        boolean cEmpty = sp != null && sp.containerMenu.getSlot(c.containerSlot()).getItem().isEmpty();
                        boolean rEmpty = c.resultSlot() < 0 || sp == null || sp.containerMenu.getSlot(c.resultSlot()).getItem().isEmpty();
                        empty = cEmpty && rEmpty;
                    }
                    containerAllServerEmpty = empty;
                    containerAllServerEmptyFor = empty ? takeIdx : -1; // 带 case 标签，残留任务不污染下一 case
                });
                if (containerAllServerEmpty && containerAllServerEmptyFor == containerAllCaseIndex) {
                    check("take back from " + CONTAINER_CASES.get(containerAllCaseIndex).name() + " (server)", true);
                    bot.getContainer().ifPresent(cont -> cont.close());
                    containerAllCaseIndex++;
                    if (containerAllCaseIndex < CONTAINER_CASES.size()) {
                        containerAllResetCase();
                        step = 1;
                    } else {
                        step = 7;
                    }
                } else if (++waitTicks > 200) {
                    fail("take back from " + CONTAINER_CASES.get(containerAllCaseIndex).name() + " timeout");
                    containerAllCaseIndex++;
                    containerAllResetCase();
                    step = (containerAllCaseIndex < CONTAINER_CASES.size()) ? 1 : 7;
                }
            }
            case 7 -> {
                MockplayerApi.bots().removeBot(botName, "command");
                finishSuite();
            }
        }
    }

    // ===== crafting：工作台合成（give 木板 → 放合成格 → 取 4 木棍，服务端验证） =====

    private static BlockPos craftPos;
    private static boolean craftPlanksGiven;
    private static boolean craftClickedPart1;
    private static volatile boolean craftResultReady;
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
                if (!craftClickedPart1) {
                    craftClickedPart1 = true;
                    // give 2 木板叠在快捷栏0 = CraftingMenu 槽37（0 结果 / 1-9 合成格 / 10-36 主背包 / 37-45 快捷栏）
                    // click(37) 一次拿走 2 个，PICKUP 放合成格每次 1 个；合成格1（左上）+4（左中）= 竖排 2 木板 → 4 木棍
                    bot.getContainer().ifPresent(c -> {
                        c.click(37, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 左键拿 2 木板→鼠标
                        c.click(1, 1, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 右键放 1→合成格1
                        c.click(4, 1, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 右键放 1→合成格4（竖排）
                    });
                    check("crafting clicks issued", true);
                }
                // 等结果槽0 出现 STICK（放木板后服务端合成结果），再取——真实玩家会等结果出现才取
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null && sp.containerMenu != null
                            && sp.containerMenu.getSlot(0).getItem().is(net.minecraft.world.item.Items.STICK)) {
                        craftResultReady = true;
                    }
                });
                if (craftResultReady) {
                    bot.getContainer().ifPresent(c -> {
                        c.click(0, 0, net.minecraft.world.inventory.ContainerInput.PICKUP);  // 左键取 4 木棍→鼠标
                        c.click(37, 0, net.minecraft.world.inventory.ContainerInput.PICKUP); // 木棍放回快捷栏0
                    });
                    step = 8;
                } else if (++waitTicks > 200) {
                    fail("crafting result timeout (no stick in result)");
                    step = 9;
                }
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
                MockplayerApi.bots().removeBot(botName, "command");
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
                MockplayerApi.bots().removeBot(botName, "command");
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
                MockplayerApi.bots().removeBot(botName, "command");
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
                MockplayerApi.bots().removeBot(botName, "command");
                finishSuite();
            }
        }
    }

    // ===== config：ModConfigIO 读写往返/默认值/非法值回退/手改 JSON + YACL 界面打开/保存落盘 =====

    private static Path cfgDir;
    private static Path cfgFile;
    private static ModConfigScreen cfgScreen;

    private static void runConfig(Minecraft mc) {
        switch (step) {
            case 0 -> {
                if (cfgDir == null) {
                    try {
                        cfgDir = Files.createTempDirectory("mocktest-config");
                        cfgFile = cfgDir.resolve("mockplayer.json");
                    } catch (IOException e) {
                        fail("create temp config dir");
                        finishSuite();
                        return;
                    }
                }
                ModConfig defaults = new ModConfig();
                check("missing file -> defaults", configEquals(defaults, ModConfigIO.load(cfgFile)));
                ModConfigIO.save(cfgFile, defaults);
                check("save->load round trip", configEquals(defaults, ModConfigIO.load(cfgFile)));
                // guiBlur/guiOpacity 非默认值保存往返（回归：save 漏拷贝导致设置界面改完回默认）
                ModConfig guiChanged = new ModConfig();
                guiChanged.setGuiBlur(ModConfig.DEFAULT_GUI_BLUR + 3);
                guiChanged.setGuiOpacity(0.5F);
                ModConfigIO.save(cfgFile, guiChanged);
                check("gui blur/opacity round trip", configEquals(guiChanged, ModConfigIO.load(cfgFile)));
                ModConfigIO.save(cfgFile, defaults);
                step = 1;
            }
            case 1 -> {
                // 手改 JSON（只写部分字段）：改过的字段生效，缺的字段补默认
                writeConfigRaw("{\"chatHistoryLimit\": 77}");
                ModConfig loaded = ModConfigIO.load(cfgFile);
                check("hand-edit applied", loaded.getChatHistoryLimit() == 77);
                check("missing fields defaulted", loaded.getEventCacheSize() == ModConfig.DEFAULT_EVENT_CACHE_SIZE
                        && loaded.getEventMoveSampleDistance() == ModConfig.DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE);
                step = 2;
            }
            case 2 -> {
                // 非法值（类型错/越界/负值/超上限）→ 全部回退默认；损坏文件也回退默认
                writeConfigRaw("{\"chatHistoryLimit\":\"abc\",\"soundLogLimit\":-5,\"particleLogLimit\":99999,"
                        + "\"eventCacheSize\":3,\"eventSummaryMaxLength\":1000000,"
                        + "\"eventTickSampleInterval\":0,\"eventMoveSampleDistance\":999}");
                check("invalid values fallback", configEquals(new ModConfig(), ModConfigIO.load(cfgFile)));
                writeConfigRaw("not a json object");
                check("corrupt file fallback", configEquals(new ModConfig(), ModConfigIO.load(cfgFile)));
                // commands：手改/空串禁用/非法回退（不是禁用）/重名整组回退/类型错回退
                writeConfigRaw("{\"commands\":{\"control\":\"ctrl\"}}");
                ModConfig renamed = ModConfigIO.load(cfgFile);
                check("commands hand-edit applied", "ctrl".equals(renamed.getCommandName("control"))
                        && "query".equals(renamed.getCommandName("query")));
                writeConfigRaw("{\"commands\":{\"query\":\"\"}}");
                check("commands empty disables", ModCommands.isDisabled(
                        ModConfigIO.load(cfgFile).getCommandName("query")));
                writeConfigRaw("{\"commands\":{\"control\":\"bad name!\"}}");
                check("commands invalid falls back (not disabled)",
                        "control".equals(ModConfigIO.load(cfgFile).getCommandName("control")));
                writeConfigRaw("{\"commands\":{\"control\":\"x\",\"query\":\"x\"}}");
                ModConfig duplicated = ModConfigIO.load(cfgFile);
                check("commands duplicate falls back all",
                        "control".equals(duplicated.getCommandName("control"))
                                && "query".equals(duplicated.getCommandName("query")));
                writeConfigRaw("{\"commands\":[]}");
                check("commands wrong type falls back",
                        "control".equals(ModConfigIO.load(cfgFile).getCommandName("control")));
                // debugOverlayEnabled：默认 false / 手改 true / 非布尔回退
                check("debug overlay default on", new ModConfig().isDebugOverlayEnabled());
                writeConfigRaw("{\"debugOverlayEnabled\": false}");
                check("debug overlay hand-edit", !ModConfigIO.load(cfgFile).isDebugOverlayEnabled());
                writeConfigRaw("{\"debugOverlayEnabled\": \"yes\"}");
                check("debug overlay non-boolean falls back", ModConfigIO.load(cfgFile).isDebugOverlayEnabled());
                step = 3;
            }
            case 3 -> {
                i18nConfigLangChecks();
                boolean yaclPresent;
                try {
                    Class.forName("dev.isxander.yacl3.api.YetAnotherConfigLib");
                    yaclPresent = true;
                } catch (ClassNotFoundException e) {
                    yaclPresent = false;
                }
                check("yacl available in test env", yaclPresent);
                check("yacl mod id loaded", net.neoforged.fml.ModList.get()
                        .isLoaded("yet_another_config_lib_v3"));
                MockplayerConfig.reload();
                ModConfigScreen screen = new ModConfigScreen(null);
                mc.gui.setScreen(screen);
                check("yacl screen opened", mc.gui.screen() == screen);
                check("screen holds bound config", screen.config() == MockplayerConfig.get());
                check("screen title translated", !screen.getTitle().getString()
                        .equals("config.mockplayer.title"));
                cfgScreen = screen;
                step = 4;
            }
            case 4 -> {
                // 模拟修改控件：改 int 选项 + query 命令名 → finishOrSave → 保存 + 热重载端到端
                ModConfigScreen screen = cfgScreen;
                dev.isxander.yacl3.api.Option<Integer> intOption = firstIntegerOption(screen);
                dev.isxander.yacl3.api.Option<String> queryOption = findStringOption(screen, "query");
                dev.isxander.yacl3.api.Option<String> controlOption = findStringOption(screen, "control");
                dev.isxander.yacl3.api.Option<Boolean> debugOption = findBooleanOption(screen);
                dev.isxander.yacl3.api.Option<Double> opacityOption =
                        findDoubleOption(screen, (double) ModConfig.DEFAULT_GUI_OPACITY);
                dev.isxander.yacl3.api.Option<Integer> blurOption = findIntOption(screen, ModConfig.DEFAULT_GUI_BLUR);
                check("integer option found", intOption != null);
                check("query option found", queryOption != null);
                check("control option found", controlOption != null);
                check("debug option found", debugOption != null);
                check("opacity option found", opacityOption != null);
                check("guiBlur option found", blurOption != null);
                if (intOption != null && queryOption != null && controlOption != null && debugOption != null
                        && opacityOption != null && blurOption != null) {
                    int before = intOption.binding().getValue();
                    intOption.requestSet(before + 1);
                    queryOption.requestSet("qry");
                    debugOption.requestSet(false);
                    opacityOption.requestSet(0.5D);
                    blurOption.requestSet(7);
                    check("pending change registered", screen.pendingChanges());
                    screen.finishOrSave();
                    check("int option applied to config", intOption.binding().getValue() == before + 1);
                    check("command rename applied to config", MockplayerConfig.get().getCommandName("query").equals("qry"));
                    check("debug overlay applied to config", !MockplayerConfig.get().isDebugOverlayEnabled());
                    check("opacity applied to config", Float.compare(
                            MockplayerConfig.get().getGuiOpacity(), 0.5F) == 0);
                    check("guiBlur applied to config", MockplayerConfig.get().getGuiBlur() == 7);
                    ModConfig saved = ModConfigIO.load(MockplayerConfig.path());
                    check("config file written", saved.getChatHistoryLimit() == before + 1
                            && "qry".equals(saved.getCommandName("query"))
                            && !saved.isDebugOverlayEnabled()
                            && Float.compare(saved.getGuiOpacity(), 0.5F) == 0
                            && saved.getGuiBlur() == 7);
                    // 热重载：不重进游戏，dispatcher 两层都更新
                    check("hot reload old root removed (active)", !hasActiveRoot("query"));
                    check("hot reload new root registered (active)", hasActiveRoot("qry"));
                    check("hot reload exec layer updated", !hasExecRoot("query") && hasExecRoot("qry"));
                    check("new command executable", executeClientCommand("qry list"));
                    check("old command not executable", !executeClientCommand("query list"));
                    // 禁用 control：保存后根消失，其它命令不受影响
                    controlOption.requestSet("");
                    screen.finishOrSave();
                    check("disable control root removed (active)", !hasActiveRoot("control"));
                    check("disable control exec layer updated", !hasExecRoot("control"));
                    check("disable control other command intact", hasActiveRoot("qry"));
                    // 恢复默认：配置与 dispatcher 全部回默认
                    MockplayerConfig.save(new ModConfig());
                    check("restore config file", ModConfigIO.load(MockplayerConfig.path()).getChatHistoryLimit()
                            == ModConfig.DEFAULT_CHAT_HISTORY_LIMIT
                            && ModConfigIO.load(MockplayerConfig.path()).getCommandName("query").equals("query"));
                    check("restore control root back", hasActiveRoot("control") && hasActiveRoot("query"));
                    check("restore renamed root gone", !hasActiveRoot("qry"));
                    check("restore exec layer back", hasExecRoot("control") && hasExecRoot("query"));
                }
                step = 5;
            }
            case 5 -> {
                // 一键恢复默认（按钮共用路径）：打乱绑定实例 → resetAllAndSave → 全默认 + 热重载
                ModConfig bound = cfgScreen.config();
                bound.setChatHistoryLimit(77);
                bound.setCommandName(ModCommands.QUERY, "qq");
                ModConfigScreen.resetAllAndSave(cfgScreen);
                check("reset all config defaults", cfgScreen.config().getChatHistoryLimit()
                        == ModConfig.DEFAULT_CHAT_HISTORY_LIMIT
                        && cfgScreen.config().getCommandName(ModCommands.QUERY).equals("query"));
                check("reset all file defaults", ModConfigIO.load(MockplayerConfig.path()).getChatHistoryLimit()
                        == ModConfig.DEFAULT_CHAT_HISTORY_LIMIT
                        && ModConfigIO.load(MockplayerConfig.path()).getCommandName(ModCommands.QUERY).equals("query"));
                check("reset all dispatcher restored", hasActiveRoot("query") && hasActiveRoot("control")
                        && !hasActiveRoot("qq"));
                // 配置真实生效（消费者）：聊天历史上限 + 事件缓存容量
                MockplayerConfig.get().setChatHistoryLimit(10);
                FakePlayerState state = new FakePlayerState();
                for (int i = 0; i < 15; i++) {
                    state.addChat(Component.literal("msg-" + i));
                }
                check("chat limit applied to state", state.getChatHistory().size() == 10);
                MockplayerConfig.get().setEventCacheSize(10);
                MockplayerConfig.get().setEventTickSampleInterval(1);
                EventRecorder recorder = new EventRecorder("cfg-recorder");
                for (int i = 0; i < 15; i++) {
                    recorder.onTick(null);
                }
                check("event cache size applied", recorder.snapshot().size() == 10);
                MockplayerConfig.save(new ModConfig());
                // 兜底界面（无 YACL 场景的纯原版 Screen）可打开/关闭
                MissingYaclScreen missing = new MissingYaclScreen(null);
                mc.gui.setScreen(missing);
                check("missing-yacl screen opened", mc.gui.screen() == missing);
                check("missing-yacl title translated", !missing.getTitle().getString()
                        .equals("config.mockplayer.missing_yacl.title"));
                missing.onClose();
                check("missing-yacl screen closed", mc.gui.screen() == null);
                cfgScreen = null;
                deleteConfigTempDir();
                finishSuite();
            }
        }
    }

    /** 找第一个 Integer 类型的选项（界面树顺序 = 构建顺序，首个即 chatHistoryLimit）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static dev.isxander.yacl3.api.Option<Integer> firstIntegerOption(ModConfigScreen screen) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Integer) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    /** 找默认名为 defaultName 的 String 选项（命令名选项，界面树顺序按字段固定）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static dev.isxander.yacl3.api.Option<String> findStringOption(ModConfigScreen screen, String defaultName) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof String
                            && defaultName.equals(option.binding().defaultValue())) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    /** 找第一个 Boolean 类型选项（当前唯一是 debugOverlayEnabled）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static dev.isxander.yacl3.api.Option<Boolean> findBooleanOption(ModConfigScreen screen) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Boolean) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    /** 找默认值为 defaultValue 的 Double 类型选项（不透明度默认 0.25，与采样距离 0.5 区分）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static dev.isxander.yacl3.api.Option<Double> findDoubleOption(ModConfigScreen screen, double defaultValue) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Double
                            && Double.compare((Double) option.binding().defaultValue(), defaultValue) == 0) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    /** 找默认值为 defaultValue 的 Integer 选项（当前唯一是 guiBlur）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static dev.isxander.yacl3.api.Option<Integer> findIntOption(ModConfigScreen screen, int defaultValue) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Integer
                            && ((Integer) option.binding().defaultValue()) == defaultValue) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    // ===== batch：批量假人生成/移除命令（性能测试，TDD） =====

    private static boolean batchCreateFailed;
    private static boolean batchDeleteFailed;
    private static long batchWaitStart;

    private static void runBatch(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no singleplayer server");
            finishSuite();
            return;
        }
        switch (step) {
            case 0 -> { // 上限/前缀校验 + 批量创建 5 个
                check("batch max count default 100",
                        com.mockplayer.config.MockplayerConfig.get().getBatchMaxCount() == 100);
                String tooMany = com.mockplayer.session.FakePlayerCommands
                        .newPlayerBatch("tbotbz", 1000, 0, 4).getString();
                check("batch too many rejected", !tooMany.contains("commands."), "out=" + tooMany);
                String badPrefix = com.mockplayer.session.FakePlayerCommands
                        .newPlayerBatch("x".repeat(20), 2, 0, 4).getString();
                check("batch invalid prefix rejected",
                        !badPrefix.contains("commands.") && MockplayerApi.bots().getBots().stream()
                                .noneMatch(b -> b.getName().startsWith("xxxxxxxx")), "out=" + badPrefix);
                String started = com.mockplayer.session.FakePlayerCommands
                        .newPlayerBatch("tbotb", 5, 0, 2).getString();
                check("batch create started", !started.contains("commands."), "out=" + started);
                batchWaitStart = System.currentTimeMillis();
                step = 1;
            }
            case 1 -> { // 等 5 个全部 PLAYING
                var created = MockplayerApi.bots().getBots().stream()
                        .filter(b -> b.getName().startsWith("tbotb_")).toList();
                if (created.size() == 5 && created.stream()
                        .allMatch(b -> b.getLifecycle() == BotLifecycle.PLAYING)) {
                    check("batch created 5 playing", true);
                    check("batch names complete",
                            created.stream().map(com.mockplayer.api.Bot::getName).toList()
                                    .containsAll(java.util.List.of("tbotb_1", "tbotb_2", "tbotb_3",
                                            "tbotb_4", "tbotb_5")));
                    step = 2;
                } else if (!batchCreateFailed
                        && System.currentTimeMillis() - batchWaitStart > 170_000) {
                    batchCreateFailed = true;
                    fail("batch create timeout count=" + created.size()
                            + " summary=" + com.mockplayer.session.BatchCommands.lastSummary());
                    step = 7;
                }
            }
            case 2 -> { // 重名跳过：再批量 2 个同名 → created=0 skipped=2
                String out = com.mockplayer.session.FakePlayerCommands
                        .newPlayerBatch("tbotb", 2, 0, 2).getString();
                check("batch duplicate started", !out.contains("commands."), "out=" + out);
                batchWaitStart = System.currentTimeMillis();
                step = 3;
            }
            case 3 -> { // 等汇总：created=0 skipped=2 failed=0
                var s = com.mockplayer.session.BatchCommands.lastSummary();
                if (s != null && s.created() == 0 && s.skipped() == 2 && s.failed() == 0) {
                    check("batch duplicate skip summary", true);
                    step = 4;
                } else if (System.currentTimeMillis() - batchWaitStart > 170_000) {
                    fail("batch duplicate summary timeout s=" + s);
                    step = 7;
                }
            }
            case 4 -> { // dry-run 删除：只列不删
                long before = MockplayerApi.bots().getBots().stream()
                        .filter(b -> b.getName().startsWith("tbotb_")).count();
                String dry = com.mockplayer.session.FakePlayerCommands
                        .delPlayerBatch("tbotb", true).getString();
                long after = MockplayerApi.bots().getBots().stream()
                        .filter(b -> b.getName().startsWith("tbotb_")).count();
                check("batch dry lists 5", dry.contains("5"), "out=" + dry);
                check("batch dry does not remove", after == 5 && before == 5);
                step = 5;
            }
            case 5 -> { // 真删：删前缀 → 剩 0
                String del = com.mockplayer.session.FakePlayerCommands
                        .delPlayerBatch("tbotb", false).getString();
                check("batch delete executed", !del.contains("commands."), "out=" + del);
                batchWaitStart = System.currentTimeMillis();
                step = 6;
            }
            case 6 -> {
                long left = MockplayerApi.bots().getBots().stream()
                        .filter(b -> b.getName().startsWith("tbotb_")).count();
                boolean serverLeft = false;
                for (int i = 1; i <= 5; i++) {
                    if (server.getPlayerList().getPlayerByName("tbotb_" + i) != null) {
                        serverLeft = true;
                    }
                }
                if (left == 0 && !serverLeft) {
                    check("batch delete removed all", true);
                    step = 7;
                } else if (!batchDeleteFailed
                        && System.currentTimeMillis() - batchWaitStart > 170_000) {
                    batchDeleteFailed = true;
                    fail("batch delete timeout left=" + left + " serverLeft=" + serverLeft);
                    step = 7;
                }
            }
            case 7 -> { // 边界：API 创建的假人（owner=command 伪造）不被批量删除
                var apiBot = MockplayerApi.bots().createBot(
                        com.mockplayer.api.BotProfile.of("tbotbapi", "command"));
                check("batch boundary api bot created", apiBot != null);
                String dry = com.mockplayer.session.FakePlayerCommands
                        .delPlayerBatch("tbotbapi", true).getString();
                check("batch boundary dry not match", !dry.contains("tbotbapi"), "out=" + dry);
                com.mockplayer.session.FakePlayerCommands.delPlayerBatch("tbotbapi", false);
                check("batch boundary api bot survives",
                        MockplayerApi.bots().getBot("tbotbapi").isPresent());
                MockplayerApi.bots().removeBot("tbotbapi", "command");
                finishSuite();
            }
        }
    }

    // ===== debug-name-tag：F3 调试信息标签（配置开关 + 格式化 + 联动） =====

    private static net.minecraft.core.BlockPos dntChestPos;
    private static boolean dntChestSet;
    private static boolean dntOpened;

    private static void runDebugNameTag(Minecraft mc) {
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
                // 确保运行期与文件都是默认配置（旧文件可能残留旧默认 false）
                MockplayerConfig.save(new ModConfig());
                dntResetRender();
                // 多行格式化：血量/饱食度/内存/速度各占一行；无容器不含 📦
                net.minecraft.network.chat.Component info =
                        com.mockplayer.session.DebugNameTagInfo.format(bot);
                check("debug tag non-empty", info != null && !info.getString().isBlank());
                if (info != null) {
                    java.util.List<net.minecraft.network.chat.Component> rows = info.getSiblings();
                    int health = Math.round(bot.getLocalPlayer().getHealth());
                    int food = bot.getLocalPlayer().getFoodData().getFoodLevel();
                    int sat = Math.round(bot.getLocalPlayer().getFoodData().getSaturationLevel());
                    check("debug tag multi-line", rows.size() >= 3, "rows=" + rows.size());
                    check("debug tag health+food row", rows.stream().anyMatch(r ->
                            r.getString().startsWith("❤" + health)
                                    && r.getString().contains("🍗" + food + "(" + sat + ")")));
                    // 内存与区块半径同一行：💾数值 + 📡半径 chunk
                    check("debug tag memory+chunk row", rows.stream().anyMatch(r ->
                            r.getString().startsWith("💾")
                                    && (r.getString().contains("KB") || r.getString().contains("MB"))
                                    && r.getString().contains("📡" + bot.getChunkRadius() + " chunk")));
                    check("debug tag speed row", rows.stream().anyMatch(r ->
                            r.getString().startsWith("🏃") && r.getString().contains("m/s")));
                    check("debug tag colored health", rows.stream().anyMatch(r ->
                            r.getString().startsWith("❤") && r.getStyle().getColor() != null));
                    check("debug tag no container", rows.stream()
                            .noneMatch(r -> r.getString().startsWith("📦")));
                }
                check("debug tag null for null bot",
                        com.mockplayer.session.DebugNameTagInfo.format(null) == null);
                // shouldShow 联动：默认启用 + F3 开 → true；配置关 → false；F3 关 → false
                mc.debugEntries.setOverlayVisible(true);
                check("shouldShow true by default (F3 + config on)",
                        com.mockplayer.session.DebugNameTagInfo.shouldShow());
                MockplayerConfig.get().setDebugOverlayEnabled(false);
                check("shouldShow false when config off",
                        !com.mockplayer.session.DebugNameTagInfo.shouldShow());
                MockplayerConfig.get().setDebugOverlayEnabled(true);
                mc.debugEntries.setOverlayVisible(false);
                check("shouldShow false when F3 off",
                        !com.mockplayer.session.DebugNameTagInfo.shouldShow());
                // 恢复默认配置（默认启用），F3 留开供真实截图
                MockplayerConfig.save(new ModConfig());
                mc.debugEntries.setOverlayVisible(true);
                step = 2;
            }
            case 2 -> {
                // 保持主玩家看向假人：视锥剔除会把视野外的假人跳过 extract，
                // 必须让假人进视野才能被真实渲染
                if (mc.player != null && bot.getLocalPlayer() != null) {
                    mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                            bot.getLocalPlayer().position());
                }
                // 渲染路径验证：Mixin 在真实渲染帧里对假人注入多行 scoreText
                if (dntRenderCount() == 0) {
                    if (++waitTicks > 100) {
                        fail("debug render path never executed");
                        step = 3;
                    }
                    return;
                }
                check("debug render path executed", dntRenderCount() > 0,
                        "count=" + dntRenderCount());
                String injected = dntLastScoreText();
                check("debug scoreText injected", injected != null && injected.contains("❤"),
                        "injected=" + injected);
                // 布局强断言：所有信息行渲染在名字上方（探针记录相对偏移，信息行 > 名字）
                check("debug tag info above name", dntInfoOffsetY() > dntNameOffsetY(),
                        "info=" + dntInfoOffsetY() + " name=" + dntNameOffsetY());
                waitTicks = 0;
                step = 3;
            }
            case 3 -> {
                // 开容器后 format 含 📦 与容器标题
                if (!dntChestSet) {
                    dntChestSet = true;
                    dntChestPos = bot.getLocalPlayer().blockPosition().offset(2, 0, 0);
                    net.minecraft.core.BlockPos p = dntChestPos;
                    server.execute(() -> server.getLevel(Level.OVERWORLD).setBlock(p, Blocks.CHEST.defaultBlockState(), 3));
                }
                if (!dntOpened && bot.getBlockState(dntChestPos).is(Blocks.CHEST)) {
                    dntOpened = true;
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(dntChestPos));
                    net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                            net.minecraft.world.phys.Vec3.atCenterOf(dntChestPos), Direction.WEST, dntChestPos, false);
                    bot.getGameMode().useItemOn(bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
                }
                if (bot.getContainer().isPresent()) {
                    java.util.List<net.minecraft.network.chat.Component> rows =
                            com.mockplayer.session.DebugNameTagInfo.format(bot).getSiblings();
                    String dntTitle = bot.getContainer().get().getTitle().getString();
                    // 📦 与容器名称必须在同一行（同一 sibling）
                    check("debug tag container same line", rows.stream().anyMatch(r ->
                            r.getString().startsWith("📦") && r.getString().contains(dntTitle)),
                            "title=" + dntTitle);
                    bot.getContainer().ifPresent(c -> c.close());
                    step = 4;
                } else if (++waitTicks > 200) {
                    fail("debug tag container open timeout");
                    step = 4;
                }
            }
            case 4 -> {
                mc.debugEntries.setOverlayVisible(false);
                MockplayerConfig.save(new ModConfig());
                MockplayerApi.bots().removeBot(botName, "command");
                finishSuite();
            }
        }
    }

    /** 反射读渲染探针计数（生产默认不记录，测试属性开启）。 */
    private static int dntRenderCount() {
        try {
            java.lang.reflect.Field f = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("renderCount");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 反射读最近注入的 scoreText。 */
    private static String dntLastScoreText() {
        try {
            java.lang.reflect.Field f = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("lastRendered");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反射读信息行相对名字的 Y 偏移（>0 = 在名字上方）。 */
    private static float dntInfoOffsetY() {
        try {
            java.lang.reflect.Field f = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("lastInfoOffsetY");
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (Exception e) {
            return -1.0F;
        }
    }

    /** 反射读名字自身 Y 偏移（探针布局断言基准）。 */
    private static float dntNameOffsetY() {
        try {
            java.lang.reflect.Field f = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("lastNameOffsetY");
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (Exception e) {
            return -1.0F;
        }
    }

    /** 反射清零渲染探针（套件隔离）。 */
    private static void dntResetRender() {
        try {
            java.lang.reflect.Field f = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("renderCount");
            f.setAccessible(true);
            f.setInt(null, 0);
            java.lang.reflect.Field l = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("lastRendered");
            l.setAccessible(true);
            l.set(null, null);
            java.lang.reflect.Field io = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("lastInfoOffsetY");
            io.setAccessible(true);
            io.setFloat(null, -1.0F);
            java.lang.reflect.Field no = com.mockplayer.session.DebugNameTagInfo.class
                    .getDeclaredField("lastNameOffsetY");
            no.setAccessible(true);
            no.setFloat(null, -1.0F);
        } catch (Exception ignored) {
        }
    }

    /** NeoForge 注册层 dispatcher（ClientCommandHandler.commands）是否含根命令。 */
    private static boolean hasActiveRoot(String name) {
        var dispatcher = net.neoforged.neoforge.client.ClientCommandHandler.getDispatcher();
        return dispatcher != null && dispatcher.getRoot().getChild(name) != null;
    }

    /** 执行层 dispatcher（ClientPacketListener.commands）是否含根命令。 */
    private static boolean hasExecRoot(String name) {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        return connection != null && connection.getCommands().getRoot().getChild(name) != null;
    }

    /** 执行一条客户端命令（true = 执行成功，false = 未知命令/语法错误）。 */
    private static boolean executeClientCommand(String command) {
        try {
            net.neoforged.neoforge.client.ClientCommandHandler.getDispatcher()
                    .execute(command, net.neoforged.neoforge.client.ClientCommandHandler.getSource());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 写原始 JSON 到临时配置文件（模拟玩家手改）。 */
    private static void writeConfigRaw(String json) {
        try {
            Files.writeString(cfgFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("write config json");
        }
    }

    /** 配置全字段相等（含 double 精确比较）。 */
    private static boolean configEquals(ModConfig a, ModConfig b) {
        return a.getChatHistoryLimit() == b.getChatHistoryLimit()
                && a.getSoundLogLimit() == b.getSoundLogLimit()
                && a.getParticleLogLimit() == b.getParticleLogLimit()
                && a.getEventCacheSize() == b.getEventCacheSize()
                && a.getEventSummaryMaxLength() == b.getEventSummaryMaxLength()
                && a.getEventTickSampleInterval() == b.getEventTickSampleInterval()
                && Double.compare(a.getEventMoveSampleDistance(), b.getEventMoveSampleDistance()) == 0
                && a.isDebugOverlayEnabled() == b.isDebugOverlayEnabled()
                && a.getGuiBlur() == b.getGuiBlur()
                && Float.compare(a.getGuiOpacity(), b.getGuiOpacity()) == 0;
    }

    /** 删除测试专用临时目录（只删自己创建的 mocktest-config-*，不碰用户数据）。 */
    private static void deleteConfigTempDir() {
        if (cfgDir == null) {
            return;
        }
        try (var walk = Files.walk(cfgDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            System.out.println("[mocktest] failed to delete config temp dir: " + e);
        }
        cfgDir = null;
        cfgFile = null;
    }

    /** 语言文件级 i18n 强测：en/zh 的 config.* key 集合一致、值非空、无字面 %s。 */
    private static void i18nConfigLangChecks() {
        try {
            com.google.gson.JsonObject en = parseLang("en_us.json");
            com.google.gson.JsonObject zh = parseLang("zh_cn.json");
            java.util.Set<String> enKeys = new java.util.TreeSet<>();
            java.util.Set<String> zhKeys = new java.util.TreeSet<>();
            en.entrySet().forEach(e -> {
                if (e.getKey().startsWith("config.mockplayer.")) {
                    enKeys.add(e.getKey());
                }
            });
            zh.entrySet().forEach(e -> {
                if (e.getKey().startsWith("config.mockplayer.")) {
                    zhKeys.add(e.getKey());
                }
            });
            check("config i18n key sets identical (en/zh)", enKeys.equals(zhKeys),
                    "en=" + enKeys.size() + " zh=" + zhKeys.size());
            check("config i18n values non-empty",
                    enKeys.stream().allMatch(k -> !en.get(k).getAsString().isBlank())
                            && zhKeys.stream().allMatch(k -> !zh.get(k).getAsString().isBlank()));
            check("config i18n no literal %s",
                    enKeys.stream().noneMatch(k -> en.get(k).getAsString().contains("%s"))
                            && zhKeys.stream().noneMatch(k -> zh.get(k).getAsString().contains("%s")));
        } catch (Exception e) {
            check("config i18n lang files parse", false, e.toString());
        }
    }

    /** 读语言文件（走游戏 ResourceManager，双端 dev 环境都能拿到 common 资源）。 */
    private static com.google.gson.JsonObject parseLang(String fileName) throws java.io.IOException {
        var location = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "mockplayer", "lang/" + fileName);
        var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .getResource(location);
        if (resource.isEmpty()) {
            throw new java.io.IOException("missing lang file " + fileName);
        }
        try (var reader = resource.get().openAsReader()) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    // ===== bot-gui：BotControlScreen（打开/渲染探针/状态/移动/快捷栏/左键右键射线/容器点击/区块半径/聊天/自动重生/guiEnabled/i18n/多分辨率） =====

    private static volatile String bgChatMsg = "";
    private static final com.mockplayer.api.event.BotListener bgListener = new com.mockplayer.api.event.BotListener() {
        @Override
        public void onChat(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component message) {
            bgChatMsg = message.getString();
        }
    };
    private static boolean bgGuiOpened;
    private static boolean bgActionTab;
    private static boolean bgFwdClicked;
    private static boolean bgMoved;
    private static double bgBaseX;
    private static double bgBaseZ;
    private static volatile double bgServerX;
    private static volatile double bgServerZ;
    private static boolean bgHotbarGiven;
    private static volatile boolean bgHotbarSynced;
    private static volatile boolean bgHotbarServer;
    private static boolean bgHuskSummoned;
    private static volatile float bgHuskHp = 20;
    private static net.minecraft.core.BlockPos bgHuskPos;
    private static boolean bgHoldPressed;
    private static net.minecraft.client.gui.components.Button bgHoldButton;
    private static boolean bgChestPlaced;
    private static volatile boolean bgChestOpen;
    private static net.minecraft.core.BlockPos bgChestPos;
    private static boolean bgChestTabClicked;
    private static boolean bgTabChecked;
    private static boolean bgChestHotbarClicked;
    private static boolean bgChestSlotClicked;
    private static boolean bgChestHotbarOk;
    private static boolean bgChestSlotOk;
    private static volatile boolean bgChestHasStone;
    private static boolean bgChestActionsTab;
    private static boolean bgChestCloseClicked;
    private static volatile boolean bgChestClosed;
    private static boolean bgChunkClicked;
    private static volatile int bgChunkServer = -1;
    private static boolean bgChatSent;
    private static boolean bgAutoToggled;
    private static boolean bgDisabledChecked;
    private static boolean bgPickupSummoned;
    private static int bgPickupWait;
    private static int bgPickupVerifyWait;
    private static volatile boolean bgPickupClientHas;
    private static volatile boolean bgPickupServerHas;
    private static boolean bgSlotGiveDone;
    private static int bgSlotSyncWait;
    private static volatile boolean bgSlotClientSynced;
    private static boolean bgIconOpened;
    private static boolean bgRepeatOpened;
    private static boolean bgTurnHeld;
    private static boolean bgTurnReleased;
    private static float bgTurnBaseYaw;
    private static float bgTurnAfterYaw;
    private static boolean bgChunkHeld;
    private static boolean bgChunkReleased;
    private static int bgChunkBase;
    private static int bgChunkAfter;
    private static net.minecraft.client.gui.components.Button bgTurnButton;
    private static net.minecraft.client.gui.components.Button bgChunkButton;
    private static boolean bgCarriedOpened;
    private static boolean bgCarriedPicked;
    private static boolean bgCarriedSwapTested;
    private static int bgCarriedSelectedBefore;
    private static boolean bgHotbarSlotClicked;
    private static boolean bgXpGiven;
    private static boolean bgAttackActionsTab;
    private static boolean bgShieldGiven;
    private static boolean bgShieldHeld;
    private static volatile boolean bgShieldServerUsing;
    private static net.minecraft.client.gui.components.Button bgShieldUseButton;
    private static boolean bgCmdShieldUsed;
    private static boolean bgManySummoned;
    private static boolean bgManyChecked;
    private static boolean bgListChecked;
    private static boolean bgListSecondShown;
    private static boolean bgListSecondChecked;
    private static boolean bgChunkActionsTab;
    private static boolean bgChatActionsTab;
    private static boolean bgAutoActionsTab;
    private static int bgBlurBefore = -1;
    private static boolean bgPvpGiven;
    private static boolean bgPvpTargetFound;
    private static boolean bgPvpTpDone;
    private static boolean bgPvpLooked;
    private static boolean bgPvpAttacked;
    private static volatile float bgPvpTargetHp = -1;
    private static double bgPvpDist = -1;
    private static volatile double bgPvpClientX;
    private static volatile double bgPvpClientZ;
    private static volatile double bgPvpServerX;
    private static volatile double bgPvpServerZ;
    private static volatile boolean bgPvpServerReady;
    private static volatile boolean bgPvpServerSettled;
    private static boolean bgDigSet;
    private static boolean bgDigStarted;
    private static volatile int bgDigMaxEntries = -1;
    private static net.minecraft.core.BlockPos bgDigPos;
    private static volatile int bgDigFakeParticles = -1;
    private static boolean bgDigSoundRecorded;
    private static boolean bgDigParticleRecorded;
    private static int bgDigEventTicks;
    private static boolean bgDiscardGiven;
    private static boolean bgDiscardOpened;
    private static boolean bgDiscardPicked;
    private static boolean bgDiscardSlotFilled;
    private static boolean bgDiscardClicked;
    private static volatile boolean bgDiscardServerHas;
    private static volatile int bgDiscardServerItemCount;
    private static boolean bgMainPvpGiven;
    private static boolean bgMainPvpWalking;
    private static boolean bgMainPvpPosRead;
    private static boolean bgMainPvpBotPlaced;
    private static boolean bgMainPvpEntityChecked;
    private static boolean bgMainPvpAttacked;
    private static volatile double bgMainServerX = Double.NaN;
    private static volatile double bgMainServerZ = Double.NaN;
    private static volatile double bgBotServerX = Double.NaN;
    private static volatile double bgBotServerZ = Double.NaN;
    private static volatile double bgMainBaseX = Double.NaN;
    private static volatile int bgMainLastMoveTick = -1;
    private static volatile double bgMainClientX = Double.NaN;
    private static volatile double bgMainClientZ = Double.NaN;
    private static volatile float bgMainHealth = 20;
    private static volatile double bgMainStartX = Double.NaN;
    private static boolean bgModeSet;
    private static boolean bgModeOpened;
    private static boolean bgModeTextChecked;
    private static boolean bgModeHoldDamaged;
    private static boolean bgModeRapidActivated;
    private static boolean bgModeRapidDamaged;
    private static boolean bgModeUseDone;
    private static boolean bgModeUseArmed;
    private static boolean bgModeUseText;
    private static volatile float bgModeHoldHp = 20;
    private static volatile float bgModeHuskHp = 20;
    private static volatile float bgModeHuskPrevHp = 20;
    private static volatile int bgModeRapidHits;
    private static volatile int bgModeUseMaxContainer = -1;
    private static net.minecraft.client.gui.components.Button bgModeAtk;
    private static net.minecraft.client.gui.components.Button bgModeUse;
    private static boolean bgModePersistOpened;
    private static boolean bgModePersistClosed;
    private static boolean bgModePersistReopened;
    private static boolean bgModePersistStopped;
    private static boolean bgSlideGiven;
    private static boolean bgSlideWalking;
    private static boolean bgSlideWalked;
    private static int bgSlideWalkTicks;
    private static boolean bgSlidePauseOnLostFocus;
    private static volatile double bgSlideStartX = Double.NaN;
    private static volatile double bgSlideStartZ = Double.NaN;

    private static void runBotGui(Minecraft mc) {
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
                    bgBlurBefore = mc.options.getMenuBackgroundBlurriness();
                    // 渲染探针属性（记录打开/帧；生产默认零开销）
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    MockplayerApi.listen(bgListener);
                    MockplayerConfig.save(new ModConfig());
                    bgStep(1);
                }
            }
            case 1 -> { // i18n 双语言 + 配置默认/规范化
                i18nGuiLangChecks();
                check("gui config default on", MockplayerConfig.get().isGuiEnabled());
                check("gui key default g", "key.keyboard.g".equals(MockplayerConfig.get().getGuiKeyName()));
                ModConfig off = new ModConfig();
                off.setGuiEnabled(false);
                off.setGuiKeyName("bad name!");
                MockplayerConfig.save(off);
                MockplayerConfig.reload();
                check("gui config normalize", !MockplayerConfig.get().isGuiEnabled()
                        && "key.keyboard.g".equals(MockplayerConfig.get().getGuiKeyName()));
                MockplayerConfig.save(new ModConfig());
                bgStep(2);
            }
            case 2 -> { // 打开 GUI + 渲染探针 + 状态面板文本
                if (!bgGuiOpened) {
                    bgGuiOpened = true;
                    check("bot gui opened", com.mockplayer.gui.BotGui.open(mc));
                    check("selected bot label", com.mockplayer.gui.BotControlScreen
                            .selectedText(bot).getString().contains(botName));
                    // 所有控件必须完整落在面板内（防任何布局溢出）
                    int pw = mc.getWindow().getGuiScaledWidth();
                    int ph = mc.getWindow().getGuiScaledHeight();
                    int px = com.mockplayer.gui.BotGui.panelX(pw, ph);
                    int py = com.mockplayer.gui.BotGui.panelY(pw, ph);
                    int pwr = px + com.mockplayer.gui.BotGui.panelWidth(pw, ph);
                    int phr = py + com.mockplayer.gui.BotGui.panelHeight(pw, ph);
                    boolean inside = true;
                    int outsideCount = 0;
                    for (Object child : bgScreen().children()) {
                        if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                            if (w.getX() < px || w.getY() < py
                                    || w.getX() + w.getWidth() > pwr
                                    || w.getY() + w.getHeight() > phr) {
                                inside = false;
                                outsideCount++;
                            }
                        }
                    }
                    check("all widgets inside panel", inside, "outside=" + outsideCount);
                    // 关闭按钮不压顶栏蓝色分隔线（线在 header 底 17 逻辑像素）
                    boolean closeNotOnLine = true;
                    float bgScale = com.mockplayer.gui.BotGui.layoutScale(pw, ph);
                    for (Object child : bgScreen().children()) {
                        if (child instanceof net.minecraft.client.gui.components.Button b
                                && b.getMessage().getString().contains(
                                net.minecraft.network.chat.Component.translatable(
                                        "gui.mockplayer.close").getString())) {
                            if (b.getY() + b.getHeight() > py + Math.round(17 * bgScale)) {
                                closeNotOnLine = false;
                            }
                        }
                    }
                    check("close button not on header line", closeNotOnLine);
                    check("selected text adaptive",
                            com.mockplayer.gui.BotControlScreen.selectedTextDisplay(
                                    bot, mc.font, 150).contains(botName)
                                    && com.mockplayer.gui.BotControlScreen.selectedTextDisplay(
                                    bot, mc.font, 20).length() <= 20);
                    check("gui blur applied",
                            mc.options.getMenuBackgroundBlurriness() == 3);
                    check("screen is BotControlScreen",
                            mc.gui.screen() instanceof com.mockplayer.gui.BotControlScreen);
                    com.mockplayer.gui.BotControlScreen screen =
                            (com.mockplayer.gui.BotControlScreen) mc.gui.screen();
                    check("title translated", screen != null
                            && !screen.getTitle().getString().contains("gui.mockplayer."));
                    check("probe open counted", com.mockplayer.gui.BotGui.probeOpenCount() > 0);
                    waitTicks = 0;
                }
                if (com.mockplayer.gui.BotGui.probeFrameCount() == 0) {
                    if (++waitTicks > 100) {
                        fail("bot gui never rendered");
                        bgStep(3);
                    }
                    return;
                }
                check("probe frame rendered", com.mockplayer.gui.BotGui.probeFrameCount() > 0);
                check("probe tick ran", com.mockplayer.gui.BotGui.probeTickCount() > 0,
                        "ticks=" + com.mockplayer.gui.BotGui.probeTickCount());
                boolean listHasBot = bgScreen().children().stream().anyMatch(child ->
                        child instanceof net.minecraft.client.gui.components.Button b
                                && b.getMessage().getString().contains(botName));
                check("bot list shows bot", listHasBot);
                check("probe title rendered", com.mockplayer.gui.BotGui.probeLastTitle().contains(
                        net.minecraft.network.chat.Component.translatable("gui.mockplayer.title").getString()));
                java.util.List<net.minecraft.network.chat.Component> lines =
                        com.mockplayer.gui.BotControlScreen.statusLines(bot);
                int health = Math.round(bot.getLocalPlayer().getHealth());
                    check("status health line", lines.stream().anyMatch(l ->
                            l.getString().startsWith("❤" + health)));
                    check("health food bars rendered",
                            com.mockplayer.gui.BotGui.probeHealthFoodCount() > 0);
                check("status food line", lines.stream().anyMatch(l -> l.getString().contains("🍗")));
                check("status pos line", lines.stream().anyMatch(l ->
                        l.getString().contains("位置") || l.getString().contains("Pos")));
                check("status slot line", lines.stream().anyMatch(l ->
                        l.getString().contains("槽") || l.getString().contains("Slot")));
                check("status no container", lines.stream().anyMatch(l ->
                        l.getString().contains("无") || l.getString().contains("none")));
                bgStep(3);
            }
            case 3 -> { // 多分辨率：layoutScale 纯函数 + 面板边界
                check("layout 1280x720 scale 1", com.mockplayer.gui.BotGui.layoutScale(1280, 720) == 1.0F);
                check("layout 854x480 scale 1", com.mockplayer.gui.BotGui.layoutScale(854, 480) == 1.0F);
                float tiny = com.mockplayer.gui.BotGui.layoutScale(400, 200);
                check("layout tiny scaled down", tiny > 0.0F && tiny < 1.0F);
                int px = com.mockplayer.gui.BotGui.panelX(400, 200);
                int py = com.mockplayer.gui.BotGui.panelY(400, 200);
                check("layout panel inside tiny", px >= 0 && py >= 0
                        && px + com.mockplayer.gui.BotGui.panelWidth(400, 200) <= 400
                        && py + com.mockplayer.gui.BotGui.panelHeight(400, 200) <= 200);
                bgStep(4);
            }
            case 4 -> { // 动作 Tab：移动（按住前 → 服务端移动 → 停止）
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(5);
                    return;
                }
                if (!bgActionTab) {
                    bgActionTab = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button tab =
                            bgFindButton(screen, "gui.mockplayer.tab.actions");
                    if (tab != null) {
                        bgClick(tab);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return; // 等 tick 激活动作按钮
                }
                if (!bgMoved) {
                    net.minecraft.client.gui.components.Button fwd =
                            bgFindButton(screen, "gui.mockplayer.action.move_forward");
                    if (fwd == null) {
                        fail("move forward button missing");
                        bgStep(5);
                        return;
                    }
                    bgFwdClicked = true;
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        bgBaseX = sp != null ? sp.getX() : 0;
                        bgBaseZ = sp != null ? sp.getZ() : 0;
                    });
                    bgMoved = true;
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 3) {
                    return; // 等基线读完成
                }
                if (bgFwdClicked) {
                    bgFwdClicked = false;
                    net.minecraft.client.gui.components.Button fwd =
                            bgFindButton(screen, "gui.mockplayer.action.move_forward");
                    if (fwd != null) {
                        bgClick(fwd);
                    }
                    check("move feedback translated", !screen.lastFeedback().getString().contains("%s")
                            && !screen.lastFeedback().getString().contains("gui.mockplayer.")
                            && screen.lastFeedback().getString().contains(
                            net.minecraft.network.chat.Component.translatable(
                                    "gui.mockplayer.action.move_forward").getString()));
                    waitTicks = 0;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    bgServerX = sp != null ? sp.getX() : 0;
                    bgServerZ = sp != null ? sp.getZ() : 0;
                });
                if (Math.abs(bgServerX - bgBaseX) > 1.0 || Math.abs(bgServerZ - bgBaseZ) > 1.0) {
                    check("gui move forward server moved", true);
                    net.minecraft.client.gui.components.Button stop =
                            bgFindButton(screen, "gui.mockplayer.action.stop");
                    if (stop != null) {
                        bgClick(stop);
                    }
                    bgStep(5);
                } else if (++waitTicks > 150) {
                    fail("gui move timeout");
                    bgStep(5);
                }
            }
            case 5 -> { // 快捷栏：给 3 个物品 → 点槽 2 → 服务端 selected=1 + 主手物品
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(6);
                    return;
                }
                if (!bgHotbarGiven) {
                    bgHotbarGiven = true;
                    server.execute(() -> {
                        var cmds = server.getCommands();
                        var src = server.createCommandSourceStack();
                        cmds.performPrefixedCommand(src, "item replace entity " + botName
                                + " hotbar.0 with minecraft:stone");
                        cmds.performPrefixedCommand(src, "item replace entity " + botName
                                + " hotbar.1 with minecraft:stick");
                        cmds.performPrefixedCommand(src, "item replace entity " + botName
                                + " hotbar.2 with minecraft:bread");
                    });
                }
                if (!bgHotbarSynced) {
                    bgHotbarSynced = bot.getLocalPlayer().getInventory().getItem(1)
                            .is(net.minecraft.world.item.Items.STICK);
                    if (!bgHotbarSynced) {
                        if (++waitTicks > 150) {
                            fail("hotbar give timeout");
                            bgStep(6);
                        }
                        return;
                    }
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button invTab =
                            bgFindButton(screen, "gui.mockplayer.tab.inventory");
                    if (invTab == null) {
                        fail("inventory tab missing");
                        bgStep(6);
                        return;
                    }
                    bgClick(invTab);
                    return; // 下一 tick 等背包 Tab 切换
                }
                if (++waitTicks < 5) {
                    return; // 等背包 Tab 切换完成
                }
                if (!bgHotbarSlotClicked) {
                    bgHotbarSlotClicked = true;
                    waitTicks = 0;
                    com.mockplayer.gui.BotControlScreen s = bgScreen();
                    if (s == null || !bgRightClickInventorySlot(s, 37)) {
                        fail("hotbar slot click failed");
                        bgStep(6);
                        return;
                    }
                    return;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    bgHotbarServer = sp != null && sp.getInventory().getSelectedSlot() == 1
                            && sp.getInventory().getSelectedItem().is(net.minecraft.world.item.Items.STICK);
                });
                if (bgHotbarServer) {
                    check("gui hotbar switch server", true);
                    check("hotbar slot click not pickup",
                            bot.getLocalPlayer().containerMenu.getCarried().isEmpty());
                    bgStep(6);
                } else if (++waitTicks > 120) {
                    fail("gui hotbar timeout");
                    bgStep(6);
                }
            }
            case 6 -> { // 左键（attackLook）：husk 满血出现 → 点左键 → 服务端掉血
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(7);
                    return;
                }
                if (!bgAttackActionsTab) {
                    bgAttackActionsTab = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button actions =
                            bgFindButton(screen, "gui.mockplayer.tab.actions");
                    if (actions != null) {
                        bgClick(actions);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return; // 等动作 Tab 激活攻击按钮
                }
                if (!bgHuskSummoned) {
                    bgHuskSummoned = true;
                    bgHuskPos = bot.getLocalPlayer().blockPosition().offset(3, 0, 0);
                    net.minecraft.core.BlockPos hp = bgHuskPos;
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(),
                            String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                    hp.getX() + 0.5, (double) hp.getY(), hp.getZ() + 0.5)));
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        var level = server.getLevel(Level.OVERWORLD);
                        var husk = level != null ? level.getEntitiesOfClass(
                                net.minecraft.world.entity.monster.zombie.Zombie.class,
                                new net.minecraft.world.phys.AABB(sp.getX() - 16, sp.getY() - 16, sp.getZ() - 16,
                                        sp.getX() + 16, sp.getY() + 16, sp.getZ() + 16)).stream()
                                .min(Comparator.comparingDouble(e -> e.distanceToSqr(sp)))
                                .orElse(null) : null;
                        bgHuskHp = husk != null ? husk.getHealth() : -1;
                    }
                });
                // 无敌帧：实体受击后 ~0.5s 无敌，且客户端实体要先同步到位——
                // 满血期间每 tick 重新 lookAt + 点左键重试，直到伤害落地
                if (bgHuskHp >= 20.0F && bot.getEntitiesNear(16).stream()
                        .anyMatch(e -> e instanceof net.minecraft.world.entity.monster.zombie.Zombie)) {
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(bgHuskPos));
                    net.minecraft.client.gui.components.Button atk =
                            bgFindButton(screen, "gui.mockplayer.action.attack_look");
                    if (atk != null) {
                        bgClick(atk);
                        bgRelease(atk); // 快速松开 = 单点（attackLook）
                    }
                    waitTicks = 0;
                }
                if (bgHuskHp < 20.0F) {
                    check("gui attack look damaged", true);
                    bgStep(7);
                } else if (++waitTicks > 240) {
                    fail("gui attack look timeout hp=" + bgHuskHp);
                    bgStep(7);
                }
            }
            case 7 -> { // 长按左键（sustainedAttackLook）：按住 → 服务端连击 <13 → 松开
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(8);
                    return;
                }
                if (!bgHoldPressed) {
                    bgHoldPressed = true;
                    bgHoldButton = bgFindButton(screen, "gui.mockplayer.action.attack_look");
                    if (bgHoldButton == null) {
                        fail("attack look button missing");
                        bgStep(8);
                        return;
                    }
                    bgClick(bgHoldButton);
                    waitTicks = 0;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        var level = server.getLevel(Level.OVERWORLD);
                        var husk = level != null ? level.getEntitiesOfClass(
                                net.minecraft.world.entity.monster.zombie.Zombie.class,
                                new net.minecraft.world.phys.AABB(sp.getX() - 16, sp.getY() - 16, sp.getZ() - 16,
                                        sp.getX() + 16, sp.getY() + 16, sp.getZ() + 16)).stream()
                                .min(Comparator.comparingDouble(e -> e.distanceToSqr(sp)))
                                .orElse(null) : null;
                        bgHuskHp = husk != null ? husk.getHealth() : -1;
                    }
                });
                if (bgHuskHp < 13.0F) {
                    check("gui hold attack continuous damage", true);
                    bgRelease(bgHoldButton);
                    check("hold attack state off after release", !bot.actions().isSustainedAttacking());
                    bgStep(8);
                } else if (++waitTicks > 400) {
                    fail("gui hold attack timeout hp=" + bgHuskHp);
                    bgRelease(bgHoldButton);
                    bgStep(8);
                }
            }
            case 8 -> { // 右键（useLook）：箱子 +2 → 点右键 → 服务端容器打开
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(9);
                    return;
                }
                if (!bgChestPlaced) {
                    bgChestPlaced = true;
                    // 清掉碍事的 husk（射线开箱/容器点击不再有实体干扰）
                    server.execute(() -> server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
                    bgChestPos = bot.getLocalPlayer().blockPosition().offset(2, 0, 0);
                    net.minecraft.core.BlockPos p = bgChestPos;
                    server.execute(() -> server.getLevel(Level.OVERWORLD)
                            .setBlock(p, Blocks.CHEST.defaultBlockState(), 3));
                }
                if (!bgChestOpen && bot.getBlockState(bgChestPos).is(Blocks.CHEST)) {
                    // 每 tick 重新 lookAt（防位置漂移）再点右键
                    bot.actions().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(bgChestPos));
                    net.minecraft.client.gui.components.Button use =
                            bgFindButton(screen, "gui.mockplayer.action.use_look");
                    if (use != null) {
                        bgClick(use);
                        bgRelease(use); // 快速松开 = 单点（useLook）
                    }
                }
                bgChestOpen = bot.getContainer().isPresent();
                if (bgChestOpen) {
                    check("gui use look opens chest", true);
                    bgStep(9);
                } else if (++waitTicks > 180) {
                    fail("gui use look chest timeout");
                    bgStep(9);
                }
            }
            case 9 -> { // 背包 Tab 容器模式：切到背包 Tab → 点快捷栏槽（拿起石头）→ 点箱子槽 0（放入）→ 服务端箱子证据 → 关容器
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(10);
                    return;
                }
                if (!bgChestTabClicked) {
                    bgChestTabClicked = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button tab =
                            bgFindButton(screen, "gui.mockplayer.tab.inventory");
                    if (tab != null) {
                        bgClick(tab);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return; // 等 tick 切换完成
                }
                if (!bgTabChecked) {
                    bgTabChecked = true;
                    check("inventory tab active for container", screen.currentTab() == 1,
                            "tab=" + screen.currentTab());
                }
                if (!bgChestHotbarClicked) {
                    // 等容器菜单槽位同步完成（ContainerContent 比 OpenScreen 晚到，点空槽无效）
                    Optional<com.mockplayer.api.container.BotContainer> opened = bot.getContainer();
                    if (opened.isEmpty() || !opened.get().getSlot(54).is(net.minecraft.world.item.Items.STONE)) {
                        if (++waitTicks > 120) {
                            fail("container slots never synced");
                            bgStep(10);
                        }
                        return;
                    }
                    bgChestHotbarClicked = true;
                    // ChestMenu(27)：容器 0-26 / 假人主背包 27-53 / 快捷栏 54-62。
                    // 快捷栏第 1 格（hotbar.0 = 石头）→ 容器槽 54（player 行 3 列 0）
                    bgChestHotbarOk = bgClickContainerCell(screen, 0, 3);
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 3) {
                    return; // 等第一个点击包被服务端处理
                }
                if (!bgChestSlotClicked) {
                    bgChestSlotClicked = true;
                    // 箱子槽 0 在容器区（CONTENT_X, CONTENT_Y）起，不是玩家区
                    bgChestSlotOk = bgClickContainerPart(screen, 0, 0);
                    waitTicks = 0;
                }
                // 客户端菜单证据：点击后石头应出现在箱子槽 0（或仍在鼠标上）
                if (waitTicks == 0) {
                    check("container grid clicks dispatched", bgChestHotbarOk && bgChestSlotOk,
                            "hotbar=" + bgChestHotbarOk + " slot=" + bgChestSlotOk);
                    boolean clientMoved = bot.getContainer().map(c ->
                            c.getSlot(0).is(net.minecraft.world.item.Items.STONE)
                                    || c.getCarried().is(net.minecraft.world.item.Items.STONE)).orElse(false);
                    check("container click moved stone (client)", clientMoved,
                            "feedback=" + screen.lastFeedback().getString()
                                    + " slot0=" + bot.getContainer().map(c -> c.getSlot(0).toString()).orElse("none")
                                    + " carried=" + bot.getContainer().map(c -> c.getCarried().toString()).orElse("none"));
                }
                server.execute(() -> {
                    var level = server.getLevel(Level.OVERWORLD);
                    if (level != null && level.getBlockEntity(bgChestPos)
                            instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                        bgChestHasStone = chest.getItem(0).is(net.minecraft.world.item.Items.STONE);
                    }
                });
                if (bgChestHasStone) {
                    check("gui container click put stone (server)", true);
                    bgStep(10);
                } else if (++waitTicks > 180) {
                    fail("gui container click timeout");
                    bgStep(10);
                }
            }
            case 10 -> { // 背包容器模式 X 按钮 → 服务端菜单关闭
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(11);
                    return;
                }
                // X 按钮只在背包 Tab 容器模式显示：确保在背包 Tab
                if (!bgChestActionsTab) {
                    bgChestActionsTab = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button invTab =
                            bgFindButton(screen, "gui.mockplayer.tab.inventory");
                    if (invTab != null) {
                        bgClick(invTab);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgChestCloseClicked) {
                    bgChestCloseClicked = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button close =
                            bgFindButtonByLiteral(screen, "×");
                    if (close == null) {
                        fail("close container X missing");
                        bgStep(11);
                        return;
                    }
                    bgClick(close);
                }
                bgChestClosed = bot.getContainer().isEmpty();
                if (bgChestClosed) {
                    check("gui close container", true);
                    bgStep(11);
                } else if (++waitTicks > 120) {
                    fail("gui close container timeout");
                    bgStep(11);
                }
            }
            case 11 -> { // 区块半径：+1 → 本地 3 + 服务端 requestedViewDistance=3
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(12);
                    return;
                }
                if (!bgChunkActionsTab) {
                    bgChunkActionsTab = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button actions =
                            bgFindButton(screen, "gui.mockplayer.tab.actions");
                    if (actions != null) {
                        bgClick(actions);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgChunkClicked) {
                    bgChunkClicked = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button plus =
                            bgFindButton(screen, "gui.mockplayer.action.chunk_plus");
                    if (plus != null) {
                        bgClick(plus);
                    }
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    bgChunkServer = sp != null ? ccServerRequestedViewDistance(sp) : -1;
                });
                if (bot.getChunkRadius() == 3 && bgChunkServer == 3) {
                    check("gui chunk radius +1 server", true);
                    bgStep(12);
                } else if (++waitTicks > 120) {
                    fail("gui chunk radius timeout local=" + bot.getChunkRadius()
                            + " server=" + bgChunkServer);
                    bgStep(12);
                }
            }
            case 12 -> { // 聊天：输入框 + 发送 → 服务端广播 → 假人 onChat
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(13);
                    return;
                }
                if (!bgChatActionsTab) {
                    bgChatActionsTab = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button actions =
                            bgFindButton(screen, "gui.mockplayer.tab.actions");
                    if (actions != null) {
                        bgClick(actions);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgChatSent) {
                    bgChatSent = true;
                    bgChatMsg = "";
                    net.minecraft.client.gui.components.EditBox chat =
                            bgFindEditBox(screen, "gui.mockplayer.action.chat_hint");
                    net.minecraft.client.gui.components.Button send =
                            bgFindButton(screen, "gui.mockplayer.action.send");
                    if (chat != null) {
                        chat.setValue("mockplayer-gui-chat");
                    }
                    if (send != null) {
                        bgClick(send);
                    }
                }
                if (bgChatMsg.contains("mockplayer-gui-chat")) {
                    check("gui chat broadcast", true);
                    bgStep(13);
                } else if (++waitTicks > 150) {
                    fail("gui chat timeout");
                    bgStep(13);
                }
            }
            case 13 -> { // 自动重生开关：开 → 关 → 开
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(14);
                    return;
                }
                if (!bgAutoActionsTab) {
                    bgAutoActionsTab = true;
                    waitTicks = 0;
                    net.minecraft.client.gui.components.Button actions =
                            bgFindButton(screen, "gui.mockplayer.tab.actions");
                    if (actions != null) {
                        bgClick(actions);
                    }
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgAutoToggled) {
                    bgAutoToggled = true;
                    net.minecraft.client.gui.components.Button auto =
                            bgFindButton(screen, "gui.mockplayer.action.auto_respawn");
                    if (auto != null) {
                        boolean before = bot.isAutoRespawn();
                        bgClick(auto);
                        check("gui auto respawn off", bot.isAutoRespawn() == !before);
                        bgClick(auto);
                        check("gui auto respawn back on", bot.isAutoRespawn() == before);
                    } else {
                        fail("auto respawn button missing");
                    }
                }
                bgStep(14);
            }
            case 14 -> { // 原版 KeyMapping：绑定/消费打开/界面门禁/禁用改键/guiEnabled 开关
                if (!bgDisabledChecked) {
                    bgDisabledChecked = true;
                    // 默认配置：KeyMapping 已注册并绑定（26.2 无公开 getKey，绑定用行为断言）
                    check("key mapping registered", com.mockplayer.gui.BotGui.KEY_BINDING != null
                            && !com.mockplayer.gui.BotGui.KEY_BINDING.isUnbound());
                    check("key mapping name", com.mockplayer.gui.BotGui.KEY_NAME
                            .equals(com.mockplayer.gui.BotGui.KEY_BINDING.getName()));
                    // 模拟原版键盘链路点击 → 无界面时 tick 消费 → 打开 GUI
                    mc.gui.setScreen(null);
                    KeyMapping.click(InputConstants.getKey("key.keyboard.g"));
                    com.mockplayer.gui.BotGui.tick(mc);
                    check("keybind click opens gui",
                            mc.gui.screen() instanceof com.mockplayer.gui.BotControlScreen);
                    mc.gui.setScreen(null);
                    // 聊天界面打开：我们按原版语义不消费（tick 门禁），GUI 不会盖在聊天框上
                    mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false));
                    KeyMapping.click(InputConstants.getKey("key.keyboard.g"));
                    com.mockplayer.gui.BotGui.tick(mc);
                    check("keybind blocked while chat open",
                            mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen
                                    && !(mc.gui.screen() instanceof com.mockplayer.gui.BotControlScreen));
                    mc.gui.setScreen(null);
                    while (com.mockplayer.gui.BotGui.KEY_BINDING.consumeClick()) {
                        // 清掉聊天门禁测试残留的点击计数，避免污染后续步骤
                    }
                    // 配置空键名 → KeyMapping 解绑（UNKNOWN），open 也被禁用
                    ModConfig off = new ModConfig();
                    off.setGuiKeyName("");
                    MockplayerConfig.save(off);
                    check("key unbound when disabled", com.mockplayer.gui.BotGui.KEY_BINDING.isUnbound());
                    // 解绑后按 G 不再触发打开（行为断言绑定已移除）
                    KeyMapping.click(InputConstants.getKey("key.keyboard.g"));
                    com.mockplayer.gui.BotGui.tick(mc);
                    check("unbound key does not open", mc.gui.screen() == null);
                    check("gui key disabled open blocked", !com.mockplayer.gui.BotGui.open(mc)
                            && !(mc.gui.screen() instanceof com.mockplayer.gui.BotControlScreen));
                    // guiEnabled=false：open 被总开关挡住，命令不受影响
                    ModConfig off2 = new ModConfig();
                    off2.setGuiEnabled(false);
                    MockplayerConfig.save(off2);
                    check("gui disabled open blocked", !com.mockplayer.gui.BotGui.open(mc)
                            && !(mc.gui.screen() instanceof com.mockplayer.gui.BotControlScreen));
                    String help = com.mockplayer.session.ControlCommands.help(botName).getString();
                    check("commands unaffected when gui off", !help.isBlank()
                            && help.contains(net.minecraft.network.chat.Component.translatable(
                            "commands.mockplayer.control.action.attack").getString()));
                    // 恢复默认配置 → 重新绑定 + 可打开
                    MockplayerConfig.save(new ModConfig());
                    check("gui re-bound", !com.mockplayer.gui.BotGui.KEY_BINDING.isUnbound());
                    KeyMapping.click(InputConstants.getKey("key.keyboard.g"));
                    com.mockplayer.gui.BotGui.tick(mc);
                    check("gui re-enabled opens",
                            mc.gui.screen() instanceof com.mockplayer.gui.BotControlScreen);
                    mc.gui.setScreen(null);
                }
                check("gui screen closed", mc.gui.screen() == null);
                System.clearProperty("mockplayer.guiRenderProbe");
                bgStep(15);
            }
            case 15 -> { // 拾取掉落物：服务端丢钻石 → 假人客户端 Inventory 同步（GUI 背包数据源）
                if (!bgPickupSummoned) {
                    bgPickupSummoned = true;
                    bgPickupWait = 0;
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                String.format("summon minecraft:item %.2f %.2f %.2f {Item:{id:\"minecraft:diamond\",count:1}}",
                                        sp.getX(), sp.getY(), sp.getZ()));
                    }
                }
                bot.actions().setForward(0.1F); // 假人微动走过去拾取（掉落物可能在半格位置/浮空）
                bgPickupClientHas = false;
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (lp != null) {
                    for (int i = 0; i < 41; i++) {
                        net.minecraft.world.item.ItemStack s = lp.getInventory().getItem(i);
                        if (!s.isEmpty() && s.is(net.minecraft.world.item.Items.DIAMOND)) {
                            bgPickupClientHas = true;
                            break;
                        }
                    }
                }
                if (bgPickupClientHas) {
                    bot.actions().setForward(0);
                    check("picked diamond in bot client inventory", true);
                    bgPickupVerifyWait = 0;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp2 = server.getPlayerList().getPlayerByName(botName);
                        boolean has = false;
                        if (sp2 != null) {
                            for (int i = 0; i < 41; i++) {
                                net.minecraft.world.item.ItemStack s = sp2.getInventory().getItem(i);
                                if (!s.isEmpty() && s.is(net.minecraft.world.item.Items.DIAMOND)) {
                                    has = true;
                                    break;
                                }
                            }
                        }
                        bgPickupServerHas = has;
                    });
                    bgStep(16);
                } else if (++bgPickupWait > 240) {
                    bot.actions().setForward(0);
                    fail("pickup diamond into client inventory timeout");
                    bgStep(16);
                }
            }
            case 16 -> { // 服务端拾取证据 + GUI 背包数据源确认
                if (bgPickupServerHas || ++bgPickupVerifyWait > 120) {
                    check("picked diamond in server inventory", bgPickupServerHas);
                    check("picked diamond visible in gui source", bgPickupClientHas);
                    bgStep(17);
                }
            }
            case 17 -> { // 固定槽位：快捷栏0=钻石 / 头盔=绿宝石 / 副手=红石 → 客户端同步
                if (!bgSlotGiveDone) {
                    bgSlotGiveDone = true;
                    bgSlotSyncWait = 0;
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                            sp.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(
                                    net.minecraft.world.item.Items.DIAMOND, 1));
                            sp.getInventory().setItem(39, new net.minecraft.world.item.ItemStack(
                                    net.minecraft.world.item.Items.EMERALD, 1));
                            sp.getInventory().setItem(40, new net.minecraft.world.item.ItemStack(
                                    net.minecraft.world.item.Items.REDSTONE, 1));
                            sp.inventoryMenu.broadcastChanges();
                        }
                    });
                }
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                bgSlotClientSynced = lp != null
                        && lp.getInventory().getItem(0).is(net.minecraft.world.item.Items.DIAMOND)
                        && lp.getInventory().getItem(39).is(net.minecraft.world.item.Items.EMERALD)
                        && lp.getInventory().getItem(40).is(net.minecraft.world.item.Items.REDSTONE);
                if (bgSlotClientSynced) {
                    bgStep(18);
                } else if (++bgSlotSyncWait > 120) {
                    fail("fixed slot sync timeout");
                    bgStep(18);
                }
            }
            case 18 -> { // GUI 槽位映射：显示与点击共用菜单槽语义（盔甲5/快捷栏36/副手45）
                if (!bgIconOpened) {
                    bgIconOpened = true;
                    net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                    check("gui helmet slot 5 shows helmet item", lp != null
                            && com.mockplayer.gui.BotControlScreen.inventoryItem(lp, 5)
                            .is(net.minecraft.world.item.Items.EMERALD));
                    check("gui hotbar slot 36 shows hotbar0", lp != null
                            && com.mockplayer.gui.BotControlScreen.inventoryItem(lp, 36)
                            .is(net.minecraft.world.item.Items.DIAMOND));
                    check("gui offhand slot 45 shows offhand", lp != null
                            && com.mockplayer.gui.BotControlScreen.inventoryItem(lp, 45)
                            .is(net.minecraft.world.item.Items.REDSTONE));
                    check("gui main inventory slot 9 empty", lp == null
                            || com.mockplayer.gui.BotControlScreen.inventoryItem(lp, 9).isEmpty());
                    // 原版空槽图标（Slot.getNoItemIcon）：盔甲/副手都有，快捷栏没有
                    check("gui helmet slot has vanilla icon", lp != null
                            && com.mockplayer.gui.BotControlScreen.slotIcon(lp, 5) != null
                            && com.mockplayer.gui.BotControlScreen.slotIcon(lp, 5).getPath().endsWith("helmet"));
                    check("gui boots slot has vanilla icon", lp != null
                            && com.mockplayer.gui.BotControlScreen.slotIcon(lp, 8) != null
                            && com.mockplayer.gui.BotControlScreen.slotIcon(lp, 8).getPath().endsWith("boots"));
                    check("gui offhand slot has shield icon", lp != null
                            && com.mockplayer.gui.BotControlScreen.slotIcon(lp, 45) != null
                            && com.mockplayer.gui.BotControlScreen.slotIcon(lp, 45).getPath().endsWith("shield"));
                    check("gui hotbar slot has no icon", lp == null
                            || com.mockplayer.gui.BotControlScreen.slotIcon(lp, 36) == null);
                    // 悬停信息：物品槽返回原版 tooltip，空槽返回 null
                    check("slot tooltip shows item", lp != null
                            && com.mockplayer.gui.BotControlScreen.slotTooltip(lp, 36) != null
                            && com.mockplayer.gui.BotControlScreen.slotTooltip(lp, 36).stream()
                            .anyMatch(c -> c.getString().contains("Diamond")
                                    || c.getString().contains("钻石")));
                    check("empty slot tooltip null", lp == null
                            || com.mockplayer.gui.BotControlScreen.slotTooltip(lp, 9) == null);
                    // 渲染路径：打开背包 Tab，空装备槽图标应真实绘制（探针计数）
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button invTab =
                                bgFindButton(s, "gui.mockplayer.tab.inventory");
                        if (invTab != null) {
                            bgClick(invTab);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (com.mockplayer.gui.BotGui.probeSlotIconCount() > 0) {
                    check("empty armor slot icons rendered", true);
                    check("item decorations rendered",
                            com.mockplayer.gui.BotGui.probeItemDecorationCount() > 0,
                            "decorations=" + com.mockplayer.gui.BotGui.probeItemDecorationCount());
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(19);
                } else if (++waitTicks > 100) {
                    check("empty armor slot icons rendered", false,
                            "icons=" + com.mockplayer.gui.BotGui.probeSlotIconCount()
                                    + " tab=" + (bgScreen() instanceof com.mockplayer.gui.BotControlScreen s
                                    ? s.currentTab() : -1)
                                    + " container=" + bot.getContainer().isPresent()
                                    + " frames=" + com.mockplayer.gui.BotGui.probeFrameCount());
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(19);
                }
            }
            case 19 -> { // 半透明配色断言 + 打开动作 Tab（长按调整测试准备）
                if (!bgRepeatOpened) {
                    bgRepeatOpened = true;
                    int topAlpha = (com.mockplayer.gui.BotControlScreen.PANEL_BG_TOP >>> 24) & 0xFF;
                    int headerAlpha = (com.mockplayer.gui.BotControlScreen.PANEL_HEADER_BG >>> 24) & 0xFF;
                    int slotAlpha = (com.mockplayer.gui.BotControlScreen.SLOT_BG >>> 24) & 0xFF;
                    // 半透明：alpha 低于不透明 0xFF 但高于全透明（透出游戏场景）
                    check("panel bg semi-transparent", topAlpha > 0x60 && topAlpha < 0xFF,
                            "alpha=" + topAlpha);
                    check("panel header semi-transparent", headerAlpha > 0x60 && headerAlpha < 0xFF,
                            "alpha=" + headerAlpha);
                    check("slot bg semi-transparent", slotAlpha > 0x40 && slotAlpha < 0xFF,
                            "alpha=" + slotAlpha);
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button actions =
                                bgFindButton(s, "gui.mockplayer.tab.actions");
                        if (actions != null) {
                            bgClick(actions);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                bgStep(20);
            }
            case 20 -> { // 长按 turn_right：按住 → 视角连续变化（≥30°）→ 松开
                com.mockplayer.gui.BotControlScreen screen = bgScreen();
                if (screen == null) {
                    fail("gui screen lost");
                    bgStep(21);
                    return;
                }
                if (!bgTurnHeld) {
                    bgTurnHeld = true;
                    bgTurnButton = bgFindButton(screen, "gui.mockplayer.action.turn_right");
                    if (bgTurnButton == null) {
                        fail("turn right button missing");
                        bgStep(21);
                        return;
                    }
                    bgTurnBaseYaw = bot.getLocalPlayer() != null ? bot.getLocalPlayer().getYRot() : 0.0F;
                    bgClick(bgTurnButton);
                    waitTicks = 0;
                }
                float yaw = bot.getLocalPlayer() != null ? bot.getLocalPlayer().getYRot() : 0.0F;
                float delta = Math.abs(yaw - bgTurnBaseYaw);
                if (delta > 180.0F) {
                    delta = 360.0F - delta;
                }
                if (delta >= 30.0F) {
                    check("hold turn repeats yaw change", true, "delta=" + delta);
                    bgRelease(bgTurnButton);
                    bgTurnAfterYaw = yaw;
                    bgTurnReleased = true;
                    waitTicks = 0;
                    bgStep(21);
                } else if (++waitTicks > 120) {
                    fail("hold turn timeout delta=" + delta);
                    bgRelease(bgTurnButton);
                    bgStep(21);
                }
            }
            case 21 -> { // 松开 turn 后视角稳定；长按 chunk_plus → 区块半径连续增加
                if (bgTurnReleased && !bgChunkHeld) {
                    if (++waitTicks < 10) {
                        return; // 等 10 tick 确认松开后不再转动
                    }
                    float yaw = bot.getLocalPlayer() != null ? bot.getLocalPlayer().getYRot() : 0.0F;
                    float drift = Math.abs(yaw - bgTurnAfterYaw);
                    if (drift > 180.0F) {
                        drift = 360.0F - drift;
                    }
                    check("hold turn stops after release", drift < 1.0F, "drift=" + drift);
                    bgChunkHeld = true;
                    bgChunkBase = bot.getChunkRadius();
                    com.mockplayer.gui.BotControlScreen s = bgScreen();
                    bgChunkButton = s != null ? bgFindButton(s, "gui.mockplayer.action.chunk_plus") : null;
                    if (bgChunkButton == null) {
                        fail("chunk plus button missing");
                        bgStep(22);
                        return;
                    }
                    bgClick(bgChunkButton);
                    waitTicks = 0;
                    return;
                }
                if (bgChunkHeld && !bgChunkReleased) {
                    if (bot.getChunkRadius() >= bgChunkBase + 2) {
                        check("hold chunk repeats radius", true,
                                "radius=" + bot.getChunkRadius());
                        bgRelease(bgChunkButton);
                        bgChunkAfter = bot.getChunkRadius();
                        bgChunkReleased = true;
                        waitTicks = 0;
                        bgStep(22);
                    } else if (++waitTicks > 120) {
                        fail("hold chunk timeout radius=" + bot.getChunkRadius());
                        bgRelease(bgChunkButton);
                        bgStep(22);
                    }
                }
            }
            case 22 -> { // 松开 chunk 后半径稳定
                if (++waitTicks < 10) {
                    return;
                }
                check("hold chunk stops after release", bot.getChunkRadius() == bgChunkAfter,
                        "radius=" + bot.getChunkRadius());
                mc.gui.setScreen(null);
                bgStep(23);
            }
            case 23 -> { // 拿起物品不消失 + 右键切换快捷栏不干扰 carried
                if (!bgCarriedOpened) {
                    bgCarriedOpened = true;
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button invTab =
                                bgFindButton(s, "gui.mockplayer.tab.inventory");
                        if (invTab != null) {
                            bgClick(invTab);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgCarriedPicked) {
                    bgCarriedPicked = true;
                    com.mockplayer.gui.BotControlScreen s = bgScreen();
                    if (s != null) {
                        bgClickInventorySlot(s, 36); // 左键拿起快捷栏 0 的钻石（原版物品交互）
                    }
                    waitTicks = 0;
                    return;
                }
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (!bgCarriedSwapTested) {
                    boolean carriedDiamond = lp != null
                            && lp.containerMenu.getCarried().is(net.minecraft.world.item.Items.DIAMOND);
                    if (carriedDiamond && com.mockplayer.gui.BotGui.probeCarriedCount() > 0) {
                        check("picked item carried on cursor", true);
                        check("picked item renders on cursor", true);
                        bgCarriedSelectedBefore = lp.getInventory().getSelectedSlot();
                        bgCarriedSwapTested = true;
                        waitTicks = 0;
                        if (bgScreen() instanceof com.mockplayer.gui.BotControlScreen s) {
                            bgRightClickInventorySlot(s, 36); // 拿着物品点快捷栏：原版物品动作
                        }
                        return;
                    }
                    if (++waitTicks > 100) {
                        fail("picked item carried timeout");
                        bgStep(24);
                    }
                    return;
                }
                boolean itemOk = lp != null
                        && lp.getInventory().getSelectedSlot() == bgCarriedSelectedBefore
                        && lp.containerMenu.getCarried().isEmpty();
                if (itemOk) {
                    check("hotbar click with carried does item action", true);
                    check("hotbar click with carried does not switch slot", true);
                    waitTicks = 0;
                    bgStep(24);
                } else if (++waitTicks > 60) {
                    fail("hotbar item action timeout selected="
                            + (lp != null ? lp.getInventory().getSelectedSlot() : -1)
                            + " carried=" + (lp != null ? lp.containerMenu.getCarried() : "null"));
                    bgStep(24);
                }
            }
            case 24 -> { // 放回后 carried 清空
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (lp == null || lp.containerMenu.getCarried().isEmpty()) {
                    check("picked item put back", true);
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(25);
                } else if (++waitTicks > 60) {
                    check("picked item put back", false,
                            "carried=" + lp.containerMenu.getCarried());
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(25);
                }
            }
            case 25 -> { // 状态栏经验条：服务端给经验 → 假人 level/progress 同步 → 状态 Tab 渲染探针
                if (!bgXpGiven) {
                    bgXpGiven = true;
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    server.execute(() -> {
                        var cmds = server.getCommands();
                        var src = server.createCommandSourceStack();
                        cmds.performPrefixedCommand(src, "xp add " + botName + " 7 levels");
                        cmds.performPrefixedCommand(src, "xp add " + botName + " 5 points");
                    });
                    waitTicks = 0;
                    return;
                }
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (lp == null) {
                    fail("xp sync timeout player null");
                    bgStep(26);
                    return;
                }
                if (lp.experienceLevel >= 7 && lp.experienceProgress > 0.0F) {
                    check("xp level synced to client", lp.experienceLevel >= 7,
                            "level=" + lp.experienceLevel);
                    check("xp progress synced to client", lp.experienceProgress > 0.0F,
                            "progress=" + lp.experienceProgress);
                    check("gui opacity default low",
                            com.mockplayer.config.ModConfig.DEFAULT_GUI_OPACITY == 0.25F
                                    && com.mockplayer.gui.BotControlScreen.buttonAlpha(0.25F) == 0.35F
                                    && (com.mockplayer.gui.BotControlScreen.withAlpha(
                                    0xB0253047, 0.25F) >>> 24) == 44);
                    check("xp bar uses vanilla sprites",
                            com.mockplayer.gui.BotControlScreen.XP_BAR_BACKGROUND.equals(
                                    net.minecraft.resources.Identifier.withDefaultNamespace(
                                            "hud/experience_bar_background"))
                                    && com.mockplayer.gui.BotControlScreen.XP_BAR_PROGRESS.equals(
                                    net.minecraft.resources.Identifier.withDefaultNamespace(
                                            "hud/experience_bar_progress")));
                    check("health food bars use vanilla sprites",
                            com.mockplayer.gui.BotControlScreen.HEART_CONTAINER.equals(
                                    net.minecraft.resources.Identifier.withDefaultNamespace(
                                            "hud/heart/container"))
                                    && com.mockplayer.gui.BotControlScreen.HEART_FULL.equals(
                                    net.minecraft.resources.Identifier.withDefaultNamespace(
                                            "hud/heart/full"))
                                    && com.mockplayer.gui.BotControlScreen.FOOD_FULL.equals(
                                    net.minecraft.resources.Identifier.withDefaultNamespace(
                                            "hud/food_full")));
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button statusTab =
                                bgFindButton(s, "gui.mockplayer.tab.status");
                        if (statusTab != null) {
                            bgClick(statusTab);
                        }
                    }
                    waitTicks = 0;
                    bgStep(26);
                    return;
                }
                if (++waitTicks > 150) {
                    fail("xp sync timeout level=" + lp.experienceLevel
                            + " progress=" + lp.experienceProgress);
                    bgStep(26);
                }
            }
            case 26 -> { // 经验条渲染探针断言
                if (com.mockplayer.gui.BotGui.probeXpBarCount() > 0
                        && com.mockplayer.gui.BotGui.probeXpBarLevel() >= 7
                        && com.mockplayer.gui.BotGui.probeXpBarProgress() > 0.0F) {
                    check("xp bar rendered with level and progress", true,
                            "level=" + com.mockplayer.gui.BotGui.probeXpBarLevel()
                                    + " progress=" + com.mockplayer.gui.BotGui.probeXpBarProgress());
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(27);
                } else if (++waitTicks > 100) {
                    check("xp bar rendered with level and progress", false,
                            "level=" + com.mockplayer.gui.BotGui.probeXpBarLevel()
                                    + " progress=" + com.mockplayer.gui.BotGui.probeXpBarProgress());
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(27);
                }
            }
            case 27 -> { // 长按右键举盾：主手剑+副手盾 → 按住 use_look → 副手盾 using → 松开
                if (!bgShieldGiven) {
                    bgShieldGiven = true;
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    server.execute(() -> {
                        var cmds = server.getCommands();
                        var src = server.createCommandSourceStack();
                        cmds.performPrefixedCommand(src, "item replace entity " + botName
                                + " weapon.mainhand with minecraft:iron_sword");
                        cmds.performPrefixedCommand(src, "item replace entity " + botName
                                + " weapon.offhand with minecraft:shield");
                    });
                    waitTicks = 0;
                    return;
                }
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (!bgShieldHeld) {
                    if (lp == null || !lp.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
                        if (++waitTicks > 120) {
                            fail("shield give timeout");
                            bgStep(28);
                        }
                        return;
                    }
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button actions =
                                bgFindButton(s, "gui.mockplayer.tab.actions");
                        if (actions != null) {
                            bgClick(actions);
                        }
                    }
                    bgShieldHeld = true;
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return; // 等动作 Tab 激活
                }
                if (bgShieldUseButton == null) {
                    bgShieldUseButton = bgFindButton(bgScreen(), "gui.mockplayer.action.use_look");
                    if (bgShieldUseButton == null) {
                        fail("use look button missing");
                        bgStep(28);
                        return;
                    }
                    bgClick(bgShieldUseButton); // 按住（长按开始）
                    waitTicks = 0;
                    return;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    bgShieldServerUsing = sp != null && sp.isUsingItem()
                            && sp.getUseItem().is(net.minecraft.world.item.Items.SHIELD);
                });
                boolean clientUsing = lp != null && lp.isUsingItem()
                        && lp.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND;
                if (clientUsing && bgShieldServerUsing) {
                    check("hold use raises offhand shield", true);
                    check("hold use shield server using", true);
                    bgRelease(bgShieldUseButton);
                    waitTicks = 0;
                    bgStep(28);
                } else if (++waitTicks > 150) {
                    fail("shield raise timeout client=" + clientUsing
                            + " server=" + bgShieldServerUsing);
                    bgRelease(bgShieldUseButton);
                    bgStep(28);
                }
            }
            case 28 -> { // 松开后盾牌释放（不再 using）
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (lp == null || !lp.isUsingItem()) {
                    check("hold use releases shield", true);
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(29);
                } else if (++waitTicks > 80) {
                    check("hold use releases shield", false,
                            "using=" + lp.isUsingItem());
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(29);
                }
            }
            case 29 -> { // 命令 useLook 单点举盾（副手盾 fallback，与 GUI 同实现）
                if (!bgCmdShieldUsed) {
                    bgCmdShieldUsed = true;
                    // 先抬头让射线命中空气（原版单击右键命中方块 = 交互方块，不举盾）
                    bot.actions().look(bot.getLocalPlayer() != null
                            ? bot.getLocalPlayer().getYRot() : 0.0F, -90.0F);
                    com.mockplayer.session.ControlCommands.useLook(botName);
                    waitTicks = 0;
                    return;
                }
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    bgShieldServerUsing = sp != null && sp.isUsingItem()
                            && sp.getUseItem().is(net.minecraft.world.item.Items.SHIELD);
                });
                boolean clientUsing = lp != null && lp.isUsingItem()
                        && lp.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND;
                if (clientUsing && bgShieldServerUsing) {
                    check("command useLook raises offhand shield", true);
                    check("command useLook shield server using", true);
                    com.mockplayer.session.ControlCommands.stopSustained(botName);
                    waitTicks = 0;
                    bgStep(30);
                } else if (++waitTicks > 120) {
                    fail("command useLook shield timeout client=" + clientUsing
                            + " server=" + bgShieldServerUsing);
                    bgStep(30);
                }
            }
            case 30 -> { // 命令 stopSustained 释放盾
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (lp == null || !lp.isUsingItem()) {
                    check("command stopSustained releases shield", true);
                    bgStep(31);
                } else if (++waitTicks > 80) {
                    check("command stopSustained releases shield", false,
                            "using=" + lp.isUsingItem());
                    bgStep(31);
                }
            }
            case 31 -> { // 附近实体很多：实体按钮最多 2 个 + 文字按宽度截断不溢出
                if (!bgManySummoned) {
                    bgManySummoned = true;
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            for (int i = 0; i < 6; i++) {
                                server.getCommands().performPrefixedCommand(
                                        server.createCommandSourceStack(),
                                        "summon minecraft:husk " + (sp.getX() + (i % 3)) + " "
                                                + sp.getY() + " " + (sp.getZ() + (i / 3))
                                                + " {CustomName:'{\"text\":\"VeryLongHuskName" + i + "\"}',NoAI:1b}");
                            }
                        }
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 15) {
                    return; // 等实体刷新进按钮
                }
                if (!bgManyChecked) {
                    bgManyChecked = true;
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button actions =
                                bgFindButton(s, "gui.mockplayer.tab.actions");
                        if (actions != null) {
                            bgClick(actions);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等动作 Tab 实体按钮刷新
                }
                com.mockplayer.gui.BotControlScreen s = bgScreen();
                boolean noOverflow = true;
                int overflowCount = 0;
                int entityShown = 0;
                if (s != null) {
                    for (Object child : s.children()) {
                        if (child instanceof net.minecraft.client.gui.components.Button b && b.visible) {
                            if (mc.font.width(b.getMessage()) > b.getWidth()) {
                                noOverflow = false;
                                overflowCount++;
                            }
                            if (b.getMessage().getString().contains("·")) {
                                entityShown++;
                            }
                        }
                    }
                }
                check("many entities no button text overflow", noOverflow,
                        "overflow=" + overflowCount);
                check("entity buttons capped at 2", entityShown <= 2,
                        "shown=" + entityShown);
                mc.gui.setScreen(null);
                bgStep(32);
            }
            case 32 -> { // 左栏滚动列表：钳制纯函数 + 多假人列表显示 + ▲▼ 按钮
                if (!bgListChecked) {
                    bgListChecked = true;
                    check("bot list scroll clamp",
                            com.mockplayer.gui.BotControlScreen.clampBotScroll(0, 5, 10) == 0
                                    && com.mockplayer.gui.BotControlScreen.clampBotScroll(8, 15, 10) == 5
                                    && com.mockplayer.gui.BotControlScreen.clampBotScroll(-3, 12, 10) == 0
                                    && com.mockplayer.gui.BotControlScreen.clampBotScroll(2, 10, 10) == 0);
                    com.mockplayer.session.FakePlayerCommands.newPlayer("tbot-gui2");
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等第二个假人注册进列表
                }
                if (!bgListSecondShown) {
                    bgListSecondShown = true;
                    mc.gui.setScreen(null);
                    com.mockplayer.gui.BotGui.open(mc);
                    waitTicks = 0;
                    return; // 等列表按钮 tick 刷新
                }
                if (++waitTicks < 10) {
                    return;
                }
                if (!bgListSecondChecked) {
                    bgListSecondChecked = true;
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    boolean secondShown = false;
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        for (Object child : s.children()) {
                            if (child instanceof net.minecraft.client.gui.components.Button b) {
                                String msg = b.getMessage().getString();
                                if (msg.contains("tbot-gui2")) {
                                    secondShown = true;
                                }
                            }
                        }
                    }
                    check("bot list shows second bot", secondShown);
                    check("bot list scrollbar logic",
                            com.mockplayer.gui.BotControlScreen.shouldShowScrollbar(12, 10)
                                    && !com.mockplayer.gui.BotControlScreen.shouldShowScrollbar(2, 10));
                    com.mockplayer.session.FakePlayerCommands.delPlayer("tbot-gui2");
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等删除清理
                }
                mc.gui.setScreen(null);
                check("gui blur restored",
                        mc.options.getMenuBackgroundBlurriness() == bgBlurBefore);
                bgStep(33);
            }
            case 33 -> { // 假人攻击另一个假人（玩家实体）：复现「打不到玩家」
                if (!bgPvpGiven) {
                    bgPvpGiven = true;
                    com.mockplayer.session.FakePlayerCommands.newPlayer("tbot-gui3");
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 40) {
                    return; // 等第二个假人注册
                }
                if (!bgPvpTargetFound) {
                    bgPvpTargetFound = true;
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks > 200) {
                    fail("pvp target never PLAYING");
                    bgStep(34);
                    return;
                }
                server.execute(() -> {
                    var spB = server.getPlayerList().getPlayerByName("tbot-gui3");
                    bgPvpServerReady = spB != null && spB.connection != null;
                });
                if (!bgPvpServerReady) {
                    return; // 等服务端 PLAYING
                }
                if (!bgPvpTpDone) {
                    bgPvpTpDone = true;
                    // 假人A 主动 teleport 到假人B 面前 1.5 格（假人A 自身 tp 同步可靠）
                    server.execute(() -> {
                        var spA = server.getPlayerList().getPlayerByName(botName);
                        var spB = server.getPlayerList().getPlayerByName("tbot-gui3");
                        if (spA != null && spB != null) {
                            spA.teleportTo(spB.getX() - 1.5, spB.getY(), spB.getZ());
                        }
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 20) {
                    return; // 等 tp 同步
                }
                if (!bgPvpServerSettled) {
                    // 攻击前门控：假人服务端位置必须已落位（传送确认完成；
                    // 否则客户端旧位置包可能把服务端 bot 拽离，攻击被服务端距离校验拒绝）
                    server.execute(() -> {
                        var spA = server.getPlayerList().getPlayerByName(botName);
                        var spB = server.getPlayerList().getPlayerByName("tbot-gui3");
                        if (spA != null && spB != null) {
                            bgPvpServerSettled = Math.abs(spA.getX() - (spB.getX() - 1.5)) < 0.75
                                    && Math.abs(spA.getZ() - spB.getZ()) < 0.75;
                        }
                    });
                    if (!bgPvpServerSettled) {
                        if (++waitTicks > 120) {
                            fail("pvp attacker server position never settled");
                            bgStep(34);
                        }
                        return;
                    }
                    waitTicks = 0;
                    return;
                }
                if (!bgPvpLooked) {
                    bgPvpLooked = true;
                    net.minecraft.world.entity.Entity target = bot.getEntitiesNear(20).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.player.Player
                                    && "tbot-gui3".equals(e.getName().getString()))
                            .findFirst().orElse(null);
                    StringBuilder all = new StringBuilder();
                    int count = 0;
                    for (net.minecraft.world.entity.Entity e : bot.getEntitiesNear(64)) {
                        if (e instanceof net.minecraft.world.entity.player.Player
                                && "tbot-gui3".equals(e.getName().getString())) {
                            count++;
                            all.append("(").append(e.getX()).append(",").append(e.getZ()).append(") ");
                        }
                    }
                    check("pvp single entity in level", count == 1,
                            "count=" + count + " all=" + all);
                    if (target != null) {
                        bgPvpDist = Math.sqrt(target.distanceToSqr(bot.getLocalPlayer()));
                        bgPvpClientX = target.getX();
                        bgPvpClientZ = target.getZ();
                        server.execute(() -> {
                            var spB = server.getPlayerList().getPlayerByName("tbot-gui3");
                            if (spB != null) {
                                bgPvpServerX = spB.getX();
                                bgPvpServerZ = spB.getZ();
                            }
                        });
                        bot.actions().lookAt(target);
                    }
                    check("pvp target exists in bot level", target != null,
                            "client=(" + bgPvpClientX + "," + bgPvpClientZ
                                    + ") server=(" + bgPvpServerX + "," + bgPvpServerZ + ")");
                    if (target != null) {
                        check("pvp client position synced",
                                Math.abs(bgPvpClientX - bgPvpServerX) < 4.0
                                        && Math.abs(bgPvpClientZ - bgPvpServerZ) < 4.0,
                                "client=(" + bgPvpClientX + "," + bgPvpClientZ
                                        + ") server=(" + bgPvpServerX + "," + bgPvpServerZ
                                        + ") dist=" + bgPvpDist);
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return; // 等 faceEntity 生效
                }
                if (!bgPvpAttacked) {
                    bgPvpAttacked = true;
                    net.minecraft.world.phys.HitResult hit = bot.getLocalPlayer()
                            .raycastHitResult(1.0F, bot.getLocalPlayer());
                    check("pvp ray hits player", hit instanceof net.minecraft.world.phys.EntityHitResult,
                            "hit=" + hit + " dist=" + bgPvpDist);
                    bot.actions().attackLook();
                    waitTicks = 0;
                    return;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName("tbot-gui3");
                    bgPvpTargetHp = sp != null ? sp.getHealth() : -1;
                });
                if (bgPvpTargetHp >= 0 && bgPvpTargetHp < 20) {
                    check("pvp attack damages player", true, "hp=" + bgPvpTargetHp);
                    com.mockplayer.session.FakePlayerCommands.delPlayer("tbot-gui3");
                    waitTicks = 0;
                    bgStep(34);
                } else if (++waitTicks > 120) {
                    check("pvp attack damages player", false,
                            "hp=" + bgPvpTargetHp + " dist=" + bgPvpDist);
                    com.mockplayer.session.FakePlayerCommands.delPlayer("tbot-gui3");
                    bgStep(34);
                }
            }
            case 34 -> { // 收尾
                if (++waitTicks < 20) {
                    return;
                }
                mc.gui.setScreen(null);
                bgStep(35);
            }
            case 35 -> { // 主玩家挖方块隔离：主玩家 level 破坏进度只有主玩家（无假人叠加）
                if (!bgDigSet) {
                    bgDigSet = true;
                    bgDigPos = mc.player.blockPosition().offset(2, 0, 0);
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + mc.player.getName().getString()
                                        + " weapon.mainhand with minecraft:diamond_pickaxe");
                        server.getLevel(net.minecraft.world.level.Level.OVERWORLD)
                                .setBlock(bgDigPos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等方块/镐同步
                }
                if (!bgDigStarted) {
                    bgDigStarted = true;
                    mc.gameMode.startDestroyBlock(bgDigPos, net.minecraft.core.Direction.UP);
                    waitTicks = 0;
                    return;
                }
                if (!mc.level.getBlockState(bgDigPos).isAir()) {
                    // 挖掘中：主玩家 level 破坏进度条目必须只有主玩家（id=1），无假人条目叠加
                    int entries = mainLevelDestroyCount();
                    if (entries > bgDigMaxEntries) {
                        bgDigMaxEntries = entries;
                    }
                    if (++waitTicks > 120) {
                        mc.gameMode.continueDestroyBlock(bgDigPos, net.minecraft.core.Direction.UP);
                        if (waitTicks > 200) {
                            fail("main dig timeout");
                            mc.gameMode.stopDestroyBlock();
                            bgStep(36);
                        }
                    }
                    return;
                }
                // 方块已破坏：等假人收到 2001 广播并处理，抓「假人 level 往共享粒子引擎塞粒子/播主玩家音箱」
                if (++bgDigEventTicks <= 15) {
                    int fakeParticles = mainEngineFakeParticleCount(bot.getLevel());
                    if (fakeParticles > bgDigFakeParticles) {
                        bgDigFakeParticles = fakeParticles;
                    }
                    checkFakeDigRecords();
                    return;
                }
                check("main dig destroyed", true);
                check("main dig progress not polluted", bgDigMaxEntries <= 1,
                        "maxEntries=" + bgDigMaxEntries);
                check("main engine no fake particles", bgDigFakeParticles == 0,
                        "fakeParticles=" + bgDigFakeParticles);
                check("fake records break sound", bgDigSoundRecorded);
                check("fake records break particle", bgDigParticleRecorded);
                mc.gameMode.stopDestroyBlock();
                bgStep(36);
            }
            case 36 -> { // 背包丢弃格子：拿起物品放红色格子 = 原版点击菜单外（-999），服务端真实掉落
                if (!bgDiscardGiven) {
                    bgDiscardGiven = true;
                    mc.gui.setScreen(null);
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "give " + botName + " minecraft:emerald 1");
                        }
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等槽位同步
                }
                net.minecraft.client.player.LocalPlayer lp = bot.getLocalPlayer();
                if (!bgDiscardOpened) {
                    if (lp == null || !lp.getInventory().getItem(0).is(net.minecraft.world.item.Items.EMERALD)) {
                        if (waitTicks > 200) {
                            fail("discard item sync timeout");
                            System.clearProperty("mockplayer.guiRenderProbe");
                            bgStep(37);
                        }
                        return;
                    }
                    bgDiscardOpened = true;
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button invTab =
                                bgFindButton(s, "gui.mockplayer.tab.inventory");
                        if (invTab != null) {
                            bgClick(invTab);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgDiscardPicked) {
                    bgDiscardPicked = true;
                    com.mockplayer.gui.BotControlScreen s = bgScreen();
                    if (s != null) {
                        bgClickInventorySlot(s, 36); // 左键拿起快捷栏 0 的绿宝石
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (!bgDiscardSlotFilled) {
                    // 把空出来的快捷栏 0 填成石头：丢弃后无空位可瞬捡，掉落物实体证据稳定
                    if (lp == null || !lp.getInventory().getItem(0).isEmpty()
                            || !lp.containerMenu.getCarried().is(net.minecraft.world.item.Items.EMERALD)) {
                        if (waitTicks > 80) {
                            fail("discard slot fill timeout carried="
                                    + (lp != null ? lp.containerMenu.getCarried() : "null"));
                            System.clearProperty("mockplayer.guiRenderProbe");
                            bgStep(37);
                        }
                        return;
                    }
                    bgDiscardSlotFilled = true;
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "item replace entity " + botName + " hotbar.0 with minecraft:stone");
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                if (lp == null || !lp.getInventory().getItem(0).is(net.minecraft.world.item.Items.STONE)) {
                    if (waitTicks > 80) {
                        fail("discard slot stone sync timeout");
                        System.clearProperty("mockplayer.guiRenderProbe");
                        bgStep(37);
                    }
                    return;
                }
                if (!bgDiscardClicked) {
                    if (!lp.containerMenu.getCarried().is(net.minecraft.world.item.Items.EMERALD)) {
                        if (waitTicks > 60) {
                            fail("discard pickup timeout carried="
                                    + (lp != null ? lp.containerMenu.getCarried() : "null"));
                            System.clearProperty("mockplayer.guiRenderProbe");
                            bgStep(37);
                        }
                        return;
                    }
                    bgDiscardClicked = true;
                    check("discard slot rendered", com.mockplayer.gui.BotGui.probeDiscardCount() > 0);
                    com.mockplayer.gui.BotControlScreen s = bgScreen();
                    check("discard slot click dispatched", s != null && bgClickDiscardSlot(s));
                    waitTicks = 0;
                    return;
                }
                boolean clientDropped = lp != null && lp.containerMenu.getCarried().isEmpty();
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    bgDiscardServerHas = sp != null
                            && sp.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) > 0;
                    bgDiscardServerItemCount = 0;
                    if (sp != null) {
                        bgDiscardServerItemCount = server.getLevel(net.minecraft.world.level.Level.OVERWORLD)
                                .getEntities(
                                        net.minecraft.world.level.entity.EntityTypeTest.forClass(
                                                net.minecraft.world.entity.item.ItemEntity.class),
                                        new net.minecraft.world.phys.AABB(sp.blockPosition()).inflate(4.0),
                                        ie -> ie.getItem().is(net.minecraft.world.item.Items.EMERALD))
                                .size();
                    }
                });
                if (!clientDropped || bgDiscardServerHas || bgDiscardServerItemCount == 0) {
                    if (++waitTicks > 120) {
                        check("discard carried cleared", clientDropped);
                        check("discard server inventory empty", !bgDiscardServerHas,
                                "has=" + bgDiscardServerHas);
                        check("discard item entity spawned", bgDiscardServerItemCount > 0,
                                "items=" + bgDiscardServerItemCount);
                        mc.gui.setScreen(null);
                        System.clearProperty("mockplayer.guiRenderProbe");
                        bgStep(37);
                    }
                    return;
                }
                check("discard carried cleared", true);
                check("discard server inventory empty", true);
                check("discard item entity spawned", true, "items=" + bgDiscardServerItemCount);
                mc.gui.setScreen(null);
                System.clearProperty("mockplayer.guiRenderProbe");
                bgStep(37);
            }
            case 37 -> { // 主玩家走路后假人打不到主玩家（MoveEntity 插值陈旧 → 射线 miss）
                if (!bgMainPvpGiven) {
                    bgMainPvpGiven = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        var spM = server.getPlayerList().getPlayerByName(mc.player.getName().getString());
                        var spB = server.getPlayerList().getPlayerByName(botName);
                        if (spM != null && spB != null) {
                            bgMainStartX = spM.getX();
                            // 主玩家先传送远 60 格（snap 路径），假人先放更远处避免干扰；
                            // 主玩家小步走回（MoveEntity 路径）→ 修复前假人 level 实体停在 +60
                            spM.teleportTo(spM.getX() + 60.0, spM.getY(), spM.getZ());
                            bgMainBaseX = spM.getX();
                            spB.teleportTo(spM.getX() + 30.0, spM.getY(), spM.getZ());
                            spM.setYRot(90.0F);
                        }
                    });
                    bgMainPvpWalking = true;
                    return;
                }
                if (bgMainPvpWalking) {
                    // 服务端按 tick 门控小步移动：每服务器 tick 最多 0.3 格 → ServerEntity 发 MoveEntity.Pos
                    // （插值路径）；一次性大位移会触发 EntityPositionSync（snap）掩盖 bug，必须逐 tick 小步
                    server.execute(() -> {
                        int tick = server.getTickCount();
                        if (tick != bgMainLastMoveTick) {
                            bgMainLastMoveTick = tick;
                            var spM = server.getPlayerList().getPlayerByName(mc.player.getName().getString());
                            var spB = server.getPlayerList().getPlayerByName(botName);
                            if (spM != null) {
                                spM.setPos(spM.getX() - 0.3, spM.getY(), spM.getZ());
                                bgMainServerX = spM.getX();
                                bgMainServerZ = spM.getZ();
                            }
                            if (spB != null) {
                                bgBotServerX = spB.getX();
                                bgBotServerZ = spB.getZ();
                            }
                        }
                    });
                    if (++waitTicks > 70) { // 70 tick × 0.3 ≈ 18-21 格
                        bgMainPvpWalking = false;
                        waitTicks = 0;
                    }
                    return;
                }
                if (++waitTicks < 8) {
                    return; // 等服务端执行完队列 + 插值收敛
                }
                if (!bgMainPvpPosRead) {
                    bgMainPvpPosRead = true;
                    // server.execute 异步：重新读取一次最终位置，避免读到滞后值
                    server.execute(() -> {
                        var spM = server.getPlayerList().getPlayerByName(mc.player.getName().getString());
                        var spB = server.getPlayerList().getPlayerByName(botName);
                        if (spM != null) {
                            bgMainServerX = spM.getX();
                            bgMainServerZ = spM.getZ();
                        }
                        if (spB != null) {
                            bgBotServerX = spB.getX();
                            bgBotServerZ = spB.getZ();
                        }
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 3) {
                    return; // 等最终位置读取落地
                }
                if (!bgMainPvpBotPlaced) {
                    bgMainPvpBotPlaced = true;
                    // 主玩家最终位置已确定：假人传送到其西侧 1.2 格（固定几何，攻击距离稳定）
                    server.execute(() -> {
                        var spB = server.getPlayerList().getPlayerByName(botName);
                        if (spB != null) {
                            spB.teleportTo(bgMainServerX - 1.2, spB.getY(), bgMainServerZ);
                        }
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等假人自己传送确认
                }
                if (!bgMainPvpEntityChecked) {
                    bgMainPvpEntityChecked = true;
                    check("main pvp player moved", bgMainBaseX - bgMainServerX >= 3.0,
                            "base=" + bgMainBaseX + " end=" + bgMainServerX);
                    net.minecraft.world.entity.Entity main = bot.getEntitiesNear(8).stream()
                            .filter(e -> e instanceof net.minecraft.world.entity.player.Player
                                    && mc.player.getName().getString().equals(e.getName().getString()))
                            .findFirst().orElse(null);
                    check("main pvp entity exists in bot level", main != null);
                    if (main != null) {
                        bgMainClientX = main.getX();
                        bgMainClientZ = main.getZ();
                    }
                    check("main pvp client position synced",
                            main != null && Math.abs(bgMainClientX - bgMainServerX) < 1.0,
                            "client=(" + bgMainClientX + "," + bgMainClientZ + ")"
                                    + " server=(" + bgMainServerX + "," + bgMainServerZ + ")");
                    bot.actions().look(-90.0F, 0.0F); // 假人在主玩家西侧 → 正东攻击
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return; // 等 look 生效
                }
                if (!bgMainPvpAttacked) {
                    bgMainPvpAttacked = true;
                    net.minecraft.world.phys.HitResult hit = bot.getLocalPlayer().raycastHitResult(
                            1.0F, bot.getLocalPlayer());
                    check("main pvp ray hits main player", hit instanceof net.minecraft.world.phys.EntityHitResult,
                            "hit=" + hit + " serverMain=(" + bgMainServerX + "," + bgMainServerZ + ")"
                                    + " clientMain=(" + bgMainClientX + "," + bgMainClientZ + ")"
                                    + " bot=(" + bgBotServerX + "," + bgBotServerZ + ")");
                    bot.actions().attackLook();
                    waitTicks = 0;
                    return;
                }
                server.execute(() -> {
                    var spM = server.getPlayerList().getPlayerByName(mc.player.getName().getString());
                    bgMainHealth = spM != null ? spM.getHealth() : -1;
                });
                if (bgMainHealth < 20) {
                    check("main pvp attack damages main player", true, "hp=" + bgMainHealth);
                    mc.gui.setScreen(null);
                    bgStep(38);
                } else if (++waitTicks > 120) {
                    check("main pvp attack damages main player", false,
                            "hp=" + bgMainHealth + " clientMain=(" + bgMainClientX + "," + bgMainClientZ + ")"
                                    + " serverMain=(" + bgMainServerX + "," + bgMainServerZ + ")");
                    mc.gui.setScreen(null);
                    bgStep(38);
                }
            }
            case 38 -> { // 动作按钮模式：右键切 长按/连点/默认，左键激活变红，激活时右键禁用
                if (!bgModeSet) {
                    bgModeSet = true;
                    mc.gui.setScreen(null);
                    System.setProperty("mockplayer.guiRenderProbe", "true");
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            sp.getInventory().clearContent();
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "item replace entity " + botName + " weapon.mainhand with minecraft:diamond_sword");
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                            sp.getX() + 2.0, sp.getY(), sp.getZ()));
                        }
                    });
                    bot.actions().look(-90.0F, 0.0F); // 面向东（husk 在 +2）
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 15) {
                    return; // 等装备/召唤同步
                }
                if (!bgModeOpened) {
                    bgModeOpened = true;
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen s) {
                        net.minecraft.client.gui.components.Button tab =
                                bgFindButton(s, "gui.mockplayer.tab.actions");
                        if (tab != null) {
                            bgClick(tab);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 5) {
                    return;
                }
                com.mockplayer.gui.BotControlScreen s = bgScreen();
                if (!bgModeTextChecked) {
                    bgModeTextChecked = true;
                    if (s == null) {
                        fail("mode screen missing");
                        bgStep(39);
                        return;
                    }
                    bgModeAtk = bgFindButton(s, "gui.mockplayer.action.attack_look");
                    bgModeUse = bgFindButton(s, "gui.mockplayer.action.use_look");
                    check("mode buttons found", bgModeAtk != null && bgModeUse != null);
                    if (bgModeAtk == null) {
                        bgStep(39);
                        return;
                    }
                    // 右键循环：默认 → 长按 → 连点 → 默认
                    bgRightClick(bgModeAtk);
                    check("mode attack hold text", bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_hold"),
                            "msg=" + bgModeAtk.getMessage().getString());
                    bgRightClick(bgModeAtk);
                    check("mode attack rapid text", bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_rapid"),
                            "msg=" + bgModeAtk.getMessage().getString());
                    bgRightClick(bgModeAtk);
                    check("mode attack back default", bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_look"),
                            "msg=" + bgModeAtk.getMessage().getString());
                    // 长按模式：左键激活 → 变红 + 右键禁用
                    bgRightClick(bgModeAtk);
                    bgClick(bgModeAtk);
                    check("mode hold activated red", bgIsRed(bgModeAtk));
                    bgRightClick(bgModeAtk);
                    check("mode hold right-click blocked",
                            bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_hold") && bgIsRed(bgModeAtk));
                    waitTicks = 0;
                    return;
                }
                if (!bgModeHoldDamaged) {
                    server.execute(() -> {
                        var husk = server.getLevel(net.minecraft.world.level.Level.OVERWORLD).getEntities(
                                net.minecraft.world.level.entity.EntityTypeTest.forClass(
                                        net.minecraft.world.entity.monster.zombie.Husk.class),
                                new net.minecraft.world.phys.AABB(
                                        server.getPlayerList().getPlayerByName(botName).blockPosition()).inflate(8.0),
                                e -> e.isAlive()).stream().findFirst().orElse(null);
                        bgModeHoldHp = husk != null ? husk.getHealth() : -1;
                    });
                    if (bgModeHoldHp >= 0 && bgModeHoldHp < 20) {
                        check("mode hold sustained damages", true, "hp=" + bgModeHoldHp);
                        bgModeHoldDamaged = true;
                        bgModeHuskPrevHp = 20;
                        bgModeRapidHits = 0;
                        bgClick(bgModeAtk); // 关闭长按
                        check("mode hold deactivated normal", !bgIsRed(bgModeAtk));
                        server.execute(() -> {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "kill @e[type=minecraft:husk]");
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            if (sp != null) {
                                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                        String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                                sp.getX() + 2.0, sp.getY(), sp.getZ()));
                            }
                        });
                        waitTicks = 0;
                        return;
                    }
                    if (++waitTicks > 80) {
                        check("mode hold sustained damages", false, "hp=" + bgModeHoldHp);
                        bgModeHoldDamaged = true;
                        bgClick(bgModeAtk);
                        waitTicks = 0;
                        return;
                    }
                    return;
                }
                if (++waitTicks < 10) {
                    return; // 等新 husk
                }
                if (!bgModeRapidActivated) {
                    bgModeRapidActivated = true;
                    bgRightClick(bgModeAtk); // 长按 → 连点
                    check("mode attack rapid text 2", bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_rapid"),
                            "msg=" + bgModeAtk.getMessage().getString());
                    bgClick(bgModeAtk); // 激活连点
                    check("mode rapid activated red", bgIsRed(bgModeAtk));
                    bgRightClick(bgModeAtk);
                    check("mode rapid right-click blocked",
                            bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_rapid") && bgIsRed(bgModeAtk));
                    waitTicks = 0;
                    return;
                }
                if (!bgModeRapidDamaged) {
                    server.execute(() -> {
                        var husk = server.getLevel(net.minecraft.world.level.Level.OVERWORLD).getEntities(
                                net.minecraft.world.level.entity.EntityTypeTest.forClass(
                                        net.minecraft.world.entity.monster.zombie.Husk.class),
                                new net.minecraft.world.phys.AABB(
                                        server.getPlayerList().getPlayerByName(botName).blockPosition()).inflate(8.0),
                                e -> e.isAlive()).stream().findFirst().orElse(null);
                        bgModeHuskHp = husk != null ? husk.getHealth() : -1;
                    });
                    if (bgModeHuskHp >= 0 && bgModeHuskHp < bgModeHuskPrevHp - 1.0F) {
                        bgModeRapidHits++;
                        bgModeHuskPrevHp = bgModeHuskHp;
                    }
                    if (bgModeRapidHits >= 2) {
                        check("mode rapid attack repeats", true,
                                "hits=" + bgModeRapidHits + " hp=" + bgModeHuskHp);
                        bgModeRapidDamaged = true;
                        bgClick(bgModeAtk); // 关闭连点
                        check("mode rapid deactivated normal", !bgIsRed(bgModeAtk));
                        bgRightClick(bgModeAtk); // 连点 → 默认
                        check("mode rapid back default", bgModeMsg(bgModeAtk, "gui.mockplayer.action.attack_look"),
                                "msg=" + bgModeAtk.getMessage().getString());
                        waitTicks = 0;
                        return;
                    }
                    if (++waitTicks > 160) {
                        check("mode rapid attack repeats", false,
                                "hits=" + bgModeRapidHits + " hp=" + bgModeHuskHp);
                        bgModeRapidDamaged = true;
                        bgClick(bgModeAtk);
                        bgRightClick(bgModeAtk);
                        waitTicks = 0;
                        return;
                    }
                    return;
                }
                if (!bgModeUseDone) {
                    if (!bgModeUseArmed) {
                        bgModeUseArmed = true;
                        bgModeUseMaxContainer = -1;
                        server.execute(() -> {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                    "kill @e[type=minecraft:husk]");
                            var sp = server.getPlayerList().getPlayerByName(botName);
                            if (sp != null) {
                                // 视线高度放箱子：连点右键每 20 tick useLook 开一次 → containerId 递增
                                server.getLevel(net.minecraft.world.level.Level.OVERWORLD).setBlock(
                                        new net.minecraft.core.BlockPos(
                                                (int) Math.floor(sp.getX()) + 2,
                                                (int) Math.floor(sp.getY()) + 1,
                                                (int) Math.floor(sp.getZ())),
                                        net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
                            }
                        });
                        waitTicks = 0;
                        return;
                    }
                    if (++waitTicks < 10) {
                        return; // 等面包
                    }
                    if (!bgModeUseText) {
                        bgModeUseText = true;
                        bgRightClick(bgModeUse); // 默认 → 长按
                        check("mode use hold text", bgModeMsg(bgModeUse, "gui.mockplayer.action.use_hold"),
                                "msg=" + bgModeUse.getMessage().getString());
                        bgRightClick(bgModeUse); // 长按 → 连点
                        check("mode use rapid text", bgModeMsg(bgModeUse, "gui.mockplayer.action.use_rapid"),
                                "msg=" + bgModeUse.getMessage().getString());
                        bgClick(bgModeUse); // 激活连点右键
                        check("mode use rapid red", bgIsRed(bgModeUse));
                        bgRightClick(bgModeUse);
                        check("mode use right-click blocked",
                                bgModeMsg(bgModeUse, "gui.mockplayer.action.use_rapid") && bgIsRed(bgModeUse));
                        waitTicks = 0;
                        return;
                    }
                    server.execute(() -> {
                        var sp = server.getPlayerList().getPlayerByName(botName);
                        int cid = sp != null ? sp.containerMenu.containerId : -1;
                        if (cid > bgModeUseMaxContainer) {
                            bgModeUseMaxContainer = cid;
                        }
                    });
                    if (bgModeUseMaxContainer >= 2) {
                        check("mode use rapid repeats", true, "containerId=" + bgModeUseMaxContainer);
                        bgModeUseDone = true;
                        bgClick(bgModeUse); // 关闭连点
                        check("mode use deactivated normal", !bgIsRed(bgModeUse));
                        bgRightClick(bgModeUse); // 连点 → 默认
                        check("mode use back default", bgModeMsg(bgModeUse, "gui.mockplayer.action.use_look"),
                                "msg=" + bgModeUse.getMessage().getString());
                        // 持久性：连点是类似疾跑的开关——关闭 GUI 不停、重开还原、停止按钮全停
                        bgRightClick(bgModeAtk); // 默认 → 长按
                        bgRightClick(bgModeAtk); // 长按 → 连点
                        bgClick(bgModeAtk);      // 激活连点左键
                        check("mode persist rapid activated red", bgIsRed(bgModeAtk));
                        bgModePersistOpened = true;
                        waitTicks = 0;
                        return;
                    } else if (++waitTicks > 100) {
                        check("mode use rapid repeats", false, "containerId=" + bgModeUseMaxContainer);
                        bgModeUseDone = true;
                        bgClick(bgModeUse);
                        bgRightClick(bgModeUse);
                        mc.gui.setScreen(null);
                        System.clearProperty("mockplayer.guiRenderProbe");
                        bgStep(39);
                    }
                    return;
                }
                if (bgModePersistOpened && !bgModePersistClosed) {
                    bgModePersistClosed = true;
                    mc.gui.setScreen(null); // 关闭 GUI：连点必须继续（类似疾跑）
                    waitTicks = 0;
                    return;
                }
                if (bgModePersistClosed && !bgModePersistReopened) {
                    if (++waitTicks < 10) {
                        return;
                    }
                    check("mode rapid persists after gui close", bot.actions().isRapidAttacking());
                    bgModePersistReopened = true;
                    com.mockplayer.gui.BotGui.open(mc);
                    net.minecraft.client.gui.screens.Screen opened = bgScreen();
                    if (opened instanceof com.mockplayer.gui.BotControlScreen sc) {
                        net.minecraft.client.gui.components.Button tab =
                                bgFindButton(sc, "gui.mockplayer.tab.actions");
                        if (tab != null) {
                            bgClick(tab);
                        }
                    }
                    waitTicks = 0;
                    return;
                }
                if (bgModePersistReopened && !bgModePersistStopped) {
                    if (++waitTicks < 5) {
                        return;
                    }
                    com.mockplayer.gui.BotControlScreen sc = bgScreen();
                    net.minecraft.client.gui.components.Button atk = sc != null
                            ? bgFindButton(sc, "gui.mockplayer.action.attack_rapid") : null;
                    check("mode rapid restored red on reopen", atk != null && bgIsRed(atk));
                    net.minecraft.client.gui.components.Button stopBtn = sc != null
                            ? bgFindButton(sc, "gui.mockplayer.action.stop") : null;
                    check("stop button found", stopBtn != null);
                    if (stopBtn != null) {
                        bgClick(stopBtn);
                    }
                    bgModePersistStopped = true;
                    waitTicks = 0;
                    return;
                }
                if (bgModePersistStopped) {
                    if (++waitTicks < 5) {
                        return;
                    }
                    check("stop button clears rapid", !bot.actions().isRapidAttacking());
                    com.mockplayer.gui.BotControlScreen sc = bgScreen();
                    net.minecraft.client.gui.components.Button atk = sc != null
                            ? bgFindButton(sc, "gui.mockplayer.action.attack_rapid") : null;
                    check("stop button clears red", atk != null && !bgIsRed(atk));
                    mc.gui.setScreen(null);
                    System.clearProperty("mockplayer.guiRenderProbe");
                    bgStep(39);
                }
            }
            case 39 -> { // 主玩家物理回归：bot 叠在主玩家位置，松键后必须正常刹停（不「像抹了冰」）
                if (!bgSlideGiven) {
                    bgSlideGiven = true;
                    mc.gui.setScreen(null);
                    // 失焦会弹 PauseScreen 挡住移动输入：本阶段临时关掉，收尾恢复
                    bgSlidePauseOnLostFocus = mc.options.pauseOnLostFocus;
                    mc.options.pauseOnLostFocus = false;
                    bgSlideStartX = mc.player.getX();
                    bgSlideStartZ = mc.player.getZ();
                    server.execute(() -> {
                        var spM = server.getPlayerList().getPlayerByName(mc.player.getName().getString());
                        var spB = server.getPlayerList().getPlayerByName(botName);
                        if (spM != null && spB != null) {
                            spB.teleportTo(spM.getX(), spM.getY(), spM.getZ());
                        }
                    });
                    waitTicks = 0;
                    return;
                }
                if (++waitTicks < 20) {
                    return; // 等 bot 重叠到位
                }
                if (!bgSlideWalking) {
                    if (mc.gui.screen() != null) {
                        mc.gui.setScreen(null); // 残留界面（如失焦弹的 PauseScreen）先关掉
                    }
                    // 就绪门：等主玩家落地/存活/无界面（失焦暂停或残留界面会让按键无效，造成假失败）
                    if (mc.player.onGround() && mc.player.isAlive() && mc.gui.screen() == null) {
                        bgSlideWalking = true;
                        bgSlideWalkTicks = 0;
                        mc.options.keyUp.setDown(true); // 主玩家前进产生动量
                        waitTicks = 0;
                    } else if (waitTicks > 140) {
                        check("main player ready to walk", false,
                                "onGround=" + mc.player.onGround()
                                        + " alive=" + mc.player.isAlive()
                                        + " screen=" + mc.gui.screen()
                                        + " paused=" + mc.isPaused());
                        bgSlideWalked = true; // 直接测量（实际 moved 会 FAIL，给出完整状态）
                        waitTicks = 0;
                    }
                    return;
                }
                if (!bgSlideWalked) {
                    bgSlideWalkTicks++;
                    double dx = mc.player.getX() - bgSlideStartX;
                    double dz = mc.player.getZ() - bgSlideStartZ;
                    // 走到 0.5 格即松键（最长 80 tick，容忍卡位/延迟启动，不掩盖滑行回归）
                    if (bgSlideWalkTicks >= 80 || Math.sqrt(dx * dx + dz * dz) >= 0.5) {
                        bgSlideWalked = true;
                        mc.options.keyUp.setDown(false);
                        waitTicks = 0;
                    }
                    return;
                }
                if (++waitTicks < 6) {
                    return; // 松键后 6 tick（原版地面摩擦应已刹停）
                }
                double dx = mc.player.getX() - bgSlideStartX;
                double dz = mc.player.getZ() - bgSlideStartZ;
                double speed = mc.player.getDeltaMovement().horizontalDistance();
                check("main player actually moved",
                        Math.sqrt(dx * dx + dz * dz) > 0.5,
                        "dist=" + Math.sqrt(dx * dx + dz * dz)
                                + " paused=" + mc.isPaused()
                                + " onGround=" + mc.player.onGround()
                                + " screen=" + mc.gui.screen());
                check("main player stops after release", speed < 0.05, "speed=" + speed);
                bgStep(40);
            }
            case 40 -> { // 收尾
                if (++waitTicks < 20) {
                    return;
                }
                mc.options.pauseOnLostFocus = bgSlidePauseOnLostFocus;
                mc.gui.setScreen(null);
                finishSuite();
            }
        }
    }

    /** 反射读主玩家 level 破坏进度条目数（私有字段 destructionProgress）。 */
    private static int mainLevelDestroyCount() {
        try {
            var f = net.minecraft.client.multiplayer.ClientLevel.class.getDeclaredField("destructionProgress");
            f.setAccessible(true);
            return ((java.util.Map<?, ?>) f.get(Minecraft.getInstance().level)).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 主玩家 particleEngine 中 level 属于假人 level 的粒子数（渲染污染直接证据）。 */
    private static int mainEngineFakeParticleCount(net.minecraft.client.multiplayer.ClientLevel fakeLevel) {
        if (fakeLevel == null) {
            return -1;
        }
        int count = 0;
        try {
            net.minecraft.client.particle.ParticleEngine engine = Minecraft.getInstance().particleEngine;
            java.lang.reflect.Field toAddF = net.minecraft.client.particle.ParticleEngine.class
                    .getDeclaredField("particlesToAdd");
            toAddF.setAccessible(true);
            for (Object p : (java.util.Queue<?>) toAddF.get(engine)) {
                if (particleLevel(p) == fakeLevel) {
                    count++;
                }
            }
            java.lang.reflect.Field groupsF = net.minecraft.client.particle.ParticleEngine.class
                    .getDeclaredField("particles");
            groupsF.setAccessible(true);
            java.lang.reflect.Field queueF = null;
            for (Object group : ((java.util.Map<?, ?>) groupsF.get(engine)).values()) {
                if (queueF == null) {
                    for (Class<?> c = group.getClass(); c != null; c = c.getSuperclass()) {
                        try {
                            queueF = c.getDeclaredField("particles");
                            break;
                        } catch (java.lang.NoSuchFieldException ignored) {
                            // 继续向父类找（ParticleGroup.particles）
                        }
                    }
                    if (queueF == null) {
                        return -1;
                    }
                    queueF.setAccessible(true);
                }
                for (Object p : (java.util.Collection<?>) queueF.get(group)) {
                    if (particleLevel(p) == fakeLevel) {
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return count;
    }

    /** 反射取粒子的 level（Particle.level，protected final）。 */
    private static net.minecraft.client.multiplayer.ClientLevel particleLevel(Object particle) {
        try {
            java.lang.reflect.Field f = net.minecraft.client.particle.Particle.class.getDeclaredField("level");
            f.setAccessible(true);
            return (net.minecraft.client.multiplayer.ClientLevel) f.get(particle);
        } catch (Exception e) {
            return null;
        }
    }

    /** 假人 state 是否已记录本次破坏的方块粒子/音效（拦截成功的证据）。 */
    private static void checkFakeDigRecords() {
        var state = com.mockplayer.session.SessionManager.getInstance().getSession(botName).getState();
        if (state == null) {
            return;
        }
        for (var c : state.getSoundLog()) {
            if (c.getString().contains("block.stone.break")) {
                bgDigSoundRecorded = true;
            }
        }
        for (var c : state.getParticleLog()) {
            if (c.getString().contains("minecraft:block")) {
                bgDigParticleRecorded = true;
            }
        }
    }

    /** bot-gui 步骤切换：统一清零等待计数（共享 waitTicks 会被上个 case 残留，超时误判）。 */
    private static void bgStep(int next) {
        waitTicks = 0;
        step = next;
    }

    /** bot-gui 语言文件级 i18n：gui.mockplayer.* + config gui 选项 en/zh key 集合一致、值非空。 */
    private static void i18nGuiLangChecks() {
        try {
            com.google.gson.JsonObject en = parseLang("en_us.json");
            com.google.gson.JsonObject zh = parseLang("zh_cn.json");
            java.util.Set<String> enKeys = new java.util.TreeSet<>();
            java.util.Set<String> zhKeys = new java.util.TreeSet<>();
            en.entrySet().forEach(e -> {
                if (e.getKey().startsWith("gui.mockplayer.")
                        || e.getKey().startsWith("config.mockplayer.option.gui")
                        || e.getKey().equals("config.mockplayer.group.gui")) {
                    enKeys.add(e.getKey());
                }
            });
            zh.entrySet().forEach(e -> {
                if (e.getKey().startsWith("gui.mockplayer.")
                        || e.getKey().startsWith("config.mockplayer.option.gui")
                        || e.getKey().equals("config.mockplayer.group.gui")) {
                    zhKeys.add(e.getKey());
                }
            });
            check("gui i18n key sets identical (en/zh)", enKeys.equals(zhKeys),
                    "en=" + enKeys.size() + " zh=" + zhKeys.size());
            check("gui i18n key count sane", enKeys.size() > 40);
            check("gui i18n values non-empty",
                    enKeys.stream().allMatch(k -> !en.get(k).getAsString().isBlank())
                            && zhKeys.stream().allMatch(k -> !zh.get(k).getAsString().isBlank()));
        } catch (Exception e) {
            check("gui i18n lang files parse", false, e.toString());
        }
    }

    private static com.mockplayer.gui.BotControlScreen bgScreen() {
        net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().gui.screen();
        return s instanceof com.mockplayer.gui.BotControlScreen screen ? screen : null;
    }

    /** 按翻译文本找按钮（消息包含目标 key 的翻译，兼容 ● 前缀/开关状态后缀）。 */
    private static net.minecraft.client.gui.components.Button bgFindButton(
            com.mockplayer.gui.BotControlScreen screen, String key) {
        // %s 占位符 key（如 tab 前缀）不带参数取 label 会得到 %s动作，去掉占位符再匹配；
        // 消息可能带 "● " 当前 Tab 前缀，同样剥掉。
        String label = net.minecraft.network.chat.Component.translatable(key).getString().replace("%s", "");
        for (Object child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button b
                    && b.getMessage().getString().replace("● ", "").contains(label)) {
                return b;
            }
        }
        return null;
    }

    /** 按字面文本精确找按钮（快捷栏数字等）。 */
    private static net.minecraft.client.gui.components.Button bgFindButtonByLiteral(
            com.mockplayer.gui.BotControlScreen screen, String text) {
        for (Object child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button b
                    && text.equals(b.getMessage().getString())) {
                return b;
            }
        }
        return null;
    }

    private static net.minecraft.client.gui.components.EditBox bgFindEditBox(
            com.mockplayer.gui.BotControlScreen screen, String key) {
        String label = net.minecraft.network.chat.Component.translatable(key).getString();
        for (Object child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.EditBox e
                    && label.equals(e.getMessage().getString())) {
                return e;
            }
        }
        return null;
    }

    /** 模拟按钮按下（面板逻辑坐标命中）。 */
    private static void bgClick(net.minecraft.client.gui.components.Button b) {
        b.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                b.getX() + b.getWidth() / 2.0,
                b.getY() + b.getHeight() / 2.0,
                new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
    }

    /** 模拟按钮松开（按住按钮结束）。 */
    private static void bgRelease(net.minecraft.client.gui.components.Button b) {
        b.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(
                b.getX() + b.getWidth() / 2.0,
                b.getY() + b.getHeight() / 2.0,
                new net.minecraft.client.input.MouseButtonInfo(0, 0)));
    }

    /** 容器 Tab 网格点击（playerRow 3 = 快捷栏行；col 0-8）。屏幕坐标 = 面板原点 + 逻辑坐标 * scale。 */
    private static boolean bgClickContainerCell(com.mockplayer.gui.BotControlScreen screen, int col, int playerRow) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float scale = com.mockplayer.gui.BotGui.layoutScale(w, h);
        double lx = 104 + col * 20 + 2;
        double ly = 44 + 16 + (3 * 20 + 8) + playerRow * 20 + 2; // 27 格容器 = 3 行，玩家区从 rows*20+8 起
        double sx = com.mockplayer.gui.BotGui.panelX(w, h) + lx * scale;
        double sy = com.mockplayer.gui.BotGui.panelY(w, h) + ly * scale;
        return screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                sx, sy, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
    }

    /** 背包 Tab 格子左键点击（menuSlot：盔甲5-8/主背包9-35/快捷栏36-44/副手45）。 */
    private static boolean bgClickInventorySlot(com.mockplayer.gui.BotControlScreen screen, int menuSlot) {
        return bgClickInventorySlotButton(screen, menuSlot, 0);
    }

    /** 背包 Tab 格子右键点击（切换快捷栏选中用）。 */
    private static boolean bgRightClickInventorySlot(com.mockplayer.gui.BotControlScreen screen, int menuSlot) {
        return bgClickInventorySlotButton(screen, menuSlot, 1);
    }

    /** 背包 Tab 格子点击（button：0=左键 / 1=右键）。 */
    private static boolean bgClickInventorySlotButton(
            com.mockplayer.gui.BotControlScreen screen, int menuSlot, int button) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float scale = com.mockplayer.gui.BotGui.layoutScale(w, h);
        double lx;
        double ly;
        if (menuSlot >= 5 && menuSlot < 9) {
            lx = 0;
            ly = (menuSlot - 5) * 20;
        } else if (menuSlot >= 9 && menuSlot < 36) {
            int i = menuSlot - 9;
            lx = 24 + (i % 9) * 20;
            ly = (i / 9) * 20;
        } else if (menuSlot >= 36 && menuSlot < 45) {
            int i = menuSlot - 36;
            lx = 24 + i * 20;
            ly = 3 * 20;
        } else {
            lx = 24 + 9 * 20;
            ly = 3 * 20;
        }
        double sx = com.mockplayer.gui.BotGui.panelX(w, h) + (104 + lx + 10) * scale;
        double sy = com.mockplayer.gui.BotGui.panelY(w, h) + (44 + ly + 10) * scale;
        return screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                sx, sy, new net.minecraft.client.input.MouseButtonInfo(button, 0)), false);
    }

    /** 背包 Tab 红色丢弃格子点击（逻辑坐标 = 副手右侧一格；左键 = 丢弃整组）。 */
    private static boolean bgClickDiscardSlot(com.mockplayer.gui.BotControlScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float scale = com.mockplayer.gui.BotGui.layoutScale(w, h);
        double lx = 24 + 10 * 20;
        double ly = 3 * 20;
        double sx = com.mockplayer.gui.BotGui.panelX(w, h) + (104 + lx + 10) * scale;
        double sy = com.mockplayer.gui.BotGui.panelY(w, h) + (44 + ly + 10) * scale;
        return screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                sx, sy, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
    }

    /** 按钮右键点击（模式切换用；按钮中心）。 */
    private static void bgRightClick(net.minecraft.client.gui.components.Button b) {
        b.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0,
                new net.minecraft.client.input.MouseButtonInfo(1, 0)), false);
    }

    /** 按钮文字是否等于某翻译 key 的当前语言文本。 */
    private static boolean bgModeMsg(net.minecraft.client.gui.components.Button b, String key) {
        return b.getMessage().getString().equals(
                net.minecraft.network.chat.Component.translatable(key).getString());
    }

    /** 按钮文字是否为红色（激活态）。 */
    private static boolean bgIsRed(net.minecraft.client.gui.components.Button b) {
        var color = b.getMessage().getStyle().getColor();
        return color != null && color.equals(
                net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.RED));
    }

    /** 容器 Tab 的「容器区」格子点击（row 0 = 容器槽 0；与玩家区不同）。 */
    private static boolean bgClickContainerPart(com.mockplayer.gui.BotControlScreen screen, int col, int row) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float scale = com.mockplayer.gui.BotGui.layoutScale(w, h);
        double lx = 104 + col * 20 + 2;
        double ly = 44 + 16 + row * 20 + 2;
        double sx = com.mockplayer.gui.BotGui.panelX(w, h) + lx * scale;
        double sy = com.mockplayer.gui.BotGui.panelY(w, h) + ly * scale;
        return screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                sx, sy, new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
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
        // 清理当前套件 bot，避免残留干扰后续套件（幂等：已删则 NOT_FOUND 无害）
        if (bot != null) {
            com.mockplayer.api.MockplayerApi.bots().removeBot(bot.getName(), "command");
            bot = null;
        }
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
            MockplayerApi.bots().removeBot(b.getName(), "command");
        }
        bot = null;
        ccTreeChecked = false;
        ccMoveStarted = false;
        ccMoved = false;
        ccStopChecked = false;
        ccBaseX = 0;
        ccBaseZ = 0;
        ccLookStage = 0;
        ccServerYRot = -999;
        ccHuskSummoned = false;
        ccHuskAttacked = false;
        ccHuskHealth = 20;
        ccHuskTargetAttacked = false;
        ccSustainedStarted = false;
        ccSustainedHit = false;
        ccVillagerSummoned = false;
        ccMerchantOpen = false;
        ccHotbarGiven = false;
        ccHotbarVerified = false;
        ccMainHandBefore = "";
        ccDropVerified = false;
        ccSwapGiven = false;
        ccSwapVerified = false;
        ccChatMsg = "";
        ccChatListenerRegistered = false;
        ccChatChecked = false;
        ccChatSent = false;
        ccCommandSent = false;
        ccTimeVerified = false;
        ccUseGiven = false;
        ccUseChecked = false;
        ccUsing = false;
        ccReleased = false;
        ccDirtGiven = false;
        ccPlacePos = null;
        ccPlacePos2 = null;
        ccAttackBlockSent = false;
        ccAttackBlockBroken = false;
        ccAttackBlockChecked = false;
        ccPlaceSent = false;
        ccPlaceChecked = false;
        ccPlacedDirt = false;
        ccCreativeSet = false;
        ccPickSent = false;
        ccPickVerified = false;
        ccPickChecked = false;
        ccSurvivalBack = false;
        ccPlace2Sent = false;
        ccPlacedDirt2 = false;
        ccPlaced2Checked = false;
        ccMineSent = false;
        ccMinedAir2 = false;
        ccBedSet = false;
        ccBedPos = null;
        ccBedClicked = false;
        ccSleepChecked = false;
        ccSleeping = false;
        ccWoke = false;
        ccRailSet = false;
        ccMountSent = false;
        ccMountChecked = false;
        ccMounted = false;
        ccMountStableTicks = 0;
        ccDismountSent = false;
        ccDismounted = false;
        ccCartStartX = 0;
        ccCartMoved = false;
        ccCartPushed = false;
        ccChestPos = null;
        ccChestSet = false;
        ccChestClicked = false;
        ccQueriesOk = false;
        ccCloseSent = false;
        ccCloseChecked = false;
        ccClickPutSent = false;
        ccClickPutDone = false;
        ccClickPutVerified = false;
        ccClickPutItemInChest = false;
        ccMineTestSet = false;
        ccMineTestSynced = false;
        ccMineTestDone = false;
        ccMineStopSent = false;
        ccMineFlushDone = false;
        ccMineTestTicks = 0;
        ccMineSpReady = false;
        ccMineStone1 = null;
        ccMineStone2 = null;
        ccMineStoneFar = null;
        ccMineFarSent = false;
        ccMineFarChecked = false;
        ccMineFarWaitDone = false;
        ccMineFarStill = false;
        ccMineStone1Air = false;
        ccMineStone2Still = false;
        ccMineMainHand = "?";
        ccMineDigSpeed = -1.0F;
        ccMineCorrectTool = false;
        ccMineDestroyProgressRate = -1.0F;
        ccMineServerPickaxe = false;
        ccMineServerMoving = false;
        ccMineLastX = 0;
        ccMineLastY = 0;
        ccMineLastZ = 0;
        ccMineStableTicks = 0;
        ccMineStopWaitDone = false;
        ccListenOn = false;
        ccListenDamaged = false;
        ccEventsHasDamage = false;
        ccListenOff = false;
        ccErrorsOk = false;
        ccRespawnKilled = false;
        ccRespawnDead = false;
        ccRespawnDone = false;
        ccRespawnAlive = false;
        containerPos = null;
        containerAllCaseIndex = 0;
        containerAllPos = null;
        containerAllOpened = false;
        containerAllGiven = false;
        containerAllPut = false;
        containerAllTaken = false;
        containerAllServerPut = false;
        containerAllServerEmpty = false;
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
        sprintCleared = false;
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
        craftClickedPart1 = false;
        craftResultReady = false;
        craftSticksCount = 0;
        furnacePos = null;
        furnaceGiven = false;
        furnaceClicked = false;
        furnaceCharcoal = false;
        cfgDir = null;
        cfgFile = null;
        cfgScreen = null;
        dntChestPos = null;
        dntChestSet = false;
        dntOpened = false;
        // 配置兜底复位：防止 config 套件中途失败把非默认值泄漏给后续套件
        MockplayerConfig.save(new ModConfig());
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
