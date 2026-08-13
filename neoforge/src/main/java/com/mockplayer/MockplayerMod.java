package com.mockplayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class MockplayerMod {

    public MockplayerMod(IEventBus eventBus) {
        // NeoForge 入口：实际功能（命令 / 按键 / tick）在 MockplayerNeoForgeClient 客户端入口注册，
        // eventBus 由加载器注入，构造器保持空实现即可。
    }
}
