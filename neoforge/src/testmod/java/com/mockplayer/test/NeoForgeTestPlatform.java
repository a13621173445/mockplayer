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

    @Override
    public boolean hasActiveRoot(String commandName) {
        var dispatcher = net.neoforged.neoforge.client.ClientCommandHandler.getDispatcher();
        return dispatcher != null && dispatcher.getRoot().getChild(commandName) != null;
    }

    @Override
    public boolean hasExecRoot(String commandName) {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        return connection != null && connection.getCommands().getRoot().getChild(commandName) != null;
    }

    @Override
    public void sendTestPayloadToBot(String botName) {
        NeoForgeTestPayloads.sendToBot(botName);
    }

    @Override
    public void sendTestPayloadToMainPlayer() {
        NeoForgeTestPayloads.sendToMainPlayer();
    }

    @Override
    public void sendTestPayloadBToBot(String botName, int count) {
        NeoForgeTestPayloads.sendBToBot(botName, count);
    }

    @Override
    public boolean isClientTestHandlerFired() {
        return NeoForgeTestPayloads.isClientFired();
    }

    @Override
    public boolean isServerTestHandlerFired() {
        return NeoForgeTestPayloads.isServerFired();
    }

    @Override
    public void resetTestPayloadFlags() {
        NeoForgeTestPayloads.reset();
    }

    @Override
    public String platformName() {
        return "NeoForge";
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
