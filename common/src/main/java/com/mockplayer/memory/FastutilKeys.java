package com.mockplayer.memory;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * fastutil 集合 key 数组的反射读取（内存记账专用）。
 *
 * 输入：fastutil 集合实例（Long2ObjectOpenHashMap / LongOpenHashSet 等）；
 * 输出：其内部 key 数组（按实际类型 cast），反射失败返回 null。
 *
 * 为什么不用 mixin accessor：fastutil 是共享第三方库，c2me 等 mod 的 preLaunch
 * entrypoint 会提前加载 Long2ObjectOpenHashMap 等类，mockplayer 的 accessor mixin
 * 目标类「已加载」→ MixinTargetAlreadyLoadedException → 游戏启动崩
 * （真实整合包环境必现，CI 干净环境复现不了）。反射读私有 key 字段在 fastutil
 * （unnamed module）上可用，且只影响内存记账（低频查询路径，失败由调用方外层
 * try-catch 降级为 size() 估算，不崩游戏）。
 */
public final class FastutilKeys {

    /** 按具体类缓存 key 字段（fastutil 每个具体类字段名都是 key）。 */
    private static final Map<Class<?>, Field> KEY_FIELD_CACHE = new ConcurrentHashMap<>();

    private FastutilKeys() {
    }

    /** 读取 fastutil 集合的 key 数组；无 key 字段/反射失败返回 null。 */
    public static Object keyOf(Object map) {
        if (map == null) {
            return null;
        }
        Class<?> type = map.getClass();
        Field field = KEY_FIELD_CACHE.computeIfAbsent(type, t -> {
            try {
                Field f = t.getDeclaredField("key");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                return null;
            }
        });
        if (field == null) {
            return null;
        }
        try {
            return field.get(map);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** long[] key（Long2ObjectMap / Long2ByteMap / Long2IntMap / LongOpenHashSet 等）。 */
    public static long[] longKey(Object map) {
        Object key = keyOf(map);
        return key instanceof long[] array ? array : null;
    }

    /** int[] key（Int2ObjectLinkedOpenHashMap 等）。 */
    public static int[] intKey(Object map) {
        Object key = keyOf(map);
        return key instanceof int[] array ? array : null;
    }

    /** Object[] key（ObjectOpenCustomHashSet / Reference2ObjectOpenHashMap 等）。 */
    public static Object[] objectKey(Object map) {
        Object key = keyOf(map);
        return key instanceof Object[] array ? array : null;
    }
}
