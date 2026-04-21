package com.fittrack.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Spring.Companion.DampingRatioNoBouncy
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * FitTrack 全局动画配置 — 多邻国 (Duolingo) 风格
 *
 * 核心特征：弹性回弹 + 缩放淡入 + 水平滑动方向感
 * 所有页面转场和组件动画统一使用此文件中的常量，确保手感一致。
 *
 * 全局调参只需修改此文件中对应语义层的 spring 配置。
 */

// ── 语义化 Spring 配置 ───────────────────────────────────────
// 按使用场景分层，各 Screen 必须引用这些常量，禁止内联硬编码。

/** 页面转场滑动 spring：中等弹性，自然回弹 */
object TransitionSpring {
    val slide = SpringSpec<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    val fade = SpringSpec<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    val scale = SpringSpec<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    val exitSlide = SpringSpec<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    val exitFade = SpringSpec<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
}

/** 列表/组件级动画 spring */
object ListSpring {
    val slide = SpringSpec<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    val fade = SpringSpec<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
    val scale = SpringSpec<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    val exit = SpringSpec<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
}

/** 按钮点击微交互 spring：快速弹回，手感干脆 */
object ButtonSpring {
    val press = SpringSpec<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
}

/** 图标/小元素按下 spring：更有弹性的手感 */
object IconSpring {
    val press = SpringSpec<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)
}

/** 庆祝特效 spring：低阻尼，可见回弹 1~2 次 */
object CelebratorySpring {
    val bounce = SpringSpec<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)
}

/** 进度条/数值动画 spring */
object ProgressSpring {
    val animate = SpringSpec<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)
}

// ── 时长常量 ────────────────────────────────────────────────

/** 列表项交错延迟间隔 (ms) */
const val STAGGER_DELAY = 60

// ── 页面转场：前进方向（向左滑入） ─────────────────────────

/** 前进 enter：从右侧滑入 + 淡入 + 从 92% 缩放到 100% */
val EnterForward: EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> (fullWidth * 0.25).toInt() },
        animationSpec = TransitionSpring.slide
    ) + fadeIn(animationSpec = TransitionSpring.fade) +
    scaleIn(initialScale = 0.92f, animationSpec = TransitionSpring.scale)

/** 前进 exit：向左侧滑出 + 淡出 + 缩小 */
val ExitForward: ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> -(fullWidth * 0.15).toInt() },
        animationSpec = TransitionSpring.exitSlide
    ) + fadeOut(animationSpec = TransitionSpring.exitFade) +
    scaleOut(targetScale = 0.95f, animationSpec = TransitionSpring.exitFade)

// ── 页面转场：返回方向（向右滑入） ─────────────────────────

/** 返回 enter：从左侧滑入 + 淡入 + 缩放 */
val EnterBackward: EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> -(fullWidth * 0.25).toInt() },
        animationSpec = TransitionSpring.slide
    ) + fadeIn(animationSpec = TransitionSpring.fade) +
    scaleIn(initialScale = 0.92f, animationSpec = TransitionSpring.scale)

/** 返回 exit：向右侧滑出 + 淡出 + 缩小 */
val ExitBackward: ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> (fullWidth * 0.15).toInt() },
        animationSpec = TransitionSpring.exitSlide
    ) + fadeOut(animationSpec = TransitionSpring.exitFade) +
    scaleOut(targetScale = 0.95f, animationSpec = TransitionSpring.exitFade)

// ── 垂直转场（用于特殊模式页面） ──────────────────────────
//
// 设计意图：Workout 和 PlanGenerator 是"特殊模式"页面。
// 前进时用垂直弹出，给用户"进入专注模式"的感觉；
// 返回时用标准水平导航，保持空间方位感。

/** 垂直向上滑入 + 淡入 + 缩放 */
val EnterUp: EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.2).toInt() },
        animationSpec = TransitionSpring.slide
    ) + fadeIn(animationSpec = TransitionSpring.fade) +
    scaleIn(initialScale = 0.95f, animationSpec = TransitionSpring.scale)

/** 垂直向下滑出 + 淡出 */
val ExitDown: ExitTransition =
    slideOutVertically(
        targetOffsetY = { fullHeight -> (fullHeight * 0.15).toInt() },
        animationSpec = TransitionSpring.exitSlide
    ) + fadeOut(animationSpec = TransitionSpring.exitFade) +
    scaleOut(targetScale = 0.95f, animationSpec = TransitionSpring.exitFade)

// ── 组件级动画：用于 AnimatedVisibility 等 ────────────────

/** 列表项入场：从下方滑入 + 淡入 + 弹性缩放 */
fun listItemEnter(delayMillis: Int = 0): EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.15).toInt() },
        animationSpec = ListSpring.slide
    ) + fadeIn(
        animationSpec = ListSpring.fade,
        initialAlpha = 0.6f
    ) + scaleIn(
        initialScale = 0.9f,
        animationSpec = ListSpring.scale,
        delayMillis = delayMillis.toLong()
    )

/** 列表项退场：向上滑出 + 淡出 */
val ListItemExit: ExitTransition =
    slideOutVertically(
        targetOffsetY = { fullHeight -> -(fullHeight * 0.1).toInt() },
        animationSpec = ListSpring.exit
    ) + fadeOut(animationSpec = ListSpring.exit) +
    scaleOut(targetScale = 0.95f, animationSpec = ListSpring.exit)

/** 消息气泡入场：从底部滑入 + 弹性缩放 */
val MessageBubbleEnter: EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.12).toInt() },
        animationSpec = TransitionSpring.slide
    ) + fadeIn(
        animationSpec = TransitionSpring.fade,
        initialAlpha = 0.5f
    ) + scaleIn(
        initialScale = 0.85f,
        animationSpec = SpringSpec<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
    )

/** 庆祝动画 enter：弹性缩放，可见回弹 1~2 次 */
val CelebratoryEnter: EnterTransition =
    scaleIn(initialScale = 0.3f, animationSpec = CelebratorySpring.bounce) +
    fadeIn(animationSpec = tween(durationMillis = 200))
