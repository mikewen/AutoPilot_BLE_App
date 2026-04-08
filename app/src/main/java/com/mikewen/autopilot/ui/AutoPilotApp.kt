package com.mikewen.autopilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.mikewen.autopilot.ui.screens.*
import com.mikewen.autopilot.viewmodel.AutopilotViewModel

sealed class Screen(val route: String) {
    object TypeSelect   : Screen("type_select")
    object Scan         : Screen("scan")
    object Dashboard    : Screen("dashboard")
    object Settings     : Screen("settings")
    object Calibration  : Screen("calibration")
    object MapTarget    : Screen("map_target")
}

@Composable
fun AutoPilotApp(vm: AutopilotViewModel = viewModel()) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.TypeSelect.route) {

        composable(Screen.TypeSelect.route) {
            // System back on the root screen exits the app — default behaviour, no BackHandler needed
            TypeSelectScreen(
                onTypeSelected = { type ->
                    vm.selectAutopilotType(type)
                    navController.navigate(Screen.Scan.route)
                }
            )
        }

        composable(Screen.Scan.route) {
            // System back = go back to TypeSelect
            BackHandler { navController.popBackStack() }
            ScanScreen(
                vm          = vm,
                onConnected = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Scan.route) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Dashboard.route) {
            // System back on the dashboard = disconnect and return to TypeSelect
            BackHandler {
                vm.disconnect()
                navController.navigate(Screen.TypeSelect.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            DashboardScreen(
                vm           = vm,
                onSettings   = { navController.navigate(Screen.Settings.route) },
                onMapTarget  = { navController.navigate(Screen.MapTarget.route) },
                onDisconnect = {
                    vm.disconnect()
                    navController.navigate(Screen.TypeSelect.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            // System back = go back to Dashboard
            BackHandler { navController.popBackStack() }
            SettingsScreen(
                vm              = vm,
                onBack          = { navController.popBackStack() },
                onCalibration   = { navController.navigate(Screen.Calibration.route) }
            )
        }

        composable(Screen.Calibration.route) {
            BackHandler { navController.popBackStack() }
            CalibrationScreen(
                vm     = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MapTarget.route) {
            BackHandler { navController.popBackStack() }
            MapTargetScreen(
                vm     = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}