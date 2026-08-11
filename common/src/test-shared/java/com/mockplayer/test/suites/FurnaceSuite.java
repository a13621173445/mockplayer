package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * furnace：熔炉烧制——give 原木+煤炭 → 放原料/燃料 → 服务端产物槽出木炭。
 */
public class FurnaceSuite extends TestSuite {

    private static final String BOT = "tbot-furn";

    public FurnaceSuite() {
        super("furnace");
        test("熔炉烧出木炭", this::smeltCharcoal);
    }

    private void smeltCharcoal(TestContext ctx) {
        BlockPos[] furnace = {null};
        AtomicBoolean clicked = new AtomicBoolean();
        AtomicBoolean charcoal = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> furnace[0] = ctx.bot.getLocalPlayer().blockPosition().offset(3, 0, 0));
        SuitesSupport.placeBlockServer(ctx, () -> furnace[0], Blocks.FURNACE);
        SuitesSupport.awaitBlockVisible(ctx, () -> furnace[0], Blocks.FURNACE, 600);
        ctx.check("client sees furnace", () -> ctx.bot.getBlockState(furnace[0]).is(Blocks.FURNACE));
        ctx.run(() -> SuitesSupport.openBlock(ctx, furnace[0]));
        ctx.await("furnace menu open", () -> ctx.bot.getContainer().isPresent()
                && ctx.bot.getContainer().get().getMenuType() == MenuType.FURNACE, 600);
        ctx.check("getContainer present", () -> ctx.bot.getContainer().isPresent());
        ctx.check("menuType is furnace", () -> ctx.bot.getContainer()
                .map(c -> c.getMenuType() == MenuType.FURNACE).orElse(false));
        SuitesSupport.give(ctx, BOT, "minecraft:oak_log 1", "minecraft:coal 1");
        ctx.await("client has log + coal", () -> ctx.bot.getLocalPlayer().getInventory().countItem(Items.OAK_LOG) > 0
                && ctx.bot.getLocalPlayer().getInventory().countItem(Items.COAL) > 0, 400);
        ctx.run(() -> {
            if (!clicked.get()) {
                clicked.set(true);
                ctx.bot.getContainer().ifPresent(c -> {
                    c.click(30, 0, ContainerInput.PICKUP);
                    c.click(0, 0, ContainerInput.PICKUP);
                    c.click(31, 0, ContainerInput.PICKUP);
                    c.click(1, 0, ContainerInput.PICKUP);
                });
            }
        });
        ctx.await("furnace produced charcoal", () -> {
            ctx.server().execute(() -> {
                AbstractFurnaceBlockEntity be = (AbstractFurnaceBlockEntity)
                        ctx.server().getLevel(Level.OVERWORLD).getBlockEntity(furnace[0]);
                charcoal.set(be != null && be.getItem(2).is(Items.CHARCOAL));
            });
            return charcoal.get();
        }, 600);
        ctx.check("furnace produced charcoal", charcoal::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }
}
