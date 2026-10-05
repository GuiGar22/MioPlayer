package it.mioplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(vm: Vm, s: Screen.Player) {
    val ctx = LocalContext.current
    var idx by remember { mutableStateOf(s.index) }
    val item = s.list[idx]
    val live = item.type == Type.LIVE
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    var hud by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    val cur = rememberUpdatedState(idx)

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Impossibile riprodurre (${e.errorCodeName})" }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && s.list[cur.value].type == Type.SERIE && cur.value < s.list.size - 1) {
                    idx = cur.value + 1
                }
                if (state == Player.STATE_READY) error = null
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(idx) {
        error = null
        player.setMediaItem(MediaItem.fromUri(item.url))
        player.prepare()
        vm.saveLast(item)
    }
    LaunchedEffect(idx, tick) { hud = true; delay(4000); hud = false }
    LaunchedEffect(Unit) { fr.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(fr)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                tick++
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp ->
                        if (live) { idx = (idx + 1) % s.list.size; true } else false
                    Key.DirectionDown, Key.ChannelDown ->
                        if (live) { idx = (idx - 1 + s.list.size) % s.list.size; true } else false
                    Key.DirectionLeft ->
                        if (!live) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); true } else false
                    Key.DirectionRight ->
                        if (!live) { player.seekTo(player.currentPosition + 10_000); true } else false
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play(); true
                    }
                    Key.Menu -> { vm.toggleFav(item); true }
                    else -> false
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    isFocusable = false
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)
        error?.let {
            Text(it, color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }
        AnimatedVisibility(hud, Modifier.align(Alignment.BottomStart)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 48.dp, vertical = 28.dp)
            ) {
                Text((if (vm.isFav(item)) "♥ " else "") + item.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (live) "▲ ▼ canale   •   OK pausa   •   Menu ♥ preferito"
                    else "◀ ▶ ±10 s   •   OK pausa   •   Menu ♥ preferito",
                    color = Cyan, fontSize = 16.sp
                )
            }
        }
    }
}
