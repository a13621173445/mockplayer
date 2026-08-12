package com.mockplayer.memory;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * 结构堆公式（针对 Minecraft 26.2 / neo_form 26.2-1 验证）。
 *
 * 换 MC 版本必须重新校准：本类依赖 26.2 的 NBT/集合内部结构（HashMap/ArrayList
 * 容量增长规则、各 Tag 类布局），升级后公式可能失效。
 *
 * 口径：公式是「当前计数器 → 堆字节」的函数，O(1) 或 O(载荷)，
 * 不递归遍历对象图、不读共享单例（Item/Block/Level 一律只算引用）。
 */
public final class StructureHeap {

    private StructureHeap() {
    }

    // ===== NBT 标签（阶段 A） =====

    /** 任意 NBT 标签的堆字节：按实际类型分发，递归子项（Compound/List 是树，O(载荷)）。 */
    public static long nbtTag(Tag tag) {
        if (tag == null) {
            return 0;
        }
        if (tag instanceof CompoundTag compound) {
            return compound(compound);
        }
        if (tag instanceof ListTag list) {
            return list(list);
        }
        if (tag instanceof StringTag string) {
            return LayoutSizes.shallowSize(StringTag.class)
                    + stringHeap(string.value());
        }
        if (tag instanceof ByteArrayTag bytes) {
            return LayoutSizes.shallowSize(ByteArrayTag.class)
                    + LayoutSizes.arraySize(bytes.getAsByteArray().length, 1);
        }
        if (tag instanceof IntArrayTag ints) {
            return LayoutSizes.shallowSize(IntArrayTag.class)
                    + LayoutSizes.arraySize(ints.getAsIntArray().length, Integer.BYTES);
        }
        if (tag instanceof LongArrayTag longs) {
            return LayoutSizes.shallowSize(LongArrayTag.class)
                    + LayoutSizes.arraySize(longs.getAsLongArray().length, Long.BYTES);
        }
        // 数值/End 等无引用字段的标签：只算浅尺寸
        return LayoutSizes.shallowSize(tag.getClass());
    }

    /**
     * CompoundTag 堆 = 浅尺寸 + 内部 HashMap（JDK 容量规则）+ 每个键的 String 堆 + 子 Tag。
     * 26.2 的 CompoundTag.tags 是 new HashMap<>() 按 0.75 负载增长（CompoundTag.java:174）。
     */
    private static long compound(CompoundTag tag) {
        long total = LayoutSizes.shallowSize(CompoundTag.class)
                + hashMapHeap(tag.size());
        for (var entry : tag.entrySet()) {
            total += stringHeap(entry.getKey());
            total += nbtTag(entry.getValue());
        }
        return total;
    }

    /**
     * ListTag 堆 = 浅尺寸 + 内部 ArrayList 底层数组（new ArrayList<>() 增长序列）+ 子 Tag。
     * 26.2 的 ListTag.list 是 ArrayList（ListTag.java:149），默认容量 10、1.5 倍增长。
     */
    private static long list(ListTag tag) {
        long total = LayoutSizes.shallowSize(ListTag.class)
                + arrayListHeap(tag.size());
        for (Tag child : tag) {
            total += nbtTag(child);
        }
        return total;
    }

    /** String 堆 = String 对象浅尺寸 + char[]（UTF-16，元素 2 字节）。 */
    public static long stringHeap(String value) {
        if (value == null) {
            return 0;
        }
        return LayoutSizes.shallowSize(String.class)
                + LayoutSizes.arraySize(value.length(), Character.BYTES);
    }

    // ===== JDK 集合通用助手（阶段 B 复用） =====

    /**
     * java.util.HashMap 堆 = 表数组（按 0.75 负载增长的 2 次幂容量）+ size 个 Node。
     * Node = 对象头 + int hash + 3 个引用（key/value/next），对齐后每节点固定。
     * 含 HashMap 对象自身浅尺寸（空表也占对象）。
     */
    public static long hashMapHeap(int size) {
        long total = LayoutSizes.shallowSize(java.util.HashMap.class);
        if (size <= 0) {
            return total;
        }
        int capacity = 16;
        while (size > capacity * 3L / 4L) {
            capacity <<= 1;
        }
        long nodeSize = LayoutSizes.align(
                LayoutSizes.OBJECT_HEADER + Integer.BYTES + 3L * LayoutSizes.REFERENCE);
        return total
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + size * nodeSize;
    }

    /**
     * ArrayList 底层数组：默认容量 10、满时 1.5 倍增长（JDK 规则）。
     * 只算数组本身（对象浅尺寸由调用方另计）；size 为当前元素数。
     */
    public static long arrayListHeap(int size) {
        return LayoutSizes.arraySize(arrayListCapacity(size), LayoutSizes.REFERENCE);
    }

    private static int arrayListCapacity(int size) {
        if (size <= 0) {
            return 0;
        }
        int capacity = 0;
        for (int i = 1; i <= size; i++) {
            if (i > capacity) {
                capacity = Math.max(10, capacity + (capacity >> 1));
            }
        }
        return capacity;
    }

    // ===== fastutil 集合通用助手（阶段 B/C） =====

    /**
     * fastutil 开放寻址容器容量：负载因子 0.75，容量为 2 的幂（n = 最小满足 size ≤ n*0.75）。
     * 各实现（Long2ObjectOpenHashMap/Object2ObjectOpenHashMap/Int2ObjectOpenHashMap/
     * ObjectOpenCustomHashSet 等）都用这同一容量规则，只是数组类型不同。
     */
    private static int fastutilCapacity(int size) {
        if (size <= 0) {
            return 0;
        }
        int capacity = 16;
        while (size > capacity * 3L / 4L) {
            capacity <<= 1;
        }
        return capacity;
    }

    /** Long2ObjectOpenHashMap：long[] key + Object[] value + boolean[] used（负载 0.75）。 */
    public static long long2ObjectMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap.class)
                + LayoutSizes.arraySize(capacity, Long.BYTES)
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** Object2ObjectOpenHashMap：Object[] key + Object[] value + boolean[] used。 */
    public static long object2ObjectMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.class)
                + 2L * LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** Int2ObjectOpenHashMap：int[] key + Object[] value + boolean[] used。 */
    public static long int2ObjectMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap.class)
                + LayoutSizes.arraySize(capacity, Integer.BYTES)
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** ObjectOpenCustomHashSet：Object[] key + boolean[] used。 */
    public static long objectOpenHashSetHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet.class)
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** LongOpenHashSet：long[] key + boolean[] used。 */
    public static long longOpenHashSetHeap(int size) {
        return longOpenHashSetHeap(size, 0.75F);
    }

    /** LongOpenHashSet（指定负载因子）：long[] key + boolean[] used。 */
    public static long longOpenHashSetHeap(int size, float loadFactor) {
        int capacity = size <= 0 ? 0 : 16;
        while (capacity > 0 && size > (int) (capacity * loadFactor)) {
            capacity <<= 1;
        }
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.LongOpenHashSet.class)
                + LayoutSizes.arraySize(capacity, Long.BYTES)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** LongArrayFIFOQueue：对象浅尺寸 + 底层 long[]（容量 = 数组长度）。 */
    public static long longArrayFIFOQueueHeap(long[] array) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue.class)
                + LayoutSizes.arraySize(array.length, Long.BYTES);
    }

    /** LongOpenHashSet 按实际预分配容量（key 数组长度）计堆。 */
    public static long longOpenHashSetHeapByCapacity(int capacity) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.LongOpenHashSet.class)
                + LayoutSizes.arraySize(capacity, Long.BYTES)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** ShortArrayList：short[] 底层数组（元素 2 字节），无负载因子。 */
    public static long shortArrayListHeap(int size) {
        int capacity = Math.max(10, size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.shorts.ShortArrayList.class)
                + LayoutSizes.arraySize(capacity, Short.BYTES);
    }

    /** ObjectArrayList：Object[] 底层数组。 */
    public static long objectArrayListHeap(int size) {
        int capacity = Math.max(10, size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.ObjectArrayList.class)
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE);
    }

    /** Long2ByteOpenHashMap：long[] key + byte[] value + boolean[] used。 */
    public static long long2ByteMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap.class)
                + LayoutSizes.arraySize(capacity, Long.BYTES)
                + LayoutSizes.arraySize(capacity, 1)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** Long2IntOpenHashMap：long[] key + int[] value + boolean[] used。 */
    public static long long2IntMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap.class)
                + LayoutSizes.arraySize(capacity, Long.BYTES)
                + LayoutSizes.arraySize(capacity, Integer.BYTES)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** LongAVLTreeSet：树形结构，按节点数（Entry 固定布局）。 */
    public static long longAVLTreeSetHeap(int size) {
        long entrySize = LayoutSizes.align(LayoutSizes.OBJECT_HEADER
                + Long.BYTES + 2L * LayoutSizes.REFERENCE + 1);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.LongAVLTreeSet.class)
                + size * entrySize;
    }

    /** AtomicReferenceArray：对象数组（引用元素）。 */
    public static long atomicReferenceArrayHeap(int length) {
        return LayoutSizes.shallowSize(java.util.concurrent.atomic.AtomicReferenceArray.class)
                + LayoutSizes.arraySize(length, LayoutSizes.REFERENCE);
    }

    /** Reference2ObjectOpenHashMap：Object[] key + Object[] value + boolean[] used。 */
    public static long reference2ObjectMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap.class)
                + 2L * LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** Int2ObjectLinkedOpenHashMap：int[] key + Object[] value + boolean[] used + 链接数组。 */
    public static long int2ObjectLinkedMapHeap(int size) {
        int capacity = fastutilCapacity(size);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap.class)
                + LayoutSizes.arraySize(capacity, Integer.BYTES)
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE)
                + 2L * LayoutSizes.arraySize(capacity, Integer.BYTES)
                + LayoutSizes.arraySize(capacity, 1);
    }

    /** java.util.PriorityQueue 底层对象数组（JDK 增长：初值 11，容量小于 64 时加 2，否则 1.5 倍）。 */
    public static long priorityQueueHeap(int size) {
        if (size <= 0) {
            return LayoutSizes.shallowSize(java.util.PriorityQueue.class);
        }
        int capacity = 11;
        while (capacity <= size) {
            capacity += (capacity < 64) ? (capacity + 2) : (capacity >> 1);
        }
        return LayoutSizes.shallowSize(java.util.PriorityQueue.class)
                + LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE);
    }

    /** fastutil Object2ObjectArrayMap：key/value 数组（容量 = size + 1）。 */
    public static long object2ObjectArrayMapHeap(int size) {
        int capacity = Math.max(4, size + 1);
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap.class)
                + 2L * LayoutSizes.arraySize(capacity, LayoutSizes.REFERENCE);
    }

    /** 按实际 key 数组长度计 fastutil 开放寻址容器（long[] key + Object[] value + boolean[] used）。 */
    public static long longKeyObjectValueMapHeapByCapacity(long[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap.class)
                + LayoutSizes.arraySize(key.length, Long.BYTES)
                + LayoutSizes.arraySize(key.length, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(key.length, 1);
    }

    /** Long2ObjectLinkedOpenHashMap：long[] key + Object[] value + boolean[] used + 2 个链接 int[]。 */
    public static long longKeyObjectValueLinkedMapHeapByCapacity(long[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap.class)
                + LayoutSizes.arraySize(key.length, Long.BYTES)
                + LayoutSizes.arraySize(key.length, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(key.length, 1)
                + 2L * LayoutSizes.arraySize(key.length, Integer.BYTES);
    }

    /** long[] key + byte[] value + boolean[] used。 */
    public static long longKeyByteValueMapHeapByCapacity(long[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap.class)
                + LayoutSizes.arraySize(key.length, Long.BYTES)
                + LayoutSizes.arraySize(key.length, 1)
                + LayoutSizes.arraySize(key.length, 1);
    }

    /** long[] key + int[] value + boolean[] used。 */
    public static long longKeyIntValueMapHeapByCapacity(long[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap.class)
                + LayoutSizes.arraySize(key.length, Long.BYTES)
                + LayoutSizes.arraySize(key.length, Integer.BYTES)
                + LayoutSizes.arraySize(key.length, 1);
    }

    /** int[] key + Object[] value + boolean[] used。 */
    public static long intKeyObjectValueMapHeapByCapacity(int[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap.class)
                + LayoutSizes.arraySize(key.length, Integer.BYTES)
                + LayoutSizes.arraySize(key.length, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(key.length, 1);
    }

    /** Object[] key + Object[] value + boolean[] used。 */
    public static long objectKeyObjectValueMapHeapByCapacity(Object[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.class)
                + 2L * LayoutSizes.arraySize(key.length, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(key.length, 1);
    }

    /** Object[] key + boolean[] used（集合）。 */
    public static long objectKeySetHeapByCapacity(Object[] key) {
        return LayoutSizes.shallowSize(it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet.class)
                + LayoutSizes.arraySize(key.length, LayoutSizes.REFERENCE)
                + LayoutSizes.arraySize(key.length, 1);
    }
}
