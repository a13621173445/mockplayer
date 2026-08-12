package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotWorldMemoryRegistry;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.memory.LayoutSizes;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * memory-accounting：可插拔世界内存记账模块。
 *
 * 覆盖：布局公式（与 tmp/memexp 实验口径一致）、挂载/卸载注册表、
 * 世界估算 > 0、方块更新触发 section 差值。
 */
public class MemoryAccountingSuite extends TestSuite {

    public MemoryAccountingSuite() {
        super("memory-accounting");
        test("布局公式", this::layoutFormula);
        test("挂载/区块记账/卸载清理", this::mountAndWorldAccounting);
        test("方块更新触发差值", this::blockUpdateDelta);
    }

    private void layoutFormula(TestContext ctx) {
        ctx.checkNow("align(16)==16", LayoutSizes.align(16) == 16);
        ctx.checkNow("align(20)==24", LayoutSizes.align(20) == 24);
        ctx.checkNow("array int[36] == 160", LayoutSizes.arraySize(36, 4) == 160);
        ctx.checkNow("array long[128] == 1040", LayoutSizes.arraySize(128, 8) == 1040);
    }

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
