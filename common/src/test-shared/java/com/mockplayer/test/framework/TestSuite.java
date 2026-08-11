package com.mockplayer.test.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * 声明式套件：注册用例、套件级 before/after。
 *
 * 每个用例独立 new TestContext，互不共享静态/实例字段；
 * 跨 await 的中间状态用用例局部变量/数组捕获，杜绝旧框架的全局静态残留。
 */
public abstract class TestSuite {

    /** 一个用例 = 名称 + 顺序步骤 DSL（run/await/check 在 {@link TestContext} 上）。 */
    public record TestCase(String name, java.util.function.Consumer<TestContext> body) {
    }

    private final String name;
    private final List<TestCase> cases = new ArrayList<>();

    protected TestSuite(String name) {
        this.name = name;
    }

    protected final void test(String name, java.util.function.Consumer<TestContext> body) {
        cases.add(new TestCase(name, body));
    }

    /** 套件开始前（已 sanitize）：准备共享环境（默认无操作）。 */
    public void before() {
    }

    /** 套件结束后（用例全部跑完）：清理（默认无操作）。 */
    public void after() {
    }

    public String name() {
        return name;
    }

    public List<TestCase> cases() {
        return List.copyOf(cases);
    }
}
