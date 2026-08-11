package com.mockplayer.test;

import com.mockplayer.test.framework.TestPlatform;

/**
 * NeoForge 平台适配：loader 查询 + 客户端命令 dispatcher 执行。
 * 唯一允许出现 neoforge 专用 API 的测试类之一（另一处是入口 MockplayerTestMod）。
 */
public final class NeoForgeTestPlatform implements TestPlatform {

    @Override
    public boolean isModLoaded(String modId) {
        return net.neoforged.fml.ModList.get().isLoaded(modId);
    }

    @Override
    public boolean executeClientCommand(String command) {
        try {
            net.neoforged.neoforge.client.ClientCommandHandler.getDispatcher()
                    .execute(command, net.neoforged.neoforge.client.ClientCommandHandler.getSource());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
