package com.fittrack.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import com.fittrack.ui.navigation.IconSpring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fittrack.ui.viewmodel.ChatMessage
import com.fittrack.ui.viewmodel.ChatViewModel
import com.fittrack.ui.components.MarkdownText
import com.fittrack.ui.navigation.MessageBubbleEnter
import com.fittrack.ui.navigation.listItemExit
import kotlinx.coroutines.launch

/**
 * AI 教练对话界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedMediaUri by viewModel.selectedMediaUri.collectAsState()
    val selectedMediaType by viewModel.selectedMediaType.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val showSessionList by viewModel.showSessionList.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // 媒体选择器
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val contentType = context.contentResolver.getType(uri) ?: ""
            val type = when {
                contentType.startsWith("image") -> "image"
                contentType.startsWith("video") -> "video"
                else -> "image"
            }
            viewModel.setMedia(uri, type)
        }
    }

    // 权限请求
    var showPermissionDialog by remember { mutableStateOf(false) }

    // 快捷问题列表
    val quickQuestions = listOf(
        "如何热身" to Icons.Filled.SelfImprovement,
        "热量估算" to Icons.Filled.LocalFireDepartment,
        "动作细节" to Icons.Filled.AccessibilityNew,
        "训练建议" to Icons.Filled.Recommend
    )

    // 当消息更新时滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.toggleSessionList() }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "AI 健身教练",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (showSessionList) "选择对话..." else if (isLoading) "正在思考..." else "点击查看历史对话",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (sessions.size > 1) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (showSessionList) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = "切换对话",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.createNewSession() }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "新对话"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 会话历史列表
                AnimatedVisibility(
                    visible = showSessionList,
                    enter = MessageBubbleEnter,
                    exit = listItemExit
                ) {
                    SessionHistoryList(
                        sessions = sessions,
                        onSelectSession = { viewModel.selectSession(it) },
                        onDeleteSession = { viewModel.deleteSession(it) },
                        onNewSession = { viewModel.createNewSession() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 消息列表
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.id }
                    ) { message ->
                        // AI 回复气泡添加从底部滑入+缩放动画
                        AnimatedVisibility(
                            visible = true,
                            enter = if (message.isFromUser) {
                                com.fittrack.ui.navigation.listItemEnter(delayMillis = 0)
                            } else {
                                MessageBubbleEnter
                            }
                        ) {
                            MessageBubble(
                                message = message,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 快捷问题（只在对话刚开始时显示）
                    if (messages.size <= 1) {
                        item {
                            QuickQuestionsSection(
                                questions = quickQuestions,
                                onQuestionClick = { question ->
                                    viewModel.quickQuestion(question)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // 输入区域
                MessageInput(
                    text = inputText,
                    isLoading = isLoading,
                    selectedMediaUri = selectedMediaUri,
                    selectedMediaType = selectedMediaType,
                    onTextChange = { viewModel.updateInputText(it) },
                    onSend = {
                        if (selectedMediaUri != null) {
                            viewModel.sendMediaMessage(context)
                        } else {
                            viewModel.sendMessage()
                        }
                        keyboardController?.hide()
                    },
                    onAttachClick = {
                        mediaPickerLauncher.launch("*/*")
                    },
                    onRemoveMedia = { viewModel.setMedia(null, null) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 会话历史列表
 */
@Composable
fun SessionHistoryList(
    sessions: List<com.fittrack.data.entity.ChatSession>,
    onSelectSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "历史对话",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )
                TextButton(onClick = onNewSession) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新对话")
                }
            }

            if (sessions.isEmpty()) {
                Text(
                    text = "暂无历史对话",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(sessions) { session ->
                        SessionHistoryItem(
                            session = session,
                            onSelect = { onSelectSession(session.id) },
                            onDelete = { onDeleteSession(session.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 会话历史项
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryItem(
    session: com.fittrack.data.entity.ChatSession,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { "新对话" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSessionTime(session.lastMessageAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除对话") },
            text = { Text("确定要删除这个对话吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 格式化会话时间
 */
fun formatSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = timestamp

    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        diff < 604800_000 -> {
            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale.CHINA)
            sdf.format(calendar.time)
        }
        else -> {
            val sdf = java.text.SimpleDateFormat("MM/dd", java.util.Locale.CHINA)
            sdf.format(calendar.time)
        }
    }
}

/**
 * 消息气泡
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.isFromUser

    Row(
        modifier = modifier,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // AI 头像
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // 消息气泡
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .padding(12.dp)
            ) {
                if (message.isLoading && message.content.isBlank()) {
                    // 正在等待响应，显示加载指示器
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = if (isUser) Color.White else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "正在思考中...",
                            color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    // 有内容时使用 Markdown 富文本渲染（包括流式输出）
                    MarkdownText(
                        markdown = message.content,
                        color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14
                    )
                    // 如果还在加载，显示打字光标效果
                    if (message.isLoading) {
                        Text(
                            text = "▌",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 时间戳
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(message.timestamp))
            Text(
                text = time,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 用户头像占位
        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 快捷问题区域
 */
@Composable
fun QuickQuestionsSection(
    questions: List<Pair<String, ImageVector>>,
    onQuestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        Text(
            text = "快捷提问",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            questions.forEach { (question, icon) ->
                SuggestionChip(
                    onClick = { onQuestionClick(question) },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = question,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

/**
 * 消息输入框
 */
@Composable
fun MessageInput(
    text: String,
    isLoading: Boolean,
    selectedMediaUri: Uri?,
    selectedMediaType: String?,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onRemoveMedia: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 媒体预览
            if (selectedMediaUri != null) {
                MediaPreview(
                    uri = selectedMediaUri,
                    mediaType = selectedMediaType ?: "image",
                    onRemove = onRemoveMedia
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 附件按钮
                IconButton(
                    onClick = onAttachClick,
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = if (selectedMediaUri != null) "添加描述（可选）..." else "输入你的问题...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if ((text.isNotBlank() || selectedMediaUri != null) && !isLoading) {
                                onSend()
                            }
                        }
                    ),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = text.isNotBlank(),
                            enter = MessageBubbleEnter,
                            exit = listItemExit
                        ) {
                            IconButton(
                                onClick = { onTextChange("") },
                                enabled = !isLoading
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )

                // 发送按钮（带弹性缩放反馈）
                val sendInteraction = remember { MutableInteractionSource() }
                val sendPressed by sendInteraction.collectIsPressedAsState()
                val sendScale by animateFloatAsState(
                    targetValue = if (sendPressed) 0.85f else 1f,
                    animationSpec = IconSpring.press,
                    label = "sendScale"
                )

                FilledIconButton(
                    onClick = onSend,
                    enabled = (text.isNotBlank() || selectedMediaUri != null) && !isLoading,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(sendScale),
                    interactionSource = sendInteraction,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * 媒体预览组件
 */
@Composable
fun MediaPreview(
    uri: Uri,
    mediaType: String,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        when (mediaType) {
            "video" -> {
                // 视频预览（显示图标）
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoFile,
                            contentDescription = "视频",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "视频文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                // 图片预览
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "选中的图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // 删除按钮
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(32.dp)
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
