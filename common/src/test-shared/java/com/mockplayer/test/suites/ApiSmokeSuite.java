package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.session.QueryCommands;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicReference;

/**
 * api-smoke：创建/生命周期/动作原语（含移动/跳跃端到端）/世界信息/管理边界/owner 删除。
 * 迁移自旧 TestRunner，断言一一对应。
 */
public class ApiSmokeSuite extends TestSuite {

    private static final String BOT = "tbot-smoke";

    public ApiSmokeSuite() {
        super("api-smoke");
        test("创建与生命周期", this::createAndLifecycle);
        test("动作原语与移动/跳跃端到端", this::actionsAndMove);
        test("世界信息/管理边界/新原语", this::worldAndBoundary);
    }

    private static void createBot(TestContext ctx) {
        MockplayerApi.bots().removeBot(BOT, "command");
        FakePlayerCommands.newPlayer(BOT);
        ctx.bot = MockplayerApi.bots().getBot(BOT).orElse(null);
        ctx.botName = BOT;
    }

    private static void readServerPos(TestContext ctx, AtomicReference<double[]> out) {
        MinecraftServer srv = ctx.server();
        srv.execute(() -> {
            ServerPlayer sp = srv.getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                out.set(new double[]{sp.getX(), sp.getZ(), sp.getY()});
            }
        });
    }

    private void createAndLifecycle(TestContext ctx) {
        ctx.run(() -> createBot(ctx));
        ctx.await("lifecycle PLAYING", () -> ctx.bot != null
                && ctx.bot.getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.check("createBot non-null", () -> ctx.bot != null);
        ctx.check("lifecycle PLAYING",
                () -> ctx.bot != null && ctx.bot.getLifecycle() == BotLifecycle.PLAYING);
        ctx.check("getLocalPlayer != null", () -> ctx.bot != null && ctx.bot.getLocalPlayer() != null);
        ctx.check("getLevel != null", () -> ctx.bot != null && ctx.bot.getLevel() != null);
        ctx.check("getGameMode != null", () -> ctx.bot != null && ctx.bot.getGameMode() != null);
        ctx.check("getOwner == command", () -> ctx.bot != null && "command".equals(ctx.bot.getOwner()));
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void actionsAndMove(TestContext ctx) {
        AtomicReference<double[]> start = new AtomicReference<>();
        AtomicReference<double[]> cur = new AtomicReference<>();
        double[] jumpStartY = {0.0};
        ctx.run(() -> createBot(ctx));
        ctx.await("lifecycle PLAYING", () -> ctx.bot != null
                && ctx.bot.getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> ctx.bot.actions().look(0.0F, 0.0F));
        ctx.check("look yRot", () -> Math.abs(ctx.bot.getLocalPlayer().getYRot() - 0.0F) < 1.0F);
        ctx.run(() -> ctx.bot.actions().turn(90.0F, 30.0F));
        ctx.check("turn yRot+90",
                () -> Math.abs(((ctx.bot.getLocalPlayer().getYRot() % 360) + 360) % 360 - 90.0F) < 1.0F);
        ctx.check("turn xRot+30", () -> Math.abs(ctx.bot.getLocalPlayer().getXRot() - 30.0F) < 1.0F);
        ctx.run(() -> {
            ctx.bot.actions().setForward(1.0F);
            ctx.bot.actions().setSneak(true);
        });
        ctx.await("forward moveVector", () -> {
            var mv = ((com.mockplayer.session.accessor.MockplayerClientInputAccessor)
                    ctx.bot.getLocalPlayer().input).mockplayer$getMoveVector();
            return mv.y > 0;
        }, 20);
        ctx.await("sneak keyPresses", () -> ctx.bot.getLocalPlayer().input.keyPresses.shift(), 20);
        ctx.server(() -> readServerPos(ctx, start));
        ctx.await("服务端移动", () -> {
            readServerPos(ctx, cur);
            double[] c = cur.get();
            double[] b = start.get();
            return b != null && c != null && (Math.abs(c[0] - b[0]) > 0.5 || Math.abs(c[1] - b[1]) > 0.5);
        }, 200);
        ctx.run(() -> {
            double[] b = cur.get();
            jumpStartY[0] = b != null ? b[2] : 0.0;
            ctx.bot.actions().stop();
            ctx.bot.actions().setForward(1.0F);
            ctx.bot.actions().jump();
        });
        ctx.await("服务端跳跃", () -> {
            readServerPos(ctx, cur);
            double[] c = cur.get();
            return c != null && c[2] > jumpStartY[0] + 0.3;
        }, 100);
        ctx.check("fake jumped on server", () -> true,
                () -> "y +" + String.format("%.2f", (cur.get() == null ? 0.0 : cur.get()[2]) - jumpStartY[0]));
        ctx.run(() -> ctx.bot.actions().stop());
        ctx.check("stop resets input", () -> !ctx.bot.getLocalPlayer().input.keyPresses.forward()
                && !ctx.bot.getLocalPlayer().input.keyPresses.shift());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void worldAndBoundary(TestContext ctx) {
        ctx.run(() -> createBot(ctx));
        ctx.await("lifecycle PLAYING", () -> ctx.bot != null
                && ctx.bot.getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.await("区块与实体同步", () -> ctx.bot != null
                && ctx.bot.isBlockLoaded(ctx.bot.getLocalPlayer().blockPosition())
                && !ctx.bot.getEntitiesNear(64).isEmpty(), 300);
        ctx.check("getEntitiesNear", () -> !ctx.bot.getEntitiesNear(64).isEmpty());
        ctx.check("isBlockLoaded", () -> ctx.bot.isBlockLoaded(ctx.bot.getLocalPlayer().blockPosition()));
        ctx.check("getBlockState air check",
                () -> ctx.bot.getBlockState(ctx.bot.getLocalPlayer().blockPosition()) != null);
        ctx.check("getContainer empty (no menu open)", () -> ctx.bot.getContainer().isEmpty());
        ctx.run(() -> {
            var boundaryApiBot = MockplayerApi.bots().createBot(
                    BotProfile.of("tbot-api-b", "command"));
            if (boundaryApiBot == null) {
                return;
            }
            String boundaryList = QueryCommands.list().getString();
            ctx.checkNow("boundary api bot not in query list",
                    !boundaryList.contains("tbot-api-b"), "list=" + boundaryList);
            String boundaryDel = FakePlayerCommands.delPlayer("tbot-api-b").getString();
            ctx.checkNow("boundary delplayer refuses api bot",
                    MockplayerApi.bots().getBot("tbot-api-b").isPresent(), "del=" + boundaryDel);
            ctx.checkNow("boundary core bot in query list",
                    boundaryList.contains(BOT), "list=" + boundaryList);
            MockplayerApi.bots().removeBot("tbot-api-b", "command");
            ctx.bot.actions().drop(0, false);
            ctx.bot.actions().mount(true);
            ctx.bot.actions().dismount();
            ctx.bot.actions().sustainedAttack(null);
            ctx.bot.actions().sustainedUse(null);
            ctx.bot.actions().stopSustained();
        });
        ctx.check("new primitives no-crash", () -> true);
        ctx.check("removeBot own owner",
                () -> MockplayerApi.bots().removeBot(BOT, "command") == RemoveResult.REMOVED);
        ctx.check("removeBot not found",
                () -> MockplayerApi.bots().removeBot(BOT, "command") == RemoveResult.NOT_FOUND);
    }
}
