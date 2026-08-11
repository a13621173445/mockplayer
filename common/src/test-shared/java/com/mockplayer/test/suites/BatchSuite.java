package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.BatchCommands;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;

import java.util.List;

/**
 * batch：批量创建/重名跳过/dry-run/真删/管理边界。
 * 用例间按顺序共享批量假人（旧框架同语义）：创建→重名→删除→边界。
 */
public class BatchSuite extends TestSuite {

    public BatchSuite() {
        super("batch");
        test("校验与批量创建 5 个", this::createBatch);
        test("重名跳过", this::duplicateSkip);
        test("dry-run 与删除", this::dryAndDelete);
        test("管理边界", this::boundary);
    }

    private void createBatch(TestContext ctx) {
        ctx.run(() -> {
            ctx.checkNow("batch max count default 100",
                    MockplayerConfig.get().getBatchMaxCount() == 100);
            String tooMany = FakePlayerCommands.newPlayerBatch("tbotbz", 1000, 0, 4).getString();
            ctx.checkNow("batch too many rejected", !tooMany.contains("commands."), "out=" + tooMany);
            String badPrefix = FakePlayerCommands.newPlayerBatch("x".repeat(20), 2, 0, 4).getString();
            ctx.checkNow("batch invalid prefix rejected",
                    !badPrefix.contains("commands.") && MockplayerApi.bots().getBots().stream()
                            .noneMatch(b -> b.getName().startsWith("xxxxxxxx")), "out=" + badPrefix);
            String started = FakePlayerCommands.newPlayerBatch("tbotb", 5, 0, 2).getString();
            ctx.checkNow("batch create started", !started.contains("commands."), "out=" + started);
        });
        ctx.await("batch created 5 playing", () -> {
            var created = createdBots();
            return created.size() == 5 && created.stream()
                    .allMatch(b -> b.getLifecycle() == BotLifecycle.PLAYING);
        }, 3400);
        ctx.check("batch created 5 playing", () -> createdBots().size() == 5);
        ctx.check("batch names complete", () -> createdBots().stream()
                .map(Bot::getName).toList()
                .containsAll(List.of("tbotb_1", "tbotb_2", "tbotb_3", "tbotb_4", "tbotb_5")));
    }

    private void duplicateSkip(TestContext ctx) {
        ctx.run(() -> {
            String out = FakePlayerCommands.newPlayerBatch("tbotb", 2, 0, 2).getString();
            ctx.checkNow("batch duplicate started", !out.contains("commands."), "out=" + out);
        });
        ctx.await("batch duplicate skip summary", () -> {
            var s = BatchCommands.lastSummary();
            return s != null && s.created() == 0 && s.skipped() == 2 && s.failed() == 0;
        }, 3400);
        ctx.check("batch duplicate skip summary", () -> {
            var s = BatchCommands.lastSummary();
            return s != null && s.created() == 0 && s.skipped() == 2 && s.failed() == 0;
        });
    }

    private void dryAndDelete(TestContext ctx) {
        ctx.run(() -> {
            long before = MockplayerApi.bots().getBots().stream()
                    .filter(b -> b.getName().startsWith("tbotb_")).count();
            String dry = FakePlayerCommands.delPlayerBatch("tbotb", true).getString();
            long after = MockplayerApi.bots().getBots().stream()
                    .filter(b -> b.getName().startsWith("tbotb_")).count();
            ctx.checkNow("batch dry lists 5", dry.contains("5"), "out=" + dry);
            ctx.checkNow("batch dry does not remove", after == 5 && before == 5);
        });
        ctx.run(() -> {
            String del = FakePlayerCommands.delPlayerBatch("tbotb", false).getString();
            ctx.checkNow("batch delete executed", !del.contains("commands."), "out=" + del);
        });
        ctx.await("batch delete removed all", () -> {
            long left = MockplayerApi.bots().getBots().stream()
                    .filter(b -> b.getName().startsWith("tbotb_")).count();
            boolean serverLeft = false;
            for (int i = 1; i <= 5; i++) {
                if (ctx.server().getPlayerList().getPlayerByName("tbotb_" + i) != null) {
                    serverLeft = true;
                }
            }
            return left == 0 && !serverLeft;
        }, 3400);
        ctx.check("batch delete removed all", () -> {
            long left = MockplayerApi.bots().getBots().stream()
                    .filter(b -> b.getName().startsWith("tbotb_")).count();
            boolean serverLeft = false;
            for (int i = 1; i <= 5; i++) {
                if (ctx.server().getPlayerList().getPlayerByName("tbotb_" + i) != null) {
                    serverLeft = true;
                }
            }
            return left == 0 && !serverLeft;
        });
    }

    private void boundary(TestContext ctx) {
        ctx.run(() -> {
            var apiBot = MockplayerApi.bots().createBot(
                    BotProfile.of("tbotbapi", "command")).orElse(null);
            ctx.checkNow("batch boundary api bot created", apiBot != null);
            String dry = FakePlayerCommands.delPlayerBatch("tbotbapi", true).getString();
            ctx.checkNow("batch boundary dry not match", !dry.contains("tbotbapi"), "out=" + dry);
            FakePlayerCommands.delPlayerBatch("tbotbapi", false);
            ctx.checkNow("batch boundary api bot survives",
                    MockplayerApi.bots().getBot("tbotbapi").isPresent());
            MockplayerApi.bots().removeBot("tbotbapi", "command");
        });
    }

    private static List<Bot> createdBots() {
        return MockplayerApi.bots().getBots().stream()
                .filter(b -> b.getName().startsWith("tbotb_")).toList();
    }
}
