package com.mockplayer;

import net.fabricmc.api.ModInitializer;

public class MockplayerMod implements ModInitializer {

    @Override
    public void onInitialize() {
        // Fabric 入口：实际功能（命令 / 按键 / tick）在 MockplayerClient 客户端入口注册，这里保持空实现即可。
    }
}
