package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * crafting：合成台开箱 → 放 2 木板（竖排）→ 等服务端合成结果 → 取 4 木棍。
 */
public class CraftingSuite extends TestSuite {

    private static final String BOT = "tbot-craft";

    public CraftingSuite() {
        super("crafting");
        test("合成台合成木棍", this::craftSticks);
    }

    private void craftSticks(TestContext ctx) {
        BlockPos[] table = {null};
        AtomicBoolean clicksIssued = new AtomicBoolean();
        AtomicBoolean resultReady = new AtomicBoolean();
        AtomicInteger sticks = new AtomicInteger();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> table[0] = ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0));
        SuitesSupport.placeBlockServer(ctx, () -> table[0], Blocks.CRAFTING_TABLE);
        SuitesSupport.awaitBlockVisible(ctx, () -> table[0], Blocks.CRAFTING_TABLE, 600);
        ctx.check("client sees crafting table", () -> ctx.bot().getBlockState(table[0]).is(Blocks.CRAFTING_TABLE));
        ctx.run(() -> SuitesSupport.openBlock(ctx, table[0]));
        ctx.await("crafting menu open", () -> ctx.bot().getContainer().isPresent()
                && ctx.bot().getContainer().get().getMenuType() == MenuType.CRAFTING, 600);
        ctx.check("getContainer present", () -> ctx.bot().getContainer().isPresent());
        ctx.check("menuType is crafting", () -> ctx.bot().getContainer()
                .map(c -> c.getMenuType() == MenuType.CRAFTING).orElse(false));
        SuitesSupport.give(ctx, BOT, "minecraft:oak_planks 2");
        ctx.await("client has 2 planks",
                () -> ctx.bot().getLocalPlayer().getInventory().countItem(Items.OAK_PLANKS) >= 2, 400);
        ctx.run(() -> {
            if (!clicksIssued.get()) {
                clicksIssued.set(true);
                ctx.bot().getContainer().ifPresent(c -> {
                    c.click(37, 0, ContainerInput.PICKUP);
                    c.click(1, 1, ContainerInput.PICKUP);
                    c.click(4, 1, ContainerInput.PICKUP);
                });
            }
        });
        ctx.await("crafting result ready", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null && sp.containerMenu != null
                        && sp.containerMenu.getSlot(0).getItem().is(Items.STICK)) {
                    resultReady.set(true);
                }
            });
            return resultReady.get();
        }, 200);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> {
            c.click(0, 0, ContainerInput.PICKUP);
            c.click(37, 0, ContainerInput.PICKUP);
        }));
        ctx.await("crafted sticks on server", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                sticks.set(sp != null ? sp.getInventory().countItem(Items.STICK) : 0);
            });
            return sticks.get() > 0;
        }, 200);
        ctx.check("crafted 4 sticks on server", () -> sticks.get() >= 4,
                () -> "count=" + sticks.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }
}
