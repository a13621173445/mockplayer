package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * merchant：召唤 NoAI 村民（特殊交易 1 绿宝石→1 钻石）→ 交互开交易菜单 →
 * 放绿宝石 → selectTrade → 点结果槽 → 服务端背包出现钻石。
 */
public class MerchantSuite extends TestSuite {

    private static final String BOT = "tbot-merk";

    public MerchantSuite() {
        super("merchant");
        test("村民交易绿宝石换钻石", this::tradeDiamond);
    }

    private void tradeDiamond(TestContext ctx) {
        AtomicBoolean interacted = new AtomicBoolean();
        AtomicBoolean traded = new AtomicBoolean();
        AtomicBoolean gotDiamond = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.server(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                String cmd = String.format(
                        "summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:diamond\",count:1},maxUses:99,xp:1}]}}",
                        sp.getX() + 3.0, sp.getY(), sp.getZ());
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(), cmd);
            }
        });
        ctx.await("client sees villager", () -> ctx.bot().getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Villager), 600);
        ctx.check("client sees villager", () -> ctx.bot().getEntitiesNear(64).stream()
                .anyMatch(e -> e instanceof Villager));
        ctx.run(() -> {
            if (!interacted.get()) {
                interacted.set(true);
                var villager = ctx.bot().getEntitiesNear(64).stream()
                        .filter(e -> e instanceof Villager)
                        .findFirst().orElse(null);
                if (villager != null) {
                    ctx.bot().actions().lookAt(villager);
                    ctx.bot().actions().interact(villager);
                }
            }
        });
        ctx.await("merchant menu open", () -> ctx.bot().getContainer().isPresent()
                && ctx.bot().getContainer().get().getMenuType() == MenuType.MERCHANT, 600);
        ctx.check("getContainer present (real villager merchant)",
                () -> ctx.bot().getContainer().isPresent());
        ctx.check("menuType is merchant", () -> ctx.bot().getContainer()
                .map(c -> c.getMenuType() == MenuType.MERCHANT).orElse(false));
        SuitesSupport.give(ctx, BOT, "minecraft:emerald 1");
        ctx.await("client has emerald", () -> ctx.bot().getLocalPlayer().getInventory()
                .countItem(Items.EMERALD) > 0, 400);
        ctx.run(() -> {
            if (!traded.get()) {
                traded.set(true);
                ctx.bot().getContainer().ifPresent(c -> {
                    c.click(30, 0, ContainerInput.PICKUP);
                    c.click(0, 0, ContainerInput.PICKUP);
                    c.selectTrade(0);
                    c.click(2, 0, ContainerInput.PICKUP);
                    c.click(30, 0, ContainerInput.PICKUP);
                });
            }
        });
        ctx.await("traded emerald -> diamond (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    gotDiamond.set(sp.getInventory().countItem(Items.DIAMOND) > 0);
                }
            });
            return gotDiamond.get();
        }, 600);
        ctx.check("traded emerald → diamond (server)", gotDiamond::get);
        ctx.run(() -> {
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:villager]"));
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }
}
