package com.mockplayer.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

/**
 * 热重载时从 Brigadier dispatcher 移除旧根命令。
 *
 * 黑魔法说明（为什么反射）：Brigadier 1.3.10 没有公共移除 API，
 * 根节点子命令存在 CommandNode 的三个 private map 里：
 * children（全部子节点）/ literals（字面量）/ arguments（参数）。
 * 反射同步移除三个 map；未来 Brigadier 若加公共 API 就替换本类。
 */
public final class CommandTreeReloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandTreeReloader.class);

    private CommandTreeReloader() {
    }

    /** 从 dispatcher 根节点移除一组根命令名（不存在则忽略）。 */
    public static <S> void removeRoots(CommandDispatcher<S> dispatcher, Collection<String> rootNames) {
        RootCommandNode<S> root = dispatcher.getRoot();
        for (String name : rootNames) {
            removeChild(root, name);
        }
    }

    private static <S> void removeChild(RootCommandNode<S> root, String name) {
        try {
            removeFromMap(root, "children", name);
            removeFromMap(root, "literals", name);
            removeFromMap(root, "arguments", name);
        } catch (ReflectiveOperationException e) {
            // 反射失败只影响本次热重载，不崩客户端（旧命令残留到下次连接）
            LOGGER.warn("Failed to remove command root '{}' during config reload", name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeFromMap(CommandNode<?> node, String fieldName, String name)
            throws ReflectiveOperationException {
        Field field = CommandNode.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((Map<String, ?>) field.get(node)).remove(name);
    }
}
