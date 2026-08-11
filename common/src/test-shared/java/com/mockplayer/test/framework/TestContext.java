package com.mockplayer.test.framework;

import com.mockplayer.api.Bot;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 用例上下文：顺序步骤 DSL + 断言记录。
 *
 * 用例方法体内按顺序调用 {@link #run}/{@link #await}/{@link #check}，
 * 它们只入队；{@link SuiteRunner} 每客户端 tick 调 {@link #tick()} 推进：
 * run/check 立即执行，await 轮询条件直至满足或超时。
 */
public final class TestContext {

    /** 断言记录（写 JSON 用）。 */
    public record Record(String name, boolean passed, String detail) {
    }

    private enum Kind { RUN, AWAIT, CHECK }

    private record Step(Kind kind, String name, BooleanSupplier cond, int timeoutTicks,
                        Runnable action, Supplier<String> detail) {
    }

    private final Queue<Step> queue = new ArrayDeque<>();
    private final List<Record> records = new ArrayList<>();
    private Step current;
    private int currentTicks;
    private boolean done;
    private boolean executing;
    private boolean failed;

    /** 当前用例的假人（用例自己创建后赋值；每用例独立，禁止跨用例共享）。 */
    public Bot bot;
    /** 当前用例的假人名（sanitize 用）。 */
    public String botName;
    /** 平台适配（由 SuiteRunner 注入）。 */
    public TestPlatform platform;

    /** 注册一个立即执行步骤（客户端主线程）。 */
    public void run(Runnable action) {
        guardNotExecuting();
        queue.add(new Step(Kind.RUN, null, null, 0, action, null));
    }

    /** 注册一个服务端执行步骤（内嵌单机服务器线程）。 */
    public void server(Runnable action) {
        guardNotExecuting();
        Minecraft mc = Minecraft.getInstance();
        queue.add(new Step(Kind.RUN, null, null, 0,
                () -> mc.getSingleplayerServer().execute(action), null));
    }

    /** 注册一个条件等待：每 tick 检查一次，满足即继续，超时记录失败并继续。 */
    public void await(String desc, BooleanSupplier cond, int timeoutTicks) {
        guardNotExecuting();
        queue.add(new Step(Kind.AWAIT, desc, cond, timeoutTicks, null, null));
    }

    /** 注册一个断言（执行时求值，不中断用例）。 */
    public void check(String name, BooleanSupplier cond) {
        check(name, cond, () -> "");
    }

    /** 注册一个断言（带失败详情，执行时求值）。 */
    public void check(String name, BooleanSupplier cond, Supplier<String> detail) {
        guardNotExecuting();
        queue.add(new Step(Kind.CHECK, name, cond, 0, null, detail));
    }

    /** 步骤执行中禁止嵌套入队：嵌套步骤会排在当前 await 之后永不执行（历史坑）。 */
    private void guardNotExecuting() {
        if (executing) {
            throw new IllegalStateException(
                    "TestContext step executed while another step is running: "
                            + "register steps in the test body, not inside run/server callbacks "
                            + "(use checkNow for immediate assertions)");
        }
    }

    /** 立即记录一个断言（供 run 步骤内部使用；普通用例主体请用延迟 {@link #check}）。 */
    public void checkNow(String name, boolean ok, String detail) {
        records.add(new Record(name, ok, ok ? "" : detail));
    }

    /** 立即记录一个断言（无详情）。 */
    public void checkNow(String name, boolean ok) {
        checkNow(name, ok, "");
    }

    /** 是否全部步骤完成。 */
    public boolean isDone() {
        return done;
    }

    /** 是否已有断言失败/等待超时（SuiteRunner 据此立即停止游戏）。 */
    public boolean failed() {
        return failed;
    }

    /** 断言记录（结果写入用）。 */
    public List<Record> records() {
        return List.copyOf(records);
    }

    /** 每客户端 tick 推进一次。 */
    public void tick() {
        if (done) {
            return;
        }
        // 连续执行到第一个 await（run/check 全部即时消费）
        while (current == null && !queue.isEmpty()) {
            current = queue.poll();
            if (current.kind() == Kind.RUN) {
                executing = true;
                try {
                    current.action().run();
                } finally {
                    executing = false;
                }
                current = null;
            } else if (current.kind() == Kind.CHECK) {
                boolean ok = current.cond().getAsBoolean();
                String detail = ok ? "" : (current.detail() != null ? current.detail().get() : "");
                records.add(new Record(current.name(), ok, detail));
                if (!ok) {
                    failed = true;
                }
                current = null;
            }
        }
        if (current == null) {
            done = true;
            return;
        }
        // 当前是 await
        if (current.cond().getAsBoolean()) {
            current = null;
            currentTicks = 0;
        } else if (++currentTicks > current.timeoutTicks()) {
            records.add(new Record("await: " + current.name(), false,
                    "timeout after " + current.timeoutTicks() + " ticks"));
            failed = true;
            current = null;
            currentTicks = 0;
        }
    }

    /** 便捷：获取单机服务器（不存在时抛异常）。 */
    public net.minecraft.server.MinecraftServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    /** 便捷：从服务端取结果到数组/局部（配合 await 轮询）。 */
    public <T> void serverRead(Supplier<T> reader, java.util.function.Consumer<T> sink) {
        server(() -> sink.accept(reader.get()));
    }
}
