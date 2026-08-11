package com.mockplayer.test.suites;

import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.event.BotListener;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * gui-actions：chat / sendCommand / editBook / editSign / setBeacon /
 * pickItemFromBlock / respawn 动作路径。
 */
public class GuiActionsSuite extends TestSuite {

    private static final String BOT = "tbot-gui";

    public GuiActionsSuite() {
        super("gui-actions");
        test("chat 消息广播", this::chat);
        test("sendCommand me", this::sendCommand);
        test("editBook 写书", this::editBook);
        test("editSign 写告示牌", this::editSign);
        test("setBeacon 信标效果", this::setBeacon);
        test("pickItemFromBlock 中键取块", this::pickItem);
        test("respawn 复活", this::respawn);
    }

    private void chat(TestContext ctx) {
        AtomicReference<String> msg = new AtomicReference<>("");
        AtomicBoolean done = new AtomicBoolean();
        MockplayerApi.listen(new BotListener() {
            @Override
            public void onChat(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component message) {
                msg.set(message.getString());
            }
        });
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            if (!done.get()) {
                done.set(true);
                msg.set("");
                ctx.bot().actions().chat("mockplayer-gui-test");
            }
        });
        ctx.await("chat message broadcast to fake",
                () -> msg.get().contains("mockplayer-gui-test"), 200);
        ctx.check("chat message broadcast to fake",
                () -> msg.get().contains("mockplayer-gui-test"));
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void sendCommand(TestContext ctx) {
        AtomicReference<String> msg = new AtomicReference<>("");
        AtomicBoolean done = new AtomicBoolean();
        MockplayerApi.listen(new BotListener() {
            @Override
            public void onChat(com.mockplayer.api.Bot b, net.minecraft.network.chat.Component message) {
                msg.set(message.getString());
            }
        });
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            if (!done.get()) {
                done.set(true);
                msg.set("");
                ctx.bot().actions().sendCommand("me mockplayer-gui-cmd");
            }
        });
        ctx.await("sendCommand me executed",
                () -> msg.get().contains("mockplayer-gui-cmd"), 200);
        ctx.check("sendCommand me executed",
                () -> msg.get().contains("mockplayer-gui-cmd"));
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void editBook(TestContext ctx) {
        AtomicBoolean verified = new AtomicBoolean();
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(),
                "item replace entity " + BOT + " weapon.mainhand with minecraft:writable_book")));
        ctx.await("book in hand", () -> ctx.bot().getLocalPlayer().getMainHandItem()
                .is(Items.WRITABLE_BOOK), 200);
        ctx.run(() -> ctx.bot().actions().editBook(
                ctx.bot().getLocalPlayer().getInventory().getSelectedSlot(),
                List.of("mockplayer page one", "second line"),
                Optional.of("Mockplayer Book")));
        ctx.await("editBook wrote written book", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                verified.set(sp != null && sp.getMainHandItem().is(Items.WRITTEN_BOOK));
            });
            return verified.get();
        }, 300);
        ctx.check("editBook wrote written book", verified::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void editSign(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean rightClicked = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean verified = new AtomicBoolean();
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                pos.set(sp.blockPosition().offset(3, 0, 0));
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "setblock " + pos.get().getX() + " " + pos.get().getY() + " " + pos.get().getZ()
                                + " minecraft:oak_sign");
            }
        }));
        ctx.await("sign visible", () -> pos.get() != null
                && ctx.bot().getBlockState(pos.get()).is(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.Identifier.tryParse("minecraft:oak_sign")).get().value()), 600);
        ctx.run(() -> {
            if (!rightClicked.get()) {
                rightClicked.set(true);
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(pos.get()), Direction.UP, pos.get(), false);
                ctx.bot().getGameMode().useItemOn(ctx.bot().getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
            }
        });
        int[] signWait = {0};
        ctx.await("sign editor ready", () -> ++signWait[0] >= 20, 40);
        ctx.run(() -> {
            if (!done.get()) {
                done.set(true);
                ctx.bot().actions().editSign(pos.get(), true,
                        new String[]{"mock", "player", "sign", "line4"});
            }
        });
        ctx.await("editSign updated block entity", () -> {
            ctx.server().execute(() -> {
                ServerLevel lv = ctx.server().getLevel(Level.OVERWORLD);
                if (pos.get() != null && lv.getBlockEntity(pos.get()) instanceof SignBlockEntity sign) {
                    verified.set("mock".equals(sign.getFrontText().getMessage(0, false).getString()));
                }
            });
            return verified.get();
        }, 300);
        ctx.check("editSign updated block entity", verified::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void setBeacon(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean paid = new AtomicBoolean();
        AtomicBoolean applied = new AtomicBoolean();
        AtomicBoolean verified = new AtomicBoolean();
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                pos.set(sp.blockPosition().offset(3, 0, 0));
                int x = pos.get().getX(), y = pos.get().getY(), z = pos.get().getZ();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                                "setblock " + (x + dx) + " " + (y - 1) + " " + (z + dz) + " minecraft:iron_block");
                    }
                }
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "setblock " + x + " " + y + " " + z + " minecraft:beacon");
            }
        }));
        ctx.await("beacon visible", () -> pos.get() != null
                && ctx.bot().getBlockState(pos.get()).is(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.Identifier.tryParse("minecraft:beacon")).get().value()), 600);
        int[] beaconWait = {0};
        ctx.await("beacon levels settle", () -> ++beaconWait[0] >= 100, 200);
        ctx.run(() -> {
            if (!opened.get()) {
                opened.set(true);
                BlockHitResult hit = new BlockHitResult(
                        Vec3.atCenterOf(pos.get()), Direction.UP, pos.get(), false);
                ctx.bot().getGameMode().useItemOn(ctx.bot().getLocalPlayer(), InteractionHand.MAIN_HAND, hit);
            }
        });
        ctx.await("beacon menu open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.await("beacon payment slot filled", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null && sp.containerMenu instanceof BeaconMenu menu) {
                    menu.getSlot(0).set(new ItemStack(Items.IRON_INGOT));
                    paid.set(true);
                }
            });
            return paid.get();
        }, 50);
        ctx.run(() -> ctx.bot().actions().setBeacon(
                Optional.of(MobEffects.SPEED), Optional.empty()));
        ctx.await("setBeacon applied speed", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                verified.set(sp != null && sp.hasEffect(MobEffects.SPEED));
            });
            return verified.get();
        }, 300);
        ctx.check("setBeacon applied speed", verified::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void pickItem(TestContext ctx) {
        AtomicBoolean verified = new AtomicBoolean();
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(), "gamemode creative " + BOT)));
        ctx.await("creative mode", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                verified.set(sp != null && sp.isCreative());
            });
            return verified.get();
        }, 100);
        ctx.run(() -> ctx.bot().actions().pickItemFromBlock(
                ctx.bot().getLocalPlayer().blockPosition().below(), false));
        ctx.await("pickItemFromBlock changed held item", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                verified.set(sp != null && !sp.getMainHandItem().isEmpty());
            });
            return verified.get();
        }, 300);
        ctx.check("pickItemFromBlock changed held item", verified::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void respawn(TestContext ctx) {
        AtomicBoolean dead = new AtomicBoolean();
        AtomicBoolean revived = new AtomicBoolean();
        AtomicBoolean killed = new AtomicBoolean();
        ctx.run(() -> SuitesSupport.createBot(ctx, BOT));
        ctx.await("lifecycle PLAYING", () -> ctx.bot() != null
                && ctx.bot().getLifecycle() == BotLifecycle.PLAYING, 300);
        ctx.run(() -> {
            if (!killed.get()) {
                killed.set(true);
                // 先关自动重生再 kill：否则 bot 立刻复活，isDeadOrDying 窗口太短会偶发漏采
                com.mockplayer.session.SessionManager.getInstance().getSession(BOT).setAutoRespawn(false);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "gamemode survival " + BOT));
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "kill " + BOT));
            }
        });
        ctx.await("fake dead", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                dead.set(sp != null && sp.isDeadOrDying());
            });
            return dead.get();
        }, 200);
        ctx.run(() -> {
            if (dead.get()) {
                ctx.bot().actions().respawn();
            }
        });
        ctx.await("respawn revived", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                revived.set(sp != null && !sp.isDeadOrDying());
            });
            return revived.get();
        }, 200);
        ctx.check("respawn revived", revived::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }
}
