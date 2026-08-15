package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotWorldMemoryRegistry;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.memory.LayoutSizes;
import com.mockplayer.memory.WorldMemoryAccountant;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.openjdk.jol.info.GraphLayout;

/**
 * memory-accounting：可插拔世界内存记账模块。
 *
 * 覆盖：运行时布局探测、挂载/卸载注册表、光照差值记账、
 * 世界记账 > 0、方块更新触发 section 差值、JOL 快照校准占比。
 */
public class MemoryAccountingSuite extends TestSuite {

    static {
        // JOL 仅测试端校准：JDK 25 公开 Unsafe 禁 record 字段偏移，需在 JOL 类加载前开官方开关
        System.setProperty("jol.magicFieldOffset", "true");
    }

    public MemoryAccountingSuite() {
        super("memory-accounting");
        test("布局探测", this::layoutProbe);
        test("挂载/区块记账/卸载清理", this::mountAndWorldAccounting);
        test("方块更新触发差值", this::blockUpdateDelta);
        test("校准：记账 vs JOL 真实保留堆", this::calibrationRatio);
    }

    private void layoutProbe(TestContext ctx) {
        ctx.checkNow("object header > 0", LayoutSizes.OBJECT_HEADER > 0);
        ctx.checkNow("array header > 0", LayoutSizes.ARRAY_HEADER > 0);
        ctx.checkNow("ref size is 4 or 8", LayoutSizes.REFERENCE == 4 || LayoutSizes.REFERENCE == 8);
        ctx.checkNow("align(16)==16", LayoutSizes.align(16) == 16);
        ctx.checkNow("align(20)==24", LayoutSizes.align(20) == 24);
        ctx.checkNow("array int[36] == 160", LayoutSizes.arraySize(36, 4) == 160);
        ctx.checkNow("array long[128] == 1040", LayoutSizes.arraySize(128, 8) == 1040);
    }

    /**
     * 校准：记账值 vs JOL 快照真实保留堆（测试端专用）。
     * 参照 = 等主世界区块数稳定（世界生成完成）后，快照 Minecraft + 注册表共享图，
     * 再建假人；假人 level 图减去快照即纯 owned 对象。快照太早会把服务端后生成的
     * 区块算进 owned（实测 7.2MB 虚高），所以必须先等服务端稳定。
     * 只记录真实占比，不预设阈值——达到 95% 才算完成，达不到如实报差距。
     */
    private void calibrationRatio(TestContext ctx) {
        // 等服务端世界生成稳定：主世界区块数连续 40 tick 不变
        AtomicInteger stableTicks = new AtomicInteger();
        AtomicInteger lastChunks = new AtomicInteger(-1);
        ctx.await("server world stable", () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            int now = server == null || server.overworld() == null
                    ? -1 : server.overworld().getChunkSource().getLoadedChunksCount();
            if (now == lastChunks.get()) {
                return stableTicks.incrementAndGet() >= 40;
            }
            lastChunks.set(now);
            stableTicks.set(0);
            return false;
        }, 1200);
        AtomicReference<GraphLayout> sharedSnapshot = new AtomicReference<>();
        ctx.run(() -> sharedSnapshot.set(GraphLayout.parseInstance(
                net.minecraft.client.Minecraft.getInstance(),
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY)));
        // createUniqueBot 立即执行，必须排队到快照 run 之后（否则假人 level 会被并进共享图）
        AtomicReference<String> name = new AtomicReference<>();
        ctx.run(() -> name.set(SuitesSupport.createUniqueBot(ctx, "cal")));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        // 等假人区块数稳定 + 光照/BE 事件到齐
        AtomicInteger settleTicks = new AtomicInteger();
        AtomicInteger botChunks = new AtomicInteger(-1);
        ctx.await("bot chunks stable", () -> {
            int now = ctx.bot().getLevel().getChunkSource().getLoadedChunksCount();
            if (now == botChunks.get()) {
                return settleTicks.incrementAndGet() >= 40;
            }
            botChunks.set(now);
            settleTicks.set(0);
            return false;
        }, 600);
        ctx.await("settle events", () -> {
            settleTicks.incrementAndGet();
            return settleTicks.get() >= 120;
        }, 600);
        AtomicLong estimate = new AtomicLong();
        AtomicLong reference = new AtomicLong();
        AtomicInteger chunks = new AtomicInteger();
        AtomicLong display = new AtomicLong();
        AtomicInteger configRadius = new AtomicInteger();
        AtomicInteger sessionRadius = new AtomicInteger();
        AtomicInteger serverRadius = new AtomicInteger();
        ctx.run(() -> {
            estimate.set(BotWorldMemoryRegistry.get(name.get())
                    .map(m -> m.estimatedBytes()).orElse(0L));
            display.set(ctx.bot().memoryInfo().displayBytes());
            net.minecraft.client.multiplayer.ClientLevel level = ctx.bot().getLevel();
            chunks.set(level.getChunkSource().getLoadedChunksCount());
            configRadius.set(com.mockplayer.config.MockplayerConfig.get().getFakePlayerChunkRadius());
            sessionRadius.set(((com.mockplayer.session.BotImpl) ctx.bot()).session().getChunkRadius());
            serverRadius.set(((com.mockplayer.session.accessor.MockplayerClientPacketListenerAccessor)
                    ((com.mockplayer.session.BotImpl) ctx.bot()).session().getPlayListener()).mockplayer$getServerChunkRadius());
            // 参照 = 边界遍历：共享身份（快照里全部对象）命中即停；Connection/服务端包即停。
            // 这样服务端对象（经本地通道可达）与客户端共享对象都不会算进 owned。
            reference.set(ownedRetainedBytes(level, sharedIdentitySet(sharedSnapshot.get()), false, null));
            LAST_FOOTPRINT = topClasses(LAST_HISTOGRAM);
            // level 基础设施独立直方图（遇 LevelChunk 即停），供离线对账
            ownedRetainedBytes(level, sharedIdentitySet(sharedSnapshot.get()), false,
                    o -> o instanceof net.minecraft.world.level.chunk.LevelChunk);
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of("levelinfra.txt"),
                        topClasses(LAST_HISTOGRAM));
            } catch (java.io.IOException e) {
                System.out.println("[mocktest] levelinfra write failed: " + e);
            }
        });
        ctx.check("estimate > 0", () -> estimate.get() > 0);
        ctx.check("reference > 0", () -> reference.get() > 0);
        ctx.check("calibration recorded", () -> {
            double ratio = estimate.get() * 100.0 / reference.get();
            System.out.printf("[mocktest] calibration estimate=%d reference=%d chunks=%d ratio=%.1f%%%n",
                    estimate.get(), reference.get(), chunks.get(), ratio);
            try {
                // 结果落盘（工作目录 = runs/client），双端校准都从这里读
                java.nio.file.Files.writeString(java.nio.file.Path.of("calibration.txt"),
                        "estimate=" + estimate.get() + " reference=" + reference.get()
                                + " chunks=" + chunks.get() + " ratio=" + ratio
                                + " display=" + display.get()
                                + " tracked=" + (display.get() - estimate.get())
                                + " configRadius=" + configRadius.get()
                                + " sessionRadius=" + sessionRadius.get()
                                + " serverRadius=" + serverRadius.get() + "\n"
                                + BotWorldMemoryRegistry.get(name.get())
                                        .map(m -> ((com.mockplayer.memory.WorldMemoryAccountant) m)
                                                .breakdown().toString()).orElse("") + "\n"
                                + LAST_FOOTPRINT);
            } catch (java.io.IOException e) {
                throw new RuntimeException("校准结果写盘失败", e);
            }
            return ratio > 0;
        });
        ctx.run(() -> MockplayerApi.bots().removeBot(name.get(), "command"));
    }

    /** 把 JOL 快照的地址集合转成对象身份集合（GraphPathRecord.obj 包私有，反射一次）。 */
    private static java.util.Set<Object> sharedIdentitySet(GraphLayout layout) {
        java.util.Set<Object> shared =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        try {
            java.lang.reflect.Method objMethod =
                    org.openjdk.jol.info.GraphPathRecord.class.getDeclaredMethod("obj");
            objMethod.setAccessible(true);
            for (Long address : layout.addresses()) {
                Object o = objMethod.invoke(layout.record(address));
                if (o != null) {
                    shared.add(o);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("快照身份集合构建失败", e);
        }
        return shared;
    }

    /**
     * 测试端边界遍历（校准参照，不进生产）：从假人 level 出发，
     * 共享身份/Connection/服务端包命中即停（不计、不深入），其余按布局公式累加。
     * 集成服务器经本地通道把服务端世界连进假人图，必须由 Connection/服务端包停止规则挡掉。
     */
    private static long ownedRetainedBytes(Object root, java.util.Set<Object> shared, boolean stopLevels,
                                           java.util.function.Predicate<Object> extraStop) {
        java.util.IdentityHashMap<Object, Boolean> visited = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Object> stack = new java.util.ArrayDeque<>();
        java.util.Map<Class<?>, long[]> byClass = new java.util.HashMap<>();
        long total = 0;
        stack.push(root);
        while (!stack.isEmpty()) {
            Object o = stack.pop();
            if (o == null || visited.containsKey(o)) {
                continue;
            }
            visited.put(o, Boolean.TRUE);
            if (shared.contains(o) || isGlobalInfrastructure(o)
                    || (stopLevels && o instanceof net.minecraft.world.level.Level && o != root)
                    || (extraStop != null && extraStop.test(o))) {
                continue;
            }
            long size = objectSize(o);
            total += size;
            byClass.computeIfAbsent(o.getClass(), k -> new long[1])[0] += size;
            pushReferenceFields(o, stack);
            if (o instanceof Object[] arr) {
                for (Object element : arr) {
                    if (element != null) {
                        stack.push(element);
                    }
                }
            }
        }
        LAST_HISTOGRAM = byClass;
        return total;
    }

    /** 普适共享规则：类/类加载器/线程/注册表/Holder/网络连接/服务端包。 */
    private static boolean isGlobalInfrastructure(Object o) {
        String name = o.getClass().getName();
        return o instanceof Class<?>
                || o instanceof ClassLoader
                || o instanceof Thread
                || o instanceof java.lang.Module
                || o instanceof net.minecraft.core.Registry<?>
                || o instanceof net.minecraft.core.Holder<?>
                || o instanceof net.minecraft.network.Connection
                // 假人会话网络层（ClientPacketListener 及子类）：成就/菜单/配方簿属会话，
                // 不是假人 level 的对象堆，参照口径不包含
                || o instanceof net.minecraft.client.multiplayer.ClientPacketListener
                || name.startsWith("net.minecraft.server.")
                || name.startsWith("net.fabricmc.fabric.impl.networking.server.");
    }

    /** 数组按长度算，普通对象按布局浅尺寸算。 */
    private static long objectSize(Object o) {
        Class<?> type = o.getClass();
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            int elementSize;
            if (component.isPrimitive()) {
                if (component == long.class || component == double.class) {
                    elementSize = 8;
                } else if (component == int.class || component == float.class) {
                    elementSize = 4;
                } else if (component == short.class || component == char.class) {
                    elementSize = 2;
                } else {
                    elementSize = 1;
                }
            } else {
                elementSize = LayoutSizes.REFERENCE;
            }
            return LayoutSizes.arraySize(java.lang.reflect.Array.getLength(o), elementSize);
        }
        return LayoutSizes.shallowSize(type);
    }

    /** 非静态引用字段入栈：Unsafe 偏移读（无需模块 opens），record 字段回退反射。 */
    private static void pushReferenceFields(Object o, java.util.ArrayDeque<Object> stack) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            FieldAccess access = fieldsOf(c);
            for (int i = 0; i < access.fields.length; i++) {
                java.lang.reflect.Field field = access.fields[i];
                if (field.getType().isPrimitive()) {
                    continue;
                }
                Object value;
                if (access.offsets[i] >= 0) {
                    value = UNSAFE.getObject(o, access.offsets[i]);
                } else {
                    try {
                        field.setAccessible(true);
                        value = field.get(o);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        continue; // 隐藏类/lambda 字段读不了就跳过
                    }
                }
                if (value != null) {
                    stack.push(value);
                }
            }
        }
    }

    /** 字段访问缓存：Unsafe 偏移（非 record）或 -1（record 回退反射）。 */
    private record FieldAccess(java.lang.reflect.Field[] fields, long[] offsets) {
    }

    private static FieldAccess fieldsOf(Class<?> type) {
        FieldAccess cached = FIELD_CACHE.get(type);
        if (cached == null) {
            java.lang.reflect.Field[] fields = java.util.Arrays.stream(type.getDeclaredFields())
                    .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    .toArray(java.lang.reflect.Field[]::new);
            long[] offsets = new long[fields.length];
            for (int i = 0; i < fields.length; i++) {
                try {
                    offsets[i] = UNSAFE.objectFieldOffset(fields[i]);
                } catch (UnsupportedOperationException e) {
                    offsets[i] = -1; // record 字段：JDK 25 公开 Unsafe 拒绝
                }
            }
            cached = new FieldAccess(fields, offsets);
            FIELD_CACHE.put(type, cached);
        }
        return cached;
    }

    /** 校准遍历用 Unsafe（与 LayoutSizes 探测同源）。 */
    private static final sun.misc.Unsafe UNSAFE = unsafe();

    private static sun.misc.Unsafe unsafe() {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (sun.misc.Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("校准遍历无法获取 Unsafe", e);
        }
    }

    private static final java.util.Map<Class<?>, FieldAccess> FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile java.util.Map<Class<?>, long[]> LAST_HISTOGRAM = java.util.Map.of();

    private static String topClasses(java.util.Map<Class<?>, long[]> byClass) {
        StringBuilder sb = new StringBuilder("top classes by bytes:\n");
        byClass.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(100000)
                .forEach(e -> sb.append(String.format("%10d %s%n", e.getValue()[0], e.getKey().getName())));
        return sb.toString();
    }

    private static volatile String LAST_FOOTPRINT = "";

    private void mountAndWorldAccounting(TestContext ctx) {
        String name = SuitesSupport.createUniqueBot(ctx, "mem");
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        ctx.check("world memory registry attached",
                () -> BotWorldMemoryRegistry.get(name).isPresent());
        ctx.await("world estimate > 0", () -> BotWorldMemoryRegistry.get(name)
                .map(m -> m.estimatedBytes() > 0).orElse(false), 200);
        ctx.run(() -> MockplayerApi.bots().removeBot(name, "command"));
        ctx.await("registry cleaned after remove",
                () -> BotWorldMemoryRegistry.get(name).isEmpty(), 100);
    }

    private void blockUpdateDelta(TestContext ctx) {
        String name = SuitesSupport.createUniqueBot(ctx, "mem");
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        SuitesSupport.awaitChunkLoaded(ctx);
        AtomicLong before = new AtomicLong(-1);
        ctx.run(() -> before.set(BotWorldMemoryRegistry.get(name)
                .map(m -> m.estimatedBytes()).orElse(-1L)));
        ctx.check("estimate captured", () -> before.get() > 0);
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        ctx.run(() -> pos.set(ctx.bot().getLocalPlayer().blockPosition()
                .offset(0, 0, 3)));
        SuitesSupport.placeBlockServer(ctx, pos::get, Blocks.GOLD_BLOCK);
        SuitesSupport.awaitBlockVisible(ctx, pos::get, Blocks.GOLD_BLOCK, 200);
        ctx.await("estimate changed after block update", () -> BotWorldMemoryRegistry.get(name)
                .map(m -> m.estimatedBytes() != before.get()).orElse(false), 100);
        ctx.run(() -> MockplayerApi.bots().removeBot(name, "command"));
    }
}
