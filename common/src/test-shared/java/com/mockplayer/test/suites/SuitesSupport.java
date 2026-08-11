package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.session.FakePlayerCommands;
import com.mockplayer.test.framework.TestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/** 套件公共原语：建 bot / 放方块 / 打开方块容器 / 服务端给物品。 */
public final class SuitesSupport {

    private SuitesSupport() {
    }

    public static void createBot(TestContext ctx, String name) {
        MockplayerApi.bots().removeBot(name, "command");
        FakePlayerCommands.newPlayer(name);
        ctx.bot = MockplayerApi.bots().getBot(name).orElse(null);
        ctx.botName = name;
    }

    /** 注册「建 bot + 等 PLAYING」两步。 */
    public static void createBotAndWaitPlaying(TestContext ctx, String name) {
        ctx.run(() -> createBot(ctx, name));
        ctx.await("lifecycle PLAYING", () -> ctx.bot != null
                && ctx.bot.getLifecycle() == BotLifecycle.PLAYING, 300);
    }

    /** 服务端放方块（异步执行；pos 延迟求值，之后用 blockVisible await 确认）。 */
    public static void placeBlockServer(TestContext ctx, Supplier<BlockPos> pos, Block block) {
        ctx.server(() -> {
            BlockPos p = pos.get();
            if (p != null) {
                ctx.server().getLevel(Level.OVERWORLD).setBlock(p, block.defaultBlockState(), 3);
            }
        });
    }

    /** 等假人客户端区块就绪（无渲染，区块靠网络包加载；就绪后再放方块避免更新包丢失）。 */
    public static void awaitChunkLoaded(TestContext ctx, int timeoutTicks) {
        ctx.await("bot chunk loaded", () -> ctx.bot != null
                && ctx.bot.getLocalPlayer() != null
                && ctx.bot.isBlockLoaded(ctx.bot.getLocalPlayer().blockPosition()), timeoutTicks);
    }

    /** 等假人客户端能看到指定方块（pos 延迟求值：可能由前面的 run 步骤赋值）。 */
    public static void awaitBlockVisible(TestContext ctx, Supplier<BlockPos> pos, Block block, int timeoutTicks) {
        ctx.await("client sees " + block, () -> ctx.bot != null
                && pos.get() != null && ctx.bot.getBlockState(pos.get()).is(block), timeoutTicks);
    }

    /** 看向方块并右键打开（原版 useItemOn 链路）。 */
    public static void openBlock(TestContext ctx, BlockPos pos) {
        ctx.bot.actions().lookAt(Vec3.atCenterOf(pos));
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(pos), Direction.WEST, pos, false);
        ctx.bot.getGameMode().useItemOn(ctx.bot.getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
    }

    /** 服务端 give 若干物品（同一命令源）。 */
    public static void give(TestContext ctx, String botName, String... items) {
        ctx.server(() -> {
            var source = ctx.server().createCommandSourceStack();
            for (String item : items) {
                ctx.server().getCommands().performPrefixedCommand(source, "give " + botName + " " + item);
            }
        });
    }

    /** 反射读服务端玩家连接是否仍在等待位置确认（26.2 awaitingPositionFromClient 是 Vec3，非 null = 等待中）。 */
    public static boolean isAwaitingPosition(ServerPlayer sp) {
        try {
            java.lang.reflect.Field f = sp.connection.getClass().getDeclaredField("awaitingPositionFromClient");
            f.setAccessible(true);
            return f.get(sp.connection) != null;
        } catch (Exception ignored) {
            return true;
        }
    }
}
