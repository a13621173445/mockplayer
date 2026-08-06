package com.mockplayer.test;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Mockplayer 自动化测试入口（neoforge testmod，仅测试环境加载）。
 *
 * 通过系统属性 mockplayer.test（gradle -Psuite=xxx 传入）指定测试套件；
 * 未指定属性时什么都不做（正常进游戏）。执行完自动写结果 JSON 并退出游戏。
 */
@Mod("mockplayer_test")
public class MockplayerTestMod {

    public MockplayerTestMod(IEventBus modBus) {
        // 26.2 @Mod 无 dist 参数，构造器里按物理端判断（FMLEnvironment.getDist() 是静态方法）
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            return;
        }
        String suite = System.getProperty("mockplayer.test");
        if (suite == null || suite.isBlank()) {
            return;
        }
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) ->
                TestRunner.tick(Minecraft.getInstance(), suite));
    }
}
