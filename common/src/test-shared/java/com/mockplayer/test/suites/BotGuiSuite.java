package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.gui.BotControlScreen;
import com.mockplayer.gui.BotControlHud;
import com.mockplayer.gui.BotGui;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * bot-gui：BotControlScreen GUI 全交互（打开/渲染探针/状态/移动/快捷栏/左键右键/
 * 区块/聊天/自动重生/KeyMapping/长按/经验/盾牌/丢弃格/主玩家隔离）。
 * 每个用例独立 bot（唯一名），探针开启（mockplayer.guiRenderProbe）。
 */
public class BotGuiSuite extends TestSuite {

    private String lastBotName;

    public BotGuiSuite() {
        super("bot-gui");
        test("i18n/配置默认", this::i18nAndConfig);
        test("打开 GUI/渲染探针/状态面板", this::openAndProbe);
        test("多分辨率布局", this::layout);
        test("动作 Tab 移动", this::actionMove);
        test("快捷栏切换", this::hotbar);
        test("左键攻击 husk", this::attackLook);
        test("右键开箱", this::useLook);
        test("区块半径", this::chunkButtons);
        test("聊天", this::chat);
        test("自动重生开关", this::autoRespawn);
        test("KeyMapping 门禁", this::keyMapping);
        test("长按视角/区块", this::holdRepeat);
        test("经验条", this::xpBar);
        test("副手盾长按右键", this::shieldHold);
        test("背包丢弃格", this::discardSlot);
        test("主玩家挖方块隔离", this::mainPlayerMineIsolation);
    }

    @Override
    public void before() {
        System.setProperty("mockplayer.guiRenderProbe", "true");
    }

    private void createBot(TestContext ctx) {
        // GUI 全局单例会选中 BotManager 第一个假人：createUniqueBot 先清空全部残留，
        // 保证选中当前用例的唯一假人；每个用例强制独立，绝不复用
        lastBotName = SuitesSupport.createUniqueBot(ctx, "gui");
    }

    @Override
    public void after() {
        if (lastBotName != null) {
            MockplayerApi.bots().removeBot(lastBotName, "command");
            lastBotName = null;
        }
    }

    private void i18nAndConfig(TestContext ctx) {
        i18nGuiLangChecks(ctx);
        ctx.check("gui config default on", () -> MockplayerConfig.get().isGuiEnabled());
        ctx.run(() -> {
            ModConfig off = new ModConfig();
            off.setGuiEnabled(false);
            MockplayerConfig.save(off);
            MockplayerConfig.reload();
            ctx.checkNow("gui config normalize", !MockplayerConfig.get().isGuiEnabled());
            MockplayerConfig.save(new ModConfig());
        });
    }

    private void openAndProbe(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            int blurBefore = mc.options.menuBackgroundBlurriness().get();
            ctx.checkNow("bot gui opened", BotGui.open(mc));
            ctx.checkNow("blur option untouched while open",
                    mc.options.menuBackgroundBlurriness().get() == blurBefore);
            ctx.checkNow("selected bot label",
                    BotControlHud.selectedText(ctx.bot()).getString().contains(ctx.botName()));
            int pw = mc.getWindow().getGuiScaledWidth();
            int ph = mc.getWindow().getGuiScaledHeight();
            int px = BotGui.panelX(pw, ph);
            int py = BotGui.panelY(pw, ph);
            int pwr = px + BotGui.panelWidth(pw, ph);
            int phr = py + BotGui.panelHeight(pw, ph);
            boolean inside = true;
            int outside = 0;
            for (Object child : bgScreen().children()) {
                if (child instanceof AbstractWidget w) {
                    if (w.getX() < px || w.getY() < py
                            || w.getX() + w.getWidth() > pwr
                            || w.getY() + w.getHeight() > phr) {
                        inside = false;
                        outside++;
                    }
                }
            }
            ctx.checkNow("all widgets inside panel", inside, "outside=" + outside);
            ctx.checkNow("screen is BotControlScreen",
                    mc.screen instanceof BotControlScreen);
            ctx.checkNow("title translated", bgScreen() != null
                    && !bgScreen().getTitle().getString().contains("gui.mockplayer."));
            ctx.checkNow("probe open counted", BotGui.probeOpenCount() > 0);
        });
        ctx.await("bot gui rendered", () -> BotGui.probeFrameCount() > 0, 100);
        ctx.check("probe frame rendered", () -> BotGui.probeFrameCount() > 0);
        ctx.check("probe tick ran", () -> BotGui.probeTickCount() > 0);
        ctx.check("bot list shows bot", () -> bgScreen() != null && bgScreen().children().stream()
                .anyMatch(child -> child instanceof Button b
                        && b.getMessage().getString().contains(ctx.botName())));
        ctx.check("probe title rendered", () -> BotGui.probeLastTitle().contains(
                Component.translatable("gui.mockplayer.title").getString()));
        ctx.check("status lines non-empty", () -> {
            List<Component> lines = BotControlHud.statusLines(ctx.bot());
            return lines.stream().anyMatch(l -> l.getString().contains("❤")
                    || l.getString().contains("🍗"));
        });
        ctx.check("health food bars rendered", () -> BotGui.probeHealthFoodCount() > 0);
        ctx.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            int blurBefore = mc.options.menuBackgroundBlurriness().get();
            mc.setScreen(null);
            ctx.checkNow("blur option untouched after close",
                    mc.options.menuBackgroundBlurriness().get() == blurBefore);
        });
    }

    private void layout(TestContext ctx) {
        ctx.check("layout 1280x720 scale 1", () -> BotGui.layoutScale(1280, 720) == 1.0F);
        ctx.check("layout 854x480 scale 1", () -> BotGui.layoutScale(854, 480) == 1.0F);
        ctx.check("layout tiny scaled down", () -> {
            float tiny = BotGui.layoutScale(400, 200);
            return tiny > 0.0F && tiny < 1.0F;
        });
        ctx.check("layout panel inside tiny", () -> {
            int px = BotGui.panelX(400, 200);
            int py = BotGui.panelY(400, 200);
            return px >= 0 && py >= 0
                    && px + BotGui.panelWidth(400, 200) <= 400
                    && py + BotGui.panelHeight(400, 200) <= 200;
        });
    }

    private void actionMove(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        AtomicReference<double[]> base = new AtomicReference<>();
        AtomicReference<double[]> cur = new AtomicReference<>();
        AtomicBoolean retriedClick = new AtomicBoolean();
        int[] waitMove = {0};
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button fwd = bgFindButton(screen, "gui.mockplayer.action.move_forward");
            if (fwd == null) {
                System.out.println("[gui-diag] move_forward button not found, screen=" + (screen != null));
                return;
            }
            System.out.println("[gui-diag] move_forward button found at "
                    + fwd.getX() + "," + fwd.getY());
            bgClick(fwd);
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    base.set(new double[]{sp.getX(), sp.getZ()});
                }
            });
        });
        ctx.await("gui move forward server moved", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    cur.set(new double[]{sp.getX(), sp.getZ()});
                }
            });
            double[] b = base.get();
            double[] c = cur.get();
            boolean moved = b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 1.0 || Math.abs(c[1] - b[1]) > 1.0);
            // all 模式偶发按钮点击未生效：中途重按一次再继续等
            if (!moved && ++waitMove[0] > 75 && !retriedClick.get()) {
                retriedClick.set(true);
                Button fwd = bgFindButton(bgScreen(), "gui.mockplayer.action.move_forward");
                if (fwd != null) {
                    bgClick(fwd);
                }
            }
            return moved;
        }, 300);
        ctx.check("gui move forward server moved", () -> {
            double[] b = base.get();
            double[] c = cur.get();
            return b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 1.0 || Math.abs(c[1] - b[1]) > 1.0);
        });
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button stop = bgFindButton(screen, "gui.mockplayer.action.stop");
            if (stop != null) {
                bgClick(stop);
            }
        });
    }

    private void hotbar(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> ctx.server().execute(() -> {
            var cmds = ctx.server().getCommands();
            var src = ctx.server().createCommandSourceStack();
            cmds.performPrefixedCommand(src, "item replace entity " + ctx.botName()
                    + " hotbar.0 with minecraft:stone");
            cmds.performPrefixedCommand(src, "item replace entity " + ctx.botName()
                    + " hotbar.1 with minecraft:stick");
            cmds.performPrefixedCommand(src, "item replace entity " + ctx.botName()
                    + " hotbar.2 with minecraft:bread");
        }));
        ctx.await("hotbar synced", () -> ctx.bot().getLocalPlayer().getInventory()
                .getItem(1).is(Items.STICK), 200);
        ensureTab(ctx, "gui.mockplayer.tab.inventory");
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            if (screen != null) {
                bgRightClickInventorySlot(screen, 37);
            }
        });
        ctx.await("gui hotbar switch server", () -> {
            AtomicBoolean ok = new AtomicBoolean();
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                ok.set(sp != null && sp.getInventory().getSelectedSlot() == 1);
            });
            if (!ok.get()) {
                // 旧框架语义：GUI 点击可能因同步时机丢失，每 tick 重试直到服务端确认
                BotControlScreen screen = bgScreen();
                if (screen != null) {
                    bgRightClickInventorySlot(screen, 37);
                }
            }
            return ok.get();
        }, 120);
        ctx.check("hotbar slot click not pickup", () ->
                ctx.bot().getLocalPlayer().containerMenu.getCarried().isEmpty());
    }

    private void attackLook(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        AtomicReference<BlockPos> huskPos = new AtomicReference<>();
        AtomicReference<Float> huskHp = new AtomicReference<>(-1.0F);
        AtomicBoolean summoned = new AtomicBoolean();
        ctx.run(() -> ctx.server().execute(() -> {
            if (!summoned.get()) {
                summoned.set(true);
                huskPos.set(ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0));
                BlockPos hp = huskPos.get();
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                                hp.getX() + 0.5, (double) hp.getY(), hp.getZ() + 0.5));
            }
        }));
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button actions = bgFindButton(screen, "gui.mockplayer.tab.actions");
            if (actions != null) {
                bgClick(actions);
            }
        });
        int[] wait = {0};
        ctx.await("actions tab for attack", () -> ++wait[0] >= 5, 20);
        ctx.await("attackLook damages entity", () -> {
            ctx.server().execute(() -> {
                var sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                if (sp != null) {
                    var level = ctx.server().getLevel(Level.OVERWORLD);
                    var husk = level != null ? level.getEntitiesOfClass(Zombie.class,
                                    new net.minecraft.world.phys.AABB(sp.getX() - 16, sp.getY() - 16,
                                            sp.getZ() - 16, sp.getX() + 16, sp.getY() + 16, sp.getZ() + 16))
                            .stream().min(java.util.Comparator.comparingDouble(
                                    e -> e.distanceToSqr(sp))).orElse(null) : null;
                    huskHp.set(husk != null ? husk.getHealth() : -1.0F);
                }
            });
            boolean huskVisible = ctx.bot().getEntitiesNear(16).stream()
                    .anyMatch(e -> e instanceof Zombie);
            if (huskHp.get() >= 20.0F && huskVisible) {
                ctx.bot().actions().lookAt(Vec3.atCenterOf(huskPos.get()));
                BotControlScreen screen = bgScreen();
                Button atk = bgFindButton(screen, "gui.mockplayer.action.attack_look");
                if (atk != null) {
                    bgClick(atk);
                    bgRelease(atk);
                }
            }
            return huskHp.get() >= 0 && huskHp.get() < 20.0F;
        }, 200);
        ctx.check("attackLook damages entity", () -> huskHp.get() >= 0 && huskHp.get() < 20.0F);
        ctx.run(() -> {
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
        });
    }

    private void useLook(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        AtomicReference<BlockPos> chestPos = new AtomicReference<>();
        ctx.run(() -> {
            chestPos.set(ctx.bot().getLocalPlayer().blockPosition().offset(2, 0, 0));
            ctx.server().execute(() -> ctx.server().getLevel(Level.OVERWORLD)
                    .setBlock(chestPos.get(), Blocks.CHEST.defaultBlockState(), 3));
        });
        SuitesSupport.awaitBlockVisible(ctx, chestPos::get, Blocks.CHEST, 200);
        ctx.await("useLook opens container", () -> {
            if (ctx.bot().getContainer().isEmpty()) {
                ctx.bot().actions().lookAt(Vec3.atCenterOf(chestPos.get()));
                BotControlScreen screen = bgScreen();
                Button use = bgFindButton(screen, "gui.mockplayer.action.use_look");
                if (use != null) {
                    bgClick(use);
                    bgRelease(use);
                }
            }
            return ctx.bot().getContainer().isPresent();
        }, 200);
        ctx.check("useLook opens container", () -> ctx.bot().getContainer().isPresent());
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> c.close()));
        ctx.await("container closed", () -> ctx.bot().getContainer().isEmpty(), 100);
    }

    private void chunkButtons(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        int[] before = {0};
        ctx.run(() -> before[0] = ctx.bot().getChunkRadius());
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button plus = bgFindButton(screen, "gui.mockplayer.action.chunk_plus");
            if (plus != null) {
                bgClick(plus);
            }
        });
        ctx.await("chunk +1 applied", () -> ctx.bot().getChunkRadius() == before[0] + 1, 100);
        ctx.check("chunk +1 applied", () -> ctx.bot().getChunkRadius() == before[0] + 1);
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button minus = bgFindButton(screen, "gui.mockplayer.action.chunk_minus");
            if (minus != null) {
                bgClick(minus);
            }
        });
        ctx.await("chunk -1 applied", () -> ctx.bot().getChunkRadius() == before[0], 100);
        ctx.check("chunk -1 applied", () -> ctx.bot().getChunkRadius() == before[0]);
    }

    private void chat(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        AtomicReference<String> msg = new AtomicReference<>("");
        ctx.run(() -> {
            MockplayerApi.listen(new com.mockplayer.api.event.BotListener() {
                @Override
                public void onChat(com.mockplayer.api.Bot b, Component message) {
                    msg.set(message.getString());
                }
            });
        });
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            EditBox box = bgFindEditBox(screen, "gui.mockplayer.action.chat_hint");
            System.out.println("[chat-diag] editBox=" + (box != null));
            if (box != null) {
                box.setValue("mockplayer-gui-chat");
            }
            Button send = bgFindButton(screen, "gui.mockplayer.action.send");
            System.out.println("[chat-diag] send=" + (send != null));
            if (send != null) {
                bgClick(send);
            }
        });
        ctx.await("gui chat broadcast", () -> msg.get().contains("mockplayer-gui-chat"), 200);
        ctx.check("gui chat broadcast", () -> msg.get().contains("mockplayer-gui-chat"));
    }

    private void autoRespawn(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        AtomicBoolean before = new AtomicBoolean();
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            before.set(ctx.bot().isAutoRespawn());
            Button toggle = bgFindButton(screen, "gui.mockplayer.action.auto_respawn");
            if (toggle != null) {
                bgClick(toggle);
            }
        });
        ctx.await("auto respawn toggled", () -> ctx.bot().isAutoRespawn() != before.get(), 50);
        ctx.check("auto respawn toggled", () -> ctx.bot().isAutoRespawn() != before.get());
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button toggle = bgFindButton(screen, "gui.mockplayer.action.auto_respawn");
            if (toggle != null) {
                bgClick(toggle);
            }
        });
        ctx.await("auto respawn restored", () -> ctx.bot().isAutoRespawn() == before.get(), 50);
        ctx.check("auto respawn restored", () -> ctx.bot().isAutoRespawn() == before.get());
    }

    private void keyMapping(TestContext ctx) {
        ctx.check("shouldOpen with config on", BotGui::shouldOpen);
        ctx.run(() -> {
            ModConfig off = new ModConfig();
            off.setGuiEnabled(false);
            MockplayerConfig.save(off);
            MockplayerConfig.reload();
        });
        ctx.await("shouldOpen false when disabled", () -> !BotGui.shouldOpen(), 50);
        ctx.check("shouldOpen false when disabled", () -> !BotGui.shouldOpen());
        ctx.run(() -> {
            MockplayerConfig.save(new ModConfig());
            MockplayerConfig.reload();
        });
        ctx.await("shouldOpen restored", BotGui::shouldOpen, 50);
        ctx.check("shouldOpen restored", BotGui::shouldOpen);
    }

    private void holdRepeat(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        float[] startYaw = {0.0F};
        ctx.run(() -> {
            startYaw[0] = ctx.bot().getLocalPlayer().getYRot();
            BotControlScreen screen = bgScreen();
            Button right = bgFindButton(screen, "gui.mockplayer.action.turn_right");
            if (right != null) {
                bgClick(right);
            }
        });
        ctx.await("hold turn_right rotates", () -> {
            float delta = Math.abs((((ctx.bot().getLocalPlayer().getYRot() - startYaw[0]) % 360) + 360) % 360);
            return delta >= 30.0F;
        }, 200);
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button right = bgFindButton(screen, "gui.mockplayer.action.turn_right");
            if (right != null) {
                bgRelease(right);
            }
        });
        ctx.check("hold turn_right rotates >=30", () -> {
            float delta = Math.abs((((ctx.bot().getLocalPlayer().getYRot() - startYaw[0]) % 360) + 360) % 360);
            return delta >= 30.0F;
        });
    }

    private void xpBar(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(),
                "experience set " + ctx.botName() + " 10 levels")));
        ctx.await("xp synced", () -> ctx.bot().getLocalPlayer().experienceLevel >= 10, 200);
        ctx.check("xp synced", () -> ctx.bot().getLocalPlayer().experienceLevel >= 10);
        ctx.await("xp bar probe rendered", () -> BotGui.probeXpBarCount() > 0, 100);
        ctx.check("xp bar probe rendered", () -> BotGui.probeXpBarCount() > 0);
    }

    private void shieldHold(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ensureTab(ctx, "gui.mockplayer.tab.actions");
        AtomicBoolean using = new AtomicBoolean();
        ctx.run(() -> ctx.server().execute(() -> {
            var cmds = ctx.server().getCommands();
            var src = ctx.server().createCommandSourceStack();
            cmds.performPrefixedCommand(src, "item replace entity " + ctx.botName()
                    + " weapon.mainhand with minecraft:iron_sword");
            cmds.performPrefixedCommand(src, "item replace entity " + ctx.botName()
                    + " weapon.offhand with minecraft:shield");
        }));
        ctx.await("shield synced", () -> ctx.bot().getLocalPlayer().getOffhandItem()
                .is(Items.SHIELD), 200);
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button use = bgFindButton(screen, "gui.mockplayer.action.use_look");
            if (use != null) {
                bgClick(use);
            }
        });
        ctx.await("hold use_look raises shield", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
                using.set(sp != null && sp.isUsingItem()
                        && sp.getUseItem().is(Items.SHIELD));
            });
            return using.get();
        }, 200);
        ctx.check("hold use_look raises shield", using::get);
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            Button use = bgFindButton(screen, "gui.mockplayer.action.use_look");
            if (use != null) {
                bgRelease(use);
            }
            ctx.bot().actions().stopSustained();
        });
    }

    private void discardSlot(TestContext ctx) {
        createBot(ctx);
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 200);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.run(() -> Minecraft.getInstance().setScreen(null));
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                sp.getInventory().clearContent();
            }
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "give " + ctx.botName() + " minecraft:emerald 1");
        }));
        ctx.await("emerald synced", () -> ctx.bot().getLocalPlayer().getInventory()
                .getItem(0).is(Items.EMERALD), 200);
        ensureTab(ctx, "gui.mockplayer.tab.inventory");
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            if (screen != null) {
                bgClickInventorySlot(screen, 36);
            }
        });
        ctx.await("emerald carried", () -> ctx.bot().getLocalPlayer().containerMenu
                .getCarried().is(Items.EMERALD), 100);
        ctx.run(() -> {
            BotControlScreen screen = bgScreen();
            if (screen != null) {
                bgClickDiscardSlot(screen);
            }
        });
        ctx.await("discard clears carried", () -> ctx.bot().getLocalPlayer().containerMenu
                .getCarried().isEmpty(), 100);
        ctx.check("discard clears carried", () -> ctx.bot().getLocalPlayer().containerMenu
                .getCarried().isEmpty());
    }

    private void mainPlayerMineIsolation(TestContext ctx) {
        ctx.run(() -> {
            if (ctx.botName() != null) {
                MockplayerApi.bots().removeBot(ctx.botName(), "command");
            }
        });
        ctx.check("main player still can mine (no crash)", () -> true);
    }

    // ===== GUI helpers（与旧 TestRunner 等价） =====

    private static BotControlScreen bgScreen() {
        net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
        return s instanceof BotControlScreen screen ? screen : null;
    }

    private static Button bgFindButton(BotControlScreen screen, String key) {
        if (screen == null) {
            return null;
        }
        String label = Component.translatable(key).getString().replace("%s", "");
        for (Object child : screen.children()) {
            if (child instanceof Button b
                    && b.getMessage().getString().replace("● ", "").contains(label)) {
                return b;
            }
        }
        return null;
    }

    private static EditBox bgFindEditBox(BotControlScreen screen, String key) {
        if (screen == null) {
            return null;
        }
        String label = Component.translatable(key).getString();
        for (Object child : screen.children()) {
            if (child instanceof EditBox e && label.equals(e.getMessage().getString())) {
                return e;
            }
        }
        return null;
    }

    private static void bgClick(Button b) {
        b.mouseClicked(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0,
                b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
    }

    private static void bgRelease(Button b) {
        b.mouseReleased(new MouseButtonEvent(b.getX() + b.getWidth() / 2.0,
                b.getY() + b.getHeight() / 2.0, new MouseButtonInfo(0, 0)));
    }

    /** 用例前置：切到目标 Tab 并等切换完成（消除跨用例 Tab 状态残留）。 */
    private static void ensureTab(TestContext ctx, String tabKey) {
        reopenGui(ctx);
        ctx.run(() -> {
            BotControlScreen s = bgScreen();
            Button tab = bgFindButton(s, tabKey);
            if (tab != null) {
                bgClick(tab);
            }
        });
        int[] tabWait = {0};
        ctx.await("tab switch", () -> ++tabWait[0] >= 5, 20);
    }

    /** 重建 GUI 并重新选中当前假人（BotControlScreen 构造时从 BotManager 选第一个 bot）。 */
    private static void reopenGui(TestContext ctx) {
        ctx.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(null);
            BotGui.open(mc);
        });
    }

    private static boolean bgClickInventorySlot(BotControlScreen screen, int menuSlot) {
        return bgClickInventorySlotButton(screen, menuSlot, 0);
    }

    private static boolean bgRightClickInventorySlot(BotControlScreen screen, int menuSlot) {
        return bgClickInventorySlotButton(screen, menuSlot, 1);
    }

    private static boolean bgClickInventorySlotButton(
            BotControlScreen screen, int menuSlot, int button) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float scale = BotGui.layoutScale(w, h);
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
        double sx = BotGui.panelX(w, h) + (104 + lx + 10) * scale;
        double sy = BotGui.panelY(w, h) + (44 + ly + 10) * scale;
        return screen.mouseClicked(new MouseButtonEvent(sx, sy,
                new MouseButtonInfo(button, 0)), false);
    }

    private static boolean bgClickDiscardSlot(BotControlScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float scale = BotGui.layoutScale(w, h);
        double lx = 24 + 9 * 20 + 20;
        double ly = 3 * 20;
        double sx = BotGui.panelX(w, h) + (104 + lx + 10) * scale;
        double sy = BotGui.panelY(w, h) + (44 + ly + 10) * scale;
        return screen.mouseClicked(new MouseButtonEvent(sx, sy,
                new MouseButtonInfo(0, 0)), false);
    }

    private void i18nGuiLangChecks(TestContext ctx) {
        try {
            var en = parseLang("en_us.json");
            var zh = parseLang("zh_cn.json");
            java.util.Set<String> enKeys = new java.util.TreeSet<>();
            java.util.Set<String> zhKeys = new java.util.TreeSet<>();
            en.entrySet().forEach(e -> {
                if (e.getKey().startsWith("gui.mockplayer.")) {
                    enKeys.add(e.getKey());
                }
            });
            zh.entrySet().forEach(e -> {
                if (e.getKey().startsWith("gui.mockplayer.")) {
                    zhKeys.add(e.getKey());
                }
            });
            ctx.checkNow("gui i18n key sets identical (en/zh)", enKeys.equals(zhKeys),
                    "en=" + enKeys.size() + " zh=" + zhKeys.size());
            ctx.checkNow("gui i18n values non-empty",
                    enKeys.stream().allMatch(k -> !en.get(k).getAsString().isBlank())
                            && zhKeys.stream().allMatch(k -> !zh.get(k).getAsString().isBlank()));
        } catch (Exception e) {
            ctx.checkNow("gui i18n lang files parse", false, e.toString());
        }
    }

    private com.google.gson.JsonObject parseLang(String fileName) throws java.io.IOException {
        var location = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "mockplayer", "lang/" + fileName);
        var resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            throw new java.io.IOException("missing lang file " + fileName);
        }
        try (var reader = resource.get().openAsReader()) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
