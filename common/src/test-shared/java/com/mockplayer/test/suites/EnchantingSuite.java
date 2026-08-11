package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * enchanting：附魔台——放剑+青金石 → 服务端算出附魔成本 → clickButton 附魔 →
 * 服务端断言剑带 ENCHANTMENTS。
 */
public class EnchantingSuite extends TestSuite {

    private static final String BOT = "tbot-enc";

    public EnchantingSuite() {
        super("enchanting");
        test("附魔台附魔钻石剑", this::enchantSword);
    }

    private void enchantSword(TestContext ctx) {
        BlockPos[] table = {null};
        AtomicBoolean loaded = new AtomicBoolean();
        AtomicInteger cost0 = new AtomicInteger();
        AtomicBoolean enchanted = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> table[0] = ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0));
        SuitesSupport.placeBlockServer(ctx, () -> table[0], Blocks.ENCHANTING_TABLE);
        SuitesSupport.awaitBlockVisible(ctx, () -> table[0], Blocks.ENCHANTING_TABLE, 600);
        ctx.run(() -> SuitesSupport.openBlock(ctx, table[0]));
        ctx.await("enchanting menu open", () -> ctx.bot().getContainer().isPresent()
                && ctx.bot().getContainer().get().getMenuType() == MenuType.ENCHANTMENT, 600);
        SuitesSupport.give(ctx, BOT, "minecraft:diamond_sword 1",
                "minecraft:lapis_lazuli 1");
        ctx.server(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(), "experience set " + BOT + " 30 levels"));
        ctx.await("client has sword + lapis", () -> ctx.bot().getLocalPlayer().getInventory()
                        .countItem(Items.DIAMOND_SWORD) > 0
                && ctx.bot().getLocalPlayer().getInventory().countItem(Items.LAPIS_LAZULI) > 0, 400);
        ctx.run(() -> {
            if (!loaded.get()) {
                loaded.set(true);
                ctx.bot().getContainer().ifPresent(c -> {
                    c.click(29, 0, ContainerInput.PICKUP);
                    c.click(0, 0, ContainerInput.PICKUP);
                    c.click(30, 0, ContainerInput.PICKUP);
                    c.click(1, 0, ContainerInput.PICKUP);
                });
            }
        });
        ctx.await("enchantment costs available", () -> {
            ctx.bot().getContainer().ifPresent(c -> {
                if (c.getMenuType() == MenuType.ENCHANTMENT) {
                    cost0.set(((EnchantmentMenu) c.raw()).costs[0]);
                }
            });
            return cost0.get() > 0;
        }, 200);
        ctx.check("enchantment costs available", () -> cost0.get() > 0,
                () -> "cost0=" + cost0.get());
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c -> c.clickButton(0)));
        ctx.await("sword enchanted", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    var sword = sp.containerMenu.getSlot(0).getItem();
                    enchanted.set(sword.is(Items.DIAMOND_SWORD)
                            && sword.has(DataComponents.ENCHANTMENTS));
                }
            });
            return enchanted.get();
        }, 200);
        ctx.check("sword enchanted (ENCHANTMENTS component)", enchanted::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }
}
