package it.mioplayer

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm by viewModels<Vm>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onStarted()
    }

    override fun onStop() {
        super.onStop()
        vm.onStopped()
    }
}

@Composable
fun App(vm: Vm) {
    val activity = LocalContext.current as? Activity
    var confirmExit by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Surface(color = Bg) {
            if (vm.splash) {
                SplashScreen(vm)
            } else if (confirmExit) {
                BackHandler(true) { confirmExit = false }
                ExitDialog(onStay = { confirmExit = false }, onExit = { activity?.finish() })
            } else {
                BackHandler(vm.screen is Screen.Home) { confirmExit = true }
                BackHandler(vm.screen !is Screen.Home) { vm.back() }
                when (val s = vm.screen) {
                    is Screen.Home -> HomeScreen(vm)
                    is Screen.Settings -> SettingsScreen(vm)
                    is Screen.Series -> SeriesScreen(vm, s)
                    is Screen.Player -> PlayerScreen(vm, s)
                }
            }
        }
    }
}
