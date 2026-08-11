package com.mockplayer.session;

/**
 * Mod 侧字节记账估算（HotSpot 64-bit 压缩对象指针布局，JDK 17+ 默认开启；
 * 不含 Map 桶、对象头外引用与容器/数组槽位开销）。
 *
 * 布局依据（OpenJDK HotSpot）：
 * - 普通对象头 12 字节（mark 8 + klass 4），字段按 4 字节引用，整体 8 字节对齐；
 * - 数组头 16 字节（mark 8 + klass 4 + length 4）；
 * - Java 17+ 紧凑字符串（COMPACT_STRINGS）：Latin-1 存 byte[]、否则 char[]（2 字节/字符）；
 * - String 对象体 = header 12 + value 4 + coder 1 + hash 4 = 21 → 对齐 24；
 * - ConcurrentHashMap.Node = header 12 + hash/key/val/next 各 4 = 28 → 对齐 32；
 * - UUID = header 12 + mostSigBits 8 + leastSigBits 8 = 28 → 对齐 32。
 */
final class ExactBytes {

    /** 普通对象头。 */
    static final int OBJECT_HEADER = 12;
    /** 数组对象头。 */
    static final int ARRAY_HEADER = 16;
    /** String 对象体（不含底层数组）。 */
    static final int STRING_OBJECT = 24;
    /** ConcurrentHashMap 条目节点。 */
    static final int MAP_NODE = 32;
    /** UUID 对象。 */
    static final int UUID_OBJECT = 32;

    private ExactBytes() {
    }

    /**
     * String 的精确堆字节：对象体 + 底层 byte[]/char[]（紧凑字符串，Latin-1 判定）。
     *
     * @param s 字符串（null 返回 0）
     * @return 精确字节数
     */
    static long stringBytes(String s) {
        if (s == null) {
            return 0;
        }
        int chars = s.length();
        boolean latin1 = true;
        for (int i = 0; i < chars; i++) {
            if (s.charAt(i) >= 0x80) {
                latin1 = false;
                break;
            }
        }
        int data = latin1 ? chars : chars * 2;
        return STRING_OBJECT + align8(ARRAY_HEADER + data);
    }

    private static long align8(long n) {
        return (n + 7) & ~7L;
    }
}
