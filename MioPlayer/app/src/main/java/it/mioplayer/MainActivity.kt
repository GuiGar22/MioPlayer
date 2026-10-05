package it.mioplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    private val vm by viewModels<Vm>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(vm) }
    }
}

@Composable
fun App(vm: Vm) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Surface(color = Bg) {
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
