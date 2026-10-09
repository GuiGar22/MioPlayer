package it.mioplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val P_NONE = 0
private const val P_AUDIO = 1
private const val P_SUBS = 2
private const val P_STYLE = 3
private const val P_SEASONS = 4
private const val P_PLAYER = 5
private const val P_CHANNELS = 6

private class PRow(
    val text: String,
    val selected: Boolean = false,
    val header: Boolean = false,
    val action: () -> Unit = {}
)

private fun trackLabel(g: Tracks.Group, n: Int): String {
    val f = g.getTrackFormat(0)
    val label = f.label
    val code = f.language
    val lang = if (code == null || code == "und") null
    else Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
    return when {
        label != null && lang != null && !label.contains(lang, true) -> "$label ($lang)"
        label != null -> label
        lang != null -> lang
        else -> "Traccia $n"
    }
}

/** Per le serie la lista di riproduzione è l'intera serie (tutte le stagioni, in ordine). */
private fun expandSeries(vm: Vm, s: Screen.Player): Pair<List<Item>, Int> {
    val cur = s.list[s.index]
    val name = cur.series
    if (cur.type != Type.SERIE || name == null) return Pair(s.list, s.index)
    val all = vm.items.filter { it.type == Type.SERIE && it.series == name }
        .sortedWith(compareBy<Item>({ it.season }, { it.episode }))
    val i = all.indexOfFirst { it.url == cur.url }
    return if (i >= 0) Pair(all, i) else Pair(s.list, s.index)
}

private fun hm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(ts * 1000))

@Composable
private fun RoundBtn(label: String, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(modifier.size(size), onClick = onClick, shape = CircleShape) { f ->
        Box(
            Modifier.fillMaxSize().background(if (f) Accent else Color(0x99000000)),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SeekBar(pos: Long, dur: Long, modifier: Modifier, onSeek: (Long) -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Box(
        modifier.height(36.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onSeek(-10_000L); true }
                else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onSeek(10_000L); true }
                else false
            }
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.fillMaxWidth().height(if (f) 12.dp else 6.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color(0x55FFFFFF))
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (f) Cyan else Color.White))
        }
    }
}

@Composable
private fun BarFill(frac: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x44FFFFFF))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(Cyan))
    }
}

/** Guida TV in sovrimpressione (canali in diretta): canale, ora in onda, a seguire, canali vicini. */
@Composable
private fun GuideBar(vm: Vm, list: List<Item>, idx: Int, epg: List<EpgEntry>) {
    val item = list[idx]
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(horizontal = 48.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E2640)),
                contentAlignment = Alignment.Center
            ) {
                if (item.logo != null) AsyncImage(
                    item.logo, null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (vm.isFav(item)) "♥ " else "") + item.name,
                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (item.group.isNotEmpty()) Text(item.group, color = Color.LightGray, fontSize = 15.sp, maxLines = 1)
            }
        }
        val now = System.currentTimeMillis() / 1000
        val curE = epg.firstOrNull { it.start <= now && it.end > now }
        val nextE = epg.firstOrNull { curE != null && it.start >= curE.end }
        if (curE != null) {
            Text(
                "Ora in onda:  ${curE.title}   ${hm(curE.start)} – ${hm(curE.end)}",
                color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            BarFill((now - curE.start).toFloat() / (curE.end - curE.start).coerceAtLeast(1L).toFloat())
        }
        if (nextE != null) Text(
            "Poi:  ${nextE.title}   ${hm(nextE.start)}",
            color = Color.LightGray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        val n = list.size
        val around = (-1..3).map { ((idx + it) % n + n) % n }.distinct().take(5)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            around.forEach { i ->
                Box(
                    Modifier.width(170.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (i == idx) Accent else Color(0x66151B2E))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(list[i].name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("▲ ▼ cambia canale   •   ◀ elenco canali   •   Menu ♥ preferito", color = Cyan, fontSize = 14.sp)
    }
}

@Composable
private fun CenterPanel(
    title: String,
    fr: FocusRequester,
    focusClose: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).heightIn(max = 440.dp)
                .clip(RoundedCornerShape(16.dp)).background(CardBg).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            content()
            Chip("Chiudi", false, modifier = if (focusClose) Modifier.focusRequester(fr) else Modifier, wide = true) { onClose() }
        }
    }
}

/** Pannello Stagioni: tutte le stagioni e gli episodi, senza uscire dal video. */
@Composable
private fun SeasonPanel(
    vm: Vm,
    list: List<Item>,
    curIdx: Int,
    seasons: List<Int>,
    season: Int,
    onSeason: (Int) -> Unit,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    val eps = remember(list, season) { list.indices.filter { list[it].season == season } }
    val state = rememberLazyListState()
    val curFr = remember { FocusRequester() }
    val curPos = eps.indexOf(curIdx)
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.5f).background(Color(0xF00B0F1A)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stagioni", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip("✕ Chiudi", false) { onClose() }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons.size) { i ->
                    Chip("Stagione ${seasons[i]}", seasons[i] == season) { onSeason(seasons[i]) }
                }
            }
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(eps.size) { n ->
                    val li = eps[n]
                    val e = list[li]
                    val nowPlaying = li == curIdx
                    val p = vm.posOf(e)
                    Focusable(
                        (if (nowPlaying) Modifier.focusRequester(curFr) else Modifier).fillMaxWidth(),
                        onClick = { onPick(li) }
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (nowPlaying) Color(0x44FFC107) else if (f) Color(0xFF2A3350) else CardBg)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E2640))) {
                                if (e.logo != null) AsyncImage(
                                    e.logo, null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (p != null && p.dur > 0) Box(
                                    Modifier.align(Alignment.BottomStart)
                                        .fillMaxWidth((p.pos.toFloat() / p.dur).coerceIn(0f, 1f))
                                        .height(4.dp).background(Cyan)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (e.episode > 0) "Episodio ${e.episode}" else e.name,
                                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1
                                )
                                Text(e.name, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (nowPlaying) Text("▶ In riproduzione", color = Color(0xFFFFC107), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(season) {
        if (curPos >= 0) {
            state.scrollToItem(maxOf(0, curPos - 1))
            delay(80)
            try { curFr.requestFocus() } catch (_: Exception) {}
        }
    }
}

/** Elenco dei canali in sovrapposizione a sinistra: il canale continua a vedersi sotto. */
@Composable
private fun ChannelPanel(vm: Vm, list: List<Item>, curIdx: Int, onPick: (Int) -> Unit) {
    val state = rememberLazyListState()
    val curFr = remember { FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.38f).background(Color(0xDD0B0F1A)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Canali", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${list.size} canali  ·  OK per cambiare  ·  Indietro per chiudere", color = Color.Gray, fontSize = 13.sp)
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.size) { i ->
                    val m = list[i]
                    val now = i == curIdx
                    Focusable(
                        (if (now) Modifier.focusRequester(curFr) else Modifier).fillMaxWidth(),
                        onClick = { onPick(i) }
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (now) Color(0x44FFC107) else if (f) Color(0xFF2A3350) else Color(0x99151B2E))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.width(72.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E2640)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (m.logo != null) AsyncImage(
                                    m.logo, null, contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(2.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                m.name, color = Color.White, fontSize = 17.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            if (vm.isFav(m)) Text("♥", color = Pink, fontSize = 18.sp)
                            if (now) Text("  ▶", color = Color(0xFFFFC107), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        state.scrollToItem(maxOf(0, curIdx - 3))
        delay(80)
        try { curFr.requestFocus() } catch (_: Exception) {}
    }
}

@Composable
fun PlayerScreen(vm: Vm, s: Screen.Player) {
    val ctx = LocalContext.current
    val start = remember { expandSeries(vm, s) }
    val list = start.first
    var idx by remember { mutableStateOf(start.second) }
    val item = list[idx]
    val live = item.type == Type.LIVE
    val isSeries = item.type == Type.SERIE
    val curItem = rememberUpdatedState(item)
    val curIdx = rememberUpdatedState(idx)
    val player = remember {
        val load = if (live) DefaultLoadControl.Builder().setBufferDurationsMs(3000, 20000, 800, 1500).build()
        else DefaultLoadControl.Builder().setBufferDurationsMs(15000, 50000, 1500, 3000).build()
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setAllowCrossProtocolRedirects(true)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
        ExoPlayer.Builder(ctx).setLoadControl(load).setMediaSourceFactory(sources).build().apply {
            playWhenReady = true
            val b = trackSelectionParameters.buildUpon()
            if (vm.audioLang != "auto") b.setPreferredAudioLanguage(vm.audioLang)
            if (vm.subLang == "off") b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else b.setPreferredTextLanguage(vm.subLang)
            trackSelectionParameters = b.build()
        }
    }
    var hud by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var panel by remember { mutableStateOf(P_NONE) }
    var panelSeason by remember { mutableStateOf(item.season) }
    var resizeMode by remember { mutableStateOf(0) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var epg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var retry by remember { mutableStateOf(0) }
    var retryAt by remember { mutableStateOf(0L) }
    var stallSince by remember { mutableStateOf(0L) }
    var stableSince by remember { mutableStateOf(0L) }
    var lastPos by remember { mutableStateOf(-1L) }
    var lastAdv by remember { mutableStateOf(System.currentTimeMillis()) }
    var reconn by remember { mutableStateOf(false) }
    val rootFr = remember { FocusRequester() }
    val playFr = remember { FocusRequester() }
    val panelFr = remember { FocusRequester() }
    val prevItem = remember { arrayOfNulls<Item>(1) }
    val seasons = remember(list) { list.map { it.season }.distinct().sorted() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Riconnette lo stesso titolo (i canali ripartono dal vivo, i film dal punto in cui erano)
    fun reconnect() {
        val cur = curItem.value
        val at = if (cur.type == Type.LIVE) 0L else player.currentPosition
        reconn = true
        stallSince = 0L
        lastAdv = System.currentTimeMillis()
        player.setMediaItem(MediaItem.fromUri(cur.url), at)
        player.prepare()
        player.play()
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val isLive = curItem.value.type == Type.LIVE
                retry += 1
                if (isLive || retry <= 3) {
                    retryAt = System.currentTimeMillis() + minOf(1000L * retry, 8000L)
                    reconn = true
                } else {
                    error = "Impossibile riprodurre (${e.errorCodeName})"
                }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && list[curIdx.value].type == Type.SERIE && curIdx.value < list.size - 1) {
                    idx = curIdx.value + 1
                }
                if (state == Player.STATE_READY) { error = null; reconn = false }
            }
        }
        player.addListener(l)
        onDispose {
            val it0 = curItem.value
            if (it0.type != Type.LIVE) vm.savePos(it0, player.currentPosition, player.duration.coerceAtLeast(0L), true)
            player.removeListener(l)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        var stopped = false
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                stopped = true
                val it0 = curItem.value
                if (it0.type != Type.LIVE) vm.savePos(it0, player.currentPosition, player.duration.coerceAtLeast(0L), true)
                player.pause()
            } else if (e == Lifecycle.Event.ON_START && stopped) {
                stopped = false
                if (curItem.value.type == Type.LIVE) { player.seekToDefaultPosition(); player.play() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(idx) {
        val prev = prevItem[0]
        if (prev != null && prev.type != Type.LIVE && prev.url != item.url) vm.savePos(prev, pos, dur, true)
        prevItem[0] = item
        error = null
        retry = 0
        retryAt = 0L
        stallSince = 0L
        stableSince = 0L
        reconn = false
        lastPos = -1L
        lastAdv = System.currentTimeMillis()
        val saved = if (live) null else vm.posOf(item)
        val startMs = if (saved != null && saved.pos > 15_000 && (saved.dur <= 0 || saved.pos < saved.dur * 0.95)) saved.pos else 0L
        player.setMediaItem(MediaItem.fromUri(item.url), startMs)
        player.prepare()
        if (live) delay(4000)
        vm.saveLast(item)
    }
    LaunchedEffect(idx) {
        while (true) {
            delay(15_000)
            if (!live) vm.savePos(item, player.currentPosition, player.duration.coerceAtLeast(0L), true)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            val now = System.currentTimeMillis()
            val position = player.currentPosition
            pos = position
            dur = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            if (position != lastPos) { lastPos = position; lastAdv = now }
            val st = player.playbackState
            val stalled = error == null && player.playWhenReady &&
                st != Player.STATE_ENDED && st != Player.STATE_IDLE &&
                (st == Player.STATE_BUFFERING || now - lastAdv > 6000L)
            if (stalled && retryAt == 0L) {
                if (stallSince == 0L) stallSince = now
                val limit = if (curItem.value.type == Type.LIVE) 8000L else 25000L
                if (now - stallSince > limit) { retry += 1; reconnect() }
            } else if (!stalled) {
                stallSince = 0L
            }
            if (!stalled && player.isPlaying) {
                if (stableSince == 0L) stableSince = now
                else if (now - stableSince > 15000L && retry > 0) retry = 0
            } else {
                stableSince = 0L
            }
            if (retryAt > 0L && now >= retryAt) { retryAt = 0L; reconnect() }
            delay(500)
        }
    }
    LaunchedEffect(idx) {
        epg = emptyList()
        if (live) epg = vm.epgFor(item)
    }
    LaunchedEffect(idx, tick, playing) {
        hud = true
        delay(5000)
        if (live || playing) hud = false
    }
    LaunchedEffect(hud, panel) {
        delay(40)
        try {
            if (panel == P_NONE) {
                if (hud && !live) playFr.requestFocus() else rootFr.requestFocus()
            } else if (panel != P_SEASONS && panel != P_CHANNELS) {
                panelFr.requestFocus()
            }
        } catch (_: Exception) {}
    }
    BackHandler(panel != P_NONE) { panel = P_NONE }

    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val subsOff = textGroups.none { it.isSelected }

    fun pickAudio(g: Tracks.Group) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }
    fun pickText(g: Tracks.Group?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        player.trackSelectionParameters = b.build()
    }
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }
    fun seekBy(d: Long) {
        val total = player.duration
        val target = (player.currentPosition + d).coerceAtLeast(0L)
        player.seekTo(if (total > 0) target.coerceAtMost(total) else target)
        pos = player.currentPosition
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFr)
            .onPreviewKeyEvent { e ->
                if (panel != P_NONE) return@onPreviewKeyEvent false
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.key == Key.MediaPlayPause) { togglePlay(); tick++; return@onPreviewKeyEvent true }
                if (e.key == Key.Menu) { vm.toggleFav(item); tick++; return@onPreviewKeyEvent true }
                if (live) {
                    tick++
                    when (e.key) {
                        Key.DirectionUp, Key.ChannelUp -> { idx = (idx + 1) % list.size; true }
                        Key.DirectionDown, Key.ChannelDown -> { idx = (idx - 1 + list.size) % list.size; true }
                        Key.DirectionLeft -> { panel = P_CHANNELS; true }
                        Key.DirectionCenter, Key.Enter, Key.DirectionRight -> true
                        else -> false
                    }
                } else if (hud) {
                    tick++
                    false
                } else {
                    when (e.key) {
                        Key.DirectionLeft -> { seekBy(-10_000L); tick++; true }
                        Key.DirectionRight -> { seekBy(10_000L); tick++; true }
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter -> { tick++; true }
                        else -> false
                    }
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
            update = { v ->
                v.resizeMode = when (resizeMode) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                val sv = v.subtitleView
                if (sv != null) {
                    sv.setFractionalTextSize(
                        when (vm.subSize) { "s" -> 0.04f; "l" -> 0.075f; else -> 0.0533f }
                    )
                    sv.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            if (vm.subBg == "dark") 0xAA000000.toInt() else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if ((buffering || reconn) && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)
        if (reconn && error == null) {
            Text(
                "Riconnessione…", color = Color.White, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center).padding(top = 110.dp)
            )
        }
        error?.let {
            Text(it, color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }

        // Guida TV (canali in diretta)
        AnimatedVisibility(
            visible = hud && live && panel == P_NONE,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = fadeIn(), exit = fadeOut()
        ) { GuideBar(vm, list, idx, epg) }

        // Controlli film e serie
        AnimatedVisibility(
            visible = hud && !live && panel == P_NONE,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 40.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (vm.isFav(item)) "♥ " else "") + (if (isSeries) (item.series ?: item.name) else item.name),
                            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val sub = when {
                            isSeries && item.episode > 0 -> "Stagione ${item.season}  >  Episodio ${item.episode}"
                            isSeries -> item.name
                            else -> item.group
                        }
                        if (sub.isNotEmpty()) Text(sub, color = Color.LightGray, fontSize = 16.sp, maxLines = 1)
                    }
                    Chip("✕", false) { vm.back() }
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundBtn("−15", 76.dp) { seekBy(-15_000L) }
                    RoundBtn(if (playing) "❚❚" else "▶", 96.dp, Modifier.focusRequester(playFr)) { togglePlay() }
                    RoundBtn("+15", 76.dp) { seekBy(15_000L) }
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(horizontal = 40.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtClock(pos), color = Color.White, fontSize = 16.sp, modifier = Modifier.width(84.dp))
                        SeekBar(pos, dur, Modifier.weight(1f), onSeek = { seekBy(it) }, onClick = { togglePlay() })
                        Text(
                            fmtClock(dur), color = Color.White, fontSize = 16.sp,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp)
                        )
                        Chip(listOf("Fit", "Riempi", "Zoom")[resizeMode], false) { resizeMode = (resizeMode + 1) % 3 }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Chip("Audio", false) { panel = P_AUDIO }
                        Spacer(Modifier.width(10.dp))
                        Chip("Sottotitoli", false) { panel = P_SUBS }
                        Spacer(Modifier.width(10.dp))
                        Chip("Stile sottotitoli", false) { panel = P_STYLE }
                        if (isSeries) {
                            Spacer(Modifier.width(10.dp))
                            Chip("Stagione", false) { panelSeason = item.season; panel = P_SEASONS }
                        }
                        Spacer(Modifier.width(10.dp))
                        Chip("Lettore", false) { panel = P_PLAYER }
                    }
                }
            }
        }

        // Pannello Stagioni (a sinistra, il video resta visibile a destra)
        if (panel == P_SEASONS) {
            SeasonPanel(
                vm = vm, list = list, curIdx = idx, seasons = seasons, season = panelSeason,
                onSeason = { panelSeason = it },
                onPick = { i -> idx = i; panel = P_NONE; tick++ },
                onClose = { panel = P_NONE }
            )
        }

        // Elenco canali (a sinistra, il canale continua a vedersi)
        if (panel == P_CHANNELS) {
            ChannelPanel(
                vm = vm, list = list, curIdx = idx,
                onPick = { i -> idx = i; panel = P_NONE; tick++ }
            )
        }

        // Pannelli centrali: audio, sottotitoli, stile, lettore
        if (panel == P_AUDIO || panel == P_SUBS || panel == P_STYLE || panel == P_PLAYER) {
            val rows: List<PRow> = when (panel) {
                P_AUDIO ->
                    if (audioGroups.isEmpty()) listOf(PRow("Nessuna traccia audio alternativa", header = true))
                    else audioGroups.take(10).mapIndexed { i, g ->
                        PRow((if (g.isSelected) "✓ " else "") + trackLabel(g, i + 1), g.isSelected) { pickAudio(g) }
                    }
                P_SUBS ->
                    listOf(PRow((if (subsOff) "✓ " else "") + "Disattivati", subsOff) { pickText(null) }) +
                        textGroups.take(10).mapIndexed { i, g ->
                            PRow((if (g.isSelected) "✓ " else "") + trackLabel(g, i + 1), g.isSelected) { pickText(g) }
                        }
                P_STYLE -> listOf(
                    PRow("Dimensione", header = true),
                    PRow("Piccola", vm.subSize == "s") { vm.changeSubSize("s") },
                    PRow("Media", vm.subSize == "m") { vm.changeSubSize("m") },
                    PRow("Grande", vm.subSize == "l") { vm.changeSubSize("l") },
                    PRow("Sfondo", header = true),
                    PRow("Trasparente", vm.subBg == "none") { vm.changeSubBg("none") },
                    PRow("Scuro", vm.subBg == "dark") { vm.changeSubBg("dark") }
                )
                else -> listOf(
                    PRow("Player integrato (in uso)", true) { panel = P_NONE },
                    PRow("Apri questo video con VLC", false) {
                        if (vm.launchVlc(item, player.currentPosition)) { player.pause(); panel = P_NONE }
                    }
                )
            }
            val firstIdx = rows.indexOfFirst { !it.header }
            val title = when (panel) {
                P_AUDIO -> "Audio"
                P_SUBS -> "Sottotitoli"
                P_STYLE -> "Stile sottotitoli"
                else -> "Lettore"
            }
            CenterPanel(title, panelFr, firstIdx < 0, onClose = { panel = P_NONE }) {
                rows.forEachIndexed { i, r ->
                    if (r.header) Text(r.text, color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    else Chip(
                        r.text, r.selected,
                        modifier = if (i == firstIdx) Modifier.focusRequester(panelFr) else Modifier,
                        wide = true
                    ) { r.action() }
                }
            }
        }
    }
}
