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

    private static final long TIMEOUT_MS = 120_000;

    /** 全部套件（suite=all / IDE 默认入口时按序连续跑） */
    private static final List<String> ALL_SUITES = List.of(
            "api-smoke", "api-full", "use-items", "containers", "containers-all", "crafting", "furnace",
            "combat-stab", "combat-sprint", "enchanting", "merchant", "gui-actions", "listener-events", "control-commands");

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
                        if (sp != null) {
                            System.out.println("[mocktest] diag giveAfter slot0=" + sp.getInventory().getItem(0)
                                    + " dirtCount=" + sp.getInventory().countItem(net.minecraft.world.item.Items.DIRT));
                        }
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
                    server.execute(() -> System.out.println("[mocktest] diag place pos="
                            + server.getLevel(Level.OVERWORLD).getBlockState(afPlacePos)
                            + " clientHand=" + bot.getLocalPlayer().getMainHandItem()));
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
                    server.execute(() -> System.out.println("[mocktest] diag mine server="
                            + server.getLevel(Level.OVERWORLD).getBlockState(afPlacePos)
                            + " client=" + bot.getBlockState(afPlacePos)
                            + " loaded=" + bot.isBlockLoaded(afPlacePos)));
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
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    net.minecraft.server.level.ServerLevel lv = server.getLevel(Level.OVERWORLD);
                    System.out.println("[mocktest] diag chest server=" + lv.getBlockState(afChestPos)
                            + " client=" + bot.getBlockState(afChestPos)
                            + " dirtPos=" + lv.getBlockState(afPlacePos)
                            + " awaiting=" + (sp != null && isAwaitingPosition(sp))
                            + " serverMenu=" + (sp != null ? sp.containerMenu : "null"));
                    fail("container open timeout");
                    step = 9;
                }
            }
            case 9 -> {
                // BotManager / MockplayerApi：getBot/getBots/getBots(owner)/allBots
                check("getBot found", MockplayerApi.bots().getBot(botName).isPresent());
                check("getBots contains", MockplayerApi.bots().getBots().stream().anyMatch(b -> botName.equals(b.getName())));
                check("getBots(owner=test) contains", MockplayerApi.bots().getBots("test").stream().anyMatch(b -> botName.equals(b.getName())));
                check("allBots contains", MockplayerApi.allBots().stream().anyMatch(b -> botName.equals(b.getName())));
                // removeBot 幂等
                check("removeBot owner ok", MockplayerApi.bots().removeBot(botName, "test") == RemoveResult.REMOVED);
                check("removeBot not found", MockplayerApi.bots().removeBot(botName, "test") == RemoveResult.NOT_FOUND);
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
    private static volatile boolean uiSnowballVisibleToMain;
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
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                    if (sp != null) {
                        // 服务端 + 主玩家视角：丢雪球对主玩家可见（雪球实体出现在主玩家 level）
                        uiSnowballServer = !sp.level().getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty();
                        uiSnowballVisibleToMain = !mc.level.getEntitiesOfClass(
                                net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball.class,
                                new net.minecraft.world.phys.AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty();
                    }
                });
                if (uiSnowballServer) {
                    check("snowball thrown (server)", true);
                    check("snowball throw visible to main player", uiSnowballVisibleToMain);
                    step = 11;
                } else if (++waitTicks > 200) {
                    fail("snowball not thrown timeout");
                    step = 11;
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
                MockplayerApi.bots().removeBot(botName, "test");
                finishSuite();
            }
        }
    }

    // ===== control-commands：/control 遥控+查询命令集强测试（命令层走 ControlCommands，
    // 底层真实网络包 + 服务端强断言；全部输出 i18n；Tab 补全逐位断言） =====

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
    private static int ccMineTestTicks;
    private static volatile boolean ccMineSpReady;
    private static volatile BlockPos ccMineStone1;
    private static volatile BlockPos ccMineStone2;
    private static volatile BlockPos ccMineStoneFar;
    private static boolean ccMineFarSent;
    private static boolean ccMineFarChecked;
    private static boolean ccMineFarWaitDone;
    private static volatile boolean ccMineFarStill;
    private static volatile boolean ccMineStone1Air;
    private static volatile boolean ccMineStone2Still;
    private static volatile String ccMineMainHand = "?";
    private static volatile float ccMineDigSpeed = -1.0F;
    private static volatile boolean ccMineCorrectTool = false;
    private static volatile float ccMineDestroyProgressRate = -1.0F;
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
                dispatcher.register(com.mockplayer.session.ControlCommands.buildCommandTree(
                        new com.mockplayer.session.ControlCommands.CommandFactory<net.minecraft.commands.CommandSourceStack>() {
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
                        }));
                var root = dispatcher.getRoot().getChildren();
                var control = root.stream().filter(n -> n.getName().equals("control")).findFirst().orElse(null);
                check("tree control root", control != null);
                if (control == null) {
                    fail("tree control missing");
                    finishSuite();
                    return;
                }
                check("tree list", control.getChildren().stream().anyMatch(n -> n.getName().equals("list")));
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
                java.util.List<String> expected = java.util.List.of(
                        "move", "stop", "sneak", "unsneak", "sprint", "unsprint", "jump",
                        "look", "lookAt", "turn", "attack", "stab", "sustainedAttack", "sustainedUse",
                        "stopSustained", "interact", "useItem", "releaseUsingItem", "useItemOn",
                        "placeBlock", "mineBlock", "attackBlock", "hotbar", "drop", "swapHands",
                        "mount", "dismount", "chat", "command", "wakeUp", "respawn", "editBook",
                        "close", "click", "button", "trade", "setSlot", "editSign", "setBeacon",
                        "renameItem", "pickItemFromBlock",
                        "info", "inventory", "container", "near", "block", "online", "chatlog", "listen", "events");
                java.util.List<String> missing = new java.util.ArrayList<>(expected);
                missing.removeAll(subs);
                check("tree actions", missing.isEmpty(), "missing=" + missing);
                net.minecraft.commands.CommandSourceStack stack = server.createCommandSourceStack();
                java.util.List<String> sugg = ccCompletions(dispatcher, stack, "control ");
                check("tab bots+list", sugg.contains(botName) && sugg.contains("list"));
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " move ");
                check("tab move dirs", sugg.containsAll(java.util.List.of("forward", "backward", "left", "right")));
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " hotbar ");
                check("tab hotbar", sugg.contains("1") && sugg.contains("9"));
                sugg = ccCompletions(dispatcher, stack, "control " + botName + " listen ");
                check("tab listen", sugg.containsAll(java.util.List.of("on", "off")), "sugg=" + sugg);
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
                        "commands.mockplayer.control.listen.on", "commands.mockplayer.control.event.onDamage",
                        "commands.mockplayer.control.action.attack")) {
                    check("i18n key " + key, !net.minecraft.network.chat.Component.translatable(key).getString().equals(key));
                }
                // list 查询
                String listText = com.mockplayer.session.ControlCommands.list().getString();
                check("list contains bot", listText.contains(botName) && listText.contains("test"),
                        "text=" + listText);
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
                    dispatcher.register(com.mockplayer.session.ControlCommands.buildCommandTree(
                            new com.mockplayer.session.ControlCommands.CommandFactory<net.minecraft.commands.CommandSourceStack>() {
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
                            }));
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
                        com.mockplayer.session.ControlCommands.attack(botName, "husk");
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
                    ccMineSent = true;
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
                } else {
                    check("mineBlock broke dirt (survival)", true);
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
            case 14 -> { // mount 矿车 + dismount
                if (!ccRailSet) {
                    ccRailSet = true;
                    waitTicks = 0;
                    server.execute(() -> {
                        var p = bot.getLocalPlayer();
                        var cartPos = p.blockPosition().offset(2, 0, 0);
                        // 铁轨上召唤矿车：矿车稳定停靠，不会因坠落/碰撞被服务端自动弹出乘客（旧场景假绿根因）
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock " + cartPos.getX() + " " + cartPos.getY() + " " + cartPos.getZ()
                                        + " minecraft:rail");
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
                        if (!ccDismountSent) {
                            ccDismountSent = true;
                            com.mockplayer.session.ControlCommands.dismount(botName);
                        }
                    }
                } else {
                    ccMountStableTicks = 0;
                }
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayerByName(botName);
                    ccDismounted = sp != null && sp.getVehicle() == null;
                });
                // 必须先 mount 成功才允许 dismount PASS：没上马时 getVehicle()==null 恒真，会假绿
                if (ccDismounted && ccMountChecked) {
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
                    String containerText = com.mockplayer.session.ControlCommands.container(botName).getString();
                    String infoText = com.mockplayer.session.ControlCommands.botInfo(botName).getString();
                    String invText = com.mockplayer.session.ControlCommands.inventory(botName).getString();
                    String nearText = com.mockplayer.session.ControlCommands.near(botName, 16.0).getString();
                    var p = bot.getLocalPlayer();
                    String blockText = com.mockplayer.session.ControlCommands.blockAt(
                            botName, p.blockPosition().getX(), p.blockPosition().getY() - 1, p.blockPosition().getZ()).getString();
                    String onlineText = com.mockplayer.session.ControlCommands.online(botName).getString();
                    String chatText = com.mockplayer.session.ControlCommands.chatHistory(botName).getString();
                    check("query container", containerText.contains("id="), "text=" + containerText);
                    check("query info", infoText.contains(botName) && !infoText.contains("commands.mockplayer.control."));
                    check("query inventory", invText.contains(" x"), "text=" + invText);
                    check("query near", nearText.contains("villager"), "text=" + nearText);
                    check("query block", blockText.contains("minecraft:"), "text=" + blockText);
                    check("query online", onlineText.contains(mc.player.getGameProfile().name()) && onlineText.contains(botName));
                    check("query chat", chatText.contains("mockplayer-ctl-chat"));
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
                    String onText = com.mockplayer.session.ControlCommands.listen(botName, true).getString();
                    check("listen on feedback", onText.contains(botName), "text=" + onText);
                    if (!ccListenDamaged) {
                        ccListenDamaged = true;
                        server.execute(() -> server.getCommands().performPrefixedCommand(
                                server.createCommandSourceStack(), "damage " + botName + " 4"));
                    }
                }
                if (ccListenDamaged && !ccEventsHasDamage) {
                    com.mockplayer.session.EventRecorder recorder = com.mockplayer.session.ControlCommands.getRecorder(botName);
                    ccEventsHasDamage = recorder != null && recorder.getPushCount() >= 1
                            && recorder.snapshot().stream().anyMatch(s -> s.startsWith("onDamage|"));
                }
                if (ccEventsHasDamage) {
                    check("listen recorded+push damage event", true);
                    if (!ccListenOff) {
                        ccListenOff = true;
                        String offText = com.mockplayer.session.ControlCommands.listen(botName, false).getString();
                        check("listen off feedback", offText.contains(botName), "text=" + offText);
                        check("listen off removes recorder", com.mockplayer.session.ControlCommands.getRecorder(botName) == null);
                        String notText = com.mockplayer.session.ControlCommands.events(botName, 10).getString();
                        check("events after off says not listening", notText.contains(botName), "text=" + notText);
                        step = 17;
                    }
                } else if (++waitTicks > 200) {
                    com.mockplayer.session.EventRecorder recorder = com.mockplayer.session.ControlCommands.getRecorder(botName);
                    fail("listen timeout recorder=" + (recorder != null)
                            + " push=" + (recorder != null ? recorder.getPushCount() : -1)
                            + " snap=" + (recorder != null ? recorder.snapshot().stream().limit(5).toList() : "[]"));
                    com.mockplayer.session.ControlCommands.listen(botName, false);
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
                        if (s.isBlank() || s.contains("commands.mockplayer.control.")) {
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
                    String noneText = com.mockplayer.session.ControlCommands.container(botName).getString();
                    check("container none text", noneText.contains(botName) && !noneText.contains("%s"),
                            "text=" + noneText);
                    // 清掉 outputs 里 move/jump 等持续输入，否则假人带着前进+跳跃进入挖掘测试
                    // （onGround=false → 挖掘速度 /5，mine time 测试会假失败）
                    com.mockplayer.session.ControlCommands.stop(botName);
                    step = 19;
                }
            }
            case 19 -> { // 挖掘时间原版锁定 + stopSustained 取消挖掘（服务端证据）
                if (!ccMineTestSet) {
                    ccMineTestSet = true;
                    waitTicks = 0;
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
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                // give 不覆盖主手（selected 槽还有木棍），必须 replace 才保证用石镐挖
                                "item replace entity " + botName + " weapon.mainhand with minecraft:stone_pickaxe");
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
                    if (++waitTicks > 20 && bot.getBlockState(ccMineStone1).is(net.minecraft.world.level.block.Blocks.STONE)
                            && bot.getBlockState(ccMineStone2).is(net.minecraft.world.level.block.Blocks.STONE)) {
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
            case 20 -> {
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
                MockplayerApi.bots().removeBot(botName, "test");
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
                    MockplayerApi.bots().removeBot("tbot-le2", "test"); // 第二个假人离开 → 主假人 onPlayerLeft
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
    private static volatile boolean stabSpearServer;
    private static boolean stabAttacked;
    private static boolean sprintSummoned;
    private static boolean sprintCleared;
    private static boolean sprintSpearGiven;
    private static volatile boolean sprintSpearServer;
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
                        bot.getLocalPlayer().getInventory().setSelectedSlot(0); // 固定主手（item replace 后 selected 可能被切换走）
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
                    // 诊断：超时打印假人移动/疾跑状态
                    server.execute(() -> {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            System.out.println("[mocktest] diag sprint sp=" + sp.position()
                                    + " onGround=" + sp.onGround()
                                    + " sprinting=" + sp.isSprinting()
                                    + " base=" + fakeMoveBaseX + "," + fakeMoveBaseZ
                                    + " below=" + sp.level().getBlockState(sp.blockPosition().below()).getBlock());
                        }
                    });
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
    private static volatile boolean containerAllServerPut;
    private static volatile boolean containerAllServerEmpty;
    private static boolean lecternBookGiven;
    private static boolean lecternBookPlaced;
    private static int lecternOpenWait;
    private static boolean horseSummoned;
    private static boolean horseInteracted;

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
                server.execute(() -> {
                    final int idx = containerAllCaseIndex;
                    ContainerCase c = CONTAINER_CASES.get(idx);
                    if ("lectern".equals(c.name())) {
                        containerAllServerPut = !server.getLevel(Level.OVERWORLD).getBlockState(containerAllPos)
                                .getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK); // 书被取走
                    } else {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            // 部分容器（砂轮/切石机等）放入即处理到结果槽，验证容器槽或结果槽有物品
                            boolean inContainer = !sp.containerMenu.getSlot(c.containerSlot()).getItem().isEmpty();
                            boolean inResult = c.resultSlot() >= 0 && !sp.containerMenu.getSlot(c.resultSlot()).getItem().isEmpty();
                            containerAllServerPut = inContainer || inResult;
                        }
                    }
                });
                if (containerAllServerPut) {
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
                server.execute(() -> {
                    final int idx = containerAllCaseIndex;
                    ContainerCase c = CONTAINER_CASES.get(idx);
                    if ("lectern".equals(c.name())) {
                        containerAllServerEmpty = bot.getLocalPlayer().getInventory()
                                .countItem(net.minecraft.world.item.Items.WRITABLE_BOOK) > 0; // 书取回到背包
                    } else {
                        net.minecraft.server.level.ServerPlayer sp = server.getPlayerList().getPlayerByName(botName);
                        if (sp != null) {
                            boolean cEmpty = sp.containerMenu.getSlot(c.containerSlot()).getItem().isEmpty();
                            boolean rEmpty = c.resultSlot() < 0 || sp.containerMenu.getSlot(c.resultSlot()).getItem().isEmpty();
                            containerAllServerEmpty = cEmpty && rEmpty;
                        }
                    }
                });
                if (containerAllServerEmpty) {
                    check("take back from " + CONTAINER_CASES.get(containerAllCaseIndex).name() + " (server)", true);
                    bot.getContainer().ifPresent(cont -> cont.close());
                    containerAllCaseIndex++;
                    if (containerAllCaseIndex < CONTAINER_CASES.size()) {
                        containerAllPos = null;
                        containerAllOpened = false;
                        containerAllGiven = false;
                        containerAllPut = false;
                        containerAllTaken = false;
                        containerAllServerPut = false;
                        containerAllServerEmpty = false;
                        lecternBookGiven = false;
                        lecternBookPlaced = false;
                        horseSummoned = false;
                        horseInteracted = false;
                        waitTicks = 0;
                        step = 1;
                    } else {
                        step = 7;
                    }
                } else if (++waitTicks > 200) {
                    fail("take back from " + CONTAINER_CASES.get(containerAllCaseIndex).name() + " timeout");
                    containerAllCaseIndex++;
                    step = (containerAllCaseIndex < CONTAINER_CASES.size()) ? 1 : 7;
                }
            }
            case 7 -> {
                MockplayerApi.bots().removeBot(botName, "test");
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
