package com.mockplayer.test.suites;

import com.mockplayer.test.framework.TestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** 战斗套件公共原语：召唤 husk / 给矛 / 读服务端伤害与蓄力 / 清区域。 */
public final class CombatSupport {

    /** 服务端状态快照（server 线程写、client 线程读）。 */
    public static final class State {
        public volatile boolean spearDamage;
        public volatile boolean swingSeen;
        public volatile boolean swingSampled;
        public volatile float scale = -1.0F;
        public volatile float huskHp = -1.0F;
    }

    private CombatSupport() {
    }

    public static void summonHusk(TestContext ctx, String bot, double ahead) {
        ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(bot);
        if (sp != null) {
            String cmd = String.format("summon minecraft:husk %.2f %.2f %.2f {NoAI:1b}",
                    sp.getX() + ahead, sp.getY(), sp.getZ());
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(), cmd);
        }
    }

    public static void giveSpear(TestContext ctx, String bot) {
        ctx.server().execute(() -> {
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "item replace entity " + bot + " weapon.mainhand with minecraft:iron_spear");
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(bot);
            if (sp != null) {
                sp.getInventory().setSelectedSlot(0);
            }
        });
        ctx.bot().getLocalPlayer().getInventory().setSelectedSlot(0);
    }

    public static void readSpearDamage(TestContext ctx, String bot, State st) {
        ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(bot);
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

    public static void removeHusks(TestContext ctx) {
        ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:husk]"));
    }

    /** 清空假人周围 radius 格方块（连跑时残留方块会卡冲刺路径）。 */
    public static void clearArea(TestContext ctx, String bot, int radius) {
        ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(bot);
            if (sp == null) {
                return;
            }
            ServerLevel level = ctx.server().getLevel(Level.OVERWORLD);
            BlockPos p = sp.blockPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos q = p.offset(dx, dy, dz);
                        if (!level.getBlockState(q).isAir()) {
                            level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        });
    }
}
