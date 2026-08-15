package com.mockplayer.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mod 显示名缓存：namespace → 平台 mod 显示名。
 *
 * 热路径优化：入站拦截/出站记录每包调平台加载器查询（FabricLoader/ModList），
 * mod 加载后映射不变，缓存安全（结果与直接查平台完全一致，逻辑等价）。
 * null（非 mod namespace，如 minecraft/c）不缓存，每次走平台查询。
 */
public final class ModNameCache {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private ModNameCache() {
    }

    /** 查询 namespace 对应 mod 显示名（未命中查平台并缓存；非 mod namespace 返回 null）。 */
    public static String get(String namespace) {
        String cached = CACHE.get(namespace);
        if (cached != null) {
            return cached;
        }
        String value = Services.PLATFORM.getModDisplayName(namespace);
        if (value != null) {
            CACHE.putIfAbsent(namespace, value);
        }
        return value;
    }
}
