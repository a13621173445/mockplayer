package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.event.BotListener;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.BotImpl;
import com.mockplayer.session.ControlCommands;
import com.mockplayer.session.EventRecorder;
import com.mockplayer.session.FakePlayerNameArgument;
import com.mockplayer.session.QueryCommands;
import com.mockplayer.session.SessionManager;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * control-commands：/control 与 /query 命令层强测试（命令树/Tab 补全/i18n/转义/
 * 动作端到端/查询/memory 精确记账/listen/respawn/挖掘时间/chunkRadius/射线交互）。
 */
public class ControlCommandsSuite extends TestSuite {

    private String lastBotName;

    public ControlCommandsSuite() {
        super("control-commands");
        test("命令树/Tab 补全/i18n/list", this::treeCompletions);
        test("move/stop", this::moveStop);
        test("look/turn/lookAt", this::lookTurn);
        test("query 全断言", this::queryAll);
        test("listen 实时事件", this::listen);
        test("错误路径/全动作 i18n/转义", this::errorsAndI18n);
        test("挖掘时间与距离", this::mineTime);
        test("chunkRadius", this::chunkRadius);
        test("换维隔离注册表", this::dimensionIsolation);
        test("容器命令路径 click/setSlot/close", this::containerCommandPaths);
        test("射线交互", this::raycast);
    }

    private void createBot(TestContext ctx) {
        // 每个用例强制独立 bot：唯一名（不同 UUID，不继承玩家数据），绝不复用
        lastBotName = SuitesSupport.createUniqueBot(ctx, "ctl");
    }

    @Override
    public void after() {
        if (lastBotName != null) {
            MockplayerApi.bots().removeBot(lastBotName, "command");
            lastBotName = null;
        }
    }

    private void treeCompletions(TestContext ctx) {
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<CommandSourceStack>();
            registerAll(dispatcher);
            var root = dispatcher.getRoot().getChildren();
            var control = root.stream().filter(n -> n.getName().equals("control")).findFirst().orElse(null);
            ctx.checkNow("tree control root", control != null);
            if (control == null) {
                return;
            }
            ctx.checkNow("tree control top has no list", control.getChildren().stream()
                    .noneMatch(n -> n.getName().equals("list")));
            var playerNode = control.getChildren().stream().filter(n -> n.getName().equals("player"))
                    .findFirst().orElse(null);
            if (playerNode == null) {
                return;
            }
            Set<String> subs = playerNode.getChildren().stream()
                    .map(com.mojang.brigadier.tree.CommandNode::getName)
                    .collect(Collectors.toSet());
            List<String> actions = List.of(
                    "move", "stop", "sneak", "unsneak", "sprint", "unsprint", "jump",
                    "look", "lookAt", "turn", "attack", "stab", "sustainedAttack", "sustainedUse",
                    "attackLook", "useLook", "sustainedAttackLook", "sustainedUseLook",
                    "stopSustained", "interact", "useItem", "releaseUsingItem", "useItemOn",
                    "placeBlock", "mineBlock", "attackBlock", "hotbar", "chunkRadius", "drop", "swapHands",
                    "mount", "dismount", "chat", "command", "wakeUp", "respawn", "editBook",
                    "close", "click", "button", "trade", "setSlot", "editSign", "setBeacon",
                    "renameItem", "pickItemFromBlock", "help");
            List<String> missing = new ArrayList<>(actions);
            missing.removeAll(subs);
            List<String> leaked = subs.stream().filter(s -> !actions.contains(s)).toList();
            ctx.checkNow("tree actions complete", missing.isEmpty(), "missing=" + missing);
            ctx.checkNow("tree control no query leak", leaked.isEmpty(), "leaked=" + leaked);
            String helpText = ControlCommands.help(ctx.botName()).getString();
            ctx.checkNow("help lists all actions",
                    helpText.lines().count() == ControlCommands.ACTIONS.size() + 1);
            ctx.checkNow("help action translated", helpText.contains(
                    Component.translatable("commands.mockplayer.control.action.attack").getString()));
            var query = root.stream().filter(n -> n.getName().equals("query")).findFirst().orElse(null);
            ctx.checkNow("tree query root", query != null);
            if (query == null) {
                return;
            }
            ctx.checkNow("tree query top list", query.getChildren().stream()
                    .anyMatch(n -> n.getName().equals("list")));
            var qPlayer = query.getChildren().stream().filter(n -> n.getName().equals("player"))
                    .findFirst().orElse(null);
            ctx.checkNow("tree query player", qPlayer != null);
            if (qPlayer == null) {
                return;
            }
            Set<String> qSubs = qPlayer.getChildren().stream()
                    .map(com.mojang.brigadier.tree.CommandNode::getName)
                    .collect(Collectors.toSet());
            List<String> queries = List.of(
                    "info", "inventory", "container", "near", "block", "chunk", "online", "chatlog",
                    "listen", "events", "memory");
            List<String> qMissing = new ArrayList<>(queries);
            qMissing.removeAll(qSubs);
            List<String> qLeaked = qSubs.stream().filter(s -> !queries.contains(s)).toList();
            ctx.checkNow("tree query complete", qMissing.isEmpty(), "missing=" + qMissing);
            ctx.checkNow("tree query no action leak", qLeaked.isEmpty(), "leaked=" + qLeaked);
            CommandSourceStack stack = ctx.server().createCommandSourceStack();
            List<String> sugg = completions(dispatcher, stack, "control ");
            ctx.checkNow("tab control bots only", sugg.contains(ctx.botName()) && !sugg.contains("list"),
                    "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "query ");
            ctx.checkNow("tab query bots+list", sugg.contains(ctx.botName()) && sugg.contains("list"), "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "query " + ctx.botName() + " ");
            ctx.checkNow("tab query subs", sugg.containsAll(queries), "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " move ");
            ctx.checkNow("tab move dirs", sugg.containsAll(List.of("forward", "backward", "left", "right")));
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " hotbar ");
            ctx.checkNow("tab hotbar", sugg.contains("1") && sugg.contains("9"));
            sugg = completions(dispatcher, stack, "query " + ctx.botName() + " listen ");
            ctx.checkNow("tab query listen", sugg.containsAll(List.of("on", "off")), "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " useItem ");
            ctx.checkNow("tab hands", sugg.containsAll(List.of("mainhand", "offhand")));
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " placeBlock 0 0 0 ");
            ctx.checkNow("tab sides", sugg.containsAll(List.of("north", "south", "east", "west", "up", "down")),
                    "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " setBeacon ");
            ctx.checkNow("tab effects", sugg.contains("minecraft:speed"), "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " drop 0 ");
            ctx.checkNow("tab drop modes", sugg.containsAll(List.of("one", "all")));
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " command ");
            ctx.checkNow("tab nested command root", sugg.contains("time") && sugg.contains("gamemode"),
                    "sugg=" + sugg);
            sugg = completions(dispatcher, stack, "control " + ctx.botName() + " command time ");
            ctx.checkNow("tab nested command sub", sugg.contains("set"), "sugg=" + sugg);
            for (String key : List.of(
                    "commands.mockplayer.control.success", "commands.mockplayer.control.not_found",
                    "commands.mockplayer.control.action.attack", "commands.mockplayer.control.suggest.yaw",
                    "commands.mockplayer.query.listen.on", "commands.mockplayer.query.events.not_listening",
                    "commands.mockplayer.query.event.onDamage", "commands.mockplayer.query.memory.jvm")) {
                ctx.checkNow("i18n key " + key,
                        !Component.translatable(key).getString().equals(key));
            }
            String listText = QueryCommands.list().getString();
            ctx.checkNow("list contains bot", listText.contains(ctx.botName()) && listText.contains("command"),
                    "text=" + listText.replace("\n", "|"));
        });
    }

    private void moveStop(TestContext ctx) {
        AtomicReference<double[]> base = new AtomicReference<>();
        AtomicReference<double[]> cur = new AtomicReference<>();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    base.set(new double[]{sp.getX(), sp.getZ()});
                }
            });
            ControlCommands.move(ctx.botName(), "forward");
        });
        ctx.await("move forward moved on server", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    cur.set(new double[]{sp.getX(), sp.getZ()});
                }
            });
            double[] b = base.get();
            double[] c = cur.get();
            return b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 0.5 || Math.abs(c[1] - b[1]) > 0.5);
        }, 200);
        ctx.run(() -> ControlCommands.stop(ctx.botName()));
        ctx.await("stop clears input", () -> ctx.bot().getLocalPlayer().input.getMoveVector().y == 0.0F, 20);
        ctx.check("move forward moved on server", () -> {
            double[] b = base.get();
            double[] c = cur.get();
            return b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 0.5 || Math.abs(c[1] - b[1]) > 0.5);
        });
        ctx.check("stop clears input", () -> ctx.bot().getLocalPlayer().input.getMoveVector().y == 0.0F);
    }

    private void lookTurn(TestContext ctx) {
        AtomicReference<Float> serverYRot = new AtomicReference<>(-999.0F);
        AtomicInteger stage = new AtomicInteger();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            ControlCommands.look(ctx.botName(), 30.0F, 20.0F);
            ctx.checkNow("look local yRot", Math.abs(ctx.bot().getLocalPlayer().getYRot() - 30.0F) < 1.0F);
            ctx.checkNow("look local xRot", Math.abs(ctx.bot().getLocalPlayer().getXRot() - 20.0F) < 1.0F);
        });
        ctx.await("look server yRot", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    serverYRot.set(sp.getYRot());
                }
            });
            return Math.abs((((serverYRot.get() % 360) + 360) % 360) - 30.0F) < 5.0F;
        }, 100);
        ctx.check("look server yRot", () ->
                Math.abs((((serverYRot.get() % 360) + 360) % 360) - 30.0F) < 5.0F);
        ctx.run(() -> {
            ControlCommands.turn(ctx.botName(), 90.0F, 10.0F);
            ctx.checkNow("turn local yRot", Math.abs((((ctx.bot().getLocalPlayer().getYRot() % 360) + 360) % 360) - 120.0F) < 1.0F);
            ctx.checkNow("turn local xRot", Math.abs(ctx.bot().getLocalPlayer().getXRot() - 30.0F) < 1.0F);
        });
        ctx.await("turn server yRot", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    serverYRot.set(sp.getYRot());
                }
            });
            return Math.abs((((serverYRot.get() % 360) + 360) % 360) - 120.0F) < 5.0F;
        }, 100);
        ctx.check("turn server yRot", () ->
                Math.abs((((serverYRot.get() % 360) + 360) % 360) - 120.0F) < 5.0F);
        ctx.run(() -> {
            var p = ctx.bot().getLocalPlayer();
            ControlCommands.lookAt(ctx.botName(), p.getX(), p.getY(), p.getZ() + 5.0);
            ctx.checkNow("lookAt local yRot ~south", Math.abs((((ctx.bot().getLocalPlayer().getYRot() % 360) + 360) % 360)) < 5.0F);
        });
        ctx.await("lookAt server yRot", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    serverYRot.set(sp.getYRot());
                }
            });
            return Math.abs((((serverYRot.get() % 360) + 360) % 360)) < 5.0F;
        }, 100);
        ctx.check("lookAt server yRot", () ->
                Math.abs((((serverYRot.get() % 360) + 360) % 360)) < 5.0F);
    }

    private void attackAll(TestContext ctx) {
        AtomicReference<Float> hp = new AtomicReference<>(20.0F);
        AtomicBoolean attacked = new AtomicBoolean();
        AtomicBoolean targetAttacked = new AtomicBoolean();
        AtomicBoolean sustainedHit = new AtomicBoolean();
        AtomicReference<Vec3> huskEye = new AtomicReference<>();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                sp.getX() + 2.0, sp.getY(), sp.getZ()));
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:diamond_sword");
            }
        }));
        ctx.await("attack husk damaged", () -> {
            boolean huskVisible = ctx.bot().getEntitiesNear(16).stream()
                    .anyMatch(e -> e instanceof Zombie);
            if (!attacked.get() && huskVisible
                    && (ctx.bot().getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F)) {
                attacked.set(true);
                ControlCommands.attack(ctx.botName(), null);
            }
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                if (level != null) {
                    level.getEntitiesOfClass(Zombie.class, ctx.bot().getLocalPlayer().getBoundingBox().inflate(8.0))
                            .stream().findFirst().ifPresent(h -> hp.set(h.getHealth()));
                }
            });
            return hp.get() < 20.0F;
        }, 160);
        ctx.check("attack nearest husk damaged", () -> hp.get() < 20.0F);
        ctx.await("attack target damaged", () -> {
            if (!targetAttacked.get() && (ctx.bot().getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F)) {
                targetAttacked.set(true);
                ctx.bot().actions().look(90.0F, 0.0F);
                huskEye.set(ctx.bot().getEntitiesNear(16.0).stream()
                        .filter(e -> e instanceof Zombie)
                        .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(ctx.bot().getLocalPlayer())))
                        .map(Entity::getEyePosition).orElse(null));
                ControlCommands.attack(ctx.botName(), "husk");
                ctx.checkNow("attack auto-face target",
                        facing(ctx.bot().getLocalPlayer(), huskEye.get(), 10.0F, 15.0F));
            }
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                if (level != null) {
                    level.getEntitiesOfClass(Zombie.class, ctx.bot().getLocalPlayer().getBoundingBox().inflate(8.0))
                            .stream().findFirst().ifPresent(h -> hp.set(h.getHealth()));
                }
            });
            return hp.get() < 10.0F;
        }, 120);
        ctx.check("attack target Husk damaged", () -> hp.get() < 10.0F);
        ctx.run(() -> ControlCommands.sustainedAttack(ctx.botName(), null));
        ctx.await("sustainedAttack hit repeatedly", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                if (level != null) {
                    level.getEntitiesOfClass(Zombie.class, ctx.bot().getLocalPlayer().getBoundingBox().inflate(8.0))
                            .stream().findFirst().ifPresent(h -> {
                                if (h.getHealth() < hp.get()) {
                                    sustainedHit.set(true);
                                }
                                hp.set(h.getHealth());
                            });
                }
            });
            return sustainedHit.get();
        }, 160);
        ctx.check("sustainedAttack hit repeatedly", sustainedHit::get);
        ctx.run(() -> {
            ControlCommands.stopSustained(ctx.botName());
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
            MockplayerApi.bots().removeBot(ctx.botName(), "command");
        });
    }

    private void interactVillager(TestContext ctx) {
        AtomicBoolean open = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:diamond\",count:1},maxUses:99,xp:1}]}}",
                                sp.getX() + 1.0, sp.getY(), sp.getZ()));
            }
        }));
        ctx.await("villager near", () -> ctx.bot().getEntitiesNear(16).stream()
                .anyMatch(e -> e instanceof Villager), 200);
        ctx.run(() -> ControlCommands.interact(ctx.botName(), null));
        ctx.await("interact villager opened merchant", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                open.set(sp != null && sp.containerMenu instanceof MerchantMenu);
            });
            return open.get();
        }, 120);
        ctx.check("interact villager opened merchant", open::get);
        ctx.run(() -> {
            ctx.bot().getContainer().ifPresent(c -> c.close());
            MockplayerApi.bots().removeBot(ctx.botName(), "command");
        });
    }

    private void hotbarDropSwap(TestContext ctx) {
        AtomicBoolean hotbarVerified = new AtomicBoolean();
        AtomicBoolean dropped = new AtomicBoolean();
        AtomicBoolean swapped = new AtomicBoolean();
        AtomicReference<String> mainBefore = new AtomicReference<>("");
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            mainBefore.set(Minecraft.getInstance().player.getMainHandItem().getHoverName().getString());
            ctx.server().execute(() -> {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " hotbar.0 with minecraft:stone");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " hotbar.1 with minecraft:oak_planks");
            });
        });
        ctx.await("hotbar synced", () -> ctx.bot().getLocalPlayer().getInventory()
                .getItem(1).is(Items.OAK_PLANKS), 200);
        ctx.run(() -> ControlCommands.hotbar(ctx.botName(), 2));
        ctx.await("hotbar switched bot slot", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                hotbarVerified.set(sp != null && sp.getInventory().getSelectedSlot() == 1
                        && Minecraft.getInstance().player.getMainHandItem().getHoverName()
                        .getString().equals(mainBefore.get()));
            });
            return hotbarVerified.get();
        }, 120);
        ctx.check("hotbar switched bot slot, main player untouched", hotbarVerified::get);
        ctx.run(() -> ControlCommands.drop(ctx.botName(), 1, false));
        ctx.await("drop slot 1 removed item", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                dropped.set(sp != null && sp.getInventory().getItem(1).isEmpty());
            });
            return dropped.get();
        }, 200);
        ctx.check("drop slot 1 removed item", dropped::get);
        ctx.run(() -> ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:stone_sword");
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + ctx.botName() + " weapon.offhand with minecraft:stick");
        }));
        ctx.await("swap items synced", () -> ctx.bot().getLocalPlayer().getOffhandItem()
                .is(Items.STICK), 200);
        ctx.run(() -> ControlCommands.swapHands(ctx.botName()));
        ctx.await("swapHands exchanged", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                swapped.set(sp != null && sp.getMainHandItem().is(Items.STICK)
                        && sp.getOffhandItem().is(Items.STONE_SWORD));
            });
            return swapped.get();
        }, 120);
        ctx.check("swapHands exchanged main/off hand", swapped::get);
    }

    private void chatUseItem(TestContext ctx) {
        AtomicReference<String> msg = new AtomicReference<>("");
        AtomicBoolean using = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean useChecked = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            MockplayerApi.listen(new com.mockplayer.api.event.BotListener() {
                @Override
                public void onChat(com.mockplayer.api.Bot b, Component message) {
                    msg.set(message.getString());
                }
            });
            msg.set("");
            ControlCommands.chat(ctx.botName(), "mockplayer-ctl-chat");
        });
        ctx.await("chat command broadcast as bot", () -> msg.get().contains("mockplayer-ctl-chat"), 200);
        ctx.check("chat command broadcast as bot", () -> msg.get().contains("mockplayer-ctl-chat"));
        ctx.run(() -> {
            msg.set("");
            ControlCommands.command(ctx.botName(), "me mockplayer-ctl-cmd");
        });
        ctx.await("command me executed", () -> msg.get().contains("mockplayer-ctl-cmd"), 200);
        ctx.check("command time set executed", () -> msg.get().contains("mockplayer-ctl-cmd"));
        ctx.run(() -> {
            ctx.bot().getContainer().ifPresent(c -> c.close());
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(),
                    "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:bread"));
        });
        ctx.await("bread in hand", () -> ctx.bot().getLocalPlayer().getMainHandItem().is(Items.BREAD), 300);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                sp.getFoodData().setFoodLevel(2);
                sp.getFoodData().setSaturation(0.0F);
            }
        }));
        ctx.await("bread hunger synced", () -> ctx.bot().getLocalPlayer()
                .getFoodData().getFoodLevel() <= 6, 100);
        ctx.run(() -> ControlCommands.useItem(ctx.botName(), "mainhand"));
        ctx.await("useItem started using", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                using.set(sp != null && sp.isUsingItem());
            });
            return using.get();
        }, 120);
        ctx.run(() -> {
            if (!useChecked.get()) {
                useChecked.set(true);
                ControlCommands.releaseUsingItem(ctx.botName());
            }
        });
        ctx.await("releaseUsingItem stopped using", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                released.set(sp != null && !sp.isUsingItem());
            });
            return released.get();
        }, 120);
        ctx.check("useItem started using", using::get);
        ctx.check("releaseUsingItem stopped using", released::get);
    }

    private void wakeUp(TestContext ctx) {
        AtomicReference<BlockPos> bed = new AtomicReference<>();
        AtomicBoolean sleeping = new AtomicBoolean();
        AtomicBoolean woke = new AtomicBoolean();
        AtomicBoolean clicked = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "time set 13000");
            var p = ctx.bot().getLocalPlayer();
            bed.set(p.blockPosition().offset(0, 0, 2));
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "setblock " + bed.get().getX() + " " + bed.get().getY() + " " + bed.get().getZ()
                            + " minecraft:red_bed[facing=south,part=head]");
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "setblock " + bed.get().getX() + " " + bed.get().getY() + " " + (bed.get().getZ() - 1)
                            + " minecraft:red_bed[facing=south,part=foot]");
            p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(bed.get()));
        }));
        ctx.await("bed visible", () -> bed.get() != null
                && ctx.bot().getBlockState(bed.get()).is(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.Identifier.tryParse("minecraft:red_bed")).get().value()), 200);
        ctx.run(() -> {
            if (!clicked.get()) {
                clicked.set(true);
                ControlCommands.useItemOn(ctx.botName(), bed.get().getX(), bed.get().getY(), bed.get().getZ(), "up");
            }
        });
        ctx.await("useItemOn bed sleeping", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                sleeping.set(sp != null && sp.isSleeping());
            });
            return sleeping.get();
        }, 200);
        ctx.run(() -> ControlCommands.wakeUp(ctx.botName()));
        ctx.await("wakeUp stopped sleeping", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                woke.set(sp != null && !sp.isSleeping());
            });
            return woke.get();
        }, 200);
        ctx.check("useItemOn bed sleeping", sleeping::get);
        ctx.check("wakeUp stopped sleeping", woke::get);
    }

    private void mountDismount(TestContext ctx) {
        AtomicBoolean mounted = new AtomicBoolean();
        AtomicBoolean moved = new AtomicBoolean();
        AtomicBoolean dismounted = new AtomicBoolean();
        AtomicBoolean pushed = new AtomicBoolean();
        AtomicReference<Double> startX = new AtomicReference<>(0.0);
        int[] stable = {0};
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            var p = ctx.bot().getLocalPlayer();
            var cartPos = p.blockPosition().offset(2, 0, 0);
            for (int i = 0; i < 5; i++) {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "setblock " + (cartPos.getX() + i) + " " + cartPos.getY() + " " + cartPos.getZ()
                                + " minecraft:rail");
            }
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    String.format("summon minecraft:minecart %.2f %.2f %.2f",
                            cartPos.getX() + 0.5, cartPos.getY() + 0.5, cartPos.getZ() + 0.5));
        }));
        AtomicInteger cartDiag = new AtomicInteger();
        ctx.await("client sees minecart", () -> {
            AtomicInteger serverCarts = new AtomicInteger();
            ctx.server().execute(() -> serverCarts.set(ctx.server().getLevel(Level.OVERWORLD)
                    .getEntitiesOfClass(net.minecraft.world.entity.vehicle.minecart.Minecart.class,
                            new net.minecraft.world.phys.AABB(-128, -128, -128, 128, 128, 128)).size()));
            boolean client = ctx.bot().getEntitiesNear(16).stream()
                    .anyMatch(e -> e instanceof net.minecraft.world.entity.vehicle.minecart.Minecart);
            if (cartDiag.incrementAndGet() % 100 == 0) {
                System.out.println("[cart-diag] server=" + serverCarts.get() + " client=" + client);
            }
            return client;
        }, 200);
        ctx.run(() -> ControlCommands.mount(ctx.botName(), "minecart", null));
        ctx.await("mount minecart stable", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                mounted.set(sp != null && sp.getVehicle() != null);
            });
            if (!mounted.get()) {
                stable[0] = 0;
            } else if (++stable[0] >= 20) {
                return true;
            }
            return false;
        }, 200);
        ctx.check("mount minecart", mounted::get);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                startX.set(sp.getX());
            }
        }));
        ctx.await("cart moving", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null && sp.getVehicle() != null && !pushed.get()) {
                    pushed.set(true);
                    ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                            "data merge entity " + sp.getVehicle().getUUID()
                                    + " {Motion:[0.6d,0.0d,0.0d]}");
                }
                if (sp != null) {
                    moved.set(Math.abs(sp.getX() - startX.get()) > 0.5);
                }
            });
            return moved.get();
        }, 200);
        ctx.run(() -> ControlCommands.dismount(ctx.botName()));
        ctx.await("dismount", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                dismounted.set(sp != null && sp.getVehicle() == null);
            });
            return dismounted.get();
        }, 220);
        ctx.check("dismount while moving", moved::get);
        ctx.check("dismount", dismounted::get);
    }

    private void blockActions(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicReference<BlockPos> pos2 = new AtomicReference<>();
        AtomicReference<BlockPos> placeAt = new AtomicReference<>();
        AtomicBoolean dirtGiven = new AtomicBoolean();
        AtomicBoolean placed1 = new AtomicBoolean();
        AtomicBoolean picked = new AtomicBoolean();
        AtomicBoolean broke = new AtomicBoolean();
        AtomicBoolean placed2 = new AtomicBoolean();
        AtomicBoolean mined2 = new AtomicBoolean();
        AtomicBoolean placedAt = new AtomicBoolean();
        AtomicBoolean creativeOk = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            if (!dirtGiven.get()) {
                dirtGiven.set(true);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:dirt"));
            }
            if (pos.get() == null) {
                var p = ctx.bot().getLocalPlayer();
                pos.set(p.blockPosition().offset(0, 0, 2));
                pos2.set(p.blockPosition().offset(0, 0, 3));
                placeAt.set(p.blockPosition().offset(0, 0, 3));
            }
        });
        ctx.await("dirt in hand", () -> ctx.bot().getLocalPlayer().getMainHandItem().is(Items.DIRT), 200);
        ctx.run(() -> ControlCommands.placeBlock(ctx.botName(),
                pos.get().getX(), pos.get().getY(), pos.get().getZ(), "up"));
        ctx.await("placeBlock placed dirt", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                placed1.set(pos.get() != null && level != null
                        && level.getBlockState(pos.get()).is(Blocks.DIRT));
            });
            return placed1.get();
        }, 200);
        ctx.check("placeBlock placed dirt", placed1::get);
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(), "gamemode creative " + ctx.botName())));
        ctx.await("creative ready", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                creativeOk.set(sp != null && sp.isCreative());
            });
            return creativeOk.get();
        }, 100);
        ctx.await("dirt block still there", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                placed1.set(pos.get() != null && level != null
                        && level.getBlockState(pos.get()).is(Blocks.DIRT));
            });
            return placed1.get();
        }, 100);
        ctx.run(() -> ControlCommands.pickItemFromBlock(ctx.botName(),
                pos.get().getX(), pos.get().getY(), pos.get().getZ(), false));
        ctx.await("pickItemFromBlock put dirt in hand", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                picked.set(sp != null && sp.getMainHandItem().is(Items.DIRT));
            });
            return picked.get();
        }, 100);
        ctx.check("pickItemFromBlock put dirt in hand", picked::get);
        ctx.run(() -> ControlCommands.attackBlock(ctx.botName(),
                pos.get().getX(), pos.get().getY(), pos.get().getZ()));
        ctx.await("attackBlock broke dirt (creative)", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                broke.set(pos.get() != null && level != null
                        && level.getBlockState(pos.get()).is(Blocks.AIR));
            });
            return broke.get();
        }, 100);
        ctx.check("attackBlock broke dirt (creative)", broke::get);
        ctx.run(() -> {
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "gamemode survival " + ctx.botName()));
            ctx.bot().actions().look(180.0F, 0.0F);
            ControlCommands.placeBlock(ctx.botName(), pos2.get().getX(), pos2.get().getY(), pos2.get().getZ(), "up");
        });
        ctx.await("placeBlock placed dirt 2", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                placed2.set(pos2.get() != null && level != null
                        && level.getBlockState(pos2.get()).is(Blocks.DIRT));
            });
            return placed2.get();
        }, 200);
        ctx.check("placeBlock placed dirt 2", placed2::get);
        ctx.check("placeBlock auto-face target", () -> facing(ctx.bot().getLocalPlayer(),
                Vec3.atCenterOf(pos2.get()), 12.0F, 20.0F));
        ctx.run(() -> {
            ctx.bot().actions().look(180.0F, 0.0F);
            ControlCommands.mineBlock(ctx.botName(), pos2.get().getX(), pos2.get().getY(), pos2.get().getZ());
        });
        ctx.await("mineBlock broke dirt (survival)", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                mined2.set(pos2.get() != null && level != null
                        && level.getBlockState(pos2.get()).is(Blocks.AIR));
            });
            return mined2.get();
        }, 200);
        ctx.check("mineBlock broke dirt (survival)", mined2::get);
        ctx.check("mineBlock auto-face target", () -> facing(ctx.bot().getLocalPlayer(),
                Vec3.atCenterOf(pos2.get()), 12.0F, 20.0F));
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(),
                "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:dirt")));
        ctx.await("dirt in hand 2", () -> ctx.bot().getLocalPlayer().getMainHandItem().is(Items.DIRT), 200);
        ctx.run(() -> {
            ctx.bot().actions().lookAt(Vec3.atCenterOf(placeAt.get()));
            ctx.bot().actions().placeBlockAt(placeAt.get());
        });
        ctx.await("placeBlockAt placed dirt", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                placedAt.set(placeAt.get() != null && level != null
                        && level.getBlockState(placeAt.get()).is(Blocks.DIRT));
            });
            return placedAt.get();
        }, 200);
        ctx.check("placeBlockAt placed dirt at exact pos (server)", placedAt::get);
        ctx.check("placeBlockAt external lookAt applied", () -> facing(ctx.bot().getLocalPlayer(),
                Vec3.atCenterOf(placeAt.get()), 12.0F, 20.0F));
    }

    private void queryAll(TestContext ctx) {
        AtomicReference<BlockPos> chestPos = new AtomicReference<>();
        AtomicBoolean clicked = new AtomicBoolean();
        AtomicBoolean queried = new AtomicBoolean();
        AtomicBoolean putDone = new AtomicBoolean();
        AtomicBoolean putVerified = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean chestHasStone = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "give " + ctx.botName() + " minecraft:stone 3");
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:stick");
            var p = ctx.bot().getLocalPlayer();
            chestPos.set(p.blockPosition().offset(2, 0, 0));
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "setblock " + chestPos.get().getX() + " " + chestPos.get().getY() + " " + chestPos.get().getZ()
                            + " minecraft:chest");
            p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(chestPos.get()));
        }));
        ctx.await("chest visible", () -> chestPos.get() != null
                && ctx.bot().getBlockState(chestPos.get()).is(Blocks.CHEST), 200);
        ctx.run(() -> {
            if (!clicked.get()) {
                clicked.set(true);
                ControlCommands.useItemOn(ctx.botName(), chestPos.get().getX(), chestPos.get().getY(),
                        chestPos.get().getZ(), "up");
            }
        });
        ctx.await("query chest open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.run(() -> ControlCommands.chat(ctx.botName(), "mockplayer-ctl-chat"));
        ctx.await("chat history ready", () -> QueryCommands.chatHistory(ctx.botName())
                .getString().contains("mockplayer-ctl-chat"), 200);
        ctx.run(() -> ctx.server().execute(() -> {
            var p = ctx.bot().getLocalPlayer().position();
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    String.format("summon minecraft:villager %.2f %.2f %.2f {NoAI:1b}",
                            p.x + 1.0, p.y, p.z));
        }));
        ctx.await("villager near for query", () -> ctx.bot().getEntitiesNear(16).stream()
                .anyMatch(e -> e instanceof Villager), 200);
        ctx.run(() -> {
            if (!queried.get()) {
                queried.set(true);
                String containerText = QueryCommands.container(ctx.botName()).getString();
                String infoText = QueryCommands.botInfo(ctx.botName()).getString();
                String invText = QueryCommands.inventory(ctx.botName()).getString();
                String nearText = QueryCommands.near(ctx.botName(), 16.0).getString();
                var p = ctx.bot().getLocalPlayer();
                String blockText = QueryCommands.blockAt(ctx.botName(),
                        p.blockPosition().getX(), p.blockPosition().getY() - 1, p.blockPosition().getZ()).getString();
                String onlineText = QueryCommands.online(ctx.botName()).getString();
                String chatText = QueryCommands.chatHistory(ctx.botName()).getString();
                ctx.checkNow("query container", containerText.contains("id="), "text=" + containerText);
                ctx.checkNow("query info", infoText.contains(ctx.botName()) && !infoText.contains("commands.mockplayer."));
                ctx.checkNow("query inventory", invText.contains(" x"), "text=" + invText);
                ctx.checkNow("query near", nearText.contains("villager"), "text=" + nearText);
                ctx.checkNow("query block", blockText.contains("minecraft:"), "text=" + blockText);
                ctx.checkNow("query online", onlineText.contains(Minecraft.getInstance().player.getGameProfile().name())
                        && onlineText.contains(ctx.botName()));
                ctx.checkNow("query chat", chatText.contains("mockplayer-ctl-chat"));
                List<String> texts = List.of(containerText, infoText, invText, nearText, blockText, onlineText, chatText);
                ctx.checkNow("query outputs no key/%s residue",
                        texts.stream().noneMatch(t -> t.contains("commands.mockplayer.") || t.contains("%s")),
                        "texts=" + texts);
                var mem = ctx.bot().memoryInfo();
                ctx.checkNow("memory jvm used", mem.jvmUsedBytes() > 0);
                ctx.checkNow("memory jvm max", mem.jvmMaxBytes() >= mem.jvmUsedBytes());
                ctx.checkNow("memory bot count", mem.botCount() >= 1);
                ctx.checkNow("memory chat exact", mem.chatBytes() > 0, "bytes=" + mem.chatBytes());
                ctx.checkNow("memory sound exact", mem.soundBytes() >= 0);
                ctx.checkNow("memory particle exact", mem.particleBytes() >= 0);
                ctx.checkNow("memory packet count", mem.packetCount() > 0, "count=" + mem.packetCount());
                ctx.checkNow("memory online exact", mem.onlinePlayersBytes() > 0, "bytes=" + mem.onlinePlayersBytes());
                ctx.checkNow("memory inventory exact", mem.inventoryBytes() > 0, "bytes=" + mem.inventoryBytes());
                ctx.checkNow("memory entity count", mem.entityCount() > 0, "count=" + mem.entityCount());
                ctx.checkNow("memory chunk count", mem.chunkCount() > 0, "count=" + mem.chunkCount());
                String memText = QueryCommands.memory(ctx.botName()).getString();
                ctx.checkNow("memory text", memText.contains("JVM") && memText.contains(ctx.botName())
                                && !memText.contains("commands.mockplayer.query."),
                        "text=" + memText.replace("\n", "|"));
                ControlCommands.hotbar(ctx.botName(), 1);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "give " + ctx.botName() + " minecraft:stone 1"));
            }
        });
        ctx.run(() -> ControlCommands.close(ctx.botName()));
        ctx.await("container closed after query", () -> ctx.bot().getContainer().isEmpty(), 100);
        ctx.check("container closed after query", () -> ctx.bot().getContainer().isEmpty());
    }

    /** 容器命令路径：setSlot 本地乐观写（客户端断言）+ click 放取（服务端断言）+ close。 */
    private void containerCommandPaths(TestContext ctx) {
        AtomicReference<BlockPos> chestPos = new AtomicReference<>();
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean put = new AtomicBoolean();
        AtomicBoolean chestHasStone = new AtomicBoolean();
        AtomicBoolean taken = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            var p = ctx.bot().getLocalPlayer();
            chestPos.set(p.blockPosition().offset(2, 0, 0));
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "setblock " + chestPos.get().getX() + " " + chestPos.get().getY() + " " + chestPos.get().getZ()
                            + " minecraft:chest");
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:stone");
        }));
        ctx.await("chest visible", () -> chestPos.get() != null
                && ctx.bot().getBlockState(chestPos.get()).is(Blocks.CHEST), 200);
        ctx.run(() -> {
            if (!opened.get()) {
                opened.set(true);
                ControlCommands.useItemOn(ctx.botName(), chestPos.get().getX(), chestPos.get().getY(),
                        chestPos.get().getZ(), "up");
            }
        });
        ctx.await("container open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.run(() -> ControlCommands.setSlot(ctx.botName(), 0));
        ctx.check("setSlot slot0 stone (client optimistic)",
                () -> ctx.bot().getContainer().map(c -> c.getSlot(0).is(Items.STONE)).orElse(false));
        ctx.run(() -> {
            if (!put.get()) {
                put.set(true);
                ControlCommands.click(ctx.botName(), 54, 0, "pickup");
                ControlCommands.click(ctx.botName(), 0, 0, "pickup");
            }
        });
        ctx.await("click put stone into chest (server)", () -> {
            ctx.server().execute(() -> {
                ChestBlockEntity chest = (ChestBlockEntity)
                        ctx.server().getLevel(Level.OVERWORLD).getBlockEntity(chestPos.get());
                chestHasStone.set(chest != null && chest.getItem(0).is(Items.STONE));
            });
            return chestHasStone.get();
        }, 200);
        ctx.check("click chest slot0 stone (server)", chestHasStone::get);
        ctx.run(() -> ControlCommands.click(ctx.botName(), 0, 0, "pickup"));
        ctx.await("click took stone back (server)", () -> {
            ctx.server().execute(() -> {
                ChestBlockEntity chest = (ChestBlockEntity)
                        ctx.server().getLevel(Level.OVERWORLD).getBlockEntity(chestPos.get());
                taken.set(chest != null && chest.getItem(0).isEmpty());
            });
            return taken.get();
        }, 200);
        ctx.check("click chest slot0 empty (server)", taken::get);
        ctx.run(() -> ControlCommands.close(ctx.botName()));
        ctx.await("close closed container", () -> ctx.bot().getContainer().isEmpty(), 100);
        ctx.check("close closed container", () -> ctx.bot().getContainer().isEmpty());
    }

    private void listen(TestContext ctx) {
        AtomicBoolean on = new AtomicBoolean();
        AtomicBoolean damaged = new AtomicBoolean();
        AtomicBoolean hasDamage = new AtomicBoolean();
        AtomicBoolean off = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.check("memory event cache zero before listen",
                () -> ctx.bot().memoryInfo().eventCacheBytes() == 0);
        ctx.run(() -> {
            if (!on.get()) {
                on.set(true);
                String onText = QueryCommands.listen(ctx.botName(), true).getString();
                ctx.checkNow("listen on feedback", onText.contains(ctx.botName()), "text=" + onText);
                // 重复 listen on 必须幂等（旧实现会重复挂载导致事件双触发）
                QueryCommands.listen(ctx.botName(), true);
            }
        });
        ctx.run(() -> {
            if (!damaged.get()) {
                damaged.set(true);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "gamemode survival " + ctx.botName()));
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "damage " + ctx.botName() + " 4"));
            }
        });
        ctx.await("listen recorded+push damage event", () -> {
            EventRecorder recorder = QueryCommands.getRecorder(ctx.botName());
            hasDamage.set(recorder != null && recorder.getPushCount() >= 1
                    && recorder.snapshot().stream().anyMatch(s -> s.startsWith("onDamage|")));
            return hasDamage.get();
        }, 200);
        ctx.check("listen recorded+push damage event", hasDamage::get);
        ctx.check("listen no duplicate damage event", () -> {
            EventRecorder recorder = QueryCommands.getRecorder(ctx.botName());
            return recorder != null && recorder.snapshot().stream()
                    .filter(s -> s.startsWith("onDamage|")).count() == 1;
        });
        ctx.check("memory event cache exact after damage",
                () -> ctx.bot().memoryInfo().eventCacheBytes() > 0);
        ctx.run(() -> {
            EventRecorder before = QueryCommands.getRecorder(ctx.botName());
            com.mockplayer.config.ModConfig cfg = com.mockplayer.config.MockplayerConfig.get();
            cfg.setEventCacheSize(30);
            com.mockplayer.config.MockplayerConfig.save(cfg);
            EventRecorder after = QueryCommands.getRecorder(ctx.botName());
            ctx.checkNow("listen recorder rebuilt on config reload",
                    after != null && after != before);
        });
        ctx.run(() -> {
            if (!off.get()) {
                off.set(true);
                String offText = QueryCommands.listen(ctx.botName(), false).getString();
                ctx.checkNow("listen off feedback", offText.contains(ctx.botName()), "text=" + offText);
                ctx.checkNow("listen off removes recorder", QueryCommands.getRecorder(ctx.botName()) == null);
                ctx.checkNow("memory event cache zero after off",
                        ctx.bot().memoryInfo().eventCacheBytes() == 0);
                String notText = QueryCommands.events(ctx.botName(), 10).getString();
                ctx.checkNow("events after off says not listening", notText.contains(ctx.botName()), "text=" + notText);
            }
        });
    }

    private void respawn(TestContext ctx) {
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean dead = new AtomicBoolean();
        AtomicBoolean respawned = new AtomicBoolean();
        AtomicBoolean alive = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            if (!killed.get()) {
                killed.set(true);
                SessionManager.getInstance().getSession(ctx.botName()).setAutoRespawn(false);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "kill " + ctx.botName()));
            }
        });
        ctx.await("fake dead", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                dead.set(sp != null && !sp.isAlive());
            });
            return dead.get();
        }, 100);
        ctx.run(() -> {
            if (dead.get()) {
                ControlCommands.respawn(ctx.botName());
            }
        });
        ctx.await("respawn recovered bot", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                alive.set(sp != null && sp.isAlive());
            });
            return alive.get();
        }, 120);
        ctx.check("respawn recovered bot", alive::get);
        ctx.run(() -> {
            SessionManager.getInstance().getSession(ctx.botName()).setAutoRespawn(true);
            MockplayerApi.bots().removeBot(ctx.botName(), "command");
        });
    }

    /** FakePlayerNameArgument 是否拒绝该名字（抛 CommandSyntaxException）。 */
    private static boolean throwsInvalidName(String name) {
        try {
            new FakePlayerNameArgument().parse(new com.mojang.brigadier.StringReader(name));
            return false;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return true;
        }
    }

    private void errorsAndI18n(TestContext ctx) {
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            String notFound = ControlCommands.attack("nobody-cc", null).getString();
            String invalidHand = ControlCommands.useItem(ctx.botName(), "bad").getString();
            String invalidSide = ControlCommands.useItemOn(ctx.botName(), 0, 0, 0, "bad").getString();
            String invalidEffect = ControlCommands.setBeacon(ctx.botName(), "minecraft:nonexistent_effect", null).getString();
            String blank = ControlCommands.chat(ctx.botName(), " ").getString();
            String noEntity = ControlCommands.attack(ctx.botName(), "zzz-no-entity").getString();
            ctx.checkNow("error not_found", notFound.contains("nobody-cc"));
            ctx.checkNow("error invalid_hand", invalidHand.contains("bad"));
            ctx.checkNow("error invalid_side", invalidSide.contains("bad"));
            ctx.checkNow("error invalid_effect", invalidEffect.contains("nonexistent"));
            ctx.checkNow("error blank_message", !blank.isBlank() && !blank.contains("commands.mockplayer.control."));
            ctx.checkNow("error entity_not_found", noEntity.contains("zzz-no-entity"));
            // 名字参数规则：超 16 字符在客户端直接报参数错误（离线服接受连字符，不做字符集限制）
            ctx.checkNow("name arg too long rejected",
                    throwsInvalidName("toolongname123456789"), "should throw");
            List<Component> outputs = new ArrayList<>();
            outputs.add(ControlCommands.move(ctx.botName(), "forward"));
            outputs.add(ControlCommands.stop(ctx.botName()));
            outputs.add(ControlCommands.setSneak(ctx.botName(), true));
            outputs.add(ControlCommands.setSprint(ctx.botName(), true));
            outputs.add(ControlCommands.jump(ctx.botName()));
            outputs.add(ControlCommands.look(ctx.botName(), 0.0F, 0.0F));
            outputs.add(ControlCommands.lookAt(ctx.botName(), 0.0, 0.0, 0.0));
            outputs.add(ControlCommands.turn(ctx.botName(), 0.0F, 0.0F));
            outputs.add(ControlCommands.stab(ctx.botName()));
            outputs.add(ControlCommands.interact(ctx.botName(), null));
            outputs.add(ControlCommands.useItem(ctx.botName(), "offhand"));
            outputs.add(ControlCommands.releaseUsingItem(ctx.botName()));
            outputs.add(ControlCommands.useItemOn(ctx.botName(), 0, 0, 0, "up"));
            outputs.add(ControlCommands.placeBlock(ctx.botName(), 0, 0, 0, "up"));
            outputs.add(ControlCommands.mineBlock(ctx.botName(), 0, 0, 0));
            outputs.add(ControlCommands.attackBlock(ctx.botName(), 0, 0, 0));
            outputs.add(ControlCommands.hotbar(ctx.botName(), 1));
            outputs.add(ControlCommands.drop(ctx.botName(), null, false));
            outputs.add(ControlCommands.swapHands(ctx.botName()));
            outputs.add(ControlCommands.mount(ctx.botName(), null, null));
            outputs.add(ControlCommands.dismount(ctx.botName()));
            outputs.add(ControlCommands.chat(ctx.botName(), "mockplayer-ctl-final"));
            outputs.add(ControlCommands.command(ctx.botName(), "time set 2000"));
            outputs.add(ControlCommands.wakeUp(ctx.botName()));
            outputs.add(ControlCommands.respawn(ctx.botName()));
            outputs.add(ControlCommands.editBook(ctx.botName(), 1, "page", null));
            outputs.add(ControlCommands.editSign(ctx.botName(), 0, 0, 0, true,
                    new String[]{"a", "b", "c", "d"}));
            outputs.add(ControlCommands.setBeacon(ctx.botName(), "minecraft:speed", null));
            outputs.add(ControlCommands.renameItem(ctx.botName(), "Test"));
            outputs.add(ControlCommands.pickItemFromBlock(ctx.botName(), 0, 0, 0, false));
            outputs.add(ControlCommands.sustainedAttack(ctx.botName(), null));
            outputs.add(ControlCommands.sustainedUse(ctx.botName(), null));
            outputs.add(ControlCommands.stopSustained(ctx.botName()));
            boolean allI18n = true;
            for (Component c : outputs) {
                String s = c.getString();
                if (s.isBlank() || s.contains("commands.mockplayer.") || s.contains("%s")) {
                    allI18n = false;
                    System.out.println("[mocktest] non-i18n output: " + s);
                }
            }
            ctx.checkNow("all control outputs i18n", allI18n);
            String escapeChat = "100%s \u00a7c\u00a7lHi";
            Component escapeOut = ControlCommands.chat(ctx.botName(), escapeChat);
            ctx.checkNow("escape chat text", escapeOut.getString().contains(escapeChat),
                    "text=" + escapeOut.getString());
            ctx.checkNow("escape chat no style inject", noInjectedStyle(escapeOut, false),
                    "styles=" + collectStyles(escapeOut));
            String escapeCmd = "say 100%s \u00a7c";
            Component escapeCmdOut = ControlCommands.command(ctx.botName(), escapeCmd);
            ctx.checkNow("escape command text", escapeCmdOut.getString().contains(escapeCmd),
                    "text=" + escapeCmdOut.getString());
            Component escapeNameOut = ControlCommands.stop("x\u00a7ly");
            ctx.checkNow("escape name text", escapeNameOut.getString().contains("x\u00a7ly"),
                    "text=" + escapeNameOut.getString());
            ctx.checkNow("escape name no style inject", noInjectedStyle(escapeNameOut, true),
                    "styles=" + collectStyles(escapeNameOut));
            String noneText = QueryCommands.container(ctx.botName()).getString();
            ctx.checkNow("container none text", noneText.contains(ctx.botName()) && !noneText.contains("%s"),
                    "text=" + noneText);
            ControlCommands.stop(ctx.botName());
        });
    }

    private void mineTime(TestContext ctx) {
        AtomicReference<BlockPos> stone1 = new AtomicReference<>();
        AtomicReference<BlockPos> stone2 = new AtomicReference<>();
        AtomicReference<BlockPos> stoneFar = new AtomicReference<>();
        AtomicBoolean synced = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean stopSent = new AtomicBoolean();
        AtomicBoolean stopChecked = new AtomicBoolean();
        AtomicBoolean farChecked = new AtomicBoolean();
        AtomicBoolean farStill = new AtomicBoolean();
        AtomicBoolean stone2Still = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger stable = new AtomicInteger();
        int[] wait = {0};
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp == null) {
                    return;
                }
                stone1.set(sp.blockPosition().offset(2, 0, 0));
                stone2.set(sp.blockPosition().offset(3, 0, 0));
                stoneFar.set(sp.blockPosition().offset(8, 0, 0));
                for (BlockPos p : List.of(stone1.get(), stone2.get(), stoneFar.get())) {
                    ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                            "setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:stone");
                }
                sp.getInventory().setSelectedSlot(0);
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:stone_pickaxe");
            });
        });
        ctx.await("mine test synced", () -> {
            if (stone1.get() == null) {
                return false;
            }
            if (++wait[0] > 20
                    && ctx.bot().getBlockState(stone1.get()).is(Blocks.STONE)
                    && ctx.bot().getBlockState(stone2.get()).is(Blocks.STONE)
                    && ctx.bot().getLocalPlayer().getMainHandItem().is(Items.STONE_PICKAXE)) {
                if (stable.incrementAndGet() < 20) {
                    return false;
                }
                synced.set(true);
                return true;
            }
            stable.set(0);
            return false;
        }, 200);
        ctx.run(() -> {
            if (stone1.get() != null) {
                ControlCommands.mineBlock(ctx.botName(),
                        stone1.get().getX(), stone1.get().getY(), stone1.get().getZ());
            }
        });
        ctx.await("mine time vanilla", () -> {
            ticks.incrementAndGet();
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                done.set(level != null && stone1.get() != null
                        && level.getBlockState(stone1.get()).is(Blocks.AIR));
            });
            return done.get();
        }, 160);
        ctx.check("mine time vanilla", () -> ticks.get() >= 6 && ticks.get() <= 24,
                () -> "ticks=" + ticks.get());
        ctx.run(() -> {
            if (stone2.get() != null) {
                ControlCommands.mineBlock(ctx.botName(),
                        stone2.get().getX(), stone2.get().getY(), stone2.get().getZ());
            }
        });
        wait[0] = 0;
        ctx.await("mine second start", () -> ++wait[0] >= 5, 20);
        ctx.run(() -> ctx.checkNow("main player no crack from bot gameMode",
                !mainDestroyingBlocksHasMainPlayer(), "count=" + mainDestroyingBlockCount()));
        ctx.run(() -> {
            if (!stopSent.get()) {
                stopSent.set(true);
                ControlCommands.stopSustained(ctx.botName());
            }
        });
        ctx.await("stopSustained cancels mining", () -> {
            if (!stopChecked.get() && ++wait[0] > 80) {
                stopChecked.set(true);
                ctx.server().execute(() -> {
                    ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                    stone2Still.set(level != null && stone2.get() != null
                            && level.getBlockState(stone2.get()).is(Blocks.STONE));
                });
                if (stoneFar.get() != null) {
                    ControlCommands.mineBlock(ctx.botName(),
                            stoneFar.get().getX(), stoneFar.get().getY(), stoneFar.get().getZ());
                }
            }
            return stone2Still.get();
        }, 160);
        ctx.check("stopSustained cancels mining", stone2Still::get);
        ctx.await("mine distance rejected", () -> {
            if (!farChecked.get() && ++wait[0] > 20) {
                farChecked.set(true);
                ctx.server().execute(() -> {
                    ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                    farStill.set(level != null && stoneFar.get() != null
                            && level.getBlockState(stoneFar.get()).is(Blocks.STONE));
                });
            }
            return farStill.get();
        }, 60);
        ctx.check("mine distance rejected", farStill::get);
        // attackBlock 同口径距离校验：远处攻击不应触发 onBreakBlock 事件（旧实现会直接发包）
        AtomicBoolean attackFarFired = new AtomicBoolean();
        BotListener farAttackListener = new BotListener() {
            @Override
            public void onBreakBlock(Bot bot, BlockPos pos) {
                if (stoneFar.get() != null && pos.equals(stoneFar.get())) {
                    attackFarFired.set(true);
                }
            }
        };
        ctx.run(() -> {
            ((BotImpl) ctx.bot()).events().addListener(farAttackListener);
            if (stoneFar.get() != null) {
                ControlCommands.attackBlock(ctx.botName(),
                        stoneFar.get().getX(), stoneFar.get().getY(), stoneFar.get().getZ());
            }
        });
        ctx.await("attackBlock far wait", () -> ++wait[0] > 10, 30);
        ctx.check("attackBlock far rejected", () -> !attackFarFired.get());
        ctx.run(() -> ((BotImpl) ctx.bot()).events().removeListener(farAttackListener));
    }

    private void chunkRadius(TestContext ctx) {
        AtomicBoolean teleported = new AtomicBoolean();
        AtomicBoolean settled = new AtomicBoolean();
        AtomicReference<BlockPos> probe = new AtomicReference<>();
        AtomicReference<Integer> serverRequested = new AtomicReference<>(-1);
        AtomicReference<Integer> serverView = new AtomicReference<>(-1);
        AtomicReference<Integer> mainOptionsBefore = new AtomicReference<>(-1);
        AtomicReference<Integer> mainServerRenderBefore = new AtomicReference<>(-1);
        AtomicBoolean defaultChecked = new AtomicBoolean();
        AtomicBoolean serverChecked = new AtomicBoolean();
        AtomicBoolean afterChecked = new AtomicBoolean();
        AtomicBoolean loadedChecked = new AtomicBoolean();
        AtomicBoolean mainChunkSource = new AtomicBoolean();
        int[] wait = {0};
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            if (!teleported.get()) {
                teleported.set(true);
                mainOptionsBefore.set(Minecraft.getInstance().options.renderDistance().get());
                mainServerRenderBefore.set(readServerRenderDistance());
                mainChunkSource.set(Minecraft.getInstance().level == null
                        || Minecraft.getInstance().level.getChunkSource() != null);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "tp " + ctx.botName() + " 3000 4 0"));
            }
        });
        ctx.await("chunk teleport settled", () -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null && !SuitesSupport.isAwaitingPosition(sp)) {
                probe.set(ctx.bot().getLocalPlayer().blockPosition());
                if (Math.abs(probe.get().getX() - 3000) < 2) {
                    settled.set(true);
                    return true;
                }
            }
            return false;
        }, 200);
        wait[0] = 0;
        ctx.await("chunk settle 20 ticks", () -> ++wait[0] >= 20, 40);
        ctx.run(() -> {
            // 默认假人区块半径 = 1（ModConfig.DEFAULT_FAKE_PLAYER_CHUNK_RADIUS，8363e58 起）
            ctx.checkNow("chunk config default 1",
                    MockplayerConfig.get().getFakePlayerChunkRadius() == 1);
            ctx.checkNow("chunk bot default radius", ctx.bot().getChunkRadius() == 1);
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                serverRequested.set(serverRequestedViewDistance(sp));
                serverView.set(serverChunkViewDistance(sp));
            });
            ctx.checkNow("chunk default +3 loaded", ctx.bot().isBlockLoaded(probe.get().offset(48, 0, 0)));
            ctx.checkNow("chunk default +4 not loaded",
                    !ctx.bot().isBlockLoaded(probe.get().offset(64, 0, 0)),
                    "pos=" + probe.get().offset(64, 0, 0));
            ctx.checkNow("chunk default +1 loaded", ctx.bot().isBlockLoaded(probe.get().offset(16, 0, 0)));
        });
        ctx.await("chunk server values read", () -> serverRequested.get() != -1
                && serverView.get() != -1, 50);
        ctx.run(() -> {
            ctx.checkNow("chunk server requestedViewDistance default", serverRequested.get() == 1,
                    "server=" + serverRequested.get());
            ctx.checkNow("chunk server tracking view default", serverView.get() == 2,
                    "view=" + serverView.get());
            String out = ControlCommands.chunkRadius(ctx.botName(), 4).getString();
            ctx.checkNow("chunk set feedback", !out.contains("commands."), "out=" + out);
            ctx.checkNow("chunk set local radius", ctx.bot().getChunkRadius() == 4);
            wait[0] = 0;
        });
        ctx.await("chunk server after set", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                serverRequested.set(serverRequestedViewDistance(sp));
                serverView.set(serverChunkViewDistance(sp));
            });
            if (++wait[0] > 30 && serverRequested.get() == 4 && serverView.get() == 4) {
                afterChecked.set(true);
                return true;
            }
            return false;
        }, 200);
        ctx.check("chunk server requestedViewDistance after set", () -> serverRequested.get() == 4,
                () -> "server=" + serverRequested.get());
        ctx.check("chunk server tracking view after set", () -> serverView.get() == 4,
                () -> "view=" + serverView.get());
        wait[0] = 0;
        ctx.await("chunk load settle 60 ticks", () -> ++wait[0] >= 60, 120);
        ctx.run(() -> {
            if (!loadedChecked.get()) {
                loadedChecked.set(true);
                ctx.checkNow("chunk set +5 loaded", ctx.bot().isBlockLoaded(probe.get().offset(80, 0, 0)));
                ctx.checkNow("chunk set +6 not loaded", !ctx.bot().isBlockLoaded(probe.get().offset(96, 0, 0)));
                ctx.checkNow("chunk main player isolated",
                        Minecraft.getInstance().options.renderDistance().get() == mainOptionsBefore.get());
                ctx.checkNow("chunk main serverRenderDistance isolated",
                        readServerRenderDistance() == mainServerRenderBefore.get(),
                        "before=" + mainServerRenderBefore.get()
                                + " now=" + readServerRenderDistance());
                String q = QueryCommands.chunk(ctx.botName()).getString();
                ctx.checkNow("chunk query readback", q.contains("4"), "q=" + q);
                String bad = ControlCommands.chunkRadius(ctx.botName(), 0).getString();
                ctx.checkNow("chunk invalid 0 rejected", !bad.contains("commands.") && ctx.bot().getChunkRadius() == 4,
                        "out=" + bad);
                String bad2 = ControlCommands.chunkRadius(ctx.botName(), 33).getString();
                ctx.checkNow("chunk invalid 33 rejected", !bad2.contains("commands.") && ctx.bot().getChunkRadius() == 4,
                        "out=" + bad2);
                ModConfig cfg5 = new ModConfig();
                cfg5.setFakePlayerChunkRadius(5);
                MockplayerConfig.save(cfg5);
                MockplayerConfig.reload();
                ctx.checkNow("chunk config json roundtrip",
                        MockplayerConfig.get().getFakePlayerChunkRadius() == 5);
                MockplayerConfig.save(new ModConfig());
                MockplayerConfig.reload();
                ctx.checkNow("chunk config restore default",
                        MockplayerConfig.get().getFakePlayerChunkRadius() == 1);
            }
        });
    }

    /** 换维（tp 到地狱）后：FakeLevelRegistry 只保留新 level，不泄漏旧 level。 */
    private void dimensionIsolation(TestContext ctx) {
        AtomicBoolean teleported = new AtomicBoolean();
        AtomicBoolean changed = new AtomicBoolean();
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            if (!teleported.get()) {
                teleported.set(true);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(),
                        "execute in minecraft:the_nether run tp " + ctx.botName() + " 0 64 0"));
            }
        });
        ctx.await("bot dimension changed", () -> {
            if (ctx.bot().getLevel() != null) {
                changed.set(ctx.bot().getLevel().dimension() == net.minecraft.world.level.Level.NETHER);
            }
            return changed.get();
        }, 400);
        ctx.check("bot dimension changed", changed::get);
        ctx.check("fake level registry not leaked", () -> fakeLevelCount() == 1,
                () -> "count=" + fakeLevelCount() + " contents=" + fakeLevelContents());
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }

    /** 反射读主玩家 Options.serverRenderDistance（无公共 getter，P0-2 隔离断言用）。 */
    private static int readServerRenderDistance() {
        try {
            java.lang.reflect.Field f =
                    Minecraft.getInstance().options.getClass().getDeclaredField("serverRenderDistance");
            f.setAccessible(true);
            return f.getInt(Minecraft.getInstance().options);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 反射读主玩家 level 的 destroyingBlocks（裂纹进度）：
     * 服务端会向主玩家广播 bot 的挖掘进度（原版行为），但绝不允许出现「主玩家自己 id」的条目
     * （旧 bug：假人 gameMode 的 lambda 用主玩家 id 往主 level 画裂纹）。P0-1 隔离断言用。
     */
    private static boolean mainDestroyingBlocksHasMainPlayer() {
        try {
            java.lang.reflect.Field f =
                    Minecraft.getInstance().level.getClass().getDeclaredField("destroyingBlocks");
            f.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) f.get(Minecraft.getInstance().level);
            return Minecraft.getInstance().player != null
                    && map.containsKey(Minecraft.getInstance().player.getId());
        } catch (Exception e) {
            return true;
        }
    }

    /** 反射读主玩家 level 的 destroyingBlocks（裂纹进度）数量（失败返回 -1）。 */
    private static int mainDestroyingBlockCount() {
        try {
            java.lang.reflect.Field f =
                    Minecraft.getInstance().level.getClass().getDeclaredField("destroyingBlocks");
            f.setAccessible(true);
            return ((java.util.Map<?, ?>) f.get(Minecraft.getInstance().level)).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 反射读 FakeLevelRegistry.FAKE_LEVELS 数量（P0-3 换维泄漏断言用）。 */
    private static int fakeLevelCount() {
        try {
            java.lang.reflect.Field f =
                    com.mockplayer.session.FakeLevelRegistry.class.getDeclaredField("FAKE_LEVELS");
            f.setAccessible(true);
            return ((java.util.Set<?>) f.get(null)).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 临时诊断：注册表内容（P0-3 排查用，修完删除）。 */
    private static String fakeLevelContents() {
        try {
            java.lang.reflect.Field f =
                    com.mockplayer.session.FakeLevelRegistry.class.getDeclaredField("FAKE_LEVELS");
            f.setAccessible(true);
            java.util.Set<?> set = (java.util.Set<?>) f.get(null);
            return set.stream()
                    .map(o -> ((net.minecraft.client.multiplayer.ClientLevel) o).dimension().toString())
                    .toList().toString();
        } catch (Exception e) {
            return "err:" + e;
        }
    }

    private void raycast(TestContext ctx) {
        AtomicReference<BlockPos> base = new AtomicReference<>();
        AtomicReference<BlockPos> huskPos = new AtomicReference<>();
        AtomicReference<Float> huskHp = new AtomicReference<>(20.0F);
        AtomicBoolean huskFound = new AtomicBoolean();
        AtomicBoolean huskSummoned = new AtomicBoolean();
        AtomicBoolean attackLooked = new AtomicBoolean();
        AtomicBoolean sustainedHit = new AtomicBoolean();
        AtomicBoolean chestPlaced = new AtomicBoolean();
        AtomicBoolean chestOpen = new AtomicBoolean();
        AtomicBoolean breadGiven = new AtomicBoolean();
        AtomicBoolean using = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean dirtPlaced = new AtomicBoolean();
        AtomicBoolean dirtBroken = new AtomicBoolean();
        AtomicBoolean farPlaced = new AtomicBoolean();
        AtomicBoolean farStill = new AtomicBoolean();
        AtomicBoolean turnHuskSummoned = new AtomicBoolean();
        AtomicBoolean turnAttacked = new AtomicBoolean();
        AtomicReference<Float> turnHp = new AtomicReference<>(20.0F);
        AtomicReference<BlockPos> chestPos = new AtomicReference<>();
        AtomicReference<BlockPos> dirtPos = new AtomicReference<>();
        AtomicReference<BlockPos> farPos = new AtomicReference<>();
        int[] stage = {0};
        int[] wait = {0};
        createBot(ctx);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            base.set(ctx.bot().getLocalPlayer().blockPosition());
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
        });
        ctx.await("husk spawned", () -> {
            if (!huskSummoned.get()) {
                huskSummoned.set(true);
                huskPos.set(base.get().offset(3, 0, 0));
                BlockPos hp = huskPos.get();
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                hp.getX() + 0.5, (double) hp.getY(), hp.getZ() + 0.5)));
            }
            ctx.server().execute(() -> {
                var h = nearestZombie(ctx);
                huskFound.set(h != null);
                huskHp.set(h != null ? h.getHealth() : -1.0F);
            });
            return huskFound.get() && huskHp.get() == 20.0F;
        }, 200);
        ctx.run(() -> {
            if (huskFound.get() && huskHp.get() >= 20.0F) {
                ctx.bot().actions().lookAt(Vec3.atCenterOf(huskPos.get()));
                ControlCommands.attackLook(ctx.botName());
            }
        });
        ctx.await("attackLook damages entity", () -> {
            ctx.server().execute(() -> {
                var h = nearestZombie(ctx);
                huskFound.set(h != null);
                huskHp.set(h != null ? h.getHealth() : 20.0F);
            });
            return huskFound.get() && huskHp.get() < 20.0F;
        }, 200);
        ctx.check("attackLook damages entity", () -> huskFound.get() && huskHp.get() < 20.0F);
        ctx.await("sustainedAttackLook continuous damage", () -> {
            if (huskFound.get() && huskHp.get() >= 13.0F) {
                ControlCommands.sustainedAttackLook(ctx.botName());
            }
            ctx.server().execute(() -> {
                var h = nearestZombie(ctx);
                huskFound.set(h != null);
                huskHp.set(h != null ? h.getHealth() : 20.0F);
            });
            if (huskFound.get() && huskHp.get() < 13.0F) {
                sustainedHit.set(true);
                return true;
            }
            return false;
        }, 300);
        ctx.check("sustainedAttackLook continuous damage", sustainedHit::get);
        ctx.run(() -> ControlCommands.stopSustained(ctx.botName()));
        ctx.run(() -> {
            if (!chestPlaced.get()) {
                chestPlaced.set(true);
                chestPos.set(base.get().offset(2, 0, 0));
                ctx.server().execute(() -> ctx.server().getLevel(Level.OVERWORLD)
                        .setBlock(chestPos.get(), Blocks.CHEST.defaultBlockState(), 3));
            }
        });
        ctx.await("useLook opens container", () -> {
            if (!chestOpen.get() && ctx.bot().getBlockState(chestPos.get()).is(Blocks.CHEST)) {
                ctx.bot().actions().lookAt(Vec3.atCenterOf(chestPos.get()));
                ControlCommands.useLook(ctx.botName());
            }
            chestOpen.set(ctx.bot().getContainer().isPresent());
            return chestOpen.get();
        }, 200);
        ctx.check("useLook opens container", chestOpen::get);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> c.close()));
        ctx.run(() -> {
            if (!dirtPlaced.get()) {
                dirtPlaced.set(true);
                dirtPos.set(base.get().offset(2, 0, 0));
                ctx.server().execute(() -> ctx.server().getLevel(Level.OVERWORLD)
                        .setBlock(dirtPos.get(), Blocks.DIRT.defaultBlockState(), 3));
            }
        });
        ctx.await("sustainedAttackLook breaks block", () -> {
            if (!dirtBroken.get() && ctx.bot().getBlockState(dirtPos.get()).is(Blocks.DIRT)) {
                ctx.bot().actions().lookAt(Vec3.atCenterOf(dirtPos.get()));
                ControlCommands.sustainedAttackLook(ctx.botName());
            }
            ctx.server().execute(() -> dirtBroken.set(ctx.server().getLevel(Level.OVERWORLD)
                    .getBlockState(dirtPos.get()).isAir()));
            return dirtBroken.get();
        }, 300);
        ctx.check("sustainedAttackLook breaks block", dirtBroken::get);
        ctx.run(() -> ControlCommands.stopSustained(ctx.botName()));
        ctx.run(() -> {
            if (!farPlaced.get()) {
                farPlaced.set(true);
                farPos.set(base.get().offset(8, 0, 0));
                BlockPos p = farPos.get();
                BlockPos b = base.get();
                ctx.server().execute(() -> {
                    for (int i = 2; i <= 7; i++) {
                        ctx.server().getLevel(Level.OVERWORLD)
                                .setBlock(b.offset(i, 0, 0), Blocks.AIR.defaultBlockState(), 3);
                    }
                    ctx.server().getLevel(Level.OVERWORLD).setBlock(p, Blocks.STONE.defaultBlockState(), 3);
                });
            }
        });
        ctx.await("look out of reach no break", () -> {
            if (wait[0] < 60) {
                wait[0]++;
                ctx.bot().actions().lookAt(Vec3.atCenterOf(farPos.get()));
                ControlCommands.sustainedAttackLook(ctx.botName());
                return false;
            }
            ctx.server().execute(() -> farStill.set(ctx.server().getLevel(Level.OVERWORLD)
                    .getBlockState(farPos.get()).is(Blocks.STONE)));
            return farStill.get();
        }, 300);
        ctx.check("look out of reach no break", farStill::get);
        ctx.run(() -> ControlCommands.stopSustained(ctx.botName()));
        ctx.run(() -> {
            if (!turnHuskSummoned.get()) {
                turnHuskSummoned.set(true);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
                huskPos.set(base.get().offset(3, 0, 0));
                BlockPos hp = huskPos.get();
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                hp.getX() + 0.5, (double) hp.getY(), hp.getZ() + 0.5)));
            }
        });
        ctx.await("turn changes look target", () -> {
            if (!turnAttacked.get() && ++wait[0] > 100) {
                turnAttacked.set(true);
                ctx.bot().actions().lookAt(Vec3.atCenterOf(huskPos.get()));
                ctx.bot().actions().turn(180.0F, 0.0F);
                ControlCommands.attackLook(ctx.botName());
                wait[0] = 0;
            }
            if (turnAttacked.get() && ++wait[0] > 40) {
                ctx.server().execute(() -> {
                    var h = nearestZombie(ctx);
                    turnHp.set(h != null ? h.getHealth() : -1.0F);
                });
                return true;
            }
            return false;
        }, 200);
        ctx.check("turn changes look target", () -> turnHp.get() >= 19.99F,
                () -> "hp=" + turnHp.get());
        ctx.run(() -> {
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "kill @e[type=!minecraft:player]"));
            String help = ControlCommands.help(ctx.botName()).getString();
            ctx.checkNow("help lists look actions",
                    help.contains(Component.translatable("commands.mockplayer.control.action.attackLook").getString())
                            && help.contains(Component.translatable("commands.mockplayer.control.action.useLook").getString())
                            && help.contains(Component.translatable("commands.mockplayer.control.action.sustainedAttackLook").getString())
                            && help.contains(Component.translatable("commands.mockplayer.control.action.sustainedUseLook").getString()));
            MockplayerApi.bots().removeBot(ctx.botName(), "command");
        });
    }

    // ===== helpers =====

    private static void registerAll(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var factory = new com.mockplayer.session.CommandSupport.CommandFactory<CommandSourceStack>() {
            @Override
            public com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
                return Commands.literal(name);
            }

            @Override
            public com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> argument(
                    String name, com.mojang.brigadier.arguments.ArgumentType<?> type) {
                return Commands.argument(name, type);
            }

            @Override
            public void sendFeedback(CommandSourceStack source, Component message) {
            }
        };
        dispatcher.register(ControlCommands.buildControlTree(factory, "control"));
        dispatcher.register(QueryCommands.buildQueryTree(factory, "query"));
    }

    private static List<String> completions(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
            CommandSourceStack source, String input) {
        try {
            var parse = dispatcher.parse(input, source);
            return dispatcher.getCompletionSuggestions(parse).get(2, java.util.concurrent.TimeUnit.SECONDS).getList()
                    .stream().map(com.mojang.brigadier.suggestion.Suggestion::getText).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean facing(net.minecraft.client.player.LocalPlayer p, Vec3 target,
                                  float yawTol, float pitchTol) {
        if (p == null || target == null) {
            return false;
        }
        Vec3 d = target.subtract(p.getEyePosition());
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

    private static Zombie nearestZombie(TestContext ctx) {
        ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
        ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
        if (sp == null || level == null) {
            return null;
        }
        return level.getEntitiesOfClass(Zombie.class,
                        new net.minecraft.world.phys.AABB(sp.getX() - 16, sp.getY() - 16, sp.getZ() - 16,
                                sp.getX() + 16, sp.getY() + 16, sp.getZ() + 16)).stream()
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(sp)))
                .orElse(null);
    }

    private static List<Style> collectStyles(Component c) {
        List<Style> styles = new ArrayList<>();
        c.visit((style, text) -> {
            styles.add(style);
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return styles;
    }

    private static boolean noInjectedStyle(Component c, boolean allowRootRed) {
        return collectStyles(c).stream().noneMatch(s ->
                (s.getColor() != null && !allowRootRed
                        && s.getColor().equals(TextColor.fromLegacyFormat(
                        net.minecraft.ChatFormatting.RED)))
                        || s.isBold() || s.isItalic() || s.isUnderlined() || s.isStrikethrough() || s.isObfuscated());
    }

    private static int serverRequestedViewDistance(ServerPlayer sp) {
        if (sp == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method m = ServerPlayer.class.getMethod("requestedViewDistance");
            return (Integer) m.invoke(sp);
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Field f = ServerPlayer.class.getDeclaredField("requestedViewDistance");
            f.setAccessible(true);
            return f.getInt(sp);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static int serverChunkViewDistance(ServerPlayer sp) {
        if (sp == null) {
            return -1;
        }
        try {
            java.lang.reflect.Method getView = ServerPlayer.class.getMethod("getChunkTrackingView");
            Object view = getView.invoke(sp);
            if (view != null && view.getClass().getSimpleName().contains("Positioned")) {
                java.lang.reflect.Method vd = view.getClass().getMethod("viewDistance");
                return (Integer) vd.invoke(view);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}



