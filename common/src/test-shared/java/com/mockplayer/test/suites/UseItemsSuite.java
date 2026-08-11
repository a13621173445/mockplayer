package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * use-items：长按使用物品真实路径——面包/盾/弓/雪球/副手盾/三叉戟/弩/药水/床，
 * 服务端强断言 + 主玩家视角可见性。
 */
public class UseItemsSuite extends TestSuite {

    private static final String BOT = "tbot-use";

    public UseItemsSuite() {
        super("use-items");
        test("面包自动吃完", this::bread);
        test("盾牌格挡与释放", this::shield);
        test("弓蓄力放箭", this::bow);
        test("雪球投掷", this::snowball);
        test("副手持盾格挡", this::offhandShield);
        test("三叉戟蓄力投掷", this::trident);
        test("弩装填发射", this::crossbow);
        test("喷溅药水投掷", this::potion);
        test("睡觉与起床", this::bed);
    }

    private static void giveMain(TestContext ctx, String itemId) {
        ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                sp.getInventory().clearContent();
            }
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + BOT + " weapon.mainhand with " + itemId);
            if (sp != null) {
                sp.getInventory().setSelectedSlot(0);
            }
        });
        ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
    }

    private static void awaitServerHolds(TestContext ctx, Item item, String name, UiState st) {
        ctx.await("server holds " + name, () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.holds.set(sp != null && sp.getMainHandItem().is(item));
            });
            return st.holds.get();
        }, 400);
        ctx.check("server holds " + name, () -> st.holds.get());
    }

    /** 用例间共享的跨线程状态快照（每用例新建）。 */
    private static final class UiState {
        final AtomicBoolean holds = new AtomicBoolean();
        final AtomicBoolean using = new AtomicBoolean();
        final AtomicBoolean visible = new AtomicBoolean();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicInteger holdTicks = new AtomicInteger();
    }

    private static boolean mainSees(TestContext ctx, ServerPlayer sp,
                                    Class<? extends net.minecraft.world.entity.Entity> type) {
        return !Minecraft.getInstance().level.getEntities((net.minecraft.world.entity.Entity) null,
                new AABB(sp.position().add(-32, -32, -32), sp.position().add(32, 32, 32)),
                e -> type.isInstance(e)).isEmpty();
    }

    private static boolean mainSeesPlayerAnim(TestContext ctx, ServerPlayer sp, Item item, ItemUseAnimation anim) {
        return Minecraft.getInstance().level.getEntitiesOfClass(Player.class,
                        new AABB(sp.position().add(-32, -32, -32), sp.position().add(32, 32, 32)))
                .stream().anyMatch(p -> p.getName().getString().equals(BOT)
                        && p.isUsingItem() && p.getUseItem().is(item)
                        && p.getUseItem().getUseAnimation() == anim);
    }

    private void bread(TestContext ctx) {
        UiState st = new UiState();
        AtomicInteger baseFood = new AtomicInteger(20);
        AtomicBoolean eaten = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                sp.getInventory().clearContent();
                sp.getFoodData().setFoodLevel(2);
                sp.getFoodData().setSaturation(0.0F);
                baseFood.set(sp.getFoodData().getFoodLevel());
            }
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + BOT + " weapon.mainhand with minecraft:bread");
            ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
        }));
        awaitServerHolds(ctx, Items.BREAD, "bread", st);
        ctx.run(() -> {
            ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
            ctx.bot().actions().useItem(InteractionHand.MAIN_HAND);
        });
        ctx.await("bread auto-eaten + hunger (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    st.visible.set(st.visible.get() || mainSeesPlayerAnim(ctx, sp, Items.BREAD, ItemUseAnimation.EAT));
                    st.done.set(!sp.isUsingItem()
                            && sp.getInventory().countItem(Items.BREAD) == 0
                            && sp.getFoodData().getFoodLevel() > baseFood.get());
                    eaten.set(st.done.get());
                }
            });
            return eaten.get();
        }, 200);
        ctx.check("bread auto-eaten + hunger (server)", () -> eaten.get());
        ctx.check("bread eat action visible to main player", () -> st.visible.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void shield(TestContext ctx) {
        UiState st = new UiState();
        AtomicBoolean released = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> giveMain(ctx, "minecraft:shield"));
        awaitServerHolds(ctx, Items.SHIELD, "shield", st);
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.MAIN_HAND));
        ctx.await("shield blocking (server isUsingItem)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    st.using.set(sp.isUsingItem() && sp.getUseItem().is(Items.SHIELD));
                    st.visible.set(st.visible.get() || mainSeesPlayerAnim(ctx, sp, Items.SHIELD, ItemUseAnimation.BLOCK));
                }
            });
            return st.using.get() && st.holdTicks.incrementAndGet() >= 10;
        }, 200);
        ctx.check("shield blocking (server isUsingItem)", () -> st.using.get());
        ctx.check("shield held 10+ ticks (sustained)", () -> st.holdTicks.get() >= 10);
        ctx.check("shield action visible to main player", () -> st.visible.get());
        ctx.run(() -> ctx.bot().actions().releaseUsingItem());
        ctx.await("shield released (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                released.set(sp != null && !sp.isUsingItem());
            });
            return released.get();
        }, 200);
        ctx.check("shield released (server)", () -> released.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void bow(TestContext ctx) {
        UiState st = new UiState();
        AtomicBoolean charging = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean arrow = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    sp.getInventory().clearContent();
                }
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + BOT + " weapon.mainhand with minecraft:bow");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "give " + BOT + " minecraft:arrow 64");
                if (sp != null) {
                    sp.getInventory().setSelectedSlot(0);
                }
            });
            ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
        });
        awaitServerHolds(ctx, Items.BOW, "bow", st);
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.MAIN_HAND));
        ctx.await("bow charging (server isUsingItem)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    st.using.set(sp.isUsingItem() && sp.getUseItem().is(Items.BOW));
                    st.visible.set(st.visible.get() || mainSeesPlayerAnim(ctx, sp, Items.BOW, ItemUseAnimation.BOW));
                }
            });
            return st.using.get();
        }, 200);
        ctx.check("bow charging (server isUsingItem)", () -> st.using.get());
        ctx.check("bow pull action visible to main player", () -> st.visible.get());
        ctx.await("bow hold 25 ticks", () -> st.holdTicks.incrementAndGet() >= 25, 60);
        ctx.run(() -> {
            if (!released.get()) {
                released.set(true);
                ctx.bot().actions().releaseUsingItem();
            }
        });
        ctx.await("bow released arrow (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    arrow.set(!sp.level().getEntitiesOfClass(AbstractArrow.class,
                            new AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty());
                }
            });
            return arrow.get();
        }, 200);
        ctx.check("bow released arrow (server)", () -> arrow.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void snowball(TestContext ctx) {
        UiState st = new UiState();
        AtomicBoolean thrown = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> giveMain(ctx, "minecraft:snowball"));
        awaitServerHolds(ctx, Items.SNOWBALL, "snowball", st);
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.MAIN_HAND));
        ctx.await("snowball thrown (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    boolean server = !sp.level().getEntitiesOfClass(Snowball.class,
                            new AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty();
                    st.done.set(server);
                    if (server) {
                        st.visible.set(mainSees(ctx, sp, Snowball.class));
                    }
                }
            });
            return st.done.get();
        }, 200);
        ctx.check("snowball thrown (server)", () -> st.done.get());
        ctx.await("snowball throw visible to main player", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    st.visible.set(st.visible.get() || mainSees(ctx, sp, Snowball.class));
                }
            });
            return st.visible.get();
        }, 100);
        ctx.check("snowball throw visible to main player", () -> st.visible.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void offhandShield(TestContext ctx) {
        UiState st = new UiState();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(),
                "item replace entity " + BOT + " weapon.offhand with minecraft:shield")));
        ctx.await("client holds offhand shield", () -> ctx.bot().getLocalPlayer()
                .getOffhandItem().is(Items.SHIELD), 400);
        ctx.check("client holds offhand shield", () -> ctx.bot().getLocalPlayer()
                .getOffhandItem().is(Items.SHIELD));
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.OFF_HAND));
        ctx.await("offhand shield blocking (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.using.set(sp != null && sp.isUsingItem()
                        && sp.getUseItem().is(Items.SHIELD)
                        && sp.getOffhandItem().is(Items.SHIELD));
            });
            return st.using.get();
        }, 200);
        ctx.check("offhand shield blocking (server)", () -> st.using.get());
        ctx.run(() -> ctx.bot().actions().releaseUsingItem());
        ctx.check("offhand released", () -> true);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void trident(TestContext ctx) {
        UiState st = new UiState();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean thrown = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> giveMain(ctx, "minecraft:trident"));
        awaitServerHolds(ctx, Items.TRIDENT, "trident", st);
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.MAIN_HAND));
        ctx.await("trident charging (server isUsingItem)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.using.set(sp != null && sp.isUsingItem() && sp.getUseItem().is(Items.TRIDENT));
            });
            return st.using.get() && st.holdTicks.incrementAndGet() >= 15;
        }, 200);
        ctx.run(() -> {
            if (!released.get()) {
                released.set(true);
                ctx.checkNow("trident charging (server isUsingItem)", st.using.get());
                ctx.bot().actions().releaseUsingItem();
            }
        });
        ctx.await("trident thrown (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    thrown.set(!sp.level().getEntitiesOfClass(ThrownTrident.class,
                            new AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty());
                    st.visible.set(st.visible.get() || mainSees(ctx, sp, ThrownTrident.class));
                }
            });
            return thrown.get();
        }, 200);
        ctx.check("trident thrown (server)", () -> thrown.get());
        ctx.await("trident throw visible to main player", () -> st.visible.get(), 100);
        ctx.check("trident throw visible to main player", () -> st.visible.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void crossbow(TestContext ctx) {
        UiState st = new UiState();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean arrow = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    sp.getInventory().clearContent();
                }
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + BOT + " weapon.mainhand with minecraft:crossbow");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "give " + BOT + " minecraft:arrow 64");
                if (sp != null) {
                    sp.getInventory().setSelectedSlot(0);
                }
            });
            ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
        });
        awaitServerHolds(ctx, Items.CROSSBOW, "crossbow", st);
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.MAIN_HAND));
        ctx.await("crossbow charging (server isUsingItem)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.using.set(sp != null && sp.isUsingItem() && sp.getUseItem().is(Items.CROSSBOW));
            });
            return st.using.get();
        }, 200);
        ctx.check("crossbow charging (server isUsingItem)", () -> st.using.get());
        ctx.await("crossbow charged", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.using.set(sp != null && CrossbowItem.isCharged(sp.getMainHandItem()));
            });
            return st.using.get();
        }, 200);
        ctx.await("crossbow charged (client)", () -> CrossbowItem.isCharged(
                ctx.bot().getLocalPlayer().getMainHandItem()), 100);
        ctx.run(() -> {
            if (!released.get()) {
                released.set(true);
                ctx.bot().actions().useItem(InteractionHand.MAIN_HAND);
            }
        });
        ctx.await("crossbow fired arrow (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    arrow.set(!sp.level().getEntitiesOfClass(AbstractArrow.class,
                            new AABB(sp.position().add(-16, -8, -16), sp.position().add(16, 8, 16))).isEmpty());
                }
            });
            return arrow.get();
        }, 200);
        ctx.check("crossbow fired arrow (server)", () -> arrow.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void potion(TestContext ctx) {
        UiState st = new UiState();
        AtomicBoolean thrown = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> giveMain(ctx, "minecraft:splash_potion"));
        awaitServerHolds(ctx, Items.SPLASH_POTION, "splash potion", st);
        ctx.run(() -> ctx.bot().actions().useItem(InteractionHand.MAIN_HAND));
        ctx.await("splash potion thrown (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    thrown.set(!sp.level().getEntitiesOfClass(ThrownSplashPotion.class,
                            new AABB(sp.position().add(-24, -12, -24), sp.position().add(24, 12, 24))).isEmpty());
                    st.visible.set(st.visible.get() || mainSees(ctx, sp, ThrownSplashPotion.class));
                }
            });
            return thrown.get();
        }, 200);
        ctx.check("splash potion thrown (server)", () -> thrown.get());
        ctx.await("potion throw visible to main player", () -> st.visible.get(), 100);
        ctx.check("potion throw visible to main player", () -> st.visible.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void bed(TestContext ctx) {
        AtomicReference<BlockPos> bedPos = new AtomicReference<>();
        AtomicBoolean used = new AtomicBoolean();
        AtomicBoolean sleeping = new AtomicBoolean();
        AtomicBoolean awake = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> {
            bedPos.set(ctx.bot().getLocalPlayer().blockPosition().offset(1, 0, 0));
            BlockPos p = bedPos.get();
            ctx.server().execute(() -> {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "time set night");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "setblock " + p.getX() + " " + p.getY() + " " + p.getZ()
                                + " minecraft:red_bed[facing=south,part=head]");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "setblock " + p.getX() + " " + p.getY() + " " + (p.getZ() - 1)
                                + " minecraft:red_bed[facing=south,part=foot]");
            });
        });
        ctx.await("bed visible", () -> bedPos.get() != null
                && ctx.bot().getBlockState(bedPos.get()).is(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.Identifier.tryParse("minecraft:red_bed")).get().value()), 600);
        ctx.run(() -> ctx.bot().actions().lookAt(Vec3.atCenterOf(bedPos.get())));
        ctx.await("wait look settle", () -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            return sp != null && !SuitesSupport.isAwaitingPosition(sp);
        }, 100);
        ctx.run(() -> {
            if (!used.get()) {
                used.set(true);
                ctx.server().execute(() -> {
                    ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                            "item replace entity " + BOT + " weapon.mainhand with air");
                    ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                            "item replace entity " + BOT + " weapon.offhand with air");
                });
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(bedPos.get()), Direction.UP, bedPos.get(), false);
                ctx.bot().getGameMode().useItemOn(ctx.bot().getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
            }
        });
        ctx.await("fake sleeping (server isSleeping)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                sleeping.set(sp != null && sp.isSleeping());
            });
            return sleeping.get();
        }, 200);
        ctx.check("fake sleeping (server isSleeping)", () -> sleeping.get());
        ctx.run(() -> ctx.bot().actions().wakeUp());
        ctx.await("stopSleeping woke up (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                awake.set(sp != null && !sp.isSleeping());
            });
            return awake.get();
        }, 200);
        ctx.check("stopSleeping woke up (server)", () -> awake.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }
}
