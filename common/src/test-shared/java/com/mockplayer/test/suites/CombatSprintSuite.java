package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * combat-sprint：冲刺戳刺（sprint + use → 动能 SPEAR 伤害）、
 * 长按右键冲刺（sustainedUseLook）、长按右键看地面蓄力不被方块分支重置。
 */
public class CombatSprintSuite extends TestSuite {

    private static final String BOT = "tbot-spr";

    public CombatSprintSuite() {
        super("combat-sprint");
        test("冲刺戳刺动能伤害", this::sprintThrust);
        test("长按右键冲刺伤害", this::holdThrust);
        test("长按右键看地面蓄力不重置", this::holdWalkCharge);
    }

    private void sprintThrust(TestContext ctx) {
        CombatSupport.State st = new CombatSupport.State();
        AtomicReference<double[]> base = new AtomicReference<>();
        AtomicReference<double[]> cur = new AtomicReference<>();
        AtomicBoolean issued = new AtomicBoolean();
        AtomicBoolean facingUsing = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> CombatSupport.clearArea(ctx, BOT, 8));
        ctx.run(() -> CombatSupport.summonHusk(ctx, BOT, 6.0));
        ctx.await("client sees husk", () -> ctx.bot().getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Zombie), 600);
        ctx.run(() -> CombatSupport.giveSpear(ctx, BOT));
        ctx.await("server holds spear", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.spearDamage = sp != null && sp.getMainHandItem().is(Items.IRON_SPEAR);
            });
            return st.spearDamage;
        }, 400);
        ctx.check("server holds spear", () -> st.spearDamage);
        ctx.run(() -> {
            if (!issued.get()) {
                issued.set(true);
                Entity target = ctx.bot().getEntitiesNear(64).stream()
                        .filter(e -> e instanceof Zombie).findFirst().orElse(null);
                if (target != null) {
                    ctx.bot().actions().lookAt(target);
                }
                ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
                ctx.bot().actions().setForward(1.0F);
                ctx.bot().actions().setSprint(true);
                ctx.bot().actions().useItem(InteractionHand.MAIN_HAND);
                ctx.server().execute(() -> {
                    ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                    if (sp != null) {
                        base.set(new double[]{sp.getX(), sp.getZ()});
                    }
                });
            }
        });
        ctx.await("fake sprinted on server (moved)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    cur.set(new double[]{sp.getX(), sp.getZ()});
                }
            });
            double[] b = base.get();
            double[] c = cur.get();
            return b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 0.5 || Math.abs(c[1] - b[1]) > 0.5);
        }, 300);
        ctx.check("fake sprinted on server (moved)", () ->
                base.get() != null && cur.get() != null
                        && (Math.abs(cur.get()[0] - base.get()[0]) > 0.5
                        || Math.abs(cur.get()[1] - base.get()[1]) > 0.5));
        ctx.await("sprint facing/using", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp == null) {
                    return;
                }
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                AABB box = new AABB(sp.getX() - 12, sp.getY() - 12, sp.getZ() - 12,
                        sp.getX() + 12, sp.getY() + 12, sp.getZ() + 12);
                var zombies = List.copyOf(level.getEntitiesOfClass(Zombie.class, box));
                if (!zombies.isEmpty()) {
                    Entity husk = zombies.get(0);
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
                    facingUsing.set(diff < 45.0 && sp.isUsingItem());
                }
            });
            return facingUsing.get();
        }, 200);
        ctx.check("fake facing husk", facingUsing::get);
        ctx.await("sprint-thrust SPEAR damage", () -> {
            CombatSupport.readSpearDamage(ctx, BOT, st);
            return st.swingSampled && st.spearDamage && st.huskHp >= 0 && st.huskHp < 20;
        }, 300);
        ctx.check("husk hurt by SPEAR (sprint-thrust)",
                () -> st.swingSampled && st.huskHp >= 0 && st.huskHp < 20);
        ctx.check("fake still PLAYING (no server crash)",
                () -> ctx.bot().getLifecycle() == BotLifecycle.PLAYING);
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.bot().actions().stopSustained();
            CombatSupport.removeHusks(ctx);
        });
    }

    private void holdThrust(TestContext ctx) {
        CombatSupport.State st = new CombatSupport.State();
        AtomicBoolean usingOk = new AtomicBoolean();
        AtomicBoolean poseSampled = new AtomicBoolean();
        int[] wait = {0};
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> CombatSupport.clearArea(ctx, BOT, 8));
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.bot().actions().stopSustained();
            CombatSupport.removeHusks(ctx);
        });
        ctx.await("wait after kill", () -> ++wait[0] >= 10, 20);
        ctx.run(() -> CombatSupport.summonHusk(ctx, BOT, 6.0));
        ctx.await("hold husk seen", () -> ctx.bot().getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Zombie), 200);
        wait[0] = 0;
        ctx.await("wait husk refresh", () -> ++wait[0] >= 20, 40);
        ctx.run(() -> {
            Entity target = ctx.bot().getEntitiesNear(64).stream()
                    .filter(e -> e instanceof Zombie).findFirst().orElse(null);
            if (target != null) {
                ctx.bot().actions().lookAt(target);
            }
            ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
            ctx.bot().actions().sustainedUseLook();
            ctx.bot().actions().setForward(1.0F);
            ctx.bot().actions().setSprint(true);
        });
        ctx.await("hold-thrust SPEAR damage", () -> {
            CombatSupport.readSpearDamage(ctx, BOT, st);
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                usingOk.set(sp != null && sp.isUsingItem()
                        && sp.getUseItem().is(Items.IRON_SPEAR));
                poseSampled.set(true);
            });
            return st.spearDamage && st.huskHp >= 0 && st.huskHp < 20 && poseSampled.get();
        }, 300);
        ctx.check("husk hurt by SPEAR (hold right-click sprint)",
                () -> st.huskHp >= 0 && st.huskHp < 20);
        ctx.check("hold right-click shows using pose", usingOk::get);
        ctx.check("fake still PLAYING (no server crash)",
                () -> ctx.bot().getLifecycle() == BotLifecycle.PLAYING);
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.bot().actions().stopSustained();
            CombatSupport.removeHusks(ctx);
        });
    }

    private void holdWalkCharge(TestContext ctx) {
        int[] wait = {0};
        AtomicBoolean issued = new AtomicBoolean();
        int[] remaining = {-1};
        int[] duration = {-1};
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.bot().actions().stopSustained();
            CombatSupport.removeHusks(ctx);
        });
        ctx.await("wait after kill", () -> ++wait[0] >= 10, 20);
        ctx.run(() -> {
            if (!issued.get()) {
                issued.set(true);
                ctx.bot().actions().look(ctx.bot().getLocalPlayer().getYRot(), -45.0F);
                ctx.bot().actions().sustainedUseLook();
            }
        });
        wait[0] = 0;
        ctx.await("hold walk charge sampling", () -> ++wait[0] >= 35, 70);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null && sp.isUsingItem()) {
                remaining[0] = sp.getUseItemRemainingTicks();
                duration[0] = sp.getUseItem().getUseDuration(sp);
            }
        }));
        ctx.await("charge sampled", () -> remaining[0] >= 0 && duration[0] > 0, 50);
        ctx.check("hold right-click charge not reset while aiming at block",
                () -> remaining[0] <= duration[0] - 20,
                () -> "remaining=" + remaining[0] + " duration=" + duration[0]);
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.bot().actions().stopSustained();
            CombatSupport.removeHusks(ctx);
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }
}
