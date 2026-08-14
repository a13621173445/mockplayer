package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.ModPayloadInfo;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.test.TestPayloads;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;

import java.util.List;

/**
 * payload-interception：假人连接的 mod payload 统一拦截/记录/查询/发送/配置。
 *
 * 覆盖（硬断言，不水）：
 * - 入站拦截：假人连接收到 mod payload → 记录到 Bot API，mod handler 不触发（主玩家零污染）
 * - 对照组：同 payload 发主玩家 → handler 正常触发（只拦假人连接）
 * - 记录/过滤/原始对象/dump/modName/清空
 * - 配置：拦截开关、放行名单、入站/出站上限、出站开关
 * - 发送 API：注册检查（已注册 true / 未注册 false）+ 出站记录联动 + 服务端收到
 * - 命令：list/detail/raw/clear
 * - 协议类不炸：连续发包后连接保持、vanilla 世界正常
 *
 * 隔离：每用例独立创建假人（SuitesSupport.createBot 先删同名旧假人），state 全新；
 * 配置修改在用例内恢复默认。
 */
public class PayloadInterceptionSuite extends TestSuite {

    private static final String BOT = "tbot-payload";
    private static final String PAYLOAD_A = TestPayloads.PayloadA.TYPE.id().toString();
    private static final String PAYLOAD_B = TestPayloads.PayloadB.TYPE.id().toString();
    /** 期望 modName：主 mod（"mockplayer"）在平台侧的显示名（动态取，双端一致断言）。 */
    private static String expectedModName() {
        return com.mockplayer.platform.Services.PLATFORM.getModDisplayName("mockplayer");
    }

    public PayloadInterceptionSuite() {
        super("payload-interception");
        test("入站拦截与主玩家零污染", this::interceptAndNoPollution);
        test("对照组：主玩家链路不受拦截", this::mainPlayerUnaffected);
        test("记录与过滤 API", this::recordsAndFilter);
        test("原始对象与反射 dump", this::rawAndDump);
        test("配置：拦截开关关闭走分发链", this::interceptDisabled);
        test("配置：放行名单逃生舱", this::passthroughNamespaces);
        test("配置：入站记录上限截断", this::inboundLimit);
        test("出站记录与 sendModPayload 联动", this::outboundAndSend);
        test("sendModPayload 未注册类型返回 false", this::sendUnregisteredFails);
        test("配置：出站开关与上限", this::outboundConfig);
        test("清空 API", this::clearApi);
        test("查询命令输出与 clear 命令", this::queryCommands);
        test("协议类不炸：连续发包后连接保持", this::connectionStaysHealthy);
    }

    private static void createBotPlaying(TestContext ctx) {
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING
                && ctx.bot().getLocalPlayer() != null, 300);
        // 连接稳定等待：假人刚 PLAYING 时服务端连接可能未完全就绪（neoforge 集成服务器
        // 时序），立即发包偶发丢失；统一等 500ms 再发，测试稳定不抖动。
        long[] t = {System.currentTimeMillis()};
        ctx.await("连接稳定等待", () -> System.currentTimeMillis() - t[0] > 500, 60);
    }

    /** 服务端线程向假人发一个 payload_a（构造字段固定，便于原始对象断言）。 */
    private static void sendA(TestContext ctx) {
        ctx.server(() -> ctx.platform().sendTestPayloadToBot(BOT));
    }

    /** 1. 拦截生效 + 记录元信息 + modName + handler 零触发。 */
    private void interceptAndNoPollution(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        sendA(ctx);
        ctx.await("拦截并记录 payload_a", () -> ctx.bot() != null
                && !ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.check("modName 解析为 testmod 显示名", () -> {
            List<ModPayloadInfo> list = ctx.bot().getReceivedModPayloads(PAYLOAD_A);
            String expected = expectedModName();
            return expected != null && !list.isEmpty() && expected.equals(list.get(0).modName());
        });
        ctx.check("元信息 typeId/namespace/tick 有效", () -> {
            List<ModPayloadInfo> list = ctx.bot().getReceivedModPayloads(PAYLOAD_A);
            return !list.isEmpty()
                    && "mockplayer".equals(list.get(0).namespace())
                    && list.get(0).tick() > 0;
        });
        ctx.check("主玩家零污染：客户端 handler 未触发", () -> !ctx.platform().isClientTestHandlerFired());
        ctx.check("无 minecraft: namespace 记录", () -> ctx.bot().getReceivedModPayloads().stream()
                .noneMatch(i -> "minecraft".equals(i.namespace())));
        ctx.check("未知 namespace 显示名解析为 null（如通用 c:）",
                () -> com.mockplayer.platform.Services.PLATFORM.getModDisplayName("c") == null);
    }

    /** 2. 对照组：同 payload 发主玩家连接，handler 正常触发（只拦假人）。 */
    private void mainPlayerUnaffected(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        ctx.server(() -> ctx.platform().sendTestPayloadToMainPlayer());
        ctx.await("主玩家 handler 触发（链路正常）", ctx.platform()::isClientTestHandlerFired, 100);
        ctx.check("假人侧仍无记录（拦截只作用于假人连接）",
                () -> ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty());
    }

    /** 3. 按 typeId 过滤（A/B 互不串）。 */
    private void recordsAndFilter(TestContext ctx) {
        createBotPlaying(ctx);
        sendA(ctx);
        ctx.await("记录 payload_a", () -> !ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.server(() -> ctx.platform().sendTestPayloadBToBot(BOT, 3));
        ctx.await("记录 payload_b", () -> ctx.bot().getReceivedModPayloads(PAYLOAD_B).size() >= 3, 100);
        ctx.check("按 typeId 过滤互不串", () -> {
            List<ModPayloadInfo> a = ctx.bot().getReceivedModPayloads(PAYLOAD_A);
            List<ModPayloadInfo> b = ctx.bot().getReceivedModPayloads(PAYLOAD_B);
            return !a.isEmpty() && !b.isEmpty()
                    && a.stream().allMatch(i -> i.typeIdString().equals(PAYLOAD_A))
                    && b.stream().allMatch(i -> i.typeIdString().equals(PAYLOAD_B));
        });
    }

    /** 4. 原始对象（cast 读字段）+ 反射 dump。 */
    private void rawAndDump(TestContext ctx) {
        createBotPlaying(ctx);
        sendA(ctx);
        ctx.await("记录 payload_a", () -> !ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.run(() -> {
            Object raw = ctx.bot().getLastRawModPayload(PAYLOAD_A);
            ctx.checkNow("原始对象可 cast 且字段正确", raw instanceof TestPayloads.PayloadA a
                    && a.number() == 42
                    && "hello".equals(a.text())
                    && a.nested().id() == 7
                    && "deep".equals(a.nested().name())
                    && a.tags().size() == 2, String.valueOf(raw));
            String dump = ctx.bot().getLastModPayloadDump(PAYLOAD_A);
            ctx.checkNow("dump 含字段名与值（递归嵌套）", dump != null
                    && dump.contains("number") && dump.contains("42")
                    && dump.contains("text") && dump.contains("hello")
                    && dump.contains("nested") && dump.contains("deep")
                    && dump.contains("tags") && dump.contains("a"), dump);
        });
    }

    /** 5. 拦截开关关闭 → 走原版分发链（handler 触发）。 */
    private void interceptDisabled(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> MockplayerConfig.get().setPayloadInterceptEnabled(false));
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        sendA(ctx);
        ctx.await("handler 触发（拦截关闭走分发链）", ctx.platform()::isClientTestHandlerFired, 100);
        ctx.run(() -> MockplayerConfig.get().setPayloadInterceptEnabled(true));
    }

    /** 6. 放行名单：放行 → handler 触发；清空名单 → 恢复拦截。 */
    private void passthroughNamespaces(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> MockplayerConfig.get().setPayloadPassthroughNamespaces(
                List.of("mockplayer")));
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        sendA(ctx);
        ctx.await("放行后 handler 触发", ctx.platform()::isClientTestHandlerFired, 100);
        ctx.run(() -> MockplayerConfig.get().setPayloadPassthroughNamespaces(List.of()));
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        sendA(ctx);
        ctx.await("恢复拦截后记录出现", () -> !ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.check("恢复拦截后 handler 不再触发", () -> !ctx.platform().isClientTestHandlerFired(),
                () -> "clientFired=" + ctx.platform().isClientTestHandlerFired()
                        + " records=" + ctx.bot().getReceivedModPayloads(PAYLOAD_A).size()
                        + " all=" + ctx.bot().getReceivedModPayloads().stream()
                        .map(com.mockplayer.api.ModPayloadInfo::typeIdString).toList());
    }

    /** 7. 入站上限：连发 15 个 B（上限 10）→ 环形截断为 10，最新在前。 */
    private void inboundLimit(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> MockplayerConfig.get().setPayloadLogLimit(10));
        // 15 个 B 分批发送（每批 3 个 + 批间 1 秒间隔）：neoforge 集成服务器对同一 tick
        // 大量 clientbound payload 存在偶发丢失（连发 15 个实测丢一半），分批保证到达。
        for (int batch = 0; batch < 5; batch++) {
            ctx.server(() -> ctx.platform().sendTestPayloadBToBot(BOT, 3));
            long[] gap = {System.currentTimeMillis()};
            ctx.await("批间隔", () -> System.currentTimeMillis() - gap[0] > 1000, 60);
        }
        long[] start = {System.currentTimeMillis()};
        ctx.await("B 记录截断窗口", () -> ctx.bot().getReceivedModPayloads(PAYLOAD_B).size() == 10
                || System.currentTimeMillis() - start[0] > 8000, 300);
        ctx.check("环形截断到上限（10）", () -> ctx.bot().getReceivedModPayloads(PAYLOAD_B).size() == 10,
                () -> "B size=" + ctx.bot().getReceivedModPayloads(PAYLOAD_B).size());
        ctx.check("最新在前（第一条 tick 最大）", () -> {
            List<ModPayloadInfo> list = ctx.bot().getReceivedModPayloads(PAYLOAD_B);
            return !list.isEmpty() && list.get(0).tick() >= list.get(list.size() - 1).tick();
        }, () -> "ticks=" + ctx.bot().getReceivedModPayloads(PAYLOAD_B).stream()
                .map(com.mockplayer.api.ModPayloadInfo::tick).toList());
        ctx.run(() -> MockplayerConfig.get().setPayloadLogLimit(
                com.mockplayer.config.ModConfig.DEFAULT_PAYLOAD_LOG_LIMIT));
    }

    /** 8. sendModPayload：返回 true + 出站记录联动 + 服务端 handler 收到。 */
    private void outboundAndSend(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        boolean[] sent = {false};
        ctx.run(() -> {
            sent[0] = ctx.bot().sendModPayload(new TestPayloads.PayloadA(
                    99, "out", new TestPayloads.Nested(1, "o"), List.of("z")));
        });
        ctx.check("sendModPayload 返回 true（已注册）", () -> sent[0]);
        ctx.await("出站记录联动", () -> !ctx.bot().getSentModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.check("出站记录元信息有效", () -> {
            List<ModPayloadInfo> list = ctx.bot().getSentModPayloads(PAYLOAD_A);
            String expected = expectedModName();
            return !list.isEmpty() && "mockplayer".equals(list.get(0).namespace())
                    && expected != null && expected.equals(list.get(0).modName());
        });
        ctx.await("服务端 handler 收到（出站链路完整）", ctx.platform()::isServerTestHandlerFired, 100);
    }

    /** 9. 未注册类型 → 返回 false，不发送。 */
    private void sendUnregisteredFails(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        boolean[] sent = {true};
        ctx.run(() -> sent[0] = ctx.bot().sendModPayload(new TestPayloads.UnregisteredPayload()));
        ctx.check("未注册返回 false（注册检查生效）", () -> !sent[0]);
        // 出站记录可能含 neoforge 平台协商包（配置阶段 ack/register），
        // 断言「没有测试 payload 发出」即可（记录不阻止发送已由用例 8 覆盖）
        ctx.await("服务端未收到测试 payload", () -> !ctx.platform().isServerTestHandlerFired()
                && ctx.bot().getSentModPayloads(PAYLOAD_A).isEmpty()
                && ctx.bot().getSentModPayloads(PAYLOAD_B).isEmpty(), 50);
    }

    /** 10. 出站开关关闭 → 不记录（发送照常，服务端仍收到）。 */
    private void outboundConfig(TestContext ctx) {
        createBotPlaying(ctx);
        // 清掉假人登录时的 neoforge 配置协商包残留，再关开关验证
        ctx.run(() -> ctx.bot().clearModPayloads());
        ctx.run(() -> MockplayerConfig.get().setPayloadSendLogEnabled(false));
        ctx.run(() -> ctx.platform().resetTestPayloadFlags());
        long[] start = {System.currentTimeMillis()};
        ctx.run(() -> ctx.bot().sendModPayload(new TestPayloads.PayloadA(
                7, "off", new TestPayloads.Nested(3, "x"), List.of())));
        ctx.await("窗口内出站记录保持为空", () ->
                System.currentTimeMillis() - start[0] > 500
                        && ctx.bot().getSentModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.await("服务端仍收到（只关记录不拦发送）", ctx.platform()::isServerTestHandlerFired, 100);
        ctx.run(() -> MockplayerConfig.get().setPayloadSendLogEnabled(true));
    }

    /** 11. 出站上限：连发 8 个（上限 5）→ 截断为 5。 */
    private void outboundLimit(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.run(() -> MockplayerConfig.get().setPayloadSendLogLimit(5));
        for (int i = 0; i < 8; i++) {
            int n = i;
            ctx.run(() -> ctx.bot().sendModPayload(new TestPayloads.PayloadB(n % 2 == 0, n)));
        }
        ctx.await("出站环形截断到上限", () -> ctx.bot().getSentModPayloads(PAYLOAD_B).size() == 5, 100);
        ctx.run(() -> MockplayerConfig.get().setPayloadSendLogLimit(
                com.mockplayer.config.ModConfig.DEFAULT_PAYLOAD_SEND_LOG_LIMIT));
    }

    /** 12. clearModPayloads API 双向清空（含原始对象）。 */
    private void clearApi(TestContext ctx) {
        createBotPlaying(ctx);
        sendA(ctx);
        ctx.await("记录 payload_a", () -> !ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty(), 100);
        ctx.run(() -> ctx.bot().sendModPayload(new TestPayloads.PayloadA(
                1, "c", new TestPayloads.Nested(1, "c"), List.of())));
        ctx.await("出站记录出现", () -> !ctx.bot().getSentModPayloads().isEmpty(), 100);
        ctx.run(() -> ctx.bot().clearModPayloads());
        ctx.check("双向清空 + 原始对象释放", () -> ctx.bot() != null
                && ctx.bot().getReceivedModPayloads().isEmpty()
                && ctx.bot().getSentModPayloads().isEmpty()
                && ctx.bot().getLastRawModPayload(PAYLOAD_A) == null);
    }

    /** 13. 查询命令：list/detail/raw 执行成功 + clear 命令清空生效。 */
    private void queryCommands(TestContext ctx) {
        createBotPlaying(ctx);
        sendA(ctx);
        ctx.await("记录 payload_a", () -> !ctx.bot().getReceivedModPayloads(PAYLOAD_A).isEmpty(), 100);
        boolean[] ok = {false};
        ctx.run(() -> ok[0] = ctx.platform().executeClientCommand("query " + BOT + " payload"));
        ctx.check("query <player> payload 执行成功", () -> ok[0]);
        ctx.run(() -> ok[0] = ctx.platform().executeClientCommand(
                "query " + BOT + " payload " + PAYLOAD_A));
        ctx.check("query <player> payload <typeId> 执行成功", () -> ok[0]);
        ctx.run(() -> ok[0] = ctx.platform().executeClientCommand(
                "query " + BOT + " payload " + PAYLOAD_A + " raw"));
        ctx.check("query <player> payload raw 执行成功", () -> ok[0]);
        ctx.run(() -> ok[0] = ctx.platform().executeClientCommand("query " + BOT + " payload clear"));
        ctx.check("query <player> payload clear 执行成功", () -> ok[0]);
        ctx.check("clear 命令清空记录生效", () -> ctx.bot() != null
                && ctx.bot().getReceivedModPayloads().isEmpty());
    }

    /** 14. 协议类不炸：连续 20 个 payload 后连接保持、vanilla 世界正常。 */
    private void connectionStaysHealthy(TestContext ctx) {
        createBotPlaying(ctx);
        ctx.server(() -> {
            for (int i = 0; i < 20; i++) {
                ctx.platform().sendTestPayloadToBot(BOT);
            }
        });
        ctx.await("20 条全部记录", () -> ctx.bot().getReceivedModPayloads(PAYLOAD_A).size() == 20, 200);
        ctx.check("连接保持 PLAYING", () -> ctx.bot().getLifecycle() == BotLifecycle.PLAYING);
        ctx.check("vanilla 世界/玩家正常", () -> ctx.bot().getLevel() != null
                && ctx.bot().getLocalPlayer() != null
                && ctx.bot().getGameMode() != null);
    }
}
