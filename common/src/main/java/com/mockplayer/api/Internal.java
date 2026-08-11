package com.mockplayer.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 mockplayer 内部契约 API。
 *
 * 被标注的类/方法虽为 public（Mixin、同包实现、测试需要），但不属于对外稳定 API：
 * 外部/附属 mod 不应依赖，后续版本可能无警告变更。跨包调用前先确认白名单
 * （Mixin 白名单见对应类的 javadoc）。
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Internal {
}
