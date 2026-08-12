package com.mockplayer.memory;

import com.mockplayer.api.BotWorldMemoryRegistry;
import com.mockplayer.session.BotImpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界内存记账模块装配点（唯一耦合接缝）。
 *
 * 核心会话只在创建/删除假人时调用 {@link #ensure(BotImpl)} / {@link #remove(String)}；
 * 其余全部走 {@link BotWorldMemoryRegistry} 与 {@link com.mockplayer.api.event.BotListener}，
 * 实现可整体替换或卸载。
 */
public final class MemoryModule {

    private static final Map<String, WorldMemoryAccountant> ACCOUNTS = new ConcurrentHashMap<>();

    private MemoryModule() {
    }

    /** 确保假人已挂载记账（幂等；挂载到 bot 事件总线并注册到 API 注册表）。 */
    public static WorldMemoryAccountant ensure(BotImpl bot) {
        return ACCOUNTS.computeIfAbsent(bot.getName(), name -> {
            WorldMemoryAccountant account = new WorldMemoryAccountant();
            bot.events().addListener(account);
            BotWorldMemoryRegistry.register(name, account);
            return account;
        });
    }

    /** 移除假人记账（删除/清空路径调用）。 */
    public static void remove(String name) {
        ACCOUNTS.remove(name);
        BotWorldMemoryRegistry.remove(name);
    }
}
