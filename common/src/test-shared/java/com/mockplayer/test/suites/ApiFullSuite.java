package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.RemoveResult;
import com.mockplayer.api.event.BotListener;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * api-full：Bot/BotActions/BotContainer/BotManager 全接口真实路径
 * （信息接口/横移/换手/丢弃/放置/挖掘事件/容器 setSlot/管理 API）。
 */
public class ApiFullSuite extends TestSuite {

    private static final String BOT = "tbot-full";

    public ApiFullSuite() {
        super("api-full");
        test("信息接口", this::infoInterfaces);
        test("横移/换手/丢弃", this::strafeSwapDrop);
        test("放置/挖掘与事件", this::placeMine);
        test("容器接口", this::containerApi);
        test("BotManager API", this::managerApi);
    }

    private void infoInterfaces(TestContext ctx) {
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        ctx.check("getUUID non-null", () -> ctx.bot().getUUID() != null);
        ctx.await("getOnlinePlayers non-empty", () -> !ctx.bot().getOnlinePlayers().isEmpty(), 200);
        ctx.check("getOnlinePlayers non-empty", () -> !ctx.bot().getOnlinePlayers().isEmpty());
        ctx.check("getEntitiesNear pred (villager filter no-crash)", () ->
                ctx.bot().getEntitiesNear(64, e -> e instanceof net.minecraft.world.entity.npc.villager.Villager) != null);
        ctx.check("getScreen alias getContainer", () ->
                ctx.bot().getScreen().isEmpty() == ctx.bot().getContainer().isEmpty());
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void strafeSwapDrop(TestContext ctx) {
        AtomicReference<double[]> base = new AtomicReference<>();
        AtomicReference<double[]> cur = new AtomicReference<>();
        AtomicBoolean strafeMoved = new AtomicBoolean();
        AtomicBoolean swapped = new AtomicBoolean();
        AtomicBoolean offhand = new AtomicBoolean();
        AtomicBoolean dropped = new AtomicBoolean();
        AtomicBoolean dropIssued = new AtomicBoolean();
        AtomicReference<Integer> beforeCount = new AtomicReference<>(0);
        AtomicBoolean reduced = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> {
            if (!strafeMoved.get()) {
                strafeMoved.set(true);
                ctx.bot().actions().setForward(0.0F);
                ctx.bot().actions().setStrafe(1.0F);
                ctx.server().execute(() -> {
                    ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                    if (sp != null) {
                        base.set(new double[]{sp.getX(), sp.getZ()});
                    }
                });
            }
        });
        ctx.await("setStrafe moved on server", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    cur.set(new double[]{sp.getX(), sp.getZ()});
                }
            });
            double[] b = base.get();
            double[] c = cur.get();
            return b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 0.3 || Math.abs(c[1] - b[1]) > 0.3);
        }, 200);
        ctx.check("setStrafe moved on server", () -> {
            double[] b = base.get();
            double[] c = cur.get();
            return b != null && c != null
                    && (Math.abs(c[0] - b[0]) > 0.3 || Math.abs(c[1] - b[1]) > 0.3);
        });
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    sp.getInventory().clearContent();
                    sp.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
                    sp.getInventory().setSelectedSlot(0);
                }
            });
        });
        ctx.run(() -> {
            if (!swapped.get()) {
                swapped.set(true);
                ctx.bot().actions().swapHands();
            }
        });
        ctx.await("swapHands moved item to offhand (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    offhand.set(sp.getOffhandItem().is(Items.OAK_PLANKS));
                }
            });
            return offhand.get();
        }, 200);
        ctx.check("swapHands moved item to offhand (server)", offhand::get);
        ctx.run(() -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    sp.getInventory().clearContent();
                    sp.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
                    sp.getInventory().setSelectedSlot(0);
                }
            });
        });
        ctx.await("main hand planks ready", () -> ctx.bot().getLocalPlayer()
                .getMainHandItem().is(Items.OAK_PLANKS), 200);
        ctx.run(() -> {
            if (!dropIssued.get()) {
                dropIssued.set(true);
                ctx.bot().actions().drop(0, false);
                ctx.server().execute(() -> {
                    ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                    if (sp != null) {
                        beforeCount.set(sp.getInventory().getItem(0).getCount());
                    }
                });
            }
        });
        ctx.await("drop reduced inventory (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    reduced.set(sp.getInventory().getItem(0).getCount() < beforeCount.get());
                }
            });
            return reduced.get();
        }, 200);
        ctx.check("drop reduced inventory (server)", reduced::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void placeMine(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean placed = new AtomicBoolean();
        AtomicBoolean placedServer = new AtomicBoolean();
        AtomicBoolean mined = new AtomicBoolean();
        AtomicBoolean minedServer = new AtomicBoolean();
        AtomicInteger breakCount = new AtomicInteger();
        AtomicBoolean listenerFired = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> {
            pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(2, 0, 0));
            MockplayerApi.listen(new BotListener() {
                @Override
                public void onBreakBlock(Bot b, BlockPos p) {
                    breakCount.incrementAndGet();
                }
            });
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                if (sp != null) {
                    sp.getInventory().clearContent();
                }
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "give " + BOT + " minecraft:dirt 1");
                ctx.server().getLevel(Level.OVERWORLD).setBlock(pos.get(), Blocks.AIR.defaultBlockState(), 3);
            });
        });
        ctx.await("main hand dirt ready", () -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            return sp != null && !SuitesSupport.isAwaitingPosition(sp)
                    && ctx.bot().getLocalPlayer().getMainHandItem().is(Items.DIRT);
        }, 400);
        ctx.run(() -> {
            if (!placed.get()) {
                placed.set(true);
                ctx.bot().actions().lookAt(Vec3.atCenterOf(pos.get()));
                ctx.bot().actions().placeBlock(pos.get(), Direction.UP);
            }
        });
        ctx.await("placeBlock placed dirt (server)", () -> {
            ctx.server().execute(() -> placedServer.set(ctx.server().getLevel(Level.OVERWORLD)
                    .getBlockState(pos.get()).is(Blocks.DIRT)));
            return placedServer.get();
        }, 400);
        ctx.check("placeBlock placed dirt (server)", placedServer::get);
        ctx.run(() -> {
            if (!mined.get()) {
                mined.set(true);
                ctx.bot().actions().lookAt(Vec3.atCenterOf(pos.get()));
                ctx.bot().actions().mineBlock(pos.get());
            }
        });
        ctx.await("mineBlock broke dirt (server)", () -> {
            ctx.server().execute(() -> minedServer.set(ctx.server().getLevel(Level.OVERWORLD)
                    .getBlockState(pos.get()).isAir()));
            return minedServer.get();
        }, 400);
        ctx.check("mineBlock broke dirt (server)", minedServer::get);
        ctx.await("BotListener onBreakBlock fired", () -> {
            listenerFired.set(breakCount.get() > 0);
            return listenerFired.get();
        }, 100);
        ctx.check("BotListener onBreakBlock fired", listenerFired::get);
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void containerApi(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean opened = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(1, 0, 0)));
        SuitesSupport.placeBlockServer(ctx, pos::get, Blocks.CHEST);
        SuitesSupport.awaitBlockVisible(ctx, pos::get, Blocks.CHEST, 600);
        ctx.await("server player interactable", () -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            return sp != null && !SuitesSupport.isAwaitingPosition(sp);
        }, 200);
        ctx.run(() -> {
            if (!opened.get()) {
                opened.set(true);
                SuitesSupport.openBlock(ctx, pos.get());
            }
        });
        ctx.await("container open", () -> ctx.bot().getContainer().isPresent(), 200);
        ctx.check("getTitle non-empty", () -> !ctx.bot().getContainer()
                .get().getTitle().getString().isEmpty());
        ctx.check("getSize > 0", () -> ctx.bot().getContainer().get().getSize() > 0);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c ->
                c.setSlot(0, new ItemStack(Items.STONE))));
        ctx.check("setSlot slot0 stone (client)", () -> ctx.bot().getContainer()
                .map(c -> c.getSlot(0).is(Items.STONE)).orElse(false));
        ctx.run(() -> MockplayerApi.bots().removeBot(BOT, "command"));
    }

    private void managerApi(TestContext ctx) {
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        ctx.check("getBot found", () -> MockplayerApi.bots().getBot(BOT).isPresent());
        ctx.check("getBots contains", () -> MockplayerApi.bots().getBots().stream()
                .anyMatch(b -> BOT.equals(b.getName())));
        ctx.check("getBots(owner=command) contains", () -> MockplayerApi.bots()
                .getBots("command").stream().anyMatch(b -> BOT.equals(b.getName())));
        ctx.check("allBots contains", () -> MockplayerApi.allBots().stream()
                .anyMatch(b -> BOT.equals(b.getName())));
        ctx.check("removeBot owner ok", () -> MockplayerApi.bots()
                .removeBot(BOT, "command") == RemoveResult.REMOVED);
        ctx.check("removeBot not found", () -> MockplayerApi.bots()
                .removeBot(BOT, "command") == RemoveResult.NOT_FOUND);
    }
}
