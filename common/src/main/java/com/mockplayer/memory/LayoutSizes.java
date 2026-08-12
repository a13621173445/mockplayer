package com.mockplayer.memory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 对象布局常量与浅尺寸公式（运行时探测，不写死任何 JVM 假设）。
 *
 * 探测方法（启动时一次，全部来自 Unsafe 公开 API）：
 * - 对象头：单字段类的首个字段偏移 = 对象头大小（JVM 已按对齐排好）；
 * - 数组头：Object[] 首元素基址偏移；
 * - 引用大小：Object[] 元素步长（4 = 压缩 oop，8 = 无压缩）；
 * - 对齐：64 位 JVM 8 字节，32 位 4 字节。
 * 因此换 JVM/换 JDK（无压缩指针、Lilliput 压缩对象头、32 位等）都自动正确，
 * 不再有硬编码 HotSpot 常量导致的跨平台错账。
 */
public final class LayoutSizes {

    public static final int OBJECT_HEADER;
    public static final int ARRAY_HEADER;
    public static final int REFERENCE;
    public static final int ALIGNMENT;

    static {
        sun.misc.Unsafe unsafe = unsafe();
        try {
            OBJECT_HEADER = (int) unsafe.objectFieldOffset(HeaderProbe.class.getDeclaredField("v"));
            ARRAY_HEADER = unsafe.arrayBaseOffset(Object[].class);
            REFERENCE = unsafe.arrayIndexScale(Object[].class);
            ALIGNMENT = "64".equals(System.getProperty("sun.arch.data.model")) ? 8 : 4;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("布局探测字段缺失", e);
        }
    }

    /** 探测对象头用：单布尔字段，其字段偏移即对象头大小。 */
    private static final class HeaderProbe {
        boolean v;
    }

    /** 获取 Unsafe（theUnsafe 反射是社区标准做法，jdk.unsupported 对无名模块导出）。 */
    private static sun.misc.Unsafe unsafe() {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法获取 Unsafe（布局探测需要运行时常量）", e);
        }
    }

    private LayoutSizes() {
    }

    /** 按 HotSpot 8 字节对齐规则补齐。 */
    public static long align(long size) {
        long rem = size % ALIGNMENT;
        return rem == 0 ? size : size + (ALIGNMENT - rem);
    }

    /** 数组对象自身尺寸（不含元素引用指向的对象）。 */
    public static long arraySize(int length, int elementSize) {
        return align((long) ARRAY_HEADER + (long) length * elementSize);
    }

    /** 类实例浅尺寸：对象头 + 全继承链实例字段 + 对齐（不含引用指向的对象）。 */
    public static long shallowSize(Class<?> type) {
        long fields = 0;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                fields += t.isPrimitive() ? primitiveSize(t) : REFERENCE;
            }
        }
        return align(OBJECT_HEADER + fields);
    }

    private static long primitiveSize(Class<?> t) {
        if (t == long.class || t == double.class) {
            return 8;
        }
        if (t == int.class || t == float.class) {
            return 4;
        }
        if (t == short.class || t == char.class) {
            return 2;
        }
        return 1; // boolean / byte
    }
}
