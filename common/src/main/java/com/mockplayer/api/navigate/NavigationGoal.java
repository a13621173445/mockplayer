package com.mockplayer.api.navigate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 假人寻路目标抽象（可插拔导航层的中立形状）。
 *
 * 上层（命令/行为树/未来 AI）只构造这些形状；底层实现（当前 Baritone）负责
 * 翻译成自己的 Goal 系列。sealed 保证实现只认这四种，不会出现未知目标。
 */
public sealed interface NavigationGoal permits NavigationGoal.BlockGoal, NavigationGoal.AreaGoal,
        NavigationGoal.EntityGoal, NavigationGoal.CompositeGoal {

    /** 站进某个方块格（→ Baritone GoalBlock）。 */
    record BlockGoal(BlockPos pos) implements NavigationGoal {
    }

    /** 靠近某位置水平半径内（→ Baritone GoalNear）。 */
    record AreaGoal(BlockPos center, int radius) implements NavigationGoal {
    }

    /** 跟随实体，stopDistance 停止距离（→ FollowProcess + 每 tick 刷新目标）。 */
    record EntityGoal(Entity target, double stopDistance) implements NavigationGoal {
    }

    /** 复合目标：任一满足即可（→ GoalComposite）。 */
    record CompositeGoal(List<NavigationGoal> goals) implements NavigationGoal {
    }
}
