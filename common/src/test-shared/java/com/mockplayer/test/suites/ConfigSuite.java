package com.mockplayer.test.suites;

import com.mockplayer.config.ModCommands;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.ModConfigIO;
import com.mockplayer.config.ModConfigScreen;
import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.MockplayerConfig;
import com.mockplayer.session.EventRecorder;
import com.mockplayer.session.FakePlayerState;
import com.mockplayer.test.framework.TestContext;
import com.mockplayer.test.framework.TestSuite;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

/**
 * config：配置 IO/手改/非法回退/命令名热重载/YACL 界面/一键恢复/消费者生效。
 * 全部操作同步（无 bot），用例间共享套件内临时配置文件（同一主题顺序执行）。
 */
public class ConfigSuite extends TestSuite {

    private Path cfgDir;
    private Path cfgFile;
    private ModConfigScreen cfgScreen;

    public ConfigSuite() {
        super("config");
        test("IO 与手改/非法值回退", this::ioAndFallback);
        test("YACL 界面与热重载", this::yaclScreen);
        test("恢复默认与配置消费者", this::resetAndConsumers);
    }

    private void ioAndFallback(TestContext ctx) {
        ctx.run(() -> {
            try {
                if (cfgDir == null) {
                    cfgDir = Files.createTempDirectory("mocktest-config");
                    cfgFile = cfgDir.resolve("mockplayer.json");
                }
            } catch (IOException e) {
                ctx.checkNow("create temp config dir", false, e.toString());
                return;
            }
            ModConfig defaults = new ModConfig();
            ctx.checkNow("missing file -> defaults", configEquals(defaults, ModConfigIO.load(cfgFile)));
            ModConfigIO.save(cfgFile, defaults);
            ctx.checkNow("save->load round trip", configEquals(defaults, ModConfigIO.load(cfgFile)));
            ModConfig guiChanged = new ModConfig();
            guiChanged.setGuiBlur(ModConfig.DEFAULT_GUI_BLUR + 3);
            guiChanged.setGuiOpacity(0.5F);
            ModConfigIO.save(cfgFile, guiChanged);
            ctx.checkNow("gui blur/opacity round trip", configEquals(guiChanged, ModConfigIO.load(cfgFile)));
            ModConfigIO.save(cfgFile, defaults);
        });
        ctx.run(() -> {
            writeConfigRaw("{\"chatHistoryLimit\": 77}");
            ModConfig loaded = ModConfigIO.load(cfgFile);
            ctx.checkNow("hand-edit applied", loaded.getChatHistoryLimit() == 77);
            ctx.checkNow("missing fields defaulted", loaded.getEventCacheSize() == ModConfig.DEFAULT_EVENT_CACHE_SIZE
                    && loaded.getEventMoveSampleDistance() == ModConfig.DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE);
        });
        ctx.run(() -> {
            writeConfigRaw("{\"chatHistoryLimit\":\"abc\",\"soundLogLimit\":-5,\"particleLogLimit\":99999,"
                    + "\"eventCacheSize\":3,\"eventSummaryMaxLength\":1000000,"
                    + "\"eventTickSampleInterval\":0,\"eventMoveSampleDistance\":999}");
            ctx.checkNow("invalid values fallback", configEquals(new ModConfig(), ModConfigIO.load(cfgFile)));
            writeConfigRaw("not a json object");
            ctx.checkNow("corrupt file fallback", configEquals(new ModConfig(), ModConfigIO.load(cfgFile)));
            writeConfigRaw("{\"commands\":{\"control\":\"ctrl\"}}");
            ModConfig renamed = ModConfigIO.load(cfgFile);
            ctx.checkNow("commands hand-edit applied", "ctrl".equals(renamed.getCommandName("control"))
                    && "query".equals(renamed.getCommandName("query")));
            writeConfigRaw("{\"commands\":{\"query\":\"\"}}");
            ctx.checkNow("commands empty disables", ModCommands.isDisabled(
                    ModConfigIO.load(cfgFile).getCommandName("query")));
            writeConfigRaw("{\"commands\":{\"control\":\"bad name!\"}}");
            ctx.checkNow("commands invalid falls back (not disabled)",
                    "control".equals(ModConfigIO.load(cfgFile).getCommandName("control")));
            writeConfigRaw("{\"commands\":{\"control\":\"x\",\"query\":\"x\"}}");
            ModConfig duplicated = ModConfigIO.load(cfgFile);
            ctx.checkNow("commands duplicate falls back all",
                    "control".equals(duplicated.getCommandName("control"))
                            && "query".equals(duplicated.getCommandName("query")));
            writeConfigRaw("{\"commands\":[]}");
            ctx.checkNow("commands wrong type falls back",
                    "control".equals(ModConfigIO.load(cfgFile).getCommandName("control")));
            ctx.checkNow("debug overlay default on", new ModConfig().isDebugOverlayEnabled());
            writeConfigRaw("{\"debugOverlayEnabled\": false}");
            ctx.checkNow("debug overlay hand-edit", !ModConfigIO.load(cfgFile).isDebugOverlayEnabled());
            writeConfigRaw("{\"debugOverlayEnabled\": \"yes\"}");
            ctx.checkNow("debug overlay non-boolean falls back",
                    ModConfigIO.load(cfgFile).isDebugOverlayEnabled());
        });
    }

    private void yaclScreen(TestContext ctx) {
        ctx.run(() -> {
            i18nConfigLangChecks(ctx);
            boolean yaclPresent;
            try {
                Class.forName("dev.isxander.yacl3.api.YetAnotherConfigLib");
                yaclPresent = true;
            } catch (ClassNotFoundException e) {
                yaclPresent = false;
            }
            ctx.checkNow("yacl available in test env", yaclPresent);
            ctx.checkNow("yacl mod id loaded", ctx.platform().isModLoaded("yet_another_config_lib_v3"));
            MockplayerConfig.reload();
            Minecraft mc = Minecraft.getInstance();
            ModConfigScreen screen = new ModConfigScreen(null);
            mc.gui.setScreen(screen);
            ctx.checkNow("yacl screen opened", mc.gui.screen() == screen);
            ctx.checkNow("screen holds bound config", screen.config() == MockplayerConfig.get());
            ctx.checkNow("screen title translated", !screen.getTitle().getString()
                    .equals("config.mockplayer.title"));
            cfgScreen = screen;
        });
        ctx.run(() -> {
            ModConfigScreen screen = cfgScreen;
            dev.isxander.yacl3.api.Option<Integer> intOption = firstIntegerOption(screen);
            dev.isxander.yacl3.api.Option<String> queryOption = findStringOption(screen, "query");
            dev.isxander.yacl3.api.Option<String> controlOption = findStringOption(screen, "control");
            dev.isxander.yacl3.api.Option<Boolean> debugOption = findBooleanOption(screen);
            dev.isxander.yacl3.api.Option<Double> opacityOption =
                    findDoubleOption(screen, (double) ModConfig.DEFAULT_GUI_OPACITY);
            dev.isxander.yacl3.api.Option<Integer> blurOption =
                    findIntOption(screen, ModConfig.DEFAULT_GUI_BLUR);
            ctx.checkNow("integer option found", intOption != null);
            ctx.checkNow("query option found", queryOption != null);
            ctx.checkNow("control option found", controlOption != null);
            ctx.checkNow("debug option found", debugOption != null);
            ctx.checkNow("opacity option found", opacityOption != null);
            ctx.checkNow("guiBlur option found", blurOption != null);
            if (intOption != null && queryOption != null && controlOption != null && debugOption != null
                    && opacityOption != null && blurOption != null) {
                int before = intOption.binding().getValue();
                intOption.requestSet(before + 1);
                queryOption.requestSet("qry");
                debugOption.requestSet(false);
                opacityOption.requestSet(0.5D);
                blurOption.requestSet(7);
                ctx.checkNow("pending change registered", screen.pendingChanges());
                screen.finishOrSave();
                ctx.checkNow("int option applied to config", intOption.binding().getValue() == before + 1);
                ctx.checkNow("command rename applied to config",
                        MockplayerConfig.get().getCommandName("query").equals("qry"));
                ctx.checkNow("debug overlay applied to config",
                        !MockplayerConfig.get().isDebugOverlayEnabled());
                ctx.checkNow("opacity applied to config", Float.compare(
                        MockplayerConfig.get().getGuiOpacity(), 0.5F) == 0);
                ctx.checkNow("guiBlur applied to config", MockplayerConfig.get().getGuiBlur() == 7);
                ModConfig saved = ModConfigIO.load(MockplayerConfig.path());
                ctx.checkNow("config file written", saved.getChatHistoryLimit() == before + 1
                        && "qry".equals(saved.getCommandName("query"))
                        && !saved.isDebugOverlayEnabled()
                        && Float.compare(saved.getGuiOpacity(), 0.5F) == 0
                        && saved.getGuiBlur() == 7);
                ctx.checkNow("hot reload old root removed (active)", !ctx.platform().hasActiveRoot("query"));
                ctx.checkNow("hot reload new root registered (active)", ctx.platform().hasActiveRoot("qry"));
                ctx.checkNow("hot reload exec layer updated",
                        !ctx.platform().hasExecRoot("query") && ctx.platform().hasExecRoot("qry"));
                ctx.checkNow("new command executable", ctx.platform().executeClientCommand("qry list"));
                ctx.checkNow("old command not executable", !ctx.platform().executeClientCommand("query list"));
                controlOption.requestSet("");
                screen.finishOrSave();
                ctx.checkNow("disable control root removed (active)", !ctx.platform().hasActiveRoot("control"));
                ctx.checkNow("disable control exec layer updated", !ctx.platform().hasExecRoot("control"));
                ctx.checkNow("disable control other command intact", ctx.platform().hasActiveRoot("qry"));
                MockplayerConfig.save(new ModConfig());
                ctx.checkNow("restore config file",
                        ModConfigIO.load(MockplayerConfig.path()).getChatHistoryLimit()
                                == ModConfig.DEFAULT_CHAT_HISTORY_LIMIT
                                && ModConfigIO.load(MockplayerConfig.path()).getCommandName("query").equals("query"));
                ctx.checkNow("restore control root back",
                        ctx.platform().hasActiveRoot("control") && ctx.platform().hasActiveRoot("query"));
                ctx.checkNow("restore renamed root gone", !ctx.platform().hasActiveRoot("qry"));
                ctx.checkNow("restore exec layer back",
                        ctx.platform().hasExecRoot("control") && ctx.platform().hasExecRoot("query"));
            }
        });
    }

    private void resetAndConsumers(TestContext ctx) {
        ctx.run(() -> {
            ModConfig bound = cfgScreen.config();
            bound.setChatHistoryLimit(77);
            bound.setCommandName(ModCommands.QUERY, "qq");
            ModConfigScreen.resetAllAndSave(cfgScreen);
            ctx.checkNow("reset all config defaults", cfgScreen.config().getChatHistoryLimit()
                    == ModConfig.DEFAULT_CHAT_HISTORY_LIMIT
                    && cfgScreen.config().getCommandName(ModCommands.QUERY).equals("query"));
            ctx.checkNow("reset all file defaults",
                    ModConfigIO.load(MockplayerConfig.path()).getChatHistoryLimit()
                            == ModConfig.DEFAULT_CHAT_HISTORY_LIMIT
                            && ModConfigIO.load(MockplayerConfig.path()).getCommandName(ModCommands.QUERY)
                            .equals("query"));
            ctx.checkNow("reset all dispatcher restored", ctx.platform().hasActiveRoot("query")
                    && ctx.platform().hasActiveRoot("control") && !ctx.platform().hasActiveRoot("qq"));
            MockplayerConfig.get().setChatHistoryLimit(10);
            FakePlayerState state = new FakePlayerState();
            for (int i = 0; i < 15; i++) {
                state.addChat(Component.literal("msg-" + i));
            }
            ctx.checkNow("chat limit applied to state", state.getChatHistory().size() == 10);
            MockplayerConfig.get().setEventCacheSize(10);
            MockplayerConfig.get().setEventTickSampleInterval(1);
            EventRecorder recorder = new EventRecorder("cfg-recorder");
            for (int i = 0; i < 15; i++) {
                recorder.onTick(null);
            }
            ctx.checkNow("event cache size applied", recorder.snapshot().size() == 10);
            MockplayerConfig.save(new ModConfig());
            Minecraft mc = Minecraft.getInstance();
            MissingYaclScreen missing = new MissingYaclScreen(null);
            mc.gui.setScreen(missing);
            ctx.checkNow("missing-yacl screen opened", mc.gui.screen() == missing);
            ctx.checkNow("missing-yacl title translated",
                    !missing.getTitle().getString().equals("config.mockplayer.missing_yacl.title"));
            missing.onClose();
            ctx.checkNow("missing-yacl screen closed", mc.gui.screen() == null);
            deleteConfigTempDir();
        });
    }

    private void writeConfigRaw(String json) {
        try {
            Files.writeString(cfgFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[mocktest] write config json failed: " + e);
        }
    }

    private boolean configEquals(ModConfig a, ModConfig b) {
        return a.getChatHistoryLimit() == b.getChatHistoryLimit()
                && a.getSoundLogLimit() == b.getSoundLogLimit()
                && a.getParticleLogLimit() == b.getParticleLogLimit()
                && a.getEventCacheSize() == b.getEventCacheSize()
                && a.getEventSummaryMaxLength() == b.getEventSummaryMaxLength()
                && a.getEventTickSampleInterval() == b.getEventTickSampleInterval()
                && Double.compare(a.getEventMoveSampleDistance(), b.getEventMoveSampleDistance()) == 0
                && a.isDebugOverlayEnabled() == b.isDebugOverlayEnabled()
                && a.getGuiBlur() == b.getGuiBlur()
                && Float.compare(a.getGuiOpacity(), b.getGuiOpacity()) == 0;
    }

    private void deleteConfigTempDir() {
        if (cfgDir == null) {
            return;
        }
        try (var walk = Files.walk(cfgDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            System.out.println("[mocktest] failed to delete config temp dir: " + e);
        }
        cfgDir = null;
        cfgFile = null;
    }

    private void i18nConfigLangChecks(TestContext ctx) {
        try {
            com.google.gson.JsonObject en = parseLang("en_us.json");
            com.google.gson.JsonObject zh = parseLang("zh_cn.json");
            Set<String> enKeys = new TreeSet<>();
            Set<String> zhKeys = new TreeSet<>();
            en.entrySet().forEach(e -> {
                if (e.getKey().startsWith("config.mockplayer.")) {
                    enKeys.add(e.getKey());
                }
            });
            zh.entrySet().forEach(e -> {
                if (e.getKey().startsWith("config.mockplayer.")) {
                    zhKeys.add(e.getKey());
                }
            });
            ctx.checkNow("config i18n key sets identical (en/zh)", enKeys.equals(zhKeys),
                    "en=" + enKeys.size() + " zh=" + zhKeys.size());
            ctx.checkNow("config i18n values non-empty",
                    enKeys.stream().allMatch(k -> !en.get(k).getAsString().isBlank())
                            && zhKeys.stream().allMatch(k -> !zh.get(k).getAsString().isBlank()));
            ctx.checkNow("config i18n no literal %s",
                    enKeys.stream().noneMatch(k -> en.get(k).getAsString().contains("%s"))
                            && zhKeys.stream().noneMatch(k -> zh.get(k).getAsString().contains("%s")));
        } catch (Exception e) {
            ctx.checkNow("config i18n lang files parse", false, e.toString());
        }
    }

    private com.google.gson.JsonObject parseLang(String fileName) throws IOException {
        var location = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "mockplayer", "lang/" + fileName);
        var resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            throw new IOException("missing lang file " + fileName);
        }
        try (var reader = resource.get().openAsReader()) {
            return com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private dev.isxander.yacl3.api.Option<Integer> firstIntegerOption(ModConfigScreen screen) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Integer) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    private dev.isxander.yacl3.api.Option<String> findStringOption(ModConfigScreen screen, String defaultName) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof String s && s.equals(defaultName)) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    private dev.isxander.yacl3.api.Option<Boolean> findBooleanOption(ModConfigScreen screen) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Boolean) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    private dev.isxander.yacl3.api.Option<Double> findDoubleOption(ModConfigScreen screen, double defaultValue) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Double d && Math.abs(d - defaultValue) < 1e-6) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }

    private dev.isxander.yacl3.api.Option<Integer> findIntOption(ModConfigScreen screen, int defaultValue) {
        for (dev.isxander.yacl3.api.ConfigCategory category : screen.config.categories()) {
            for (dev.isxander.yacl3.api.OptionGroup group : category.groups()) {
                for (dev.isxander.yacl3.api.Option<?> option : group.options()) {
                    if (option.binding().getValue() instanceof Integer i && i == defaultValue) {
                        return (dev.isxander.yacl3.api.Option) option;
                    }
                }
            }
        }
        return null;
    }
}
