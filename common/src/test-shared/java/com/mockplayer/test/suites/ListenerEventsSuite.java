package com.mockplayer.test.suites;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.api.event.BotListener;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.SimpleContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * listener-events：BotListener 全事件真实触发 + 计数强断言。
 * listener 在套件 before 注册一次，counts 跨用例共享（同一 suite 实例）。
 */
public class ListenerEventsSuite extends TestSuite {

        private int botSeq;
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private volatile Bot damageBot;
    private volatile Bot attackedBot;
    private volatile Bot healthBot;
    private volatile float damageAmount;
    private volatile DamageSource damageSource;
    private volatile DamageSource attackedSource;
    private volatile float attackedAmount;
    private volatile float healthOld;
    private volatile float healthNew;

    private final BotListener listener = new BotListener() {
        @Override public void onSpawned(Bot b) { counts.merge("onSpawned", 1, Integer::sum); }
        @Override public void onPlayReady(Bot b) { counts.merge("onPlayReady", 1, Integer::sum); }
        @Override public void onDisconnected(Bot b, net.minecraft.network.DisconnectionDetails d) { counts.merge("onDisconnected", 1, Integer::sum); }
        @Override public void onRespawn(Bot b) { counts.merge("onRespawn", 1, Integer::sum); }
        @Override public void onDimensionChange(Bot b, net.minecraft.resources.ResourceKey<Level> f, net.minecraft.resources.ResourceKey<Level> t) { counts.merge("onDimensionChange", 1, Integer::sum); }
        @Override public void onChat(Bot b, Component m) { counts.merge("onChat", 1, Integer::sum); }
        @Override public void onDamage(Bot b, DamageSource s, float a) { damageBot = b; damageSource = s; damageAmount = a; counts.merge("onDamage", 1, Integer::sum); }
        @Override public void onDeath(Bot b, Component d) { counts.merge("onDeath", 1, Integer::sum); }
        @Override public void onHealthChanged(Bot b, float o, float n) { healthBot = b; healthOld = o; healthNew = n; counts.merge("onHealthChanged", 1, Integer::sum); }
        @Override public void onAttackEntity(Bot b, Entity t) { counts.merge("onAttackEntity", 1, Integer::sum); }
        @Override public void onEntityAttacked(Bot b, DamageSource s, float a) { attackedBot = b; attackedSource = s; attackedAmount = a; counts.merge("onEntityAttacked", 1, Integer::sum); }
        @Override public void onInteractBlock(Bot b, BlockPos p, Direction s) { counts.merge("onInteractBlock", 1, Integer::sum); }
        @Override public void onPlaceBlock(Bot b, BlockPos p) { counts.merge("onPlaceBlock", 1, Integer::sum); }
        @Override public void onBreakBlock(Bot b, BlockPos p) { counts.merge("onBreakBlock", 1, Integer::sum); }
        @Override public void onUseItem(Bot b, InteractionHand h, ItemStack s) { counts.merge("onUseItem", 1, Integer::sum); }
        @Override public void onInteractEntity(Bot b, Entity t) { counts.merge("onInteractEntity", 1, Integer::sum); }
        @Override public void onContainerOpened(Bot b, MenuType<?> t, int c, Component ti) { counts.merge("onContainerOpened", 1, Integer::sum); }
        @Override public void onContainerSlotChanged(Bot b, int c, int s, ItemStack st) { counts.merge("onContainerSlotChanged", 1, Integer::sum); }
        @Override public void onContainerClosed(Bot b, int c) { counts.merge("onContainerClosed", 1, Integer::sum); }
        @Override public void onMerchantOffersUpdated(Bot b, net.minecraft.world.item.trading.MerchantOffers o) { counts.merge("onMerchantOffersUpdated", 1, Integer::sum); }
        @Override public void onPlayerJoined(Bot b, com.mojang.authlib.GameProfile p) { counts.merge("onPlayerJoined", 1, Integer::sum); }
        @Override public void onPlayerLeft(Bot b, com.mojang.authlib.GameProfile p) { counts.merge("onPlayerLeft", 1, Integer::sum); }
        @Override public void onHeldSlotChanged(Bot b, int s) { counts.merge("onHeldSlotChanged", 1, Integer::sum); }
        @Override public void onItemCooldown(Bot b, net.minecraft.resources.Identifier i, int d) { counts.merge("onItemCooldown", 1, Integer::sum); }
        @Override public void onPickupItem(Bot b, ItemStack s) { counts.merge("onPickupItem", 1, Integer::sum); }
        @Override public void onDropItem(Bot b, ItemStack s) { counts.merge("onDropItem", 1, Integer::sum); }
        @Override public void onSwapHands(Bot b) { counts.merge("onSwapHands", 1, Integer::sum); }
        @Override public void onSneakToggle(Bot b, boolean s) { counts.merge("onSneakToggle", 1, Integer::sum); }
        @Override public void onSprintToggle(Bot b, boolean s) { counts.merge("onSprintToggle", 1, Integer::sum); }
        @Override public void onTick(Bot b) { counts.merge("onTick", 1, Integer::sum); }
        @Override public void onMove(Bot b) { counts.merge("onMove", 1, Integer::sum); }
    };

    public ListenerEventsSuite() {
        super("listener-events");
        test("生命周期/输入/槽位", this::lifecycleInputs);
        test("交互/聊天/方块", this::interactChatBlock);
        test("战斗伤害", this::combatDamage);
        test("容器事件", this::containerEvents);
        test("物品事件", this::itemEvents);
        test("交易/换维/复活/断开", this::miscEvents);
    }

    @Override
    public void before() {
        counts.clear();
        MockplayerApi.listen(listener);
    }

    private int count(String name) {
        return counts.getOrDefault(name, 0);
    }

    private void lifecycleInputs(TestContext ctx) {
        ctx.setBotName("tbot-le" + (++botSeq));
        SuitesSupport.createBotAndWaitPlaying(ctx, ctx.botName());
        ctx.await("lifecycle events", () -> count("onSpawned") >= 1 && count("onPlayReady") >= 1
                && count("onPlayerJoined") >= 1, 100);
        ctx.check("onSpawned", () -> count("onSpawned") >= 1);
        ctx.check("onPlayReady", () -> count("onPlayReady") >= 1);
        ctx.check("onPlayerJoined", () -> count("onPlayerJoined") >= 1);
        ctx.await("onTick", () -> count("onTick") >= 10, 100);
        ctx.check("onTick", () -> count("onTick") >= 10);
        ctx.run(() -> ctx.bot().actions().setForward(0.5F));
        ctx.await("onMove", () -> count("onMove") >= 1, 60);
        ctx.check("onMove", () -> count("onMove") >= 1);
        ctx.run(() -> {
            ctx.bot().actions().stop();
            ctx.bot().actions().setSneak(true);
            ctx.bot().actions().setSprint(true);
        });
        ctx.await("onSneakToggle/onSprintToggle",
                () -> count("onSneakToggle") >= 1 && count("onSprintToggle") >= 1, 30);
        ctx.check("onSneakToggle", () -> count("onSneakToggle") >= 1);
        ctx.check("onSprintToggle", () -> count("onSprintToggle") >= 1);
        ItemStack[] hotbarBefore = new ItemStack[9];
        ctx.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                for (int i = 0; i < hotbarBefore.length; i++) {
                    hotbarBefore[i] = mc.player.getInventory().getItem(i).copy();
                }
            }
            ctx.bot().actions().setSelectedSlot(1);
        });
        ctx.await("onHeldSlotChanged", () -> count("onHeldSlotChanged") >= 1, 30);
        ctx.run(() -> ctx.bot().actions().swapHands());
        ctx.await("onSwapHands", () -> count("onSwapHands") >= 1, 30);
        ctx.check("onHeldSlotChanged", () -> count("onHeldSlotChanged") >= 1);
        ctx.check("onSwapHands", () -> count("onSwapHands") >= 1);
        ctx.check("main hotbar isolated", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return false;
            }
            for (int i = 0; i < hotbarBefore.length; i++) {
                if (!ItemStack.matches(hotbarBefore[i], mc.player.getInventory().getItem(i))) {
                    return false;
                }
            }
            return true;
        });
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }

    private void interactChatBlock(TestContext ctx) {
        int[] wait = {0};
        AtomicBoolean placed = new AtomicBoolean();
        ctx.setBotName("tbot-le" + (++botSeq));
        SuitesSupport.createBotAndWaitPlaying(ctx, ctx.botName());
        ctx.run(() -> {
            ctx.server().execute(() -> {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:bread");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "setblock " + (int) (ctx.bot().getLocalPlayer().getX() + 3) + " "
                                + (int) ctx.bot().getLocalPlayer().getY() + " "
                                + (int) ctx.bot().getLocalPlayer().getZ() + " minecraft:dirt");
            });
        });
        ctx.await("interact wait 20", () -> ++wait[0] >= 20, 40);
        ctx.run(() -> ctx.bot().actions().use(InteractionHand.MAIN_HAND));
        ctx.await("interact wait 40", () -> ++wait[0] >= 40, 40);
        ctx.run(() -> ctx.bot().actions().use(
                ctx.bot().getLocalPlayer().blockPosition().offset(3, 0, 0), Direction.UP));
        ctx.await("onUseItem/onInteractBlock",
                () -> count("onUseItem") >= 1 && count("onInteractBlock") >= 1, 100);
        ctx.check("onUseItem", () -> count("onUseItem") >= 1);
        ctx.check("onInteractBlock", () -> count("onInteractBlock") >= 1);
        ctx.run(() -> ctx.bot().actions().chat("mockplayer-le-chat"));
        ctx.await("onChat", () -> count("onChat") >= 1, 100);
        ctx.check("onChat", () -> count("onChat") >= 1);
        ctx.run(() -> {
            ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack(),
                    "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:dirt"));
        });
        ctx.await("dirt in hand", () -> ctx.bot().getLocalPlayer().getMainHandItem().is(Items.DIRT), 200);
        ctx.run(() -> ctx.bot().actions().place(
                ctx.bot().getLocalPlayer().blockPosition().offset(4, 0, 0), Direction.UP));
        ctx.await("onPlaceBlock", () -> count("onPlaceBlock") >= 1, 100);
        ctx.run(() -> ctx.bot().actions().mine(
                ctx.bot().getLocalPlayer().blockPosition().offset(4, 0, 0)));
        ctx.await("onBreakBlock", () -> count("onBreakBlock") >= 1, 200);
        ctx.check("onPlaceBlock", () -> count("onPlaceBlock") >= 1);
        ctx.check("onBreakBlock", () -> count("onBreakBlock") >= 1);
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }

    private void combatDamage(TestContext ctx) {
        AtomicBoolean interacted = new AtomicBoolean();
        AtomicBoolean attacked = new AtomicBoolean();
        AtomicBoolean killed = new AtomicBoolean();
        ctx.setBotName("tbot-le" + (++botSeq));
        SuitesSupport.createBotAndWaitPlaying(ctx, ctx.botName());
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "time set midnight");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:zombie %.2f %.2f %.2f", sp.getX() + 2.0, sp.getY(), sp.getZ()));
            }
        }));
        ctx.await("zombie near", () -> {
            Entity zombie = ctx.bot().getEntitiesNear(4).stream()
                    .filter(e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getType()).getPath().equals("zombie"))
                    .findFirst().orElse(null);
            if (zombie != null && !interacted.get()) {
                interacted.set(true);
                ctx.bot().actions().lookAt(zombie);
                ctx.bot().actions().use(zombie);
            }
            if (zombie != null && !attacked.get()) {
                attacked.set(true);
                ctx.bot().actions().lookAt(zombie);
                ctx.bot().actions().attack(zombie);
            }
            return count("onDamage") >= 1;
        }, 360);
        ctx.run(() -> {
            if (!killed.get()) {
                killed.set(true);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "kill @e[type=minecraft:zombie]"));
            }
        });
        ctx.await("entity attacked events", () -> count("onEntityAttacked") >= 1
                && count("onHealthChanged") >= 1, 100);
        ctx.check("onInteractEntity", () -> count("onInteractEntity") >= 1);
        ctx.check("onAttackEntity", () -> count("onAttackEntity") >= 1);
        ctx.check("onEntityAttacked", () -> count("onEntityAttacked") >= 1);
        ctx.check("onDamage", () -> count("onDamage") >= 1);
        ctx.check("onHealthChanged", () -> count("onHealthChanged") >= 1);
        ctx.check("damage callback belongs to bot", () -> damageBot != null
                && damageBot.getLocalPlayer() != Minecraft.getInstance().player
                && damageSource != null && damageAmount > 0.0F);
        ctx.check("entity-attacked callback belongs to bot", () -> attackedBot != null
                && attackedBot.getLocalPlayer() != Minecraft.getInstance().player
                && attackedSource != null && attackedAmount > 0.0F);
        ctx.check("health callback belongs to bot", () -> healthBot != null && healthNew < healthOld);
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }

    private void containerEvents(TestContext ctx) {
        AtomicReference<BlockPos> pos = new AtomicReference<>();
        AtomicBoolean opened = new AtomicBoolean();
        int[] wait = {0};
        ctx.setBotName("tbot-le" + (++botSeq));
        SuitesSupport.createBotAndWaitPlaying(ctx, ctx.botName());
        ctx.run(() -> {
            pos.set(ctx.bot().getLocalPlayer().blockPosition().offset(2, 0, 0));
            ctx.server().execute(() -> {
                ctx.server().getLevel(Level.OVERWORLD).setBlock(pos.get(),
                        Blocks.CHEST.defaultBlockState(), 3);
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:stone");
            });
        });
        ctx.await("container wait 30", () -> ++wait[0] >= 30, 60);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                sp.openMenu(new net.minecraft.world.SimpleMenuProvider(
                        (id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv,
                                new SimpleContainer(27), 3),
                        Component.literal("test")));
            }
        }));
        ctx.await("container open", () -> ctx.bot().getContainer().isPresent(), 100);
        ctx.await("container wait 60", () -> ++wait[0] >= 60, 40);
        ctx.run(() -> ctx.bot().getContainer().ifPresent(c ->
                c.click(54, 0, ContainerInput.PICKUP)));
        ctx.await("container wait 90", () -> ++wait[0] >= 90, 40);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null && sp.containerMenu != sp.inventoryMenu) {
                sp.closeContainer();
            }
        }));
        ctx.await("container events", () -> count("onContainerOpened") >= 1
                && count("onContainerSlotChanged") >= 1
                && count("onContainerClosed") >= 1, 100);
        ctx.check("onContainerOpened", () -> count("onContainerOpened") >= 1);
        ctx.check("onContainerSlotChanged", () -> count("onContainerSlotChanged") >= 1);
        ctx.check("onContainerClosed", () -> count("onContainerClosed") >= 1);
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }

    private void itemEvents(TestContext ctx) {
        AtomicBoolean used = new AtomicBoolean();
        AtomicBoolean cooldown = new AtomicBoolean();
        AtomicBoolean dropped = new AtomicBoolean();
        AtomicBoolean picked = new AtomicBoolean();
        ctx.setBotName("tbot-le" + (++botSeq));
        SuitesSupport.createBotAndWaitPlaying(ctx, ctx.botName());
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(),
                "item replace entity " + ctx.botName() + " weapon.mainhand with minecraft:ender_pearl")));
        ctx.await("ender pearl in hand", () -> ctx.bot().getLocalPlayer()
                .getMainHandItem().is(Items.ENDER_PEARL), 200);
        ctx.run(() -> {
            if (!used.get()) {
                used.set(true);
                ctx.bot().actions().use(InteractionHand.MAIN_HAND);
            }
        });
        ctx.await("onItemCooldown", () -> {
            if (count("onItemCooldown") >= 1 && !cooldown.get()) {
                cooldown.set(true);
                ctx.bot().actions().drop();
            }
            return count("onDropItem") >= 1;
        }, 200);
        ctx.check("onItemCooldown", () -> count("onItemCooldown") >= 1);
        ctx.check("onDropItem", () -> count("onDropItem") >= 1);
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format("summon minecraft:item %.2f %.2f %.2f {Item:{id:\"minecraft:diamond\",count:1}}",
                                sp.getX(), sp.getY(), sp.getZ()));
            }
        }));
        ctx.run(() -> ctx.bot().actions().setForward(0.1F));
        ctx.await("onPickupItem", () -> {
            if (count("onPickupItem") >= 1) {
                picked.set(true);
                return true;
            }
            return false;
        }, 200);
        ctx.run(() -> ctx.bot().actions().setForward(0));
        ctx.check("onPickupItem", () -> picked.get());
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }

    private void miscEvents(TestContext ctx) {
        AtomicBoolean interacted = new AtomicBoolean();
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean le2Removed = new AtomicBoolean();
        AtomicReference<Bot> bot2 = new AtomicReference<>();
        int[] wait = {0};
        ctx.setBotName("tbot-le" + (++botSeq));
        SuitesSupport.createBotAndWaitPlaying(ctx, ctx.botName());
        ctx.run(() -> ctx.server().execute(() -> {
            ServerPlayer sp = ctx.server().getPlayerList().getPlayerByName(ctx.botName());
            if (sp != null) {
                double x = sp.getX() + 5.0;
                double y = sp.getY();
                double z = sp.getZ();
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        "kill @e[type=minecraft:villager]");
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format("tp %s %.2f %.2f %.2f", ctx.botName(), x, y, z));
                ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(),
                        String.format(
                                "summon minecraft:villager %.2f %.2f %.2f {NoAI:1b,Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:1},sell:{id:\"minecraft:diamond\",count:1},maxUses:99,xp:1}]}}",
                                x + 1.0, y, z));
            }
        }));
        ctx.await("onMerchantOffersUpdated", () -> {
            Entity villager = ctx.bot().getEntitiesNear(64).stream()
                    .filter(e -> e instanceof Villager)
                    .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(ctx.bot().getLocalPlayer())))
                    .orElse(null);
            if (villager != null && !interacted.get()) {
                interacted.set(true);
                ctx.bot().actions().lookAt(villager);
                ctx.bot().actions().use(villager);
            }
            return count("onMerchantOffersUpdated") >= 1;
        }, 200);
        ctx.check("onMerchantOffersUpdated", () -> count("onMerchantOffersUpdated") >= 1);
        ctx.run(() -> ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                ctx.server().createCommandSourceStack(),
                "execute in minecraft:the_nether run tp " + ctx.botName() + " 0 64 0")));
        ctx.await("onDimensionChange", () -> count("onDimensionChange") >= 1, 200);
        ctx.check("onDimensionChange", () -> count("onDimensionChange") >= 1);
        ctx.run(() -> {
            if (!killed.get()) {
                killed.set(true);
                ctx.server().execute(() -> ctx.server().getCommands().performPrefixedCommand(
                        ctx.server().createCommandSourceStack(), "kill " + ctx.botName()));
            }
        });
        ctx.await("onDeath", () -> count("onDeath") >= 1, 200);
        ctx.run(() -> {
            if (count("onDeath") >= 1) {
                ctx.bot().actions().respawn();
            }
        });
        ctx.await("onRespawn", () -> count("onRespawn") >= 1, 200);
        ctx.check("onDeath", () -> count("onDeath") >= 1);
        ctx.check("onRespawn", () -> count("onRespawn") >= 1);
        ctx.run(() -> {
            bot2.set(MockplayerApi.bots().createBot(BotProfile.of("tbot-le2", "test")).orElse(null));
        });
        ctx.await("bot2 seen", () -> {
            Bot b2 = bot2.get();
            boolean seen = b2 != null && b2.getLifecycle() == BotLifecycle.PLAYING
                    && ctx.bot().getOnlinePlayers().stream()
                    .anyMatch(p -> p.getProfile().name().equals("tbot-le2"));
            if (seen && !le2Removed.get()) {
                le2Removed.set(true);
                MockplayerApi.bots().removeBot("tbot-le2", "command");
            }
            return count("onDisconnected") >= 1 && count("onPlayerLeft") >= 1;
        }, 600);
        ctx.check("onDisconnected", () -> count("onDisconnected") >= 1);
        ctx.check("onPlayerLeft", () -> count("onPlayerLeft") >= 1);
        ctx.run(() -> MockplayerApi.bots().removeBot(ctx.botName(), "command"));
    }
}


