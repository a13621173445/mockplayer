package com.mockplayer.test;

import com.mockplayer.test.framework.TestPlatform;
import net.minecraft.client.Minecraft;

/**
 * Fabric 平台适配：loader 查询 + 客户端命令 dispatcher 执行。
 * 唯一允许出现 fabric 专用 API 的测试类之一（另一处是入口 MockplayerTestMod）。
 */
public final class FabricTestPlatform implements TestPlatform {

    @Override
    public boolean isModLoaded(String modId) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean executeClientCommand(String command) {
        try {
            var source = (net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)
                    Minecraft.getInstance().getConnection().getSuggestionsProvider();
            net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher()
                    .execute(command, source);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
