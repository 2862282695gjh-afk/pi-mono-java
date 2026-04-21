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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.fittrack.data.entity.WorkoutPlan
import com.fittrack.ui.navigation.STAGGER_DELAY
import com.fittrack.ui.navigation.listItemEnter
import com.fittrack.ui.navigation.listItemExit
import com.fittrack.ui.viewmodel.FitTrackViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanListScreen(
    viewModel: FitTrackViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAddPlan: () -> Unit,
    onNavigateToPlanDetail: (Long) -> Unit
) {
    val allPlans by viewModel.allPlans.collectAsState(initial = emptyList())

    // 控制列表整体入场
    val listVisible = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { listVisible.targetState = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("训练计划") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            // 带弹性缩放的 FAB
            val fabInteraction = remember { MutableInteractionSource() }
            val fabPressed by fabInteraction.collectIsPressedAsState()
            val fabScale by animateFloatAsState(
                targetValue = if (fabPressed) 0.88f else 1f,
                animationSpec = IconSpring.press,
                label = "fabScale"
            )
            FloatingActionButton(
                onClick = onNavigateToAddPlan,
                modifier = Modifier.scale(fabScale),
                interactionSource = fabInteraction
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加计划")
            }
        }
    ) { padding ->
        if (allPlans.isEmpty()) {
            AnimatedVisibility(
                visibleState = listVisible,
                enter = listItemEnter(delayMillis = 0)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FitnessCenter,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "还没有训练计划",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击右下角按钮创建你的第一个计划",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(allPlans, key = { _, item -> item.id }) { index, plan ->
                    AnimatedVisibility(
                        visibleState = listVisible,
                        enter = listItemEnter(delayMillis = index * STAGGER_DELAY),
                        exit = listItemExit
                    ) {
                        PlanListItem(
                            plan = plan,
                            onClick = { onNavigateToPlanDetail(plan.id) },
                            onDelete = { viewModel.deletePlan(plan) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlanListItem(
    plan: WorkoutPlan,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    plan.name,
                    style = MaterialTheme.typography.titleMedium
                )
                if (plan.description.isNotEmpty()) {
                    Text(
                        plan.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(
                        onClick = { },
                        label = { Text(getGoalLabel(plan.goal)) }
                    )
                    if (plan.isActive) {
                        SuggestionChip(
                            onClick = { },
                            label = { Text("激活中") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除计划") },
            text = { Text("确定要删除「${plan.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
