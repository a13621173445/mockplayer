package com.mockplayer.config;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.utils.OptionUtils;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * YACL 配置界面（common 一次编写，Fabric ModMenu / NeoForge IConfigScreenFactory 共用）。
 *
 * 输入：MockplayerConfig.get() 当前配置实例
 * 输出：界面选项绑定同一实例，保存按钮把实例写入 config/mockplayer.json
 *
 * 仅当 YACL 在运行时存在时被构造（平台入口已做条件加载），缺失时用 MissingYaclScreen 兜底。
 */
public final class ModConfigScreen extends YACLScreen {

    /** 当前绑定的配置实例（保存时写入同一个实例，测试可直接断言）。 */
    private final ModConfig boundConfig;

    public ModConfigScreen(Screen parent) {
        super(buildYacl(MockplayerConfig.get()), parent);
        this.boundConfig = MockplayerConfig.get();
    }

    /** 测试/查询用：当前界面绑定的配置实例。 */
    public ModConfig config() {
        return this.boundConfig;
    }

    /** 构建 YACL 界面树（title + 通用分类 + 命令分类 + 保存函数）。 */
    private static YetAnotherConfigLib buildYacl(ModConfig cfg) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("config.mockplayer.title"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("config.mockplayer.category.general"))
                        .tooltip(Component.translatable("config.mockplayer.category.general.tooltip"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.logs"))
                                .option(intOption("chatHistoryLimit",
                                        ModConfig.DEFAULT_CHAT_HISTORY_LIMIT,
                                        ModConfig.MIN_CHAT_HISTORY_LIMIT, ModConfig.MAX_CHAT_HISTORY_LIMIT,
                                        cfg::getChatHistoryLimit, cfg::setChatHistoryLimit))
                                .option(intOption("soundLogLimit",
                                        ModConfig.DEFAULT_SOUND_LOG_LIMIT,
                                        ModConfig.MIN_SOUND_LOG_LIMIT, ModConfig.MAX_SOUND_LOG_LIMIT,
                                        cfg::getSoundLogLimit, cfg::setSoundLogLimit))
                                .option(intOption("particleLogLimit",
                                        ModConfig.DEFAULT_PARTICLE_LOG_LIMIT,
                                        ModConfig.MIN_PARTICLE_LOG_LIMIT, ModConfig.MAX_PARTICLE_LOG_LIMIT,
                                        cfg::getParticleLogLimit, cfg::setParticleLogLimit))
                                .option(intOption("eventCacheSize",
                                        ModConfig.DEFAULT_EVENT_CACHE_SIZE,
                                        ModConfig.MIN_EVENT_CACHE_SIZE, ModConfig.MAX_EVENT_CACHE_SIZE,
                                        cfg::getEventCacheSize, cfg::setEventCacheSize))
                                .option(intOption("eventSummaryMaxLength",
                                        ModConfig.DEFAULT_EVENT_SUMMARY_MAX_LENGTH,
                                        ModConfig.MIN_EVENT_SUMMARY_MAX_LENGTH, ModConfig.MAX_EVENT_SUMMARY_MAX_LENGTH,
                                        cfg::getEventSummaryMaxLength, cfg::setEventSummaryMaxLength))
                                .option(intOption("eventTickSampleInterval",
                                        ModConfig.DEFAULT_EVENT_TICK_SAMPLE_INTERVAL,
                                        ModConfig.MIN_EVENT_TICK_SAMPLE_INTERVAL, ModConfig.MAX_EVENT_TICK_SAMPLE_INTERVAL,
                                        cfg::getEventTickSampleInterval, cfg::setEventTickSampleInterval))
                                .option(doubleOption("eventMoveSampleDistance",
                                        ModConfig.DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE,
                                        ModConfig.MIN_EVENT_MOVE_SAMPLE_DISTANCE, ModConfig.MAX_EVENT_MOVE_SAMPLE_DISTANCE,
                                        cfg::getEventMoveSampleDistance, cfg::setEventMoveSampleDistance))
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("config.mockplayer.category.commands"))
                        .tooltip(Component.translatable("config.mockplayer.category.commands.tooltip"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable("config.mockplayer.group.commands"))
                                .option(stringOption(ModCommands.CONTROL,
                                        () -> cfg.getCommandName(ModCommands.CONTROL),
                                        value -> cfg.setCommandName(ModCommands.CONTROL, value)))
                                .option(stringOption(ModCommands.QUERY,
                                        () -> cfg.getCommandName(ModCommands.QUERY),
                                        value -> cfg.setCommandName(ModCommands.QUERY, value)))
                                .option(stringOption(ModCommands.NEWPLAYER,
                                        () -> cfg.getCommandName(ModCommands.NEWPLAYER),
                                        value -> cfg.setCommandName(ModCommands.NEWPLAYER, value)))
                                .option(stringOption(ModCommands.DELPLAYER,
                                        () -> cfg.getCommandName(ModCommands.DELPLAYER),
                                        value -> cfg.setCommandName(ModCommands.DELPLAYER, value)))
                                .option(stringOption(ModCommands.CONNECT,
                                        () -> cfg.getCommandName(ModCommands.CONNECT),
                                        value -> cfg.setCommandName(ModCommands.CONNECT, value)))
                                .option(resetButton())
                                .build())
                        .build())
                .save(() -> MockplayerConfig.save(cfg))
                .build();
    }

    /** 一键恢复默认按钮：全部选项恢复默认后保存（写文件 + 热重载）。 */
    private static ButtonOption resetButton() {
        return ButtonOption.createBuilder()
                .name(Component.translatable("config.mockplayer.reset_all"))
                .text(Component.translatable("config.mockplayer.reset_all"))
                .description(OptionDescription.of(Component.translatable("config.mockplayer.reset_all.tooltip")))
                .action(ModConfigScreen::resetAllAndSave)
                .build();
    }

    /** 恢复全部配置为默认并保存（按钮与测试共用同一路径）。 */
    public static void resetAllAndSave(YACLScreen screen) {
        OptionUtils.forEachOptions(screen.config, option -> {
            if (!(option instanceof ButtonOption)) {
                option.requestSetDefault();
            }
        });
        screen.finishOrSave();
    }

    /** int 字段选项：名称/描述/绑定/数字输入控件（带范围）。 */
    private static Option<Integer> intOption(String key, int fallback, int min, int max,
                                             Supplier<Integer> getter, Consumer<Integer> setter) {
        return Option.<Integer>createBuilder()
                .name(Component.translatable("config.mockplayer.option." + key))
                .description(OptionDescription.of(Component.translatable("config.mockplayer.option." + key + ".description")))
                .binding(fallback, getter, setter)
                .controller(option -> IntegerFieldControllerBuilder.create(option).range(min, max))
                .build();
    }

    /** double 字段选项：名称/描述/绑定/数字输入控件（带范围）。 */
    private static Option<Double> doubleOption(String key, double fallback, double min, double max,
                                               Supplier<Double> getter, Consumer<Double> setter) {
        return Option.<Double>createBuilder()
                .name(Component.translatable("config.mockplayer.option." + key))
                .description(OptionDescription.of(Component.translatable("config.mockplayer.option." + key + ".description")))
                .binding(fallback, getter, setter)
                .controller(option -> DoubleFieldControllerBuilder.create(option).range(min, max))
                .build();
    }

    /** 命令名字符串选项：名称/描述/绑定/文本输入（空串 = 禁用）。 */
    private static Option<String> stringOption(String key,
                                               Supplier<String> getter, Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(Component.translatable("config.mockplayer.command." + key))
                .description(OptionDescription.of(
                        Component.translatable("config.mockplayer.command." + key + ".description")))
                .binding(key, getter, setter)
                .controller(option -> StringControllerBuilder.create(option))
                .build();
    }
}
