package com.dskmusic.lokate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dskmusic.lokate.di.ServiceLocator
import com.dskmusic.lokate.location.LocationServiceController
import com.dskmusic.lokate.location.LocationUpdateWorker
import com.dskmusic.lokate.ui.admin.AdminScreen
import com.dskmusic.lokate.ui.auth.LoginScreen
import com.dskmusic.lokate.ui.auth.RegisterScreen
import com.dskmusic.lokate.ui.emergency.EmergencyMessageScreen
import com.dskmusic.lokate.ui.group.GroupScreen
import com.dskmusic.lokate.ui.history.HistoryScreen
import com.dskmusic.lokate.ui.map.MapScreen
import com.dskmusic.lokate.ui.member.MemberDetailScreen
import com.dskmusic.lokate.ui.onboarding.PermissionOnboardingScreen
import com.dskmusic.lokate.ui.people.PeopleScreen
import com.dskmusic.lokate.ui.settings.SettingsScreen
import com.dskmusic.lokate.ui.zones.ZoneEditScreen
import com.dskmusic.lokate.ui.zones.ZonesScreen

@Composable
fun LokateNavHost(locator: ServiceLocator, startDestination: String) {
    val navController: NavHostController = rememberNavController()
    val context = LocalContext.current

    fun ensureLocationSharingStarted() {
        LocationServiceController.ensureStarted(context)
        LocationUpdateWorker.schedule(context)
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            PermissionOnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.LOGIN) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                locator = locator,
                onLoggedIn = {
                    ensureLocationSharingStarted()
                    navController.navigate(Routes.MAP) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onGoToRegister = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                locator = locator,
                onRegistered = {
                    ensureLocationSharingStarted()
                    navController.navigate(Routes.MAP) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onGoToLogin = { navController.popBackStack() },
            )
        }
        composable(Routes.GROUP) {
            GroupScreen(locator = locator, onDone = { navController.popBackStack() })
        }
        composable(Routes.MAP) { backStackEntry ->
            MapScreen(
                locator = locator,
                onOpenZones = { navController.navigate(Routes.ZONES) },
                onOpenPeople = { navController.navigate(Routes.PEOPLE) },
                onOpenHistory = { navController.navigate(Routes.history()) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenGroup = { navController.navigate(Routes.GROUP) },
                onOpenMember = { userId -> navController.navigate(Routes.memberDetail(userId)) },
                onOpenAdmin = { navController.navigate(Routes.ADMIN) },
                focusUserIdFlow = backStackEntry.savedStateHandle.getStateFlow("focus_user_id", null as String?),
                onFocusUserIdConsumed = { backStackEntry.savedStateHandle.remove<String>("focus_user_id") },
            )
        }
        composable(Routes.ADMIN) {
            AdminScreen(locator = locator, onBack = { navController.popBackStack() })
        }
        composable(Routes.PEOPLE) {
            PeopleScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onSelectMember = { userId ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("focus_user_id", userId)
                    navController.popBackStack()
                },
                onOpenDetail = { userId -> navController.navigate(Routes.memberDetail(userId)) },
            )
        }
        composable(Routes.ZONES) {
            ZonesScreen(
                locator = locator,
                onAddZone = { navController.navigate(Routes.ZONE_EDIT) },
                onEditZone = { zoneId -> navController.navigate("zone_edit/$zoneId") },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ZONE_EDIT) {
            ZoneEditScreen(locator = locator, zoneId = null, onDone = { navController.popBackStack() })
        }
        composable(Routes.ZONE_EDIT_WITH_ID) { backStackEntry ->
            ZoneEditScreen(
                locator = locator,
                zoneId = backStackEntry.arguments?.getString("zoneId"),
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            Routes.HISTORY,
            arguments = listOf(
                navArgument("userId") { type = NavType.StringType; defaultValue = "" },
                navArgument("displayName") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId").orEmpty().ifBlank { null }
            val displayNameEncoded = backStackEntry.arguments?.getString("displayName").orEmpty()
            val displayName = if (displayNameEncoded.isBlank()) null else java.net.URLDecoder.decode(displayNameEncoded, "UTF-8")
            HistoryScreen(
                locator = locator,
                targetUserId = userId,
                targetDisplayName = displayName,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.MEMBER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId").orEmpty()
            MemberDetailScreen(
                locator = locator,
                userId = userId,
                onBack = { navController.popBackStack() },
                onOpenHistory = { id, name -> navController.navigate(Routes.history(id, name)) },
                onOpenMap = { id ->
                    navController.getBackStackEntry(Routes.MAP).savedStateHandle["focus_user_id"] = id
                    navController.popBackStack(Routes.MAP, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                locator = locator,
                onBack = { navController.popBackStack() },
                onLoggedOut = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(
            Routes.EMERGENCY_MESSAGE,
            arguments = listOf(
                navArgument("sender") { type = NavType.StringType; defaultValue = "" },
                navArgument("text") { type = NavType.StringType; defaultValue = "" },
                navArgument("attachmentUrl") { type = NavType.StringType; defaultValue = "" },
                navArgument("attachmentKind") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            fun arg(name: String) = backStackEntry.arguments?.getString(name).orEmpty().let {
                if (it.isBlank()) "" else java.net.URLDecoder.decode(it, "UTF-8")
            }
            EmergencyMessageScreen(
                sender = arg("sender"),
                text = arg("text"),
                attachmentUrl = arg("attachmentUrl").ifBlank { null },
                attachmentKind = arg("attachmentKind").ifBlank { null },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
