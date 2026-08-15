package com.mockplayer.neoforge;

import com.mojang.brigadier.CommandDispatcher;

import com.mockplayer.Constants;
import com.mockplayer.config.CommandTreeReloader;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.ModConfigScreenFactory;
import com.mockplayer.gui.BotGui;
import com.mockplayer.session.ClientCommandRegistrar;
import com.mockplayer.session.SessionManager;
import com.mockplayer.session.CommandSupport;

import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.CommandBuildContext;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashSet;
import java.util.Set;

/**
 * NeoForge 客户端专属入口：注册假人命令 + 驱动假人连接 tick。
 * 仅物理客户端加载（dist = Dist.CLIENT）。
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class MockplayerNeoForgeClient {

    /** 本 mod 当前已注册的根命令名（热重载时先移除旧的）。 */
    private static final Set<String> registeredRoots = new HashSet<>();

    public MockplayerNeoForgeClient(IEventBus modBus, ModContainer container) {
        // RegisterClientCommandsEvent / ClientTickEvent.Post / ClientPlayerNetworkEvent.LoggingOut 都是
        // GAME 事件（发在 NeoForge.EVENT_BUS），不是 IModBusEvent——注册到 mod bus 会抛
        // "This bus only accepts subclasses of IModBusEvent"。必须注册到 NeoForge.EVENT_BUS。
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::registerCommands);
        NeoForge.EVENT_BUS.addListener(MockplayerNeoForgeClient::onClientTick);
        // 配置保存/重载后立即重建命令树（GUI 保存即热重载）
        MockplayerConfig.onReload(MockplayerNeoForgeClient::reloadCommands);
        // 配置热重载 → 重应用全部假人 baritone settings（含渲染三态全局同步）
        MockplayerConfig.onReload(com.mockplayer.session.NavigateSupport::applyAll);
        // 原版按键注册（mod 总线 IModBusEvent）：GUI 快捷键走原版 KeyMapping 链路
        modBus.addListener(MockplayerNeoForgeClient::registerKeyMappings);
        // YACL 可选：缺 YACL 时模组列表不出现「配置」按钮，配置仍可手改 JSON（零崩溃）
        if (ModList.get().isLoaded("yet_another_config_lib_v3")) {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (java.util.function.Supplier<IConfigScreenFactory>)
                            () -> (modContainer, parent) -> {
                                // 反射桥：类加载验证不直接引用 YACL 类，缺 YACL 也不会 NoClassDefFoundError
                                net.minecraft.client.gui.screens.Screen screen =
                                        ModConfigScreenFactory.create(parent);
                                return screen != null ? screen : new MissingYaclScreen(parent);
                            });
        }
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        ClientCommandRegistrar.registerAll(event.getDispatcher(), factory(), registeredRoots);
    }

    /**
     * 配置热重载：从执行层移除旧根 → 重建客户端命令并合并服务端命令写回。
     * NeoForge 的事件 dispatcher 每次重建是临时对象，正确入口是
     * ClientCommandHandler.mergeServerCommands（@ApiStatus.Internal，版本升级时留意）：
     * 它重新触发 RegisterClientCommandsEvent（自动用新配置注册）并返回合并后的执行 dispatcher。
     */
    private static void reloadCommands() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        Set<String> oldRoots = new HashSet<>(registeredRoots);
        @SuppressWarnings("rawtypes")
        CommandDispatcher exec = connection.getCommands();
        CommandTreeReloader.removeRoots(exec, oldRoots);
        CommandDispatcher<ClientSuggestionProvider> merged = ClientCommandHandler.mergeServerCommands(
                exec,
                CommandBuildContext.simple(connection.registryAccess(), connection.enabledFeatures()));
        connection.commands = merged;
    }

    /** 双端共用命令树工厂：NeoForge 提供 literal/argument/反馈函数。 */
    private static CommandSupport.CommandFactory<net.minecraft.commands.CommandSourceStack> factory() {
        return new CommandSupport.CommandFactory<net.minecraft.commands.CommandSourceStack>() {
            @Override
            public com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> literal(String name) {
                return Commands.literal(name);
            }

            @Override
            public com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, ?> argument(
                    String name, com.mojang.brigadier.arguments.ArgumentType<?> type) {
                return Commands.argument(name, type);
            }

            @Override
            public void sendFeedback(net.minecraft.commands.CommandSourceStack source, net.minecraft.network.chat.Component message) {
                source.sendSuccess(() -> message, false);
            }
        };
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        // 每 tick 驱动假人连接，保持在线
        SessionManager.getInstance().tick();
        // GUI 快捷键：只消费原版 KeyMapping 点击（界面打开时不触发）
        BotGui.tick(Minecraft.getInstance());
    }

    /** 原版按键注册：键位/禁用由 BotGui.applyKeyFromConfig 在配置热重载时同步。 */
    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(BotGui.KEY_BINDING);
    }

}
