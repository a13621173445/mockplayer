package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.DebugNameTagInfo;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.List;

/**
 * debug-name-tag：F3 调试标签格式（多行/血量/饱食/内存/区块/速度/颜色/容器同行）、
 * shouldShow 联动、真实渲染路径探针（scoreText 注入 + 信息在名字上方）。
 */
public class DebugNameTagSuite extends TestSuite {

    private static final String BOT = "tbot-dbg";

    public DebugNameTagSuite() {
        super("debug-name-tag");
        test("格式与开关联动", this::formatAndToggle);
        test("渲染路径与容器行", this::renderAndContainer);
    }

    private static void createBot(TestContext ctx) {
        MockplayerApi.bots().removeBot(BOT, "command");
        FakePlayerCommands.newPlayer(BOT);
        ctx.setBot(MockplayerApi.bots().getBot(BOT).orElse(null));
        ctx.setBotName(BOT);
    }

    private void formatAndToggle(TestContext ctx) {
        ctx.run(() -> createBot(ctx));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            MockplayerConfig.save(new ModConfig());
            resetRender();
            Component info = DebugNameTagInfo.format(ctx.bot());
            ctx.checkNow("debug tag non-empty", info != null && !info.getString().isBlank());
            if (info != null) {
                List<Component> rows = info.getSiblings();
                int health = Math.round(ctx.bot().getLocalPlayer().getHealth());
                int food = ctx.bot().getLocalPlayer().getFoodData().getFoodLevel();
                int sat = Math.round(ctx.bot().getLocalPlayer().getFoodData().getSaturationLevel());
                ctx.checkNow("debug tag multi-line", rows.size() >= 3, "rows=" + rows.size());
                ctx.checkNow("debug tag health+food row", rows.stream().anyMatch(r ->
                        r.getString().startsWith("❤" + health)
                                && r.getString().contains("🍗" + food + "(" + sat + ")")));
                ctx.checkNow("debug tag memory+chunk row", rows.stream().anyMatch(r ->
                        r.getString().startsWith("💾")
                                && (r.getString().contains("KB") || r.getString().contains("MB")
                                || r.getString().contains(" B"))
                                && r.getString().contains("📡" + ctx.bot().getChunkRadius() + " chunk")));
                ctx.checkNow("debug tag speed row", rows.stream().anyMatch(r ->
                        r.getString().startsWith("🏃") && r.getString().contains("m/s")));
                ctx.checkNow("debug tag colored health", rows.stream().anyMatch(r ->
                        r.getString().startsWith("❤") && r.getStyle().getColor() != null));
                ctx.checkNow("debug tag no container", rows.stream()
                        .noneMatch(r -> r.getString().startsWith("📦")));
            }
            ctx.checkNow("debug tag null for null bot", DebugNameTagInfo.format(null) == null);
            Minecraft mc = Minecraft.getInstance();
            mc.debugEntries.setOverlayVisible(true);
            ctx.checkNow("shouldShow true by default (F3 + config on)",
                    DebugNameTagInfo.shouldShow());
            MockplayerConfig.get().setDebugOverlayEnabled(false);
            ctx.checkNow("shouldShow false when config off", !DebugNameTagInfo.shouldShow());
            MockplayerConfig.get().setDebugOverlayEnabled(true);
            mc.debugEntries.setOverlayVisible(false);
            ctx.checkNow("shouldShow false when F3 off", !DebugNameTagInfo.shouldShow());
            MockplayerConfig.save(new ModConfig());
            mc.debugEntries.setOverlayVisible(true);
        });
    }

    private void renderAndContainer(TestContext ctx) {
        ctx.run(() -> createBot(ctx));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> resetRender());
        ctx.await("debug render path executed", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && ctx.bot() != null && ctx.bot().getLocalPlayer() != null) {
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, ctx.bot().getLocalPlayer().position());
            }
            return renderCount() > 0;
        }, 100);
        ctx.check("debug render path executed", () -> renderCount() > 0,
                () -> "count=" + renderCount());
        ctx.check("debug scoreText injected", () -> {
            String injected = lastScoreText();
            return injected != null && injected.contains("❤");
        }, () -> "injected=" + lastScoreText());
        ctx.check("debug tag info above name", () -> infoOffsetY() > nameOffsetY(),
                () -> "info=" + infoOffsetY() + " name=" + nameOffsetY());
        ctx.run(() -> {
            BlockPos chestPos = ctx.bot().getLocalPlayer().blockPosition().offset(2, 0, 0);
            BlockPos p = chestPos;
            ctx.server().execute(() -> ctx.server().getLevel(Level.OVERWORLD)
                    .setBlock(p, Blocks.CHEST.defaultBlockState(), 3));
        });
        ctx.run(() -> {
            BlockPos chestPos = ctx.bot().getLocalPlayer().blockPosition().offset(2, 0, 0);
            ctx.bot().actions().lookAt(Vec3.atCenterOf(chestPos));
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(chestPos), Direction.WEST, chestPos, false);
            ctx.bot().getGameMode().useItemOn(ctx.bot().getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
        });
        ctx.await("debug tag container open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.check("debug tag container same line", () -> {
            List<Component> rows = DebugNameTagInfo.format(ctx.bot()).getSiblings();
            String title = ctx.bot().getContainer().get().getTitle().getString();
            return rows.stream().anyMatch(r ->
                    r.getString().startsWith("📦") && r.getString().contains(title));
        }, () -> "title=" + ctx.bot().getContainer().map(c -> c.getTitle().getString()).orElse(""));
        ctx.run(() -> {
            ctx.bot().getContainer().ifPresent(c -> c.close());
            Minecraft mc = Minecraft.getInstance();
            mc.debugEntries.setOverlayVisible(false);
            MockplayerConfig.save(new ModConfig());
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }

    private static int renderCount() {
        return readInt("renderCount", -1);
    }

    private static String lastScoreText() {
        try {
            Field f = DebugNameTagInfo.class.getDeclaredField("lastRendered");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static float infoOffsetY() {
        return readFloat("lastInfoOffsetY", -1.0F);
    }

    private static float nameOffsetY() {
        return readFloat("lastNameOffsetY", -1.0F);
    }

    private static void resetRender() {
        try {
            Field f = DebugNameTagInfo.class.getDeclaredField("renderCount");
            f.setAccessible(true);
            f.setInt(null, 0);
            Field l = DebugNameTagInfo.class.getDeclaredField("lastRendered");
            l.setAccessible(true);
            l.set(null, null);
            Field io = DebugNameTagInfo.class.getDeclaredField("lastInfoOffsetY");
            io.setAccessible(true);
            io.setFloat(null, -1.0F);
            Field no = DebugNameTagInfo.class.getDeclaredField("lastNameOffsetY");
            no.setAccessible(true);
            no.setFloat(null, -1.0F);
        } catch (Exception ignored) {
        }
    }

    private static int readInt(String fieldName, int fallback) {
        try {
            Field f = DebugNameTagInfo.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float readFloat(String fieldName, float fallback) {
        try {
            Field f = DebugNameTagInfo.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getFloat(null);
        } catch (Exception e) {
            return fallback;
        }
    }
}
