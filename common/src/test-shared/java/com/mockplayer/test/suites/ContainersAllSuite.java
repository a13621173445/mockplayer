package com.mockplayer.test.suites;

import com.mockplayer.api.MockplayerApi;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * containers-all：19 种容器类型真实交互（放方块/马 → 打开 → 断言菜单 → give 物品 →
 * click 放入 → 服务端验证 → 取回）。每种容器一个独立用例，互不共享状态。
 */
public class ContainersAllSuite extends TestSuite {

    private static final String BOT = "tbot-call";

    private record Case(String name, Block block, String itemId, MenuType<?> menuType,
                        int hotbarSlot, int containerSlot, int resultSlot) {
    }

    private static final List<Case> CASES = List.of(
            new Case("hopper", Blocks.HOPPER, "minecraft:stone", MenuType.HOPPER, 32, 0, -1),
            new Case("dropper", Blocks.DROPPER, "minecraft:stone", MenuType.GENERIC_3x3, 36, 0, -1),
            new Case("barrel", Blocks.BARREL, "minecraft:stone", MenuType.GENERIC_9x3, 54, 0, -1),
            new Case("shulker_box", Blocks.SHULKER_BOX, "minecraft:stone", MenuType.SHULKER_BOX, 54, 0, -1),
            new Case("ender_chest", Blocks.ENDER_CHEST, "minecraft:stone", MenuType.GENERIC_9x3, 54, 0, -1),
            new Case("anvil", Blocks.ANVIL, "minecraft:stone", MenuType.ANVIL, 30, 0, 2),
            new Case("grindstone", Blocks.GRINDSTONE, "minecraft:diamond_sword", MenuType.GRINDSTONE, 30, 0, 2),
            new Case("stonecutter", Blocks.STONECUTTER, "minecraft:stone", MenuType.STONECUTTER, 29, 0, 1),
            new Case("blast_furnace", Blocks.BLAST_FURNACE, "minecraft:coal", MenuType.BLAST_FURNACE, 30, 1, 2),
            new Case("smoker", Blocks.SMOKER, "minecraft:coal", MenuType.SMOKER, 30, 1, 2),
            new Case("brewing_stand", Blocks.BREWING_STAND, "minecraft:glass_bottle", MenuType.BREWING_STAND, 32, 0, -1),
            new Case("lectern", Blocks.LECTERN, "minecraft:written_book", MenuType.LECTERN, 28, 0, -1),
            new Case("loom", Blocks.LOOM, "minecraft:white_banner", MenuType.LOOM, 31, 0, 3),
            new Case("cartography_table", Blocks.CARTOGRAPHY_TABLE, "minecraft:paper", MenuType.CARTOGRAPHY_TABLE, 30, 1, 2),
            new Case("smithing_table", Blocks.SMITHING_TABLE, "minecraft:iron_ingot", MenuType.SMITHING, 31, 2, 3),
            new Case("beacon", Blocks.BEACON, "minecraft:emerald", MenuType.BEACON, 28, 0, -1),
            new Case("crafter", Blocks.CRAFTER, "minecraft:stone", MenuType.CRAFTER_3x3, 36, 0, 45),
            new Case("trapped_chest", Blocks.TRAPPED_CHEST, "minecraft:stone", MenuType.GENERIC_9x3, 54, 0, -1),
            new Case("large_chest", Blocks.CHEST, "minecraft:stone", MenuType.GENERIC_9x6, 81, 0, -1),
            new Case("horse", null, "minecraft:saddle", null, 29, 0, -1));

    public ContainersAllSuite() {
        super("containers-all");
        test("containers-all start (" + CASES.size() + " types)", ctx -> ctx.checkNow(
                "containers-all start (" + CASES.size() + " types)", true));
        for (Case c : CASES) {
            test(c.name(), ctx -> handleCase(ctx, c));
        }
    }

    private void handleCase(TestContext ctx, Case c) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean opened = new AtomicBoolean();
        AtomicBoolean put = new AtomicBoolean();
        AtomicBoolean taken = new AtomicBoolean();
        AtomicBoolean serverPut = new AtomicBoolean();
        AtomicBoolean serverEmpty = new AtomicBoolean();
        SuitesSupport.createBotAndWaitPlaying(ctx, BOT);
        SuitesSupport.awaitChunkLoaded(ctx, 600);
        ctx.run(() -> pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0)));
        if ("horse".equals(c.name())) {
            horseCase(ctx, c, pos, opened);
        } else {
            blockCase(ctx, c, pos, opened);
        }
        ctx.await("open " + c.name(), () -> ctx.bot().getContainer().isPresent(), 600);
        ctx.check("open " + c.name(), () -> ctx.bot().getContainer().isPresent());
        ctx.check("menuType " + c.name() + " correct", () -> ctx.bot().getContainer()
                .map(cont -> cont.getMenuType() == c.menuType()).orElse(false));
        if ("lectern".equals(c.name())) {
            lecternCase(ctx, c, pos, serverPut);
        } else {
            normalCase(ctx, c, put, serverPut, taken, serverEmpty);
        }
        ctx.run(() -> {
            ctx.bot().getContainer().ifPresent(cont -> cont.close());
            if ("horse".equals(c.name())) {
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:horse]"));
            }
            MockplayerApi.bots().removeBot(BOT, "command");
        });
    }

    private void blockCase(TestContext ctx, Case c, AtomicReference<BlockPos> pos, AtomicBoolean opened) {
        if ("large_chest".equals(c.name())) {
            ctx.server(() -> {
                {
                    var cl = Blocks.CHEST.defaultBlockState()
                            .setValue(ChestBlock.FACING, Direction.NORTH)
                            .setValue(ChestBlock.TYPE, ChestType.LEFT);
                    var cr = Blocks.CHEST.defaultBlockState()
                            .setValue(ChestBlock.FACING, Direction.NORTH)
                            .setValue(ChestBlock.TYPE, ChestType.RIGHT);
                    ctx.server().getLevel(Level.OVERWORLD).setBlock(pos.get(), cl, 3);
                    ctx.server().getLevel(Level.OVERWORLD).setBlock(pos.get().east(), cr, 3);
                }
            });
        } else {
            SuitesSupport.placeBlockServer(ctx, pos::get, c.block());
        }
        if ("lectern".equals(c.name())) {
            // 讲台：服务端放书后再等客户端看到 HAS_BOOK
            ctx.await("lectern block visible", () -> pos.get() != null
                    && ctx.bot().getBlockState(pos.get()).is(c.block()), 600);
            ctx.run(() -> ctx.server().execute(() -> {
                BlockEntity be = ctx.server().getLevel(Level.OVERWORLD).getBlockEntity(pos.get());
                if (be instanceof LecternBlockEntity le) {
                    le.setBook(new ItemStack(Items.WRITABLE_BOOK));
                }
                var bs = ctx.server().getLevel(Level.OVERWORLD).getBlockState(pos.get());
                ctx.server().getLevel(Level.OVERWORLD).setBlock(pos.get(),
                        bs.setValue(LecternBlock.HAS_BOOK, true), 3);
            }));
            ctx.await("lectern has book", () -> pos.get() != null
                    && ctx.bot().getBlockState(pos.get()).hasProperty(LecternBlock.HAS_BOOK)
                    && ctx.bot().getBlockState(pos.get()).getValue(LecternBlock.HAS_BOOK), 600);
        } else {
            SuitesSupport.awaitBlockVisible(ctx, pos::get, c.block(), 600);
            if ("large_chest".equals(c.name())) {
                ctx.await("large chest merged", () -> pos.get() != null
                        && ctx.bot().getBlockState(pos.get()).hasProperty(ChestBlock.TYPE)
                        && ctx.bot().getBlockState(pos.get()).getValue(ChestBlock.TYPE) != ChestType.SINGLE, 600);
            }
        }
        ctx.run(() -> {
            if (!opened.get()) {
                opened.set(true);
                SuitesSupport.openBlock(ctx, pos.get());
            }
        });
    }

    private void horseCase(TestContext ctx, Case c, AtomicReference<BlockPos> pos, AtomicBoolean opened) {
        ctx.run(() -> ctx.server().execute(() -> {
            Vec3 hp = ctx.bot().getLocalPlayer().position().add(1.0, 0.0, 0.0);
            Horse horse = new Horse(EntityTypes.HORSE, ctx.server().getLevel(Level.OVERWORLD));
            horse.setPos(hp);
            horse.setTamed(true);
            horse.setNoAi(true);
            ctx.server().getLevel(Level.OVERWORLD).addFreshEntity(horse);
        }));
        ctx.await("client sees horse", () -> {
            var horses = ctx.bot().getLocalPlayer().level().getEntitiesOfClass(AbstractHorse.class,
                    new AABB(ctx.bot().getLocalPlayer().position().add(-8.0, -8.0, -8.0),
                            ctx.bot().getLocalPlayer().position().add(8.0, 12.0, 8.0)));
            return !horses.isEmpty();
        }, 600);
        ctx.run(() -> {
            if (!opened.get()) {
                opened.set(true);
                var horses = ctx.bot().getLocalPlayer().level().getEntitiesOfClass(AbstractHorse.class,
                        new AABB(ctx.bot().getLocalPlayer().position().add(-8.0, -8.0, -8.0),
                                ctx.bot().getLocalPlayer().position().add(8.0, 12.0, 8.0)));
                horses.stream().min(java.util.Comparator.comparingDouble(
                                h -> h.distanceToSqr(ctx.bot().getLocalPlayer())))
                        .ifPresent(horse -> ctx.bot().getLocalPlayer().connection.send(
                                new ServerboundInteractPacket(horse.getId(), InteractionHand.MAIN_HAND,
                                        Vec3.ZERO, true)));
            }
        });
    }

    private void normalCase(TestContext ctx, Case c, AtomicBoolean put, AtomicBoolean serverPut,
                            AtomicBoolean taken, AtomicBoolean serverEmpty) {
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
            if (sp != null) {
                sp.getInventory().clearContent();
            }
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                    "give " + BOT + " " + c.itemId() + " 1");
        }));
        Item item = BuiltInRegistries.ITEM.get(Identifier.tryParse(c.itemId())).get().value();
        ctx.await("client has " + c.name() + " item", () -> ctx.bot().getLocalPlayer()
                .getInventory().countItem(item) > 0, 400);
        ctx.run(() -> {
            if (!put.get()) {
                put.set(true);
                ctx.bot().getContainer().ifPresent(cont -> {
                    cont.click(c.hotbarSlot(), 0, ContainerInput.PICKUP);
                    cont.click(c.containerSlot(), 0, ContainerInput.PICKUP);
                });
            }
        });
        ctx.await("put into " + c.name() + " (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                boolean ok = sp != null
                        && (!sp.containerMenu.getSlot(c.containerSlot()).getItem().isEmpty()
                        || (c.resultSlot() >= 0 && !sp.containerMenu.getSlot(c.resultSlot()).getItem().isEmpty()));
                serverPut.set(ok);
            });
            return serverPut.get();
        }, 200);
        ctx.check("put into " + c.name() + " (server)", serverPut::get);
        ctx.run(() -> {
            if (!taken.get()) {
                taken.set(true);
                ctx.bot().getContainer().ifPresent(cont -> {
                    if (!cont.getSlot(c.containerSlot()).isEmpty()) {
                        cont.click(c.containerSlot(), 0, ContainerInput.PICKUP);
                    }
                    if (c.resultSlot() >= 0 && c.resultSlot() != c.containerSlot()
                            && !cont.getSlot(c.resultSlot()).isEmpty()) {
                        cont.click(c.resultSlot(), 0, ContainerInput.PICKUP);
                    }
                    cont.click(c.hotbarSlot(), 0, ContainerInput.PICKUP);
                });
            }
        });
        ctx.await("take back from " + c.name() + " (server)", () -> {
            ctx.server().execute(() -> {
                ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(BOT);
                boolean cEmpty = sp != null && sp.containerMenu.getSlot(c.containerSlot()).getItem().isEmpty();
                boolean rEmpty = c.resultSlot() < 0 || sp == null
                        || sp.containerMenu.getSlot(c.resultSlot()).getItem().isEmpty();
                serverEmpty.set(cEmpty && rEmpty);
            });
            return serverEmpty.get();
        }, 200);
        ctx.check("take back from " + c.name() + " (server)", serverEmpty::get);
    }

    private void lecternCase(TestContext ctx, Case c, AtomicReference<BlockPos> pos,
                             AtomicBoolean serverPut) {
        ctx.run(() -> ctx.bot().getContainer().ifPresent(cont -> cont.clickButton(3)));
        ctx.await("put into lectern (server)", () -> {
            ctx.server().execute(() -> {
                var state = ctx.server().getLevel(Level.OVERWORLD)
                        .getBlockState(pos.get());
                serverPut.set(state.hasProperty(LecternBlock.HAS_BOOK)
                        && !state.getValue(LecternBlock.HAS_BOOK));
            });
            return serverPut.get();
        }, 200);
        ctx.check("put into lectern (server)", serverPut::get);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(cont -> cont.clickButton(3)));
        ctx.await("take back from lectern (server)", () -> ctx.bot().getLocalPlayer()
                .getInventory().countItem(Items.WRITABLE_BOOK) > 0, 200);
        ctx.check("take back from lectern (server)", () -> ctx.bot().getLocalPlayer()
                .getInventory().countItem(Items.WRITABLE_BOOK) > 0);
    }
}
