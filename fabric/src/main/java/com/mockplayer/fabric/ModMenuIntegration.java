package com.mockplayer.fabric;

import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.ModConfigScreen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;

/**
 * ModMenu 模组列表入口（ModMenu 为可选依赖，本类只在 ModMenu 存在时被加载）。
 *
 * 输入：玩家在模组列表点「配置」
 * 输出：有 YACL → YACL 配置界面；无 YACL → 原版兜底提示界面（配置仍可手改 JSON）
 *
 * YACL 引用全部藏在 ModConfigScreen 里，且先用 isModLoaded 短路，
 * 缺 YACL 时不会触发 NoClassDefFoundError。
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
                return new ModConfigScreen(parent);
            }
            return new MissingYaclScreen(parent);
        };
    }
}
