package com.mockplayer.test;

import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.ModConfig;
import com.mockplayer.config.ModConfigIO;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 无 YACL 冒烟（fabric noYaclClient 专用，自动退出）。
 *
 * 验证：YACL 未被加载、ModMenu 入口守卫返回 MissingYaclScreen、兜底界面可用、
 * 配置 JSON 照常读写（缺 YACL 也能手改）。
 *
 * 铁律：本文件严禁 import dev.isxander.* ——否则冒烟环境编译/加载就会碰到 YACL，
 * 失去「无 YACL 也能跑」的验证意义。
 */
public final class NoYaclSmoke {

    private static final long TIMEOUT_MS = 120_000;

    private enum Phase { WAIT_TITLE, WAIT_WORLD, RUN, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static long phaseStart = System.currentTimeMillis();
    private static boolean worldStarted;
    private static final List<Record> records = new ArrayList<>();

    private record Record(String name, boolean passed, String detail) {
    }

    private NoYaclSmoke() {
    }

    /** 主线程 tick 驱动：等单机世界就绪 → 断言 → 写 JSON → 退出。 */
    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        switch (phase) {
            case WAIT_TITLE -> {
                if (mc.level != null) {
                    phase = Phase.WAIT_WORLD;
                    phaseStart = now;
                } else if (!worldStarted) {
                    worldStarted = true;
                    phaseStart = now;
                    deleteOldWorld(mc);
                    createWorld(mc);
                }
            }
            case WAIT_WORLD -> {
                if (mc.getSingleplayerServer() != null && mc.level != null && mc.player != null) {
                    phase = Phase.RUN;
                    phaseStart = now;
                }
            }
            case RUN -> {
                run(mc);
                phase = Phase.DONE;
                writeResult();
                Minecraft.getInstance().stop();
            }
            default -> {
            }
        }
        if (phase != Phase.DONE && now - phaseStart > TIMEOUT_MS) {
            check("no-yacl smoke timeout", false);
            phase = Phase.DONE;
            writeResult();
            Minecraft.getInstance().stop();
        }
    }

    /** 删除旧冒烟世界（世界未加载时调用）。 */
    private static void deleteOldWorld(Minecraft mc) {
        try {
            var source = mc.getLevelSource();
            if (source.levelExists("mocktest-noyacl")) {
                Path levelPath = source.getLevelPath("mocktest-noyacl");
                System.out.println("[mocktest-noyacl] deleting old world: " + levelPath);
                try (var walk = Files.walk(levelPath)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (Exception e) {
            System.out.println("[mocktest-noyacl] failed to delete old world: " + e);
        }
    }

    /** 创建单机冒烟世界（超平坦，加载快）。 */
    private static void createWorld(Minecraft mc) {
        System.out.println("[mocktest-noyacl] creating singleplayer world 'mocktest-noyacl' (flat)");
        mc.createWorldOpenFlows().createFreshLevel(
                "mocktest-noyacl",
                new LevelSettings("mocktest-noyacl", GameType.SURVIVAL,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                new WorldOptions(0L, false, false),
                WorldPresets::createTestWorldDimensions,
                null);
    }

    private static void run(Minecraft mc) {
        check("yacl not loaded", !FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3"));
        check("modmenu loaded (guard exercised)", FabricLoader.getInstance().isModLoaded("modmenu"));
        try {
            Object screen = new com.mockplayer.fabric.ModMenuIntegration()
                    .getModConfigScreenFactory().create(null);
            check("modmenu guard returns MissingYaclScreen", screen instanceof MissingYaclScreen);
        } catch (Throwable t) {
            check("modmenu guard returns MissingYaclScreen", false, t.toString());
        }
        MissingYaclScreen missing = new MissingYaclScreen(null);
        mc.gui.setScreen(missing);
        check("missing-yacl screen opens", mc.gui.screen() == missing);
        missing.onClose();
        check("missing-yacl screen closes", mc.gui.screen() == null);
        configIoChecks();
    }

    /** 缺 YACL 时配置 JSON 照常读写（手改/非法值回退）。 */
    private static void configIoChecks() {
        try {
            Path dir = Files.createTempDirectory("mocktest-noyacl-config");
            Path file = dir.resolve("mockplayer.json");
            ModConfig defaults = new ModConfig();
            check("config missing -> defaults", configEquals(defaults, ModConfigIO.load(file)));
            ModConfigIO.save(file, defaults);
            check("config save->load round trip", configEquals(defaults, ModConfigIO.load(file)));
            Files.writeString(file, "{\"chatHistoryLimit\": 77}", StandardCharsets.UTF_8);
            check("config hand-edit applied", ModConfigIO.load(file).getChatHistoryLimit() == 77);
            Files.writeString(file, "{\"eventCacheSize\": 3}", StandardCharsets.UTF_8);
            check("config invalid fallback", ModConfigIO.load(file).getEventCacheSize()
                    == ModConfig.DEFAULT_EVENT_CACHE_SIZE);
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            check("config io", false, e.toString());
        }
    }

    private static boolean configEquals(ModConfig a, ModConfig b) {
        return a.getChatHistoryLimit() == b.getChatHistoryLimit()
                && a.getSoundLogLimit() == b.getSoundLogLimit()
                && a.getParticleLogLimit() == b.getParticleLogLimit()
                && a.getEventCacheSize() == b.getEventCacheSize()
                && a.getEventSummaryMaxLength() == b.getEventSummaryMaxLength()
                && a.getEventTickSampleInterval() == b.getEventTickSampleInterval()
                && Double.compare(a.getEventMoveSampleDistance(), b.getEventMoveSampleDistance()) == 0;
    }

    private static void check(String name, boolean ok) {
        check(name, ok, ok ? "" : "assertion failed");
    }

    private static void check(String name, boolean ok, String detail) {
        records.add(new Record(name, ok, ok ? "" : detail));
        String suffix = (ok || detail == null || detail.isEmpty()) ? "" : " <" + detail + ">";
        System.out.println("[mocktest-noyacl] " + (ok ? "PASS " : "FAIL ") + name + suffix);
    }

    /** 写 runs/client/test-results/noyacl.json 并打印汇总。 */
    private static void writeResult() {
        boolean passed = records.stream().allMatch(Record::passed);
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"suite\": \"noyacl\",\n");
        json.append("  \"passed\": ").append(passed).append(",\n");
        json.append("  \"results\": [\n");
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            json.append("    {\"name\": \"").append(r.name().replace("\"", "\\\""))
                    .append("\", \"status\": \"").append(r.passed() ? "PASS" : "FAIL")
                    .append("\", \"detail\": \"").append(r.detail().replace("\"", "\\\""))
                    .append("\"}");
            if (i < records.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n}\n");
        try {
            File dir = new File("test-results");
            if (!dir.exists() && !dir.mkdirs()) {
                System.out.println("[mocktest-noyacl] cannot create test-results dir");
            }
            File out = new File(dir, "noyacl.json");
            try (FileWriter w = new FileWriter(out, StandardCharsets.UTF_8)) {
                w.write(json.toString());
            }
            System.out.println("[mocktest-noyacl] wrote " + out.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[mocktest-noyacl] failed to write result: " + e);
        }
        System.out.println("[mocktest-noyacl] smoke " + (passed ? "PASSED" : "FAILED")
                + " (" + records.size() + " checks)");
    }
}
