package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * containers：开箱/菜单断言/关闭，放取物品端到端（服务端箱子状态强断言），
 * 主玩家暂停界面隔离（假人容器交互不打断 PauseScreen）。
 */
public class ContainerSuite extends TestSuite {

    private static final String BOT = "tbot-cont";

    public ContainerSuite() {
        super("containers");
        test("开箱与菜单断言", this::openAndAssert);
        test("放取物品端到端", this::putAndTake);
        test("暂停界面隔离", this::pauseIsolation);
    }

    private void openAndAssert(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0)));
        SuitesSupport.placeBlockServer(ctx, pos::get, Blocks.CHEST);
        SuitesSupport.awaitBlockVisible(ctx, pos::get, Blocks.CHEST, 600);
        ctx.await("server player interactable", () -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            return sp != null && !isAwaitingPosition(sp);
        }, 200);
        ctx.run(() -> SuitesSupport.openBlock(ctx, pos.get()));
        ctx.await("chest menu open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.check("menuType is chest", () -> ctx.bot().getContainer()
                .map(c -> c.getMenuType() == MenuType.GENERIC_9x3).orElse(false));
        ctx.check("container total slots == 63", () -> ctx.bot().getContainer()
                .map(c -> c.getSize() == 63).orElse(false));
        ctx.check("containerId > 0", () -> ctx.bot().getContainer()
                .map(c -> c.getContainerId() > 0).orElse(false));
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> c.close()));
        ctx.await("container closed", () -> ctx.bot().getContainer().isEmpty(), 200);
    }

    private void putAndTake(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean open = new AtomicBoolean();
        AtomicBoolean stoneInChest = new AtomicBoolean();
        AtomicBoolean chestEmpty = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0)));
        SuitesSupport.placeBlockServer(ctx, pos::get, Blocks.CHEST);
        SuitesSupport.awaitBlockVisible(ctx, pos::get, Blocks.CHEST, 600);
        SuitesSupport.give(ctx, BOT, "minecraft:stone 1");
        ctx.await("client has stone", () -> ctx.bot().getLocalPlayer().getInventory()
                .countItem(Items.STONE) > 0, 400);
        ctx.run(() -> {
            if (!open.get()) {
                open.set(true);
                SuitesSupport.openBlock(ctx, pos.get());
            }
        });
        ctx.await("chest menu open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> {
            c.click(54, 0, ContainerInput.PICKUP);
            c.click(0, 0, ContainerInput.PICKUP);
        }));
        ctx.await("stone in chest (server)", () -> {
            ctx.server().execute(() -> {
                ChestBlockEntity chest = (ChestBlockEntity)
                        ctx.server().getLevel(Level.OVERWORLD).getBlockEntity(pos.get());
                stoneInChest.set(chest != null && chest.getItem(0).is(Items.STONE));
            });
            return stoneInChest.get();
        }, 100);
        ctx.check("chest slot0 has stone (server)", stoneInChest::get);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c ->
                c.click(0, 0, ContainerInput.PICKUP)));
        ctx.await("chest empty after pickup (server)", () -> {
            ctx.server().execute(() -> {
                ChestBlockEntity chest = (ChestBlockEntity)
                        ctx.server().getLevel(Level.OVERWORLD).getBlockEntity(pos.get());
                chestEmpty.set(chest != null && chest.getItem(0).isEmpty());
            });
            return chestEmpty.get();
        }, 100);
        ctx.check("chest slot0 empty after pickup (server)", chestEmpty::get);
    }

    private void pauseIsolation(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean screenOk = new AtomicBoolean(true);
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0)));
        SuitesSupport.placeBlockServer(ctx, pos::get, Blocks.CHEST);
        SuitesSupport.awaitBlockVisible(ctx, pos::get, Blocks.CHEST, 600);
        ctx.run(() -> {
            if (!opened.get()) {
                opened.set(true);
                SuitesSupport.openBlock(ctx, pos.get());
            }
        });
        ctx.await("chest menu open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                mc.setScreen(new PauseScreen(true));
            }
        });
        ctx.check("pause screen open", () -> Minecraft.getInstance().screen
                instanceof PauseScreen);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> {
            c.click(0, 0, ContainerInput.PICKUP);
            c.close();
        }));
        ctx.await("container closed during pause isolation", () -> {
            if (!(Minecraft.getInstance().screen instanceof PauseScreen)) {
                screenOk.set(false);
            }
            return ctx.bot().getContainer().isEmpty();
        }, 100);
        ctx.check("pause screen not interrupted", screenOk::get);
        ctx.run(() -> {
            Minecraft.getInstance().setScreen(null);
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }

    /** 反射读服务端玩家连接是否仍在等待位置确认（26.2 awaitingPositionFromClient 是 Vec3，非 null = 等待中）。 */
    private static boolean isAwaitingPosition(ServerPlayer sp) {
        try {
            java.lang.reflect.Field f = sp.connection.getClass().getDeclaredField("awaitingPositionFromClient");
            f.setAccessible(true);
            return f.get(sp.connection) != null;
        } catch (Exception ignored) {
            return true;
        }
    }
}
