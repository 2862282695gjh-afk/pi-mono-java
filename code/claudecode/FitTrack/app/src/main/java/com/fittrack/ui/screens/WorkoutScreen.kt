package com.fittrack.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import com.fittrack.ui.navigation.ButtonSpring
import com.fittrack.ui.navigation.IconSpring
import com.fittrack.ui.navigation.CelebratorySpring
import com.fittrack.ui.navigation.ProgressSpring
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fittrack.ui.navigation.STAGGER_DELAY
import com.fittrack.ui.navigation.listItemEnter
import com.fittrack.ui.viewmodel.FitTrackViewModel
import com.fittrack.ui.viewmodel.WorkoutSession
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    planId: Long,
    viewModel: FitTrackViewModel,
    onWorkoutComplete: () -> Unit
) {
    var showFinishDialog by remember { mutableStateOf(false) }
    var workoutNotes by remember { mutableStateOf("") }
    var workoutFeeling by remember { mutableStateOf(3) }
    var sleepQuality by remember { mutableStateOf(3) }
    var appetite by remember { mutableStateOf(3) }
    var energyLevel by remember { mutableStateOf(3) }

    val session by viewModel.workoutSession.collectAsState()
    val selectedPlan by viewModel.selectedPlan.collectAsState()

    // 当从首页直接进入训练时，需要先加载计划并开始训练会话
    LaunchedEffect(planId) {
        if (session == null) {
            viewModel.selectPlan(planId)
        }
    }

    // 当计划加载完成后，开始训练会话
    LaunchedEffect(selectedPlan) {
        if (session == null && selectedPlan != null && selectedPlan?.id == planId) {
            viewModel.startWorkout(selectedPlan!!)
        }
    }

    // 控制内容入场
    val contentVisible = remember { MutableTransitionState(false) }
    LaunchedEffect(session) {
        if (session != null) {
            contentVisible.targetState = true
        }
    }

    // 进度条弹性动画值
    val progressAnimatable = remember { Animatable(0f) }
    LaunchedEffect(session?.currentExerciseIndex) {
        session?.let {
            progressAnimatable.animateTo(
                targetValue = (it.currentExerciseIndex + 1).toFloat() / it.exercises.size,
                animationSpec = ProgressSpring.animate
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.planName ?: "训练中...")
                        if (session != null) {
                            val elapsed = (System.currentTimeMillis() - (session?.startTime ?: System.currentTimeMillis())) / 60000
                            Text(
                                "已进行 $elapsed 分钟",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        session?.let { workout ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 进度指示
                item {
                    AnimatedVisibility(
                        visibleState = contentVisible,
                        enter = listItemEnter(delayMillis = 0)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // 弹性数字动画
                                val exerciseIndex by animateFloatAsState(
                                    targetValue = (workout.currentExerciseIndex + 1).toFloat(),
                                    animationSpec = ProgressSpring.animate,
                                    label = "exerciseIndex"
                                )
                                val totalExercises by animateFloatAsState(
                                    targetValue = workout.exercises.size.toFloat(),
                                    animationSpec = ProgressSpring.animate,
                                    label = "totalExercises"
                                )
                                Text(
                                    "动作 ${exerciseIndex.toInt()} / ${totalExercises.toInt()}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                LinearProgressIndicator(
                                    progress = { progressAnimatable.value },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // 当前动作
                val currentExercise = workout.exercises.getOrNull(workout.currentExerciseIndex)
                if (currentExercise != null) {
                    item {
                        AnimatedVisibility(
                            visibleState = contentVisible,
                            enter = listItemEnter(delayMillis = STAGGER_DELAY)
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        currentExercise.name,
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("目标: ${currentExercise.defaultSets}组 x ${currentExercise.defaultReps}次")
                                        if (currentExercise.defaultWeight > 0) {
                                            Text("${currentExercise.defaultWeight}kg")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 记录输入
                    item {
                        AnimatedVisibility(
                            visibleState = contentVisible,
                            enter = listItemEnter(delayMillis = STAGGER_DELAY * 2)
                        ) {
                            var sets by remember { mutableStateOf(currentExercise.defaultSets.toString()) }
                            var reps by remember { mutableStateOf("") }
                            var weights by remember { mutableStateOf("") }

                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("记录本次训练", style = MaterialTheme.typography.titleMedium)

                                    OutlinedTextField(
                                        value = sets,
                                        onValueChange = { sets = it.filter { c -> c.isDigit() } },
                                        label = { Text("完成组数") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = reps,
                                        onValueChange = { reps = it },
                                        label = { Text("每组次数 (用逗号分隔)") },
                                        placeholder = { Text("如: 10,10,8") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = weights,
                                        onValueChange = { weights = it },
                                        label = { Text("每组重量 (用逗号分隔)") },
                                        placeholder = { Text("如: 60,65,70") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )

                                    // 带弹性缩放的保存按钮
                                    val saveInteraction = remember { MutableInteractionSource() }
                                    val savePressed by saveInteraction.collectIsPressedAsState()
                                    val saveScale by animateFloatAsState(
                                        targetValue = if (savePressed) 0.95f else 1f,
                                        animationSpec = ButtonSpring.press,
                                        label = "saveScale"
                                    )

                                    Button(
                                        onClick = {
                                            viewModel.updateExerciseRecord(
                                                exerciseId = currentExercise.id,
                                                sets = sets.toIntOrNull() ?: 0,
                                                reps = reps,
                                                weights = weights
                                            )
                                            if (workout.currentExerciseIndex < workout.exercises.size - 1) {
                                                viewModel.nextExercise()
                                            } else {
                                                showFinishDialog = true
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .scale(saveScale),
                                        interactionSource = saveInteraction
                                    ) {
                                        Text(if (workout.currentExerciseIndex < workout.exercises.size - 1) "保存并继续" else "完成训练")
                                    }
                                }
                            }
                        }
                    }
                }

                // 导航按钮
                item {
                    AnimatedVisibility(
                        visibleState = contentVisible,
                        enter = listItemEnter(delayMillis = STAGGER_DELAY * 3)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (workout.currentExerciseIndex > 0) {
                                OutlinedButton(
                                    onClick = { viewModel.previousExercise() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("上一个")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.cancelWorkout()
                                    onWorkoutComplete()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("取消")
                            }
                        }
                    }
                }
            }
        } ?: run {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    // 完成训练对话框 — 带庆祝缩放动画
    if (showFinishDialog) {
        var dialogVisible by remember { mutableStateOf(false) }
        val dialogAnimScale = remember { Animatable(0.5f) }

        LaunchedEffect(Unit) {
            dialogVisible = true
            dialogAnimScale.animateTo(
                targetValue = 1f,
                animationSpec = CelebratorySpring.bounce
            )
        }

        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 庆祝图标弹性动画
                    val celebScale by animateFloatAsState(
                        targetValue = if (dialogVisible) 1f else 0f,
                        animationSpec = CelebratorySpring.bounce,
                        label = "celebScale"
                    )
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .scale(celebScale),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("训练完成!")
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("今天的训练感觉如何？")

                    // 感受评分
                    StarRatingRow(
                        currentRating = workoutFeeling,
                        onRatingChange = { workoutFeeling = it }
                    )
                    Text(
                        text = "训练感受",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("睡眠质量 (1=很差, 5=很好)", style = MaterialTheme.typography.bodySmall)
                    StarRatingRow(
                        currentRating = sleepQuality,
                        onRatingChange = { sleepQuality = it }
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("食欲 (1=很差, 5=很好)", style = MaterialTheme.typography.bodySmall)
                    StarRatingRow(
                        currentRating = appetite,
                        onRatingChange = { appetite = it }
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("能量水平 (1=很差, 5=很好)", style = MaterialTheme.typography.bodySmall)
                    StarRatingRow(
                        currentRating = energyLevel,
                        onRatingChange = { energyLevel = it }
                    )

                    OutlinedTextField(
                        value = workoutNotes,
                        onValueChange = { workoutNotes = it },
                        label = { Text("训练笔记（可选）") },
                        placeholder = { Text("记录一下今天的训练感受") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.finishWorkout(
                            workoutFeeling,
                            workoutNotes,
                            sleepQuality,
                            appetite,
                            energyLevel
                        ) {
                            onWorkoutComplete()
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) {
                    Text("继续训练")
                }
            }
        )
    }
}

/** 可复用的星级评分组件（带弹性缩放反馈） */
@Composable
private fun StarRatingRow(
    currentRating: Int,
    onRatingChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        repeat(5) { index ->
            val rating = index + 1
            val starInteraction = remember { MutableInteractionSource() }
            val starPressed by starInteraction.collectIsPressedAsState()
            val starScale by animateFloatAsState(
                targetValue = if (starPressed) 0.7f else if (rating <= currentRating) 1.1f else 1f,
                animationSpec = IconSpring.press,
                label = "starScale$index"
            )
            IconButton(
                onClick = { onRatingChange(rating) },
                interactionSource = starInteraction
            ) {
                Icon(
                    if (rating <= currentRating) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.scale(starScale),
                    tint = if (rating <= currentRating)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
