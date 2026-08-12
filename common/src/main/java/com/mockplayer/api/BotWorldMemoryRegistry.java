package com.mockplayer.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界内存记账注册表（API 层接缝）。
 *
 * 显示/查询只依赖 {@link #get(String)} 读取；可插拔模块通过
 * {@link #register(String, BotWorldMemory)} 挂载、{@link #remove(String)} 卸载，
 * 核心会话代码不引用任何模块实现。
 */
public final class BotWorldMemoryRegistry {

    private static final Map<String, BotWorldMemory> ACCOUNTS = new ConcurrentHashMap<>();

    private BotWorldMemoryRegistry() {
    }

    /** 查询某假人的记账（未挂载返回 empty）。 */
    public static Optional<BotWorldMemory> get(String name) {
        return Optional.ofNullable(ACCOUNTS.get(name));
    }

    /** 挂载记账实现（重复挂载以最新为准）。 */
    public static void register(String name, BotWorldMemory account) {
        if (name != null && account != null) {
            ACCOUNTS.put(name, account);
        }
    }

    /** 卸载记账（假人删除/全部清空时调用）。 */
    public static void remove(String name) {
        ACCOUNTS.remove(name);
    }

    /** 清空全部记账。 */
    public static void clear() {
        ACCOUNTS.clear();
    }
}
