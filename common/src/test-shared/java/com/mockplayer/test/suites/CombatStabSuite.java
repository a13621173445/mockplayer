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

    public CombatStabSuite() {
        super("combat-stab");
        test("左键戳刺与 GUI 左键/右键", this::stabAll);
    }

    private void stabAll(TestContext ctx) {
        CombatSupport.State st = new CombatSupport.State();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.server(() -> CombatSupport.summonHusk(ctx, BOT, 3.0));
        ctx.await("client sees husk", () -> ctx.bot().getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Zombie), 600);
        ctx.run(() -> CombatSupport.giveSpear(ctx, BOT));
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
        ctx.await("stab damage", () -> {
            CombatSupport.readSpearDamage(ctx, BOT, st);
            if (st.spearDamage && st.huskHp >= 0 && st.huskHp < 20 && st.swingSampled) {
                return true;
            }
            if (st.scale >= 1.0F) {
                Entity target = ctx.bot().getEntitiesNear(64).stream()
                        .filter(e -> e instanceof Zombie).findFirst().orElse(null);
                if (target != null) {
                    ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
                    ctx.bot().actions().lookAt(target);
                    ctx.bot().actions().stab();
                }
            }
            return false;
        }, 400);
        ctx.check("husk hurt by SPEAR (left-click stab)", () -> st.huskHp >= 0 && st.huskHp < 20);
        ctx.check("stab swing animation broadcast", () -> st.swingSeen);
        ctx.check("fake still PLAYING (no server crash)",
                () -> ctx.bot().getLifecycle() == BotLifecycle.PLAYING);
        // GUI 左键：蓄力满 → 记录血 → attackLook → 血降
        float[] hpBefore = {-1.0F};
        ctx.await("gui spear charge", () -> ctx.bot().getLocalPlayer() != null
                && ctx.bot().getLocalPlayer().getAttackStrengthScale(1.0F) >= 0.99F, 200);
        ctx.server(() -> {
            ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
            var husk = level != null ? level.getEntitiesOfClass(
                    Zombie.class, new AABB(-64, -64, -64, 64, 64, 64))
                    .stream().findFirst().orElse(null) : null;
            hpBefore[0] = husk != null ? husk.getHealth() : -1.0F;
        });
        ctx.run(() -> {
            Entity target = ctx.bot().getEntitiesNear(64).stream()
                    .filter(e -> e instanceof Zombie).findFirst().orElse(null);
            if (target != null) {
                ctx.bot().actions().lookAt(target);
            }
            ctx.bot().actions().attackLook();
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
            Entity target = ctx.bot().getEntitiesNear(64).stream()
                    .filter(e -> e instanceof Zombie).findFirst().orElse(null);
            if (target != null) {
                ctx.bot().actions().lookAt(target);
            }
            ctx.bot().actions().useLook();
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
            ctx.bot().actions().stopSustained();
            CombatSupport.removeHusks(ctx);
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }
}
