package com.mockplayer.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Mockplayer 自动化测试入口（fabric testmod，仅测试环境加载）。
 *
 * 通过系统属性 mockplayer.test（gradle -Psuite=xxx 传入）指定测试套件；
 * 未指定属性时什么都不做（正常进游戏）。执行完自动写结果 JSON 并退出游戏。
 */
public class MockplayerTestMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        String suite = System.getProperty("mockplayer.test");
        if (suite == null || suite.isBlank()) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(mc -> TestRunner.tick(mc, suite));
    }
}
