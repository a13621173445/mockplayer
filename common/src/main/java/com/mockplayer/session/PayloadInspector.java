package com.mockplayer.session;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * payload 对象的反射观测工具（纯代码规则，不做语义解释）。
 *
 * 输入：FakePlayerState 记录的原始 payload 对象（网络层已解码）
 * 输出：
 * - {@link #toTree(Object)} 结构化树（Map/List/标量）——AI/JSON 友好
 * - {@link #toJson(Object)} JSON 字符串（原版自带 Gson，零新依赖）
 * - {@link #describe(Object)} 人类可读缩进文本（/query payload raw 输出）
 * - {@link #estimateSize(Object)} 轻量字节估算（ModPayloadInfo.sizeBytes）
 *
 * 防爆炸三件套：seen 防循环引用、深度上限、非 JDK/非 record 复杂对象只输出类名不递归
 * （payload 若嵌 Entity/Level 不会把整个对象图拖进来）。
 *
 * 已知限制：混淆 mod（如 YSM）字段名是乱码（值仍可读）；字段语义需查 mod 源码，
 * 本工具只负责把数据掏出来。
 */
public final class PayloadInspector {

    /** 递归深度上限（防止深层嵌套对象图爆炸）。 */
    private static final int MAX_DEPTH = 8;
    /** 字节估算的递归深度上限（轻量，足够覆盖常见 payload）。 */
    private static final int MAX_SIZE_DEPTH = 3;

    private PayloadInspector() {
    }

    /** 反射出结构化树：record 组件优先，普通类枚举字段，JDK 集合/数组/标量递归。 */
    public static Object toTree(Object value) {
        return toTree(value, 0, new IdentityHashMap<>());
    }

    private static Object toTree(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            return "<depth-limit>";
        }
        // 标量直接返回（String/Number/Boolean/Character/Enum/Identifier 等）
        if (isScalar(value)) {
            return value.toString();
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            return "<cycle>";
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.put(String.valueOf(e.getKey()), toTree(e.getValue(), depth + 1, seen));
                }
                return out;
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> out = new java.util.ArrayList<>();
                for (Object item : iterable) {
                    out.add(toTree(item, depth + 1, seen));
                }
                return out;
            }
            if (value.getClass().isArray()) {
                List<Object> out = new java.util.ArrayList<>();
                int len = Array.getLength(value);
                for (int i = 0; i < len; i++) {
                    out.add(toTree(Array.get(value, i), depth + 1, seen));
                }
                return out;
            }
            // record：getRecordComponents + 访问器（比字段反射更稳，record 组件带名字）
            if (value.getClass().isRecord()) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (RecordComponent comp : value.getClass().getRecordComponents()) {
                    try {
                        out.put(comp.getName(), toTree(comp.getAccessor().invoke(value), depth + 1, seen));
                    } catch (ReflectiveOperationException e) {
                        out.put(comp.getName(), "<unreadable>");
                    }
                }
                return out;
            }
            // 其他复杂对象：只输出类名不递归（防对象图爆炸）
            return "<" + value.getClass().getSimpleName() + ">";
        } finally {
            seen.remove(value);
        }
    }

    /** JSON 序列化（原版自带 Gson）。 */
    public static String toJson(Object payload) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(toTree(payload));
    }

    /** 人类可读缩进文本（命令输出）。 */
    public static String describe(Object payload) {
        return describe(toTree(payload), 0);
    }

    private static String describe(Object node, int indent) {
        if (node == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        String pad = "  ".repeat(indent);
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(pad).append(e.getKey()).append(": ");
                Object v = e.getValue();
                if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    sb.append('\n').append(describe(v, indent + 1));
                } else {
                    sb.append(v).append('\n');
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    sb.append(pad).append("- ").append(describe(item, indent + 1));
                } else {
                    sb.append(pad).append("- ").append(item).append('\n');
                }
            }
        } else {
            sb.append(pad).append(node).append('\n');
        }
        return sb.toString();
    }

    /** 轻量字节估算（保守近似，用于 sizeBytes 元信息；深度受限不精确）。 */
    public static int estimateSize(Object value) {
        return estimateSize(value, 0, new IdentityHashMap<>());
    }

    private static int estimateSize(Object value, int depth, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return 0;
        }
        if (depth > MAX_SIZE_DEPTH) {
            return 16;
        }
        if (value instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8).length + 16;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character) {
            return 4;
        }
        if (value instanceof Integer || value instanceof Float) {
            return 4;
        }
        if (value instanceof Long || value instanceof Double) {
            return 8;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            return 0;
        }
        try {
            if (value instanceof Map<?, ?> map) {
                int size = 32;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    size += estimateSize(e.getKey(), depth + 1, seen);
                    size += estimateSize(e.getValue(), depth + 1, seen);
                }
                return size;
            }
            if (value instanceof Iterable<?> iterable) {
                int size = 16;
                for (Object item : iterable) {
                    size += estimateSize(item, depth + 1, seen);
                }
                return size;
            }
            if (value.getClass().isArray()) {
                int size = 16;
                int len = Array.getLength(value);
                for (int i = 0; i < len; i++) {
                    size += estimateSize(Array.get(value, i), depth + 1, seen);
                }
                return size;
            }
            if (value.getClass().isRecord()) {
                int size = 16;
                for (RecordComponent comp : value.getClass().getRecordComponents()) {
                    try {
                        size += estimateSize(comp.getAccessor().invoke(value), depth + 1, seen);
                    } catch (ReflectiveOperationException e) {
                        size += 4;
                    }
                }
                return size;
            }
            // 其他对象：固定近似
            return 16;
        } finally {
            seen.remove(value);
        }
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum
                || value instanceof net.minecraft.resources.Identifier
                || value instanceof net.minecraft.core.Holder<?>;
    }
}
