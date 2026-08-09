package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.BotLifecycle;
import com.mockplayer.api.MockplayerApi;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * /control 与 /query 双端命令共用辅助：样式、反馈、查找、基础补全、命令树工厂。
 *
 * 拆分类原则（主人拍板 2026-08-09）：ControlCommands 只放动作、QueryCommands 只放查询，
 * 二者共用的「样式/反馈/查找/补全」集中在这里，不互相引用、不复制。
 */
public final class CommandSupport {

    /** 假人名字显示样式：水蓝色高亮（原版玩家名风格）。 */
    static final ChatFormatting NAME_COLOR = ChatFormatting.AQUA;
    /** 成功消息颜色。 */
    static final ChatFormatting SUCCESS_COLOR = ChatFormatting.GREEN;
    /** 失败消息颜色。 */
    static final ChatFormatting FAIL_COLOR = ChatFormatting.RED;
    /** 查询输出颜色。 */
    static final ChatFormatting INFO_COLOR = ChatFormatting.YELLOW;

    private CommandSupport() {
    }

    static MutableComponent playerName(String name) {
        return Component.literal(name).withStyle(NAME_COLOR);
    }

    static Component fail(String key, Object... args) {
        return Component.translatable(key, args).withStyle(FAIL_COLOR);
    }

    static MutableComponent info(String key, Object... args) {
        return Component.translatable(key, args).withStyle(INFO_COLOR);
    }

    static Bot findBot(String name) {
        return MockplayerApi.bots().getBot(name).orElse(null);
    }

    /** 取 bot + PLAYING 校验；失败返回反馈组件，null 表示成功拿到 bot。 */
    static Component requirePlaying(String name, String notFoundKey, String notPlayingKey) {
        Bot bot = findBot(name);
        if (bot == null) {
            return fail(notFoundKey, playerName(name));
        }
        if (bot.getLifecycle() != BotLifecycle.PLAYING || bot.getLocalPlayer() == null) {
            return fail(notPlayingKey, playerName(name));
        }
        return null;
    }

    /** 主玩家聊天栏推送（EventRecorder 实时反馈用；命令回调/事件派发都在主线程）。 */
    static void pushToChat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(message);
        }
    }

    /** 假人名字补全（/control 与 /query 的 player 参数共用）。 */
    static <S extends SharedSuggestionProvider> SuggestionProvider<S> botNames() {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(
                SessionManager.getInstance().getFakePlayerNames(), builder);
    }

    /** 固定候选补全。 */
    static <S extends SharedSuggestionProvider> SuggestionProvider<S> fixed(String... values) {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(List.of(values), builder);
    }

    /** 从命令上下文取已解析的 player 参数（找不到返回 null，不抛）。 */
    static Bot botFromContext(CommandContext<?> ctx) {
        try {
            String name = StringArgumentType.getString(ctx, "player");
            return findBot(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 数字补全通用实现：只建议当前参数的一个值，并带 i18n tooltip 说明语义，
     * 避免一个参数冒出多个候选（如 x 参数同时给 x/y/z）导致语义不清。
     */
    static <S extends SharedSuggestionProvider> SuggestionProvider<S> playerNumber(
            String tooltipKey, java.util.function.ToDoubleFunction<Player> getter, String format) {
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

    /** 双端命令树工厂：平台只提供 literal/argument/反馈函数。 */
    public interface CommandFactory<S> {
        LiteralArgumentBuilder<S> literal(String name);

        RequiredArgumentBuilder<S, ?> argument(String name, ArgumentType<?> type);

        void sendFeedback(S source, Component message);
    }
}
