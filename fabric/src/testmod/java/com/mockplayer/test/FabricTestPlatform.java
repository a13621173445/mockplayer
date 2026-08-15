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
            System.out.println("[mocktest] command failed: " + command + " :: " + e);
            return false;
        }
    }

    @Override
    public boolean hasActiveRoot(String commandName) {
        var dispatcher = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
        return dispatcher != null && dispatcher.getRoot().getChild(commandName) != null;
    }

    @Override
    public boolean hasExecRoot(String commandName) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.getCommands().getRoot().getChild(commandName) != null;
    }

    @Override
    public void sendTestPayloadToBot(String botName) {
        FabricTestPayloads.sendToBot(botName);
    }

    @Override
    public void sendTestPayloadToMainPlayer() {
        FabricTestPayloads.sendToMainPlayer();
    }

    @Override
    public void sendTestPayloadBToBot(String botName, int count) {
        FabricTestPayloads.sendBToBot(botName, count);
    }

    @Override
    public boolean isClientTestHandlerFired() {
        return FabricTestPayloads.isClientFired();
    }

    @Override
    public boolean isServerTestHandlerFired() {
        return FabricTestPayloads.isServerFired();
    }

    @Override
    public void resetTestPayloadFlags() {
        FabricTestPayloads.reset();
    }

    @Override
    public String platformName() {
        return "Fabric";
    }

    @Override
    public void kickBot(String botName) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        var sp = mc.getSingleplayerServer().getPlayerList().getPlayerByName(botName);
        if (sp != null) {
            sp.connection.disconnect(net.minecraft.network.chat.Component.literal("kicked by mocktest"));
        }
    }
}
