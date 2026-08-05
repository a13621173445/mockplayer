package com.mockplayer.session.accessor;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LevelExtractor 的区块更新跟踪器（sectionUpdateTracker）。
 *
 * /control 切换时，setLevel 会重建一个空的 SectionUpdateTracker，而已加载区块没有新的 setDirty
 * 事件通知它编译 → 区块网格空 → 方块不渲染（透明）。ControlManager 通过本 accessor 拿到
 * SectionUpdateTracker，对当前 level 视距内的 section 手动 setDirty，强制重新编译。
 */
@Mixin(LevelExtractor.class)
public interface MockplayerLevelExtractorAccessor {

    @Accessor("sectionUpdateTracker")
    SectionUpdateTracker mockplayer$getSectionUpdateTracker();

    /**
     * shouldResetLevelRenderData：restore 时 setLevel 会把它置 true，下一帧 extract 会 resetLevelRenderData
     * （dispose viewArea/sectionUpdateTracker/sectionRenderDispatcher）。ControlManager 在手动重建区块后
     * 把它置 false，防止 extract 再把刚重建的渲染资源 dispose → 仍然不渲染。
     */
    @Accessor("shouldResetLevelRenderData")
    void mockplayer$setShouldResetLevelRenderData(boolean shouldReset);
}
