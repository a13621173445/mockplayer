package com.mockplayer.memory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * HotSpot 对象布局常量与浅尺寸公式（与 JOL 在 JDK 25 GraalVM 实测一致）：
 * 对象头 12B（Mark Word 8 + 压缩类指针 4）、数组头 16B、引用 4B、对齐 8B。
 * 基本类型：boolean/byte 1、short/char 2、int/float 4、long/double 8。
 * 总尺寸 = align8(对象头 + 实例字段总和)，字段按 JVM 重排后总量不变。
 */
public final class LayoutSizes {

    public static final int OBJECT_HEADER = 12;
    public static final int ARRAY_HEADER = 16;
    public static final int REFERENCE = 4;
    public static final int ALIGNMENT = 8;

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
