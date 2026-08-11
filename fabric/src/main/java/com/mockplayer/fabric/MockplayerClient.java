package com.mockplayer.fabric;

import com.mojang.brigadier.CommandDispatcher;

import com.mockplayer.config.CommandTreeReloader;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.gui.BotGui;
import com.mockplayer.session.ClientCommandRegistrar;
import com.mockplayer.session.SessionManager;
import com.mockplayer.session.CommandSupport;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.HashSet;
import java.util.Set;

/**
 * Fabric 客户端入口：注册假人命令 + 驱动假人连接 tick。
 */
public class MockplayerClient implements ClientModInitializer {

    /** 当前会话的命令 dispatcher（Fabric 每次连接重建，保存引用供热重载）。 */
    private static CommandDispatcher<FabricClientCommandSource> lastDispatcher;
    /** 本 mod 当前已注册的根命令名（热重载时先移除旧的）。 */
    private static final Set<String> registeredRoots = new HashSet<>();

    @Override
    public void onInitializeClient() {
        // 配置保存/重载后立即重建命令树（GUI 保存即热重载）
        MockplayerConfig.onReload(MockplayerClient::reloadCommands);
        // 原版按键注册：GUI 快捷键（配置 guiEnabled/guiKeyName 由 BotGui 静态块同步）
        KeyMappingHelper.registerKeyMapping(BotGui.KEY_BINDING);
        registerCommands();
        registerTick();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            lastDispatcher = dispatcher;
            ClientCommandRegistrar.registerAll(dispatcher, factory(), registeredRoots);
        });
    }

    /**
     * 配置热重载：移除旧根 → 按新配置注册 → 同步执行层。
     * Fabric 客户端命令分两层：activeDispatcher（注册层）与 ClientPacketListener.commands（执行层），
     * 两层都要移除旧根，再调 ClientCommandInternals.addCommands 把新树拷进执行层。
     */
    private static void reloadCommands() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = lastDispatcher;
        if (dispatcher == null) {
            return;
        }
        Set<String> oldRoots = new HashSet<>(registeredRoots);
        CommandTreeReloader.removeRoots(dispatcher, oldRoots);
        ClientCommandRegistrar.registerAll(dispatcher, factory(), registeredRoots);
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            @SuppressWarnings("rawtypes")
            CommandDispatcher exec = connection.getCommands();
            CommandTreeReloader.removeRoots(exec, oldRoots);
            // impl 类（fabric-command-api-v2 内部）：把 activeDispatcher 拷贝进执行层，
            // 与 Fabric 每次进世界时的同步路径一致（@ApiStatus.Internal，版本升级时留意）
            ClientCommandInternals.addCommands(
                    exec,
                    (FabricClientCommandSource) connection.getSuggestionsProvider());
        }
    }

    /** 双端共用命令树工厂：Fabric 提供 literal/argument/反馈函数。 */
    private static CommandSupport.CommandFactory<FabricClientCommandSource> factory() {
        return new CommandSupport.CommandFactory<FabricClientCommandSource>() {
            @Override
            public com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
                return ClientCommands.literal(name);
            }

            @Override
            public com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, ?> argument(
                    String name, com.mojang.brigadier.arguments.ArgumentType<?> type) {
                return ClientCommands.argument(name, type);
            }

            @Override
            public void sendFeedback(FabricClientCommandSource source, net.minecraft.network.chat.Component message) {
                source.sendFeedback(message);
            }
        };
    }

    private void registerTick() {
        // 每 tick 驱动假人连接，保持在线
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            SessionManager.getInstance().tick();
            // GUI 快捷键：只消费原版 KeyMapping 点击（界面打开时不触发）
            BotGui.tick(minecraft);
        });
    }

}
