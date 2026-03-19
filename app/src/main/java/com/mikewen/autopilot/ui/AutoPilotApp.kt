package com.mikewen.autopilot.ui

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mikewen.autopilot.ui.screens.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel

sealed class Screen(val route: String) {
    object TypeSelect  : Screen("type_select")
    object Scan        : Screen("scan")
    object Dashboard   : Screen("dashboard")
    object Settings    : Screen("settings")
}

@Composable
fun AutoPilotApp(vm: AutopilotViewModel = viewModel()) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.TypeSelect.route) {

        composable(Screen.TypeSelect.route) {
            TypeSelectScreen(
                onTypeSelected = { type ->
                    vm.selectAutopilotType(type)
                    navController.navigate(Screen.Scan.route)
                }
            )
        }

        composable(Screen.Scan.route) {
            ScanScreen(
                vm = vm,
                onConnected = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Scan.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                vm = vm,
                onSettings = { navController.navigate(Screen.Settings.route) },
                onDisconnect = {
                    vm.disconnect()
                    navController.navigate(Screen.TypeSelect.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
