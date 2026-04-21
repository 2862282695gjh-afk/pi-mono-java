package com.fittrack.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fittrack.data.db.FitTrackDatabase
import com.fittrack.data.repository.FitTrackRepository
import com.fittrack.data.storage.SettingsManager
import com.fittrack.data.api.QwenRepository
import com.fittrack.ui.screens.AddPlanScreen
import com.fittrack.ui.screens.ChatScreen
import com.fittrack.ui.screens.HomeScreen
import com.fittrack.ui.screens.OnboardingScreen
import com.fittrack.ui.screens.PlanDetailScreen
import com.fittrack.ui.screens.PlanGeneratorScreen
import com.fittrack.ui.screens.PlanListScreen
import com.fittrack.ui.screens.ProfileScreen
import com.fittrack.ui.screens.SettingsScreen
import com.fittrack.ui.screens.StatisticsScreen
import com.fittrack.ui.screens.WorkoutScreen
import com.fittrack.ui.viewmodel.ChatViewModel
import com.fittrack.ui.viewmodel.FitTrackViewModel
import com.fittrack.ui.viewmodel.PlanGeneratorViewModel
import com.fittrack.ui.viewmodel.ProfileViewModel
import com.fittrack.ui.viewmodel.SettingsViewModel

@Composable
fun FitTrackNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current

    // 使用 remember 创建单例数据库实例
    val database = remember { FitTrackDatabase.getDatabase(context) }

    // 使用 remember 创建 repository 实例
    val repository = remember {
        FitTrackRepository(
            workoutPlanDao = database.workoutPlanDao(),
            exerciseDao = database.exerciseDao(),
            workoutRecordDao = database.workoutRecordDao(),
            exerciseRecordDao = database.exerciseRecordDao(),
            userProfileDao = database.userProfileDao()
        )
    }

    val settingsManager = SettingsManager.getInstance(context)
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(settingsManager)
    )

    // 创建 QwenRepository 实例用于 AI 功能
    val qwenRepository = QwenRepository.getInstance(settingsManager)

    // 创建 FitTrackViewModel 并注入 QwenRepository
    val viewModel: FitTrackViewModel = viewModel(factory = FitTrackViewModel.Factory(repository, qwenRepository))

    // 检查是否已配置 API Key（使用初始值，避免动态变化导致导航问题）
    val isConfigured by settingsViewModel.isConfigured.collectAsState()

    // 使用 remember 保存初始目的地，避免重组时变化
    val startDestination = remember { Screen.Onboarding.route }

    // 监听配置状态变化，自动导航
    LaunchedEffect(isConfigured) {
        if (isConfigured && navController.currentDestination?.route == Screen.Onboarding.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Onboarding.route) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // 首次启动引导页
        composable(
            route = Screen.Onboarding.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            OnboardingScreen(
                viewModel = settingsViewModel,
                onComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Home.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToPlanList = { navController.navigate(Screen.PlanList.route) },
                onNavigateToPlanDetail = { planId ->
                    navController.navigate(Screen.PlanDetail.createRoute(planId))
                },
                onStartWorkout = { planId ->
                    navController.navigate(Screen.Workout.createRoute(planId))
                },
                onNavigateToStatistics = { navController.navigate(Screen.Statistics.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                onNavigateToChat = { navController.navigate(Screen.Chat.route) },
                onNavigateToPlanGenerator = { navController.navigate(Screen.PlanGenerator.route) }
            )
        }

        composable(
            route = Screen.PlanList.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            PlanListScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAddPlan = { navController.navigate(Screen.AddPlan.route) },
                onNavigateToPlanDetail = { planId ->
                    navController.navigate(Screen.PlanDetail.createRoute(planId))
                }
            )
        }

        composable(
            route = Screen.AddPlan.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            AddPlanScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PlanDetail.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) { backStackEntry ->
            val planId = backStackEntry.arguments?.getString("planId")?.toLongOrNull() ?: 0L
            PlanDetailScreen(
                planId = planId,
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onStartWorkout = {
                    navController.navigate(Screen.Workout.createRoute(planId))
                }
            )
        }

        composable(
            route = Screen.Workout.route,
            enterTransition = { EnterUp },
            exitTransition = { ExitDown },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) { backStackEntry ->
            val planId = backStackEntry.arguments?.getString("planId")?.toLongOrNull() ?: 0L
            WorkoutScreen(
                planId = planId,
                viewModel = viewModel,
                onWorkoutComplete = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                }
            )
        }

        composable(
            route = Screen.Statistics.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            StatisticsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Settings.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onClearApiKey = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Profile.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            val profileViewModel: ProfileViewModel = viewModel(
                factory = ProfileViewModel.Factory(repository, context)
            )
            ProfileScreen(
                viewModel = profileViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Chat.route,
            enterTransition = { EnterForward },
            exitTransition = { ExitForward },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            val chatViewModel: ChatViewModel = viewModel(
                factory = ChatViewModel.Factory(qwenRepository, repository, database.chatDao())
            )
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PlanGenerator.route,
            enterTransition = { EnterUp },
            exitTransition = { ExitDown },
            popEnterTransition = { EnterBackward },
            popExitTransition = { ExitBackward }
        ) {
            val generatorViewModel: PlanGeneratorViewModel = viewModel(
                factory = PlanGeneratorViewModel.Factory(qwenRepository, repository)
            )
            PlanGeneratorScreen(
                viewModel = generatorViewModel,
                onNavigateBack = { navController.popBackStack() },
                onPlanSaved = { planId ->
                    navController.navigate(Screen.PlanDetail.createRoute(planId)) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                }
            )
        }
    }
}
