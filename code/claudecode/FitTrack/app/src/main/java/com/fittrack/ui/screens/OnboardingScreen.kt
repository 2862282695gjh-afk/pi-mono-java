package com.fittrack.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import com.fittrack.ui.navigation.ButtonSpring
import com.fittrack.ui.navigation.IconSpring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fittrack.ui.navigation.STAGGER_DELAY
import com.fittrack.ui.navigation.listItemEnter
import com.fittrack.ui.viewmodel.SettingsViewModel

/**
 * 首次启动引导页面
 * 用于配置百炼平台 API Key
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: SettingsViewModel,
    onComplete: () -> Unit
) {
    val apiKeyInput by viewModel.apiKeyInput.collectAsState()
    val apiBaseUrl by viewModel.apiBaseUrlInput.collectAsState()
    val showApiKey by viewModel.showApiKey.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    // 保存成功后自动跳转
    LaunchedEffect(saveState) {
        if (saveState is SettingsViewModel.SaveState.Success) {
            onComplete()
        }
    }

    // 控制各部分交错入场
    val step1Visible = remember { MutableTransitionState(false) }
    val step2Visible = remember { MutableTransitionState(false) }
    val step3Visible = remember { MutableTransitionState(false) }

    LaunchedEffect(Unit) {
        step1Visible.targetState = true
    }
    LaunchedEffect(step1Visible.currentState) {
        if (step1Visible.currentState) step2Visible.targetState = true
    }
    LaunchedEffect(step2Visible.currentState) {
        if (step2Visible.currentState) step3Visible.targetState = true
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // ── Logo 和标题 ──
            AnimatedVisibility(
                visibleState = step1Visible,
                enter = listItemEnter(delayMillis = 0)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Logo 弹性入场
                    val logoScale by animateFloatAsState(
                        targetValue = if (step1Visible.currentState) 1f else 0.5f,
                        animationSpec = IconSpring.press,
                        label = "logoScale"
                    )
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        modifier = Modifier
                            .size(80.dp)
                            .scale(logoScale),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "欢迎使用 FitTrack",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "智能健身助手",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── 说明卡片 ──
            AnimatedVisibility(
                visibleState = step1Visible,
                enter = listItemEnter(delayMillis = STAGGER_DELAY * 2)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "配置说明",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "FitTrack 使用阿里云百炼平台的 AI 能力为您提供智能健身建议。" +
                                    "\n\n请输入您的百炼平台 API Key 以开始使用。" +
                                    "\n\n获取方式：登录阿里云百炼控制台 → API-KEY 管理 → 创建新的 API Key",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── API Key 输入 ──
            AnimatedVisibility(
                visibleState = step2Visible,
                enter = listItemEnter(delayMillis = 0)
            ) {
                Column {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { viewModel.updateApiKeyInput(it) },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-xxxxxxxx") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.toggleShowApiKey() }) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示"
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Key, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        isError = saveState is SettingsViewModel.SaveState.Error
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // API Base URL 输入
                    OutlinedTextField(
                        value = apiBaseUrl,
                        onValueChange = { viewModel.updateApiBaseUrlInput(it) },
                        label = { Text("API 地址") },
                        placeholder = { Text("https://dashscope.aliyuncs.com/compatible-mode/v1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        leadingIcon = {
                            Icon(Icons.Default.Cloud, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("默认使用阿里云百炼平台地址，一般无需修改")
                        }
                    )

                    // 错误提示
                    if (saveState is SettingsViewModel.SaveState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = (saveState as SettingsViewModel.SaveState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── 保存按钮（弹性按压效果） ──
            AnimatedVisibility(
                visibleState = step3Visible,
                enter = listItemEnter(delayMillis = 0)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val buttonInteraction = remember { MutableInteractionSource() }
                    val isPressed by buttonInteraction.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.95f else 1f,
                        animationSpec = ButtonSpring.press,
                        label = "buttonScale"
                    )

                    Button(
                        onClick = { viewModel.saveApiKey() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .scale(buttonScale),
                        enabled = apiKeyInput.isNotBlank() && saveState !is SettingsViewModel.SaveState.Saving,
                        interactionSource = buttonInteraction
                    ) {
                        if (saveState is SettingsViewModel.SaveState.Saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开始使用", fontSize = 16.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = { /* TODO: 打开帮助页面 */ }) {
                        Text("如何获取 API Key？")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── 底部版权信息 ──
            AnimatedVisibility(
                visibleState = step3Visible,
                enter = listItemEnter(delayMillis = STAGGER_DELAY)
            ) {
                Text(
                    text = "Powered by 阿里云百炼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
