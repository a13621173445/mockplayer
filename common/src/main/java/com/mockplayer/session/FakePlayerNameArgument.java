package com.mockplayer.session;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.network.chat.Component;

import java.util.Collection;

/**
 * 假人名字参数类型。
 * 复用 {@link StringArgumentType#word()} 的单词解析（字母/数字/下划线），
 * 但把 Brigadier 的英文错误消息替换为本地化消息：
 * 双端（Fabric/NeoForge）命令渲染器都走 {@code ComponentUtils.fromMessage(rawMessage)}，
 * 而 {@code Component} 实现了 {@code com.mojang.brigadier.Message}，
 * 因此抛出 translatable 组件即可让「参数错误」提示随语言文件翻译。
 */
public class FakePlayerNameArgument implements ArgumentType<String> {

    private static final SimpleCommandExceptionType INVALID_NAME =
            new SimpleCommandExceptionType(Component.translatable("commands.mockplayer.name.invalid"));

    public static FakePlayerNameArgument fakePlayerName() {
        return new FakePlayerNameArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        try {
            String name = StringArgumentType.word().parse(reader);
            // 只校验长度：word() 已保证无空格；离线服实际接受 '-'/'.'（测试假人名就带连字符），
            // 字符集限制比服务端还严会误伤。超长名字服务端会拒绝，这里提前给出友好报错。
            if (name.length() > 16) {
                throw INVALID_NAME.createWithContext(reader);
            }
            return name;
        } catch (CommandSyntaxException e) {
            throw INVALID_NAME.createWithContext(reader);
        }
    }

    @Override
    public Collection<String> getExamples() {
        return StringArgumentType.word().getExamples();
    }
}
