package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.container.BotContainer;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * /control 命令集的执行逻辑与 Tab 补全（平台无关）——只包含「动作」命令。
 *
 * 动作命令全部走 {@link com.mockplayer.api.action.BotActions}（真实网络包路径）；
 * 查询命令已迁移到 {@link QueryCommands}（/query 顶层命令，语义分离）。
 * 所有反馈消息走语言文件（en_us / zh_cn），颜色在代码端用 ChatFormatting 设置。
 *
 * 设计约束（主人拍板 2026-08-08）：
 * - 不做客户端全局身份切换（不碰 Minecraft.player/gameMode/level）
 * - 全部参数可 Tab 补全
 */
public class ControlCommands {

    /** 全部动作命令（与 buildControlTree 的 player 子命令同步；/control help 与测试共用）。 */
    public static final List<String> ACTIONS = List.of(
            "move", "stop", "sneak", "unsneak", "sprint", "unsprint", "jump",
            "look", "lookAt", "turn", "attack", "stab", "sustainedAttack", "sustainedUse",
            "stopSustained", "interact", "useItem", "releaseUsingItem", "useItemOn",
            "placeBlock", "mineBlock", "attackBlock", "hotbar", "drop", "swapHands",
            "mount", "dismount", "close", "click", "button", "trade", "setSlot",
            "chat", "command", "wakeUp", "respawn", "editBook", "editSign", "setBeacon",
            "renameItem", "pickItemFromBlock", "help");

    private ControlCommands() {
    }

    /** /control help：列出全部动作命令（i18n；ACTIONS 与树同步，测试防漂移）。 */
    public static Component help(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        MutableComponent out = info("commands.mockplayer.control.help.header", playerName(name));
        for (String action : ACTIONS) {
            out.append(Component.literal("\n")).append(info(
                    "commands.mockplayer.control.help.entry",
                    Component.translatable("commands.mockplayer.control.action." + action)));
        }
        return out;
    }

    // ===== 通用辅助 =====

    private static MutableComponent playerName(String name) {
        return CommandSupport.playerName(name);
    }

    private static Component fail(String key, Object... args) {
        return CommandSupport.fail(key, args);
    }

    private static MutableComponent info(String key, Object... args) {
        return CommandSupport.info(key, args);
    }

    /** 动作成功反馈：动作名翻译 key（commands.mockplayer.control.action.<x>）+ 假人名字。 */
    private static Component success(String actionKey, String name, Object... extra) {
        Object[] args = new Object[2 + extra.length];
        args[0] = playerName(name);
        args[1] = Component.translatable("commands.mockplayer.control.action." + actionKey);
        System.arraycopy(extra, 0, args, 2, extra.length);
        return Component.translatable("commands.mockplayer.control.success", args)
                .withStyle(CommandSupport.SUCCESS_COLOR);
    }

    /** 带内容的成功反馈（chat/command/renameItem）：模板含 %3$s 显示用户输入原文。 */
    private static Component contentSuccess(String actionKey, String name, Object content) {
        return Component.translatable("commands.mockplayer.control.success.content",
                playerName(name),
                Component.translatable("commands.mockplayer.control.action." + actionKey),
                content).withStyle(CommandSupport.SUCCESS_COLOR);
    }

    private static Bot findBot(String name) {
        return CommandSupport.findBot(name);
    }

    /** 取 bot + PLAYING 校验；失败返回反馈组件，null 表示成功拿到 bot。 */
    private static Component requirePlaying(String name) {
        return CommandSupport.requirePlaying(name,
                "commands.mockplayer.control.not_found",
                "commands.mockplayer.control.not_playing");
    }

    /** 实体名解析：null = 最近的非玩家实体；否则按名称匹配（精确，含 display name）。 */
    private static Entity resolveEntity(Bot bot, String target) {
        List<Entity> near = bot.getEntitiesNear(16.0);
        if (target == null || target.isBlank()) {
            return near.stream()
                    .filter(e -> !(e instanceof Player))
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot.getLocalPlayer())))
                    .orElse(null);
        }
        return near.stream()
                .filter(e -> target.equals(e.getName().getString())
                        || target.equals(e.getDisplayName().getString())
                        // 类型路径（如 "husk"）：语言无关，测试/脚本输入稳定
                        || target.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).getPath()))
                .findFirst()
                .orElse(null);
    }

    private static Direction resolveSide(String side) {
        return side == null ? null : Direction.byName(side.toLowerCase(java.util.Locale.ROOT));
    }

    private static InteractionHand resolveHand(String hand) {
        return hand == null ? InteractionHand.MAIN_HAND
                : switch (hand.toLowerCase(java.util.Locale.ROOT)) {
                    case "mainhand" -> InteractionHand.MAIN_HAND;
                    case "offhand" -> InteractionHand.OFF_HAND;
                    default -> null;
                };
    }

    private static Optional<Holder<MobEffect>> resolveEffect(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id))
                    .map(r -> (Holder<MobEffect>) r);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ===== 动作命令 =====

    public static Component move(String name, String dir) {
        if (!doMove(name, dir)) {
            Component blocked = requirePlaying(name);
            if (blocked != null) {
                return blocked;
            }
            return fail("commands.mockplayer.control.invalid_dir", dir == null ? "" : dir);
        }
        return switch (dir == null ? "" : dir.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.forward"));
            case "backward" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.backward"));
            case "left" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.left"));
            case "right" -> success("move", name, Component.translatable("commands.mockplayer.control.dir.right"));
            default -> fail("commands.mockplayer.control.invalid_dir", dir == null ? "" : dir);
        };
    }

    /** move 实际执行（返回是否成功，供命令树与测试共用）。 */
    static boolean doMove(String name, String dir) {
        Bot bot = findBot(name);
        if (bot == null || bot.getLifecycle() != BotLifecycle.PLAYING) {
            return false;
        }
        return switch (dir == null ? "" : dir.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> {
                bot.actions().setForward(1.0F);
                yield true;
            }
            case "backward" -> {
                bot.actions().setForward(-1.0F);
                yield true;
            }
            case "left" -> {
                bot.actions().setStrafe(-1.0F);
                yield true;
            }
            case "right" -> {
                bot.actions().setStrafe(1.0F);
                yield true;
            }
            default -> false;
        };
    }

    public static Component stop(String name) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail("commands.mockplayer.control.not_found", playerName(name));
        }
        if (bot.getLifecycle() != BotLifecycle.PLAYING) {
            return fail("commands.mockplayer.control.not_playing", playerName(name));
        }
        bot.actions().stop();
        return success("stop", name);
    }

    public static Component setSneak(String name, boolean on) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail("commands.mockplayer.control.not_found", playerName(name));
        }
        bot.actions().setSneak(on);
        return success(on ? "sneak" : "unsneak", name);
    }

    public static Component setSprint(String name, boolean on) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail("commands.mockplayer.control.not_found", playerName(name));
        }
        bot.actions().setSprint(on);
        return success(on ? "sprint" : "unsprint", name);
    }

    public static Component jump(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().jump();
        return success("jump", name);
    }

    public static Component look(String name, float yaw, float pitch) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().look(yaw, pitch);
        return success("look", name, yaw, pitch);
    }

    public static Component lookAt(String name, double x, double y, double z) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().lookAt(new Vec3(x, y, z));
        return success("lookAt", name, String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", x, y, z));
    }

    public static Component turn(String name, float yaw, float pitch) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().turn(yaw, pitch);
        return success("turn", name, yaw, pitch);
    }

    public static Component attack(String name, String target) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found", playerName(name), target == null ? "?" : target);
        }
        bot.actions().attack(entity);
        return success("attack", name, entity.getName());
    }

    public static Component stab(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().stab();
        return success("stab", name);
    }

    public static Component sustainedAttack(String name, String target) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found", playerName(name), target == null ? "?" : target);
        }
        bot.actions().sustainedAttack(entity);
        return success("sustainedAttack", name, entity.getName());
    }

    public static Component sustainedUse(String name, String target) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found", playerName(name), target == null ? "?" : target);
        }
        bot.actions().sustainedUse(entity);
        return success("sustainedUse", name, entity.getName());
    }

    public static Component stopSustained(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().stopSustained();
        return success("stopSustained", name);
    }

    public static Component interact(String name, String target) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found", playerName(name), target == null ? "?" : target);
        }
        bot.actions().interact(entity);
        return success("interact", name, entity.getName());
    }

    public static Component useItem(String name, String hand) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        InteractionHand h = resolveHand(hand);
        if (h == null) {
            return fail("commands.mockplayer.control.invalid_hand", hand == null ? "" : hand);
        }
        findBot(name).actions().useItem(h);
        return success("useItem", name, h == InteractionHand.MAIN_HAND
                ? Component.translatable("commands.mockplayer.control.hand.main")
                : Component.translatable("commands.mockplayer.control.hand.off"));
    }

    public static Component releaseUsingItem(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().releaseUsingItem();
        return success("releaseUsingItem", name);
    }

    public static Component useItemOn(String name, int x, int y, int z, String side) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Direction dir = resolveSide(side);
        if (dir == null) {
            return fail("commands.mockplayer.control.invalid_side", side == null ? "" : side);
        }
        findBot(name).actions().useItemOn(new BlockPos(x, y, z), dir);
        return success("useItemOn", name, x + " " + y + " " + z + " " + dir.getName());
    }

    public static Component placeBlock(String name, int x, int y, int z, String side) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Direction dir = resolveSide(side);
        if (dir == null) {
            return fail("commands.mockplayer.control.invalid_side", side == null ? "" : side);
        }
        findBot(name).actions().placeBlock(new BlockPos(x, y, z), dir);
        return success("placeBlock", name, x + " " + y + " " + z + " " + dir.getName());
    }

    public static Component mineBlock(String name, int x, int y, int z) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().mineBlock(new BlockPos(x, y, z));
        return success("mineBlock", name, x + " " + y + " " + z);
    }

    public static Component attackBlock(String name, int x, int y, int z) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().attackBlock(new BlockPos(x, y, z));
        return success("attackBlock", name, x + " " + y + " " + z);
    }

    public static Component hotbar(String name, int slot) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        int index = Math.max(0, Math.min(8, slot - 1));
        findBot(name).actions().setSelectedSlot(index);
        return success("hotbar", name, index + 1);
    }

    public static Component drop(String name, Integer slot, Boolean all) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        if (slot == null || slot < 0) {
            bot.actions().dropSelected();
        } else {
            bot.actions().drop(Math.min(8, slot), all != null && all);
        }
        return success("drop", name, slot == null ? "-" : slot);
    }

    public static Component swapHands(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().swapHands();
        return success("swapHands", name);
    }

    public static Component mount(String name, String target, Boolean onlyRideables) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        if (target == null || target.isBlank()) {
            // 自动模式：骑最近的可骑乘实体（或任意实体，由 onlyRideables 决定）
            bot.actions().mount(onlyRideables == null || onlyRideables);
            return success("mount", name);
        }
        Entity entity = resolveEntity(bot, target);
        if (entity == null) {
            return fail("commands.mockplayer.control.entity_not_found", playerName(name), target);
        }
        bot.actions().mount(entity);
        return success("mount", name, entity.getName());
    }

    public static Component dismount(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().dismount();
        return success("dismount", name);
    }

    /** 关闭假人当前打开的容器 GUI（原版 LocalPlayer.closeContainer 等价，发关闭包）。 */
    public static Component close(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        c.get().close();
        return success("close", name);
    }

    /** 点击容器槽位（拿取/放置/换位/拖拽，ContainerInput 枚举小写名）。 */
    public static Component click(String name, int slot, int button, String mode) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        net.minecraft.world.inventory.ContainerInput input = resolveClickMode(mode);
        if (input == null) {
            return fail("commands.mockplayer.control.invalid_click_mode", mode == null ? "" : mode);
        }
        BotContainer container = c.get();
        if (slot < -1 || slot >= container.getSize()) {
            // 越界点击会让原版 clicked 抛 IndexOutOfBounds（客户端崩溃），命令层先拦下
            return fail("commands.mockplayer.control.invalid_slot", slot);
        }
        container.click(slot, button, input);
        return success("click", name, slot, button, mode);
    }

    private static net.minecraft.world.inventory.ContainerInput resolveClickMode(String mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "pickup" -> net.minecraft.world.inventory.ContainerInput.PICKUP;
            case "quickmove", "quick_move" -> net.minecraft.world.inventory.ContainerInput.QUICK_MOVE;
            case "swap" -> net.minecraft.world.inventory.ContainerInput.SWAP;
            case "clone" -> net.minecraft.world.inventory.ContainerInput.CLONE;
            case "throw" -> net.minecraft.world.inventory.ContainerInput.THROW;
            case "quickcraft", "quick_craft" -> net.minecraft.world.inventory.ContainerInput.QUICK_CRAFT;
            case "pickupall", "pickup_all" -> net.minecraft.world.inventory.ContainerInput.PICKUP_ALL;
            default -> null;
        };
    }

    /** 点击菜单按钮（附魔台选附魔等）。 */
    public static Component button(String name, int buttonId) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        c.get().clickButton(buttonId);
        return success("button", name, buttonId);
    }

    /** 选择交易菜单中的一笔报价。 */
    public static Component trade(String name, int index) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        c.get().selectTrade(index);
        return success("trade", name, index);
    }

    /** 把假人主手物品乐观写入容器槽位（服务端回包为准）。 */
    public static Component setSlot(String name, int slot) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Bot bot = findBot(name);
        Optional<BotContainer> c = bot.getContainer();
        if (c.isEmpty()) {
            return info("commands.mockplayer.control.container.none", playerName(name));
        }
        if (bot.getLocalPlayer() == null) {
            return fail("commands.mockplayer.control.not_playing", playerName(name));
        }
        BotContainer container = c.get();
        if (slot < 0 || slot >= container.getSize()) {
            // 越界写入会让原版 setItem 抛 IndexOutOfBounds（客户端崩溃），命令层先拦下
            return fail("commands.mockplayer.control.invalid_slot", slot);
        }
        container.setSlot(slot, bot.getLocalPlayer().getMainHandItem());
        return success("setSlot", name, slot);
    }

    public static Component chat(String name, String message) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (message == null || message.isBlank()) {
            return fail("commands.mockplayer.control.blank_message");
        }
        findBot(name).actions().chat(message);
        return contentSuccess("chat", name, message);
    }

    public static Component command(String name, String commandLine) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (commandLine == null || commandLine.isBlank()) {
            return fail("commands.mockplayer.control.blank_message");
        }
        findBot(name).actions().sendCommand(commandLine);
        return contentSuccess("command", name, commandLine);
    }

    public static Component wakeUp(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().wakeUp();
        return success("wakeUp", name);
    }

    public static Component respawn(String name) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().respawn();
        return success("respawn", name);
    }

    public static Component editBook(String name, int slot, String page, String title) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        int index = Math.max(0, Math.min(8, slot - 1));
        findBot(name).actions().editBook(index,
                List.of(page == null ? "" : page),
                title == null || title.isBlank() ? Optional.empty() : Optional.of(title));
        return success("editBook", name, index + 1);
    }

    public static Component editSign(String name, int x, int y, int z, boolean front, String[] lines) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        String[] four = new String[4];
        for (int i = 0; i < 4; i++) {
            four[i] = lines != null && i < lines.length ? lines[i] : "";
        }
        findBot(name).actions().editSign(new BlockPos(x, y, z), front, four);
        return success("editSign", name, x + " " + y + " " + z);
    }

    public static Component setBeacon(String name, String primary, String secondary) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        Optional<Holder<MobEffect>> p = resolveEffect(primary);
        Optional<Holder<MobEffect>> s = resolveEffect(secondary);
        if (primary != null && !primary.isBlank() && p.isEmpty()) {
            return fail("commands.mockplayer.control.invalid_effect", primary);
        }
        if (secondary != null && !secondary.isBlank() && s.isEmpty()) {
            return fail("commands.mockplayer.control.invalid_effect", secondary);
        }
        findBot(name).actions().setBeacon(p, s);
        return success("setBeacon", name, primary == null ? "-" : primary);
    }

    public static Component renameItem(String name, String newName) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        if (newName == null || newName.isBlank()) {
            return fail("commands.mockplayer.control.blank_message");
        }
        findBot(name).actions().renameItem(newName);
        return contentSuccess("renameItem", name, newName);
    }

    public static Component pickItemFromBlock(String name, int x, int y, int z, boolean includeData) {
        Component blocked = requirePlaying(name);
        if (blocked != null) {
            return blocked;
        }
        findBot(name).actions().pickItemFromBlock(new BlockPos(x, y, z), includeData);
        return success("pickItemFromBlock", name, x + " " + y + " " + z);
    }

    // ===== Tab 补全 =====

    private static <S extends SharedSuggestionProvider> SuggestionProvider<S> fixed(String... values) {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(List.of(values), builder);
    }

    private static Bot botFromContext(CommandContext<?> ctx) {
        try {
            String name = StringArgumentType.getString(ctx, "player");
            return findBot(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 实体目标补全：只补全附近实体的类型路径（villager / minecart 等，语言无关）。
     * 不补全本地化显示名，避免中文环境出现「村民」「矿车」这类随语言变化的字符串。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> entityTypes() {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            if (bot == null) {
                return builder.buildFuture();
            }
            List<String> typeIds = bot.getEntitiesNear(16.0).stream()
                    .map(e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(e.getType()).getPath())
                    .distinct()
                    .toList();
            return SharedSuggestionProvider.suggest(typeIds, builder);
        };
    }

    /**
     * 数字补全通用实现：只建议当前参数的一个值，并带 i18n tooltip 说明语义，
     * 避免一个参数冒出多个候选（如 x 参数同时给 x/y/z）导致语义不清。
     */
    private static <S extends SharedSuggestionProvider> SuggestionProvider<S> playerNumber(
            String tooltipKey, java.util.function.ToDoubleFunction<net.minecraft.world.entity.player.Player> getter,
            String format) {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            if (bot == null || bot.getLocalPlayer() == null) {
                return builder.buildFuture();
            }
            builder.suggest(
                    String.format(java.util.Locale.ROOT, format, getter.applyAsDouble(bot.getLocalPlayer())),
                    Component.translatable(tooltipKey));
            return builder.buildFuture();
        };
    }

    /** X 坐标补全（只建议当前 X，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> coordX() {
        return playerNumber("commands.mockplayer.control.suggest.x", p -> p.getX(), "%.0f");
    }

    /** Y 坐标补全（只建议当前 Y，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> coordY() {
        return playerNumber("commands.mockplayer.control.suggest.y", p -> p.getY(), "%.0f");
    }

    /** Z 坐标补全（只建议当前 Z，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> coordZ() {
        return playerNumber("commands.mockplayer.control.suggest.z", p -> p.getZ(), "%.0f");
    }

    /** 偏航角补全（只建议当前 yaw，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> yawNow() {
        return playerNumber("commands.mockplayer.control.suggest.yaw", p -> p.getYRot(), "%.1f");
    }

    /** 俯仰角补全（只建议当前 pitch，tooltip 标明语义）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> pitchNow() {
        return playerNumber("commands.mockplayer.control.suggest.pitch", p -> p.getXRot(), "%.1f");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> directions() {
        return fixed("forward", "backward", "left", "right");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> sides() {
        return fixed("north", "south", "east", "west", "up", "down");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> hands() {
        return fixed("mainhand", "offhand");
    }

    /** mount 目标补全：附近实体类型 id + 自动模式关键字（真实候选）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> mountTargets() {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (bot != null) {
                bot.getEntitiesNear(16.0).stream()
                        .map(e -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .getKey(e.getType()).getPath())
                        .distinct()
                        .forEach(all::add);
            }
            all.add("rideables");
            all.add("anything");
            return SharedSuggestionProvider.suggest(new java.util.ArrayList<>(all), builder);
        };
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> oneAll() {
        return fixed("one", "all");
    }

    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> effectIds() {
        return (ctx, builder) -> {
            List<String> ids = BuiltInRegistries.MOB_EFFECT.keySet().stream()
                    .map(Identifier::toString)
                    .toList();
            return SharedSuggestionProvider.suggest(ids, builder);
        };
    }

    /** 容器槽位补全：当前打开容器 0..size-1（真实候选，不是示例）。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> containerSlots() {
        return (ctx, builder) -> {
            Bot bot = botFromContext(ctx);
            if (bot == null || bot.getContainer().isEmpty()) {
                return builder.buildFuture();
            }
            List<String> slots = new java.util.ArrayList<>();
            for (int i = 0; i < bot.getContainer().get().getSize(); i++) {
                slots.add(String.valueOf(i));
            }
            return SharedSuggestionProvider.suggest(slots, builder);
        };
    }

    /** 点击模式补全：ContainerInput 枚举小写名。 */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> clickModes() {
        return fixed("pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all");
    }

    /**
     * 嵌套命令补全：/control &lt;player&gt; command &lt;cmd&gt; 复用客户端命令树，
     * 与原版 /execute ... run 同款机制（CommandSuggestions 用本连接 dispatcher 解析补全）。
     * 让假人执行命令时也能逐级 Tab 补全（如 time set 1000 / gamemode creative）。
     */
    public static <S extends SharedSuggestionProvider> SuggestionProvider<S> nestedCommands() {
        return (ctx, builder) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.connection == null) {
                return builder.buildFuture();
            }
            var dispatcher = mc.player.connection.getCommands();
            // 只解析 command 参数剩余部分（如 "time "），避免依赖完整输入里的参数起点；
            // 子命令建议的 range 基于剩余串，最后平移到 builder 的绝对位置。
            String remaining = builder.getRemaining();
            StringReader reader = new StringReader(remaining);
            var parse = dispatcher.parse(reader, mc.player.connection.getSuggestionsProvider());
            // 原版 CommandSuggestions 传的是输入末尾位置（含尾随空格），不是 parse 后的 reader cursor；
            // 否则 findSuggestionContext 找不到已匹配节点，建议会回退成顶层命令列表。
            int cursor = remaining.length();
            int base = builder.getStart();
            return dispatcher.getCompletionSuggestions(parse, cursor).thenApply(inner -> {
                java.util.List<com.mojang.brigadier.suggestion.Suggestion> shifted = inner.getList().stream()
                        .map(s -> new com.mojang.brigadier.suggestion.Suggestion(
                                new com.mojang.brigadier.context.StringRange(
                                        base + s.getRange().getStart(), base + s.getRange().getEnd()),
                                s.getText(), s.getTooltip()))
                        .toList();
                String full = builder.getInput();
                return new com.mojang.brigadier.suggestion.Suggestions(
                        new com.mojang.brigadier.context.StringRange(0, full.length()), shifted);
            });
        };
    }

    // ===== 命令树构建（双端共用，平台只提供 literal/argument/反馈函数） =====

    public static <S extends SharedSuggestionProvider> LiteralArgumentBuilder<S> buildControlTree(
            CommandSupport.CommandFactory<S> f, String rootName) {
        LiteralArgumentBuilder<S> root = f.literal(rootName);

        RequiredArgumentBuilder<S, ?> player = f.argument("player", FakePlayerNameArgument.fakePlayerName())
                .suggests(CommandSupport.botNames());

        player.then(f.literal("move").then(f.argument("dir", StringArgumentType.word())
                .suggests(directions())
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), move(StringArgumentType.getString(ctx, "player"),
                            StringArgumentType.getString(ctx, "dir")));
                    return 1;
                })));
        player.then(f.literal("stop").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), stop(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("sneak").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), setSneak(StringArgumentType.getString(ctx, "player"), true));
            return 1;
        }));
        player.then(f.literal("unsneak").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), setSneak(StringArgumentType.getString(ctx, "player"), false));
            return 1;
        }));
        player.then(f.literal("sprint").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), setSprint(StringArgumentType.getString(ctx, "player"), true));
            return 1;
        }));
        player.then(f.literal("unsprint").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), setSprint(StringArgumentType.getString(ctx, "player"), false));
            return 1;
        }));
        player.then(f.literal("jump").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), jump(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));

        player.then(f.literal("look")
                .then(f.argument("yaw", FloatArgumentType.floatArg())
                        .suggests(yawNow())
                        .then(f.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
                                .suggests(pitchNow())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    f.sendFeedback(ctx.getSource(), look(name,
                                            FloatArgumentType.getFloat(ctx, "yaw"),
                                            FloatArgumentType.getFloat(ctx, "pitch")));
                                    return 1;
                                }))));
        player.then(f.literal("lookAt")
                .then(f.argument("x", DoubleArgumentType.doubleArg())
                        .suggests(coordX())
                        .then(f.argument("y", DoubleArgumentType.doubleArg())
                                .suggests(coordY())
                                .then(f.argument("z", DoubleArgumentType.doubleArg())
                                        .suggests(coordZ())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            f.sendFeedback(ctx.getSource(), lookAt(name,
                                                    DoubleArgumentType.getDouble(ctx, "x"),
                                                    DoubleArgumentType.getDouble(ctx, "y"),
                                                    DoubleArgumentType.getDouble(ctx, "z")));
                                            return 1;
                                        })))));
        player.then(f.literal("turn")
                .then(f.argument("yaw", FloatArgumentType.floatArg())
                        .then(f.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    f.sendFeedback(ctx.getSource(), turn(name,
                                            FloatArgumentType.getFloat(ctx, "yaw"),
                                            FloatArgumentType.getFloat(ctx, "pitch")));
                                    return 1;
                                }))));

        player.then(f.literal("attack")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), attack(StringArgumentType.getString(ctx, "player"), null));
                    return 1;
                })
                .then(f.argument("target", StringArgumentType.word())
                        .suggests(entityTypes())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), attack(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "target")));
                            return 1;
                        })));
        player.then(f.literal("stab").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), stab(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("sustainedAttack")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), sustainedAttack(StringArgumentType.getString(ctx, "player"), null));
                    return 1;
                })
                .then(f.argument("target", StringArgumentType.word())
                        .suggests(entityTypes())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), sustainedAttack(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "target")));
                            return 1;
                        })));
        player.then(f.literal("sustainedUse")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), sustainedUse(StringArgumentType.getString(ctx, "player"), null));
                    return 1;
                })
                .then(f.argument("target", StringArgumentType.word())
                        .suggests(entityTypes())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), sustainedUse(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "target")));
                            return 1;
                        })));
        player.then(f.literal("stopSustained").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), stopSustained(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));

        player.then(f.literal("interact")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), interact(StringArgumentType.getString(ctx, "player"), null));
                    return 1;
                })
                .then(f.argument("target", StringArgumentType.word())
                        .suggests(entityTypes())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), interact(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "target")));
                            return 1;
                        })));
        player.then(f.literal("useItem")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), useItem(StringArgumentType.getString(ctx, "player"), null));
                    return 1;
                })
                .then(f.argument("hand", StringArgumentType.word())
                        .suggests(hands())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), useItem(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "hand")));
                            return 1;
                        })));
        player.then(f.literal("releaseUsingItem").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), releaseUsingItem(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("useItemOn")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .then(f.argument("side", StringArgumentType.word())
                                                .suggests(sides())
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "player");
                                                    f.sendFeedback(ctx.getSource(), useItemOn(name,
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                            IntegerArgumentType.getInteger(ctx, "z"),
                                                            StringArgumentType.getString(ctx, "side")));
                                                    return 1;
                                                }))))));
        player.then(f.literal("placeBlock")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .then(f.argument("side", StringArgumentType.word())
                                                .suggests(sides())
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "player");
                                                    f.sendFeedback(ctx.getSource(), placeBlock(name,
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                            IntegerArgumentType.getInteger(ctx, "z"),
                                                            StringArgumentType.getString(ctx, "side")));
                                                    return 1;
                                                }))))));
        player.then(f.literal("mineBlock")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            f.sendFeedback(ctx.getSource(), mineBlock(name,
                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                    IntegerArgumentType.getInteger(ctx, "z")));
                                            return 1;
                                        })))));
        player.then(f.literal("attackBlock")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            f.sendFeedback(ctx.getSource(), attackBlock(name,
                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                    IntegerArgumentType.getInteger(ctx, "z")));
                                            return 1;
                                        })))));

        player.then(f.literal("hotbar")
                .then(f.argument("slot", IntegerArgumentType.integer(1, 9))
                        .suggests(fixed("1", "2", "3", "4", "5", "6", "7", "8", "9"))
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), hotbar(
                                    StringArgumentType.getString(ctx, "player"),
                                    IntegerArgumentType.getInteger(ctx, "slot")));
                            return 1;
                        })));
        player.then(f.literal("drop")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), drop(StringArgumentType.getString(ctx, "player"), null, false));
                    return 1;
                })
                .then(f.argument("slot", IntegerArgumentType.integer(0, 8))
                        .suggests(fixed("0", "1", "2", "3", "4", "5", "6", "7", "8"))
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), drop(
                                    StringArgumentType.getString(ctx, "player"),
                                    IntegerArgumentType.getInteger(ctx, "slot"), false));
                            return 1;
                        })
                        .then(f.argument("mode", StringArgumentType.word())
                                .suggests(oneAll())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    String mode = StringArgumentType.getString(ctx, "mode");
                                    f.sendFeedback(ctx.getSource(), drop(name,
                                            IntegerArgumentType.getInteger(ctx, "slot"),
                                            "all".equals(mode)));
                                    return 1;
                                }))));
        player.then(f.literal("swapHands").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), swapHands(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));

        player.then(f.literal("mount")
                .executes(ctx -> {
                    f.sendFeedback(ctx.getSource(), mount(StringArgumentType.getString(ctx, "player"), null, null));
                    return 1;
                })
                .then(f.argument("target", StringArgumentType.word())
                        .suggests(mountTargets())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            String target = StringArgumentType.getString(ctx, "target");
                            // 关键字：rideables/anything = 自动模式；否则 = 指定实体
                            if ("rideables".equalsIgnoreCase(target) || "anything".equalsIgnoreCase(target)) {
                                f.sendFeedback(ctx.getSource(), mount(name, null,
                                        "rideables".equalsIgnoreCase(target)));
                            } else {
                                f.sendFeedback(ctx.getSource(), mount(name, target, null));
                            }
                            return 1;
                        })));
        player.then(f.literal("dismount").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), dismount(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("close").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), close(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("click")
                .then(f.argument("slot", IntegerArgumentType.integer(0))
                        .suggests(containerSlots())
                        .then(f.argument("button", IntegerArgumentType.integer(0, 2))
                                .then(f.argument("mode", StringArgumentType.word())
                                        .suggests(clickModes())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            f.sendFeedback(ctx.getSource(), click(name,
                                                    IntegerArgumentType.getInteger(ctx, "slot"),
                                                    IntegerArgumentType.getInteger(ctx, "button"),
                                                    StringArgumentType.getString(ctx, "mode")));
                                            return 1;
                                        })))));
        player.then(f.literal("button")
                .then(f.argument("id", IntegerArgumentType.integer(0, 3))
                        .suggests(fixed("0", "1", "2", "3"))
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            f.sendFeedback(ctx.getSource(), button(name,
                                    IntegerArgumentType.getInteger(ctx, "id")));
                            return 1;
                        })));
        player.then(f.literal("trade")
                .then(f.argument("index", IntegerArgumentType.integer(0))
                        .suggests(fixed("0", "1", "2", "3", "4", "5"))
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            f.sendFeedback(ctx.getSource(), trade(name,
                                    IntegerArgumentType.getInteger(ctx, "index")));
                            return 1;
                        })));
        player.then(f.literal("setSlot")
                .then(f.argument("slot", IntegerArgumentType.integer(0))
                        .suggests(containerSlots())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            f.sendFeedback(ctx.getSource(), setSlot(name,
                                    IntegerArgumentType.getInteger(ctx, "slot")));
                            return 1;
                        })));

        player.then(f.literal("chat")
                .then(f.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), chat(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "message")));
                            return 1;
                        })));
        player.then(f.literal("command")
                .then(f.argument("command", StringArgumentType.greedyString())
                        .suggests(nestedCommands())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), command(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "command")));
                            return 1;
                        })));
        player.then(f.literal("wakeUp").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), wakeUp(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("respawn").executes(ctx -> {
            f.sendFeedback(ctx.getSource(), respawn(StringArgumentType.getString(ctx, "player")));
            return 1;
        }));
        player.then(f.literal("editBook")
                .then(f.argument("slot", IntegerArgumentType.integer(1, 9))
                        .suggests(fixed("1", "2", "3", "4", "5", "6", "7", "8", "9"))
                        .then(f.argument("page", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    f.sendFeedback(ctx.getSource(), editBook(name,
                                            IntegerArgumentType.getInteger(ctx, "slot"),
                                            StringArgumentType.getString(ctx, "page"), null));
                                    return 1;
                                })
                                .then(f.argument("title", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "player");
                                            f.sendFeedback(ctx.getSource(), editBook(name,
                                                    IntegerArgumentType.getInteger(ctx, "slot"),
                                                    StringArgumentType.getString(ctx, "page"),
                                                    StringArgumentType.getString(ctx, "title")));
                                            return 1;
                                        })))));
        player.then(f.literal("editSign")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .suggests(coordX())
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .suggests(coordY())
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .suggests(coordZ())
                                        .then(f.argument("side", StringArgumentType.word())
                                                .suggests(fixed("front", "back"))
                                                .then(f.argument("line1", StringArgumentType.word())
                                                        .then(f.argument("line2", StringArgumentType.word())
                                                                .then(f.argument("line3", StringArgumentType.word())
                                                                        .then(f.argument("line4", StringArgumentType.word())
                                                                                .executes(ctx -> {
                                                                                    String name = StringArgumentType.getString(ctx, "player");
                                                                                    boolean front = "front".equals(
                                                                                            StringArgumentType.getString(ctx, "side"));
                                                                                    f.sendFeedback(ctx.getSource(), editSign(name,
                                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                                                            IntegerArgumentType.getInteger(ctx, "z"),
                                                                                            front,
                                                                                            new String[]{
                                                                                                    StringArgumentType.getString(ctx, "line1"),
                                                                                                    StringArgumentType.getString(ctx, "line2"),
                                                                                                    StringArgumentType.getString(ctx, "line3"),
                                                                                                    StringArgumentType.getString(ctx, "line4")
                                                                                            }));
                                                                                    return 1;
                                                                                }))))))))));
        player.then(f.literal("setBeacon")
                .then(f.argument("primary", StringArgumentType.word())
                        .suggests(effectIds())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            f.sendFeedback(ctx.getSource(), setBeacon(name,
                                    StringArgumentType.getString(ctx, "primary"), null));
                            return 1;
                        })
                        .then(f.argument("secondary", StringArgumentType.word())
                                .suggests(effectIds())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "player");
                                    f.sendFeedback(ctx.getSource(), setBeacon(name,
                                            StringArgumentType.getString(ctx, "primary"),
                                            StringArgumentType.getString(ctx, "secondary")));
                                    return 1;
                                }))));
        player.then(f.literal("renameItem")
                .then(f.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            f.sendFeedback(ctx.getSource(), renameItem(
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "name")));
                            return 1;
                        })));
        player.then(f.literal("pickItemFromBlock")
                .then(f.argument("x", IntegerArgumentType.integer())
                        .suggests(coordX())
                        .then(f.argument("y", IntegerArgumentType.integer())
                                .suggests(coordY())
                                .then(f.argument("z", IntegerArgumentType.integer())
                                        .suggests(coordZ())
                                        .then(f.argument("includeData", StringArgumentType.word())
                                                .suggests(fixed("true", "false"))
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "player");
                                                    f.sendFeedback(ctx.getSource(), pickItemFromBlock(name,
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                            IntegerArgumentType.getInteger(ctx, "z"),
                                                            "true".equals(StringArgumentType.getString(ctx, "includeData"))));
                                                    return 1;
                                                }))))));

        player.then(f.literal("help")
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "player");
                    f.sendFeedback(ctx.getSource(), help(name));
                    return 1;
                }));

        root.then(player);
        return root;
    }

}
