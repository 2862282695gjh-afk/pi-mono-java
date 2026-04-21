package com.fittrack.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * FitTrack 全局动画配置 — 多邻国 (Duolingo) 风格
 *
 * 核心特征：弹性回弹 + 缩放淡入 + 水平滑动方向感
 * 所有页面转场和组件动画统一使用此文件中的常量，确保手感一致。
 */

// ── Spring 配置 ──────────────────────────────────────────────

/** 页面转场主 spring：中等弹性，自然回弹 */
val FitTrackSpring = spring<Float>(
    dampingRatio = 0.65f,
    stiffness = Spring.StiffnessMediumLow
)

/** 微交互（按钮点击等）spring：快速弹回，手感干脆 */
val FitTrackBouncySpring = spring<Float>(
    dampingRatio = 0.5f,
    stiffness = Spring.StiffnessMedium
)

/** 列表项交错入场 spring */
val FitTrackStaggerSpring = spring<Float>(
    dampingRatio = 0.7f,
    stiffness = Spring.StiffnessMediumLow
)

// ── 时长常量 ────────────────────────────────────────────────

/** 页面转场基准时长 (ms) */
const val TRANSITION_DURATION = 350

/** 列表项交错延迟间隔 (ms) */
const val STAGGER_DELAY = 60

/** 微交互时长 (ms) */
const val MICRO_DURATION = 150

// ── 页面转场：前进方向（向左滑入） ─────────────────────────

/** 前进 enter：从右侧滑入 + 淡入 + 从 95% 缩放到 100% */
val EnterForward: EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> (fullWidth * 0.25).toInt() },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    ) + scaleIn(
        initialScale = 0.92f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    )

/** 前进 exit：向左侧滑出 + 淡出 + 缩小 */
val ExitForward: ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> -(fullWidth * 0.15).toInt() },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    ) + fadeOut(
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    ) + scaleOut(
        targetScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    )

// ── 页面转场：返回方向（向右滑入） ─────────────────────────

/** 返回 enter：从左侧滑入 + 淡入 + 从 95% 缩放到 100% */
val EnterBackward: EnterTransition =
    slideInHorizontally(
        initialOffsetX = { fullWidth -> -(fullWidth * 0.25).toInt() },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    ) + scaleIn(
        initialScale = 0.92f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    )

/** 返回 exit：向右侧滑出 + 淡出 + 缩小 */
val ExitBackward: ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> (fullWidth * 0.15).toInt() },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    ) + fadeOut(
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    ) + scaleOut(
        targetScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    )

// ── 替代转场：垂直方向（用于 Tab 切换等） ─────────────────

/** 垂直向上滑入 + 淡入 + 缩放 */
val EnterUp: EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.2).toInt() },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
    ) + scaleIn(
        initialScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    )

/** 垂直向下滑出 + 淡出 */
val ExitDown: ExitTransition =
    slideOutVertically(
        targetOffsetY = { fullHeight -> (fullHeight * 0.15).toInt() },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    ) + fadeOut(
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    ) + scaleOut(
        targetScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
    )

// ── 组件级动画：用于 AnimatedVisibility 等 ────────────────

/** 列表项入场：从下方滑入 + 淡入 + 弹性缩放 */
fun listItemEnter(delayMillis: Int = 0): EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.15).toInt() },
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow
        )
    ) + fadeIn(
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = Spring.StiffnessMediumLow
        ),
        initialAlpha = 0.6f
    ) + scaleIn(
        initialScale = 0.9f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = Spring.StiffnessMedium
        ),
        delayMillis = delayMillis.toLong()
    )

/** 列表项退场：向上滑出 + 淡出 */
val ListItemExit: ExitTransition =
    slideOutVertically(
        targetOffsetY = { fullHeight -> -(fullHeight * 0.1).toInt() },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    ) + fadeOut(
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    ) + scaleOut(
        targetScale = 0.95f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    )

/** 消息气泡入场：从底部滑入 + 弹性缩放 */
val MessageBubbleEnter: EnterTransition =
    slideInVertically(
        initialOffsetY = { fullHeight -> (fullHeight * 0.12).toInt() },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    ) + fadeIn(
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        initialAlpha = 0.5f
    ) + scaleIn(
        initialScale = 0.85f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
    )

/** 庆祝动画 enter：弹性缩放从 0 到 110% 再回弹到 100% */
val CelebratoryEnter: EnterTransition =
    scaleIn(
        initialScale = 0.3f,
        animationSpec = spring(
            dampingRatio = 0.4f,
            stiffness = Spring.StiffnessMedium
        )
    ) + fadeIn(
        animationSpec = tween(durationMillis = 200)
    )
