package com.fittrack.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import com.fittrack.ui.navigation.ButtonSpring
import com.fittrack.ui.navigation.IconSpring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fittrack.data.entity.WorkoutPlan
import com.fittrack.data.entity.WorkoutRecord
import com.fittrack.ui.components.MarkdownText
import com.fittrack.ui.navigation.STAGGER_DELAY
import com.fittrack.ui.navigation.listItemEnter
import com.fittrack.ui.viewmodel.FitTrackViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * 带弹性缩放反馈的 FAB */
@Composable
fun BouncyExtendedFloatingActionButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = ButtonSpring.press,
        label = "fabScale"
    )

    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.scale(scale),
        icon = icon,
        text = text,
        containerColor = containerColor,
        contentColor = contentColor
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: FitTrackViewModel,
    onNavigateToPlanList: () -> Unit,
    onNavigateToPlanDetail: (Long) -> Unit,
    onStartWorkout: (Long) -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToPlanGenerator: () -> Unit = {}
) {
    val allPlans by viewModel.allPlans.collectAsState(initial = emptyList())
    val weeklyCount by viewModel.weeklyWorkoutCount.collectAsState(initial = 0)
    val monthlyCount by viewModel.monthlyWorkoutCount.collectAsState(initial = 0)

    // 控制内容交错入场的动画状态
    val contentVisible = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { contentVisible.targetState = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FitTrack") },
                actions = {
                    val chatInteraction = remember { MutableInteractionSource() }
                    val chatPressed by chatInteraction.collectIsPressedAsState()
                    val chatScale by animateFloatAsState(
                        targetValue = if (chatPressed) 0.85f else 1f,
                        animationSpec = IconSpring.press,
                        label = "chatIconScale"
                    )
                    IconButton(
                        onClick = onNavigateToChat,
                        interactionSource = chatInteraction
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = "AI 教练",
                            modifier = Modifier.scale(chatScale)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            BouncyExtendedFloatingActionButton(
                onClick = onNavigateToChat,
                icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                text = { Text("AI 教练") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        bottomBar = {
            NavigationBar {
                NavItem(
                    selected = true,
                    onClick = { },
                    icon = Icons.Default.Home,
                    label = "首页"
                )
                NavItem(
                    selected = false,
                    onClick = onNavigateToPlanList,
                    icon = Icons.Default.List,
                    label = "计划"
                )
                NavItem(
                    selected = false,
                    onClick = onNavigateToStatistics,
                    icon = Icons.Default.BarChart,
                    label = "统计"
                )
                NavItem(
                    selected = false,
                    onClick = onNavigateToProfile,
                    icon = Icons.Default.Person,
                    label = "我的"
                )
                NavItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = Icons.Default.Settings,
                    label = "设置"
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 日期显示 ──
            item {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = listItemEnter(delayMillis = 0)
                ) {
                    val today = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA).format(Date())
                    Text(
                        text = today,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 统计卡片 ──
            item {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = listItemEnter(delayMillis = STAGGER_DELAY)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "本周训练",
                            value = "${weeklyCount}次",
                            icon = Icons.Default.FitnessCenter
                        )
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "本月训练",
                            value = "${monthlyCount}次",
                            icon = Icons.Default.CalendarMonth
                        )
                    }
                }
            }

            // ── 今日训练状态 ──
            item {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = listItemEnter(delayMillis = STAGGER_DELAY * 2)
                ) {
                    TodayStatusCard(
                        viewModel = viewModel,
                        weeklyCount = weeklyCount
                    )
                }
            }

            // ── 本周训练记录 ──
            item {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = listItemEnter(delayMillis = STAGGER_DELAY * 3)
                ) {
                    WeeklyRecordsSection(viewModel = viewModel)
                }
            }

            // ── AI 生成计划入口 ──
            item {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = listItemEnter(delayMillis = STAGGER_DELAY * 4)
                ) {
                    AIGeneratorCard(onClick = onNavigateToPlanGenerator)
                }
            }

            // ── 我的计划 ──
            item {
                AnimatedVisibility(
                    visibleState = contentVisible,
                    enter = listItemEnter(delayMillis = STAGGER_DELAY * 5)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "我的计划",
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(onClick = onNavigateToPlanList) {
                            Text("查看全部")
                        }
                    }
                }
            }

            // ── 计划列表 ──
            if (allPlans.isEmpty()) {
                item {
                    AnimatedVisibility(
                        visibleState = contentVisible,
                        enter = listItemEnter(delayMillis = STAGGER_DELAY * 6)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "还没有训练计划",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = onNavigateToPlanList) {
                                    Text("创建计划")
                                }
                            }
                        }
                    }
                }
            } else {
                items(allPlans.take(3), key = { it.id }) { plan ->
                    AnimatedVisibility(
                        visibleState = contentVisible,
                        enter = listItemEnter(delayMillis = STAGGER_DELAY * 6)
                    ) {
                        PlanCard(
                            plan = plan,
                            onClick = { onNavigateToPlanDetail(plan.id) },
                            onStartWorkout = { onStartWorkout(plan.id) }
                        )
                    }
                }
            }
        }
    }
}

// ── 底部导航项（带弹性缩放） ──────────────────────────────

@Composable
private fun NavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = IconSpring.press,
        label = "navScale"
    )

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(icon, contentDescription = label, modifier = Modifier.scale(scale))
        },
        label = { Text(label) },
        interactionSource = interactionSource
    )
}

// ── 今日训练状态卡片 ──────────────────────────────────────

@Composable
private fun TodayStatusCard(
    viewModel: FitTrackViewModel,
    weeklyCount: Int
) {
    val allRecords by viewModel.allRecords.collectAsState(initial = emptyList())
    val recentRecords = allRecords.take(7)
    val avgMetabolicPressure = if (recentRecords.isNotEmpty()) {
        recentRecords.map { it.metabolicPressure }.average().toInt()
    } else 0
    val avgMentalPressure = if (recentRecords.isNotEmpty()) {
        recentRecords.map { it.mentalPressure }.average().toInt()
    } else 0
    val needsDeload = recentRecords.any { it.isDeload }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (needsDeload)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (needsDeload) Icons.Default.Refresh else Icons.Default.SelfImprovement,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (needsDeload)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (needsDeload) "检测到高压状态，建议减载训练"
                else if (weeklyCount > 0) "本周已训练 ${weeklyCount} 次，继续加油！"
                else "开始你的健身之旅吧~",
                style = MaterialTheme.typography.bodyLarge,
                color = if (needsDeload)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (recentRecords.isNotEmpty() && !needsDeload) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "代谢压力: $avgMetabolicPressure | 精神压力: $avgMentalPressure",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsDeload)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

// ── 本周训练记录区域 ──────────────────────────────────────

@Composable
private fun WeeklyRecordsSection(viewModel: FitTrackViewModel) {
    val allRecords by viewModel.allRecords.collectAsState(initial = emptyList())
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
    val weekStart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

    val weeklyRecords = allRecords
        .filter { it.date >= weekStart }
        .sortedByDescending { it.date }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "本周训练记录",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (weeklyRecords.isNotEmpty()) {
                Text(
                    "${weeklyRecords.size} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (weeklyRecords.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FitnessCenter,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "本周还没有训练记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            weeklyRecords.forEach { record ->
                WeeklyRecordCard(record = record)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ── AI 计划生成入口卡片 ───────────────────────────────────

@Composable
private fun AIGeneratorCard(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = ButtonSpring.press,
        label = "aiCardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI 智能生成计划",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "根据你的目标和经验，定制专属训练计划",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}

// ── 统计卡片 ─────────────────────────────────────────────

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ── 计划卡片 ─────────────────────────────────────────────

@Composable
fun PlanCard(
    plan: WorkoutPlan,
    onClick: () -> Unit,
    onStartWorkout: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plan.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (plan.description.isNotEmpty()) {
                        Text(
                            plan.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                AssistChip(
                    onClick = onStartWorkout,
                    label = { Text("开始") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = { },
                    label = { Text(getGoalLabel(plan.goal)) }
                )
                if (plan.reminderDays.isNotEmpty()) {
                    SuggestionChip(
                        onClick = { },
                        label = { Text(plan.reminderTime) },
                        icon = {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

fun getGoalLabel(goal: String): String {
    return when (goal) {
        "muscle_gain" -> "增肌"
        "fat_loss" -> "减脂"
        "strength" -> "力量"
        "endurance" -> "耐力"
        else -> "综合"
    }
}

/**
 * 本周训练记录卡片
 */
@Composable
fun WeeklyRecordCard(
    record: WorkoutRecord,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 记录头部
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = record.date,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${record.totalDuration}分钟 | 感受 ${record.feeling}/5",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 压力指标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = { },
                    label = { Text("代谢 ${record.metabolicPressure}", fontSize = 11.sp) }
                )
                SuggestionChip(
                    onClick = { },
                    label = { Text("精神 ${record.mentalPressure}", fontSize = 11.sp) }
                )
                if (record.isDeload) {
                    SuggestionChip(
                        onClick = { },
                        label = { Text("减载日", fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    )
                }
            }

            // AI 建议摘要（展开时显示）
            if (expanded && record.aiSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "AI 训练建议",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownText(
                    markdown = record.aiSummary,
                    fontSize = 12,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 提示展开查看 AI 建议
            if (!expanded && record.aiSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击查看 AI 建议 →",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
