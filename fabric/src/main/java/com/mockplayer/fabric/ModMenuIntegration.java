package com.mockplayer.fabric;

import com.mockplayer.config.MissingYaclScreen;
import com.mockplayer.config.ModConfigScreenFactory;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;


/**
 * ModMenu 模组列表入口（ModMenu 为可选依赖，本类只在 ModMenu 存在时被加载）。
 *
 * 输入：玩家在模组列表点「配置」
 * 输出：有 YACL → YACL 配置界面；无 YACL → 原版兜底提示界面（配置仍可手改 JSON）
 *
 * YACL 引用全部藏在 ModConfigScreen 里；本类及其 lambda 只引用零 YACL 的
 * ModConfigScreenFactory（反射桥），缺 YACL 时类加载验证不会碰 YACL 类。
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            net.minecraft.client.gui.screens.Screen screen =
                    ModConfigScreenFactory.create(parent);
            return screen != null ? screen : new MissingYaclScreen(parent);
        };
    }
}
