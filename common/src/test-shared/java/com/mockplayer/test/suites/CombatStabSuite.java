package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * combat-stab：铁矛戳刺（STAB 包）→ 服务端 SPEAR 伤害 + 挥动广播；
 * GUI 左键 attackLook 戳刺 / 右键举矛。
 */
public class CombatStabSuite extends TestSuite {

    private static final String BOT = "tbot-stab";

    /** 服务端状态快照（server 线程写、client 线程读，volatile 保证可见）。 */
    private static final class State {
        volatile boolean spearDamage;
        volatile boolean swingSeen;
        volatile boolean swingSampled;
        volatile float scale = -1.0F;
        volatile float huskHp = -1.0F;
    }

    public CombatStabSuite() {
        super("combat-stab");
        test("左键戳刺与 GUI 左键/右键", this::stabAll);
    }

    private void stabAll(TestContext ctx) {
        State st = new State();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.server(() -> summonHusk(ctx, 3.0));
        ctx.await("client sees husk", () -> ctx.bot.getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Zombie), 600);
        ctx.check("client sees husk", () -> ctx.bot.getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Zombie));
        ctx.run(() -> giveSpear(ctx));
        ctx.await("server holds spear", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                st.spearDamage = false;
                if (sp != null) {
                    st.spearDamage = sp.getMainHandItem().is(Items.IRON_SPEAR);
                }
            });
            return st.spearDamage;
        }, 400);
        ctx.check("server holds spear", () -> st.spearDamage);
        ctx.await("stab damage", () -> {
            readSpearDamage(ctx, st);
            if (st.spearDamage && st.huskHp >= 0 && st.huskHp < 20 && st.swingSampled) {
                return true;
            }
            if (st.scale >= 1.0F) {
                Entity target = ctx.bot.getEntitiesNear(64).stream()
                        .filter(e -> e instanceof Zombie).findFirst().orElse(null);
                if (target != null) {
                    ctx.bot.getLocalPlayer().getInventory().setSelectedSlot(0);
                    ctx.bot.actions().lookAt(target);
                    ctx.bot.actions().stab();
                }
            }
            return false;
        }, 400);
        ctx.check("husk hurt by SPEAR (left-click stab)", () -> st.huskHp >= 0 && st.huskHp < 20);
        ctx.check("stab swing animation broadcast", () -> st.swingSeen);
        ctx.check("fake still PLAYING (no server crash)",
                () -> ctx.bot.getLifecycle() == BotLifecycle.PLAYING);
        // GUI 左键：蓄力满 → 记录血 → attackLook → 血降
        float[] hpBefore = {-1.0F};
        ctx.await("gui spear charge", () -> ctx.bot.getLocalPlayer() != null
                && ctx.bot.getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F, 200);
        ctx.server(() -> {
            ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
            var husk = level != null ? level.getEntitiesOfClass(
                    Zombie.class, new AABB(-64, -64, -64, 64, 64, 64))
                    .stream().findFirst().orElse(null) : null;
            hpBefore[0] = husk != null ? husk.getHealth() : -1.0F;
        });
        ctx.run(() -> {
            Entity target = ctx.bot.getEntitiesNear(64).stream()
                    .filter(e -> e instanceof Zombie).findFirst().orElse(null);
            if (target != null) {
                ctx.bot.actions().lookAt(target);
            }
            ctx.bot.actions().attackLook();
        });
        ctx.await("gui left-click spear stabs", () -> {
            ctx.server().execute(() -> {
                ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
                var husk = level != null ? level.getEntitiesOfClass(
                        Zombie.class, new AABB(-64, -64, -64, 64, 64, 64))
                        .stream().findFirst().orElse(null) : null;
                st.huskHp = husk != null ? husk.getHealth() : -1.0F;
            });
            return st.huskHp >= 0 && hpBefore[0] > 0 && st.huskHp < hpBefore[0];
        }, 120);
        ctx.check("gui left-click spear stabs", () -> st.huskHp >= 0 && hpBefore[0] > 0
                && st.huskHp < hpBefore[0], () -> "hp " + hpBefore[0] + " -> " + st.huskHp);
        ctx.run(() -> {
            Entity target = ctx.bot.getEntitiesNear(64).stream()
                    .filter(e -> e instanceof Zombie).findFirst().orElse(null);
            if (target != null) {
                ctx.bot.actions().lookAt(target);
            }
            ctx.bot.actions().useLook();
        });
        AtomicBoolean using = new AtomicBoolean();
        ctx.await("gui right-click raises spear", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                using.set(sp != null && sp.isUsingItem()
                        && sp.getUseItem().is(Items.IRON_SPEAR));
            });
            return using.get();
        }, 100);
        ctx.check("gui right-click raises spear", using::get);
        ctx.run(() -> {
            ctx.bot.actions().stopSustained();
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }

    private static void summonHusk(TestContext ctx, double ahead) {
        ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
        if (sp != null) {
            String cmd = String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                    sp.getX() + ahead, sp.getY(), sp.getZ());
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(), cmd);
        }
    }

    private static void giveSpear(TestContext ctx) {
        ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + BOT + " weapon.mainhand with minecraft:iron_spear");
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                sp.getInventory().setSelectedSlot(0);
            }
        });
        ctx.bot.getLocalPlayer().getInventory().setSelectedSlot(0);
    }

    private static void readSpearDamage(TestContext ctx, State st) {
        ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp == null) {
                return;
            }
            ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
            AABB box = new AABB(sp.getX() - 12, sp.getY() - 12, sp.getZ() - 12,
                    sp.getX() + 12, sp.getY() + 12, sp.getZ() + 12);
            var zombies = List.copyOf(level.getEntitiesOfClass(Zombie.class, box));
            var ds = zombies.isEmpty() ? null : zombies.get(0).getLastDamageSource();
            st.spearDamage = ds != null && ds.is(DamageTypes.SPEAR);
            st.scale = sp.getAttackStrengthScale(1.0F);
            st.huskHp = zombies.isEmpty() ? -1.0F : zombies.get(0).getHealth();
            st.swingSeen = sp.swinging;
            st.swingSampled = true;
        });
    }
}
