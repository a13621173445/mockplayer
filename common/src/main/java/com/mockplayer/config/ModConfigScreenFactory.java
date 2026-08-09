package com.mockplayer.config;

import net.minecraft.client.gui.screens.Screen;

/**
 * 反射创建 YACL 配置界面（YACL 可选依赖安全桥）。
 *
 * 为什么必须反射：JVM 在类加载验证阶段就会解析方法里 {@code new} 的目标类，
 * ModConfigScreen 继承 YACLScreen；缺 YACL 时任何直接引用它的类都会在加载瞬间
 * 抛 NoClassDefFoundError。本类只引用类名字符串，缺 YACL 时完全安全。
 *
 * 输入：父界面；输出：YACL 配置界面；缺 YACL / 类加载失败 → null（调用方兜底）。
 */
public final class ModConfigScreenFactory {

    private ModConfigScreenFactory() {
    }

    /** 反射构造 ModConfigScreen；失败返回 null，绝不抛出（调用方用 MissingYaclScreen 兜底）。 */
    public static Screen create(Screen parent) {
        try {
            Class<?> screenClass = Class.forName("com.mockplayer.config.ModConfigScreen");
            return (Screen) screenClass.getConstructor(Screen.class).newInstance(parent);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
