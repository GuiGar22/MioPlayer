package it.mioplayer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

val Bg = Color(0xFF0B0F1A)
val Accent = Color(0xFF7C5CFF)
val Cyan = Color(0xFF22D3EE)
val CardBg = Color(0xFF151B2E)
val Pink = Color(0xFFFF4D6D)

private fun hostOf(u: String): String = try { Uri.parse(u).host ?: "" } catch (e: Exception) { "" }

private fun sortLabel(s: String) = when (s) {
    "az" -> "A → Z"
    "za" -> "Z → A"
    "views" -> "Più visti"
    else -> "Ordine provider"
}

fun fmtClock(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

private val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
private val byGroup = compareBy<Pair<String, List<Item>>, String>(String.CASE_INSENSITIVE_ORDER) { it.first }

private fun orderItems(
    base: List<Item>, grp: String?, q: String, sort: String,
    counts: Map<String, Int>, sortable: Boolean
): List<Item> {
    val f = base.filter { (grp == null || it.group == grp) && (q.isEmpty() || it.name.contains(q, true)) }
    if (!sortable) return f
    return when (sort) {
        "az" -> f.sortedWith(byName)
        "za" -> f.sortedWith(byName.reversed())
        "views" -> f.sortedWith(compareByDescending<Item> { counts[it.url] ?: 0 }.then(byName))
        else -> f
    }
}

private fun favSortLabel(s: String) = when (s) {
    "recent" -> "Più recenti"
    "old" -> "Più vecchi"
    "az" -> "A → Z"
    "za" -> "Z → A"
    else -> "Mio ordine"
}

private fun orderFavs(list: List<Item>, q: String, mode: String, ts: Map<String, Long>): List<Item> {
    val f = list.filter { q.isEmpty() || it.name.contains(q, true) }
    return when (mode) {
        "recent" -> f.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Item>> { ts[it.value.url] ?: 0L }.thenByDescending { it.index })
            .map { it.value }
        "old" -> f.withIndex()
            .sortedWith(compareBy<IndexedValue<Item>> { ts[it.value.url] ?: 0L }.thenBy { it.index })
            .map { it.value }
        "az" -> f.sortedWith(byName)
        "za" -> f.sortedWith(byName.reversed())
        else -> f
    }
}

private fun favKey(list: List<Item>): (Int) -> Any = { i -> list[i].url }

/** Con un preferito "afferrato": su/giù lo spostano, le altre frecce vengono ignorate. */
private fun grabKey(e: KeyEvent, vm: Vm, url: String): Boolean {
    val arrow = e.key == Key.DirectionUp || e.key == Key.DirectionDown ||
        e.key == Key.DirectionLeft || e.key == Key.DirectionRight
    if (!arrow) return false
    if (e.type == KeyEventType.KeyDown) {
        if (e.key == Key.DirectionUp) vm.moveFav(url, -1)
        else if (e.key == Key.DirectionDown) vm.moveFav(url, 1)
    }
    return true
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    grow: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f && grow) 1.07f else 1f, label = "sc")
    Box(
        modifier.scale(sc)
            .border(3.dp, if (f) Cyan else Color.Transparent, shape)
            .clip(shape)
            .onFocusChanged { f = it.isFocused; onFocus?.invoke(it.isFocused) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) { content(f) }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    onClick: () -> Unit
) {
    Focusable(if (wide) modifier.fillMaxWidth() else modifier, onClick = onClick) { f ->
        Box(
            (if (wide) Modifier.fillMaxWidth() else Modifier)
                .background(if (selected) Accent else if (f) Color(0xFF2A3350) else CardBg)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
    }
}

/** Campo di testo "da TV": premendo OK si apre una finestra con la tastiera; il testo si applica con Conferma. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    Focusable(modifier, onClick = { open = true }) { f ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (f) Color(0xFF2A3350) else CardBg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon) { Icon(Icons.Default.Search, null, tint = Color.Gray); Spacer(Modifier.width(10.dp)) }
            Text(
                if (value.isEmpty()) placeholder else value,
                color = if (value.isEmpty()) Color.Gray else Color.White,
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (open) {
        TextEntryDialog(
            initial = value, title = placeholder,
            onDone = { onValueChange(it); open = false },
            onCancel = { open = false }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TextEntryDialog(initial: String, title: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(760.dp).clip(RoundedCornerShape(18.dp)).background(CardBg).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(title, color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone(text) }, onDone = { onDone(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("Conferma", true) { onDone(text) }
                Chip("Cancella testo", false) { text = "" }
                Chip("Annulla", false) { onCancel() }
            }
            LaunchedEffect(Unit) {
                delay(150)
                try { fr.requestFocus(); kb?.show() } catch (_: Exception) {}
            }
        }
    }
}

@Composable
fun ItemCard(vm: Vm, m: Item, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    val live = m.type == Type.LIVE
    Focusable(onClick = onClick, onFocus = onFocus) {
        Column(Modifier.fillMaxWidth().background(CardBg)) {
            Box(
                Modifier.fillMaxWidth().height(if (live) 110.dp else 230.dp).background(Color(0xFF1E2640)),
                contentAlignment = Alignment.Center
            ) {
                if (m.logo != null) AsyncImage(
                    m.logo, null,
                    contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (live) 12.dp else 0.dp)
                ) else Icon(Icons.Default.PlayArrow, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                if (vm.isFav(m)) Text(
                    "♥", color = Pink, fontSize = 26.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
            Text(
                m.name, color = Color.White, fontSize = 14.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp).fillMaxWidth()
            )
        }
    }
}

@Composable
fun ItemRow(
    vm: Vm,
    m: Item,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    grabbed: Boolean = false,
    onClick: () -> Unit
) {
    val live = m.type == Type.LIVE
    Focusable(modifier.fillMaxWidth(), onClick = onClick, onFocus = onFocus) { f ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (grabbed) Color(0x66FFC107) else if (f) Color(0xFF2A3350) else CardBg)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (number != null) Text("$number.", color = Color.Gray, fontSize = 16.sp, modifier = Modifier.width(44.dp))
            Box(
                Modifier.width(if (live) 80.dp else 56.dp).height(if (live) 45.dp else 80.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E2640)),
                contentAlignment = Alignment.Center
            ) {
                if (m.logo != null) AsyncImage(
                    m.logo, null,
                    contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) else Icon(Icons.Default.PlayArrow, null, tint = Color.Gray)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.group.isNotEmpty()) Text(m.group, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (grabbed) Text("⇅ sposta con ▲ ▼", color = Color(0xFFFFC107), fontSize = 14.sp)
            if (vm.isFav(m)) Text("  ♥", color = Pink, fontSize = 22.sp)
        }
    }
}

/** "Riprendi da dove hai interrotto": riporta all'ultima cosa vista, dal punto in cui si era fermata. */
@Composable
fun ResumeCard(vm: Vm, item: Item, modifier: Modifier = Modifier) {
    val p = vm.posOf(item)
    val live = item.type == Type.LIVE
    val isSeries = item.type == Type.SERIE
    val title = if (isSeries) (item.series ?: item.name) else item.name
    val sub = when {
        live -> "Canale in diretta"
        isSeries && item.episode > 0 -> "Stagione ${item.season} · Episodio ${item.episode}"
        else -> item.group
    }
    val hasPos = p != null && !live && p.dur > 0
    Focusable(modifier, onClick = { vm.play(listOf(item), 0) }) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) Color(0xFF2A3350) else Color(0xFF1B2140)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E2640)),
                contentAlignment = Alignment.Center
            ) {
                if (item.logo != null) AsyncImage(
                    item.logo, null,
                    contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) else Icon(Icons.Default.PlayArrow, null, tint = Color.Gray)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Riprendi da dove hai interrotto", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    title + (if (sub.isNotEmpty()) "  ·  $sub" else ""),
                    color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (hasPos && p != null) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth((p.pos.toFloat() / p.dur).coerceIn(0f, 1f)).fillMaxHeight().background(Cyan))
                    }
                }
            }
            if (hasPos && p != null) {
                Spacer(Modifier.width(14.dp))
                Text("${fmtClock(p.pos)} / ${fmtClock(p.dur)}", color = Color.LightGray, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.PlayArrow, null, tint = Cyan, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun QrCode(text: String, dim: Dp) {
    val matrix = remember(text) {
        try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        } catch (e: Exception) { null }
    }
    Canvas(Modifier.size(dim).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
        val m = matrix ?: return@Canvas
        val n = m.width
        val cell = this.size.width / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
            }
        }
    }
}

/** QR code + indirizzo per caricare la lista dal telefono (il server resta attivo solo finché è visibile). */
@Composable
fun PhoneUpload(vm: Vm, onLink: (String) -> Unit = {}) {
    val ip = remember { UploadServer.localIp() }
    var ok by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val server = UploadServer(8080, { vm.saveUrlAndRefresh(it); onLink(it) }, { vm.importText(it) })
        ok = try { server.start(); true } catch (_: Exception) { false }
        onDispose { server.stop() }
    }
    val addr = if (ip != null && ok) "http://$ip:8080" else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (addr != null) {
            QrCode(addr, 210.dp)
            Spacer(Modifier.width(26.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Aggiorna la lista dal telefono", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (addr != null) {
                Text("1. Inquadra il QR con la fotocamera del telefono", fontSize = 16.sp, color = Color.LightGray)
                Text("2. Incolla il link della lista (o scegli il file .m3u)", fontSize = 16.sp, color = Color.LightGray)
                Text("3. Premi «Invia alla TV»", fontSize = 16.sp, color = Color.LightGray)
                Text("Telefono e TV sulla stessa Wi-Fi  ·  $addr", fontSize = 14.sp, color = Cyan)
            } else {
                Text("Collega la TV alla Wi-Fi per usare il QR", fontSize = 16.sp, color = Color.LightGray)
            }
        }
    }
}

@Composable
fun SplashScreen(vm: Vm) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg, Color(0xFF1B1442)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = Cyan, modifier = Modifier.size(72.dp))
                Text("GG", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = Cyan)
                Text("PLAY", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(Modifier.width(360.dp), color = Cyan, trackColor = Color(0x33FFFFFF))
            Spacer(Modifier.height(14.dp))
            Text(vm.splashMsg, fontSize = 18.sp, color = Color.LightGray)
            if (vm.progressCount > 0) Text("${vm.progressCount} titoli", fontSize = 14.sp, color = Color.Gray)
        }
    }
}

@Composable
fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Text("Vuoi uscire da GGPLAY?", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Chip("Resta", true, Modifier.focusRequester(fr)) { onStay() }
                Chip("Esci", false) { onExit() }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); try { fr.requestFocus() } catch (_: Exception) {} }
}

@Composable
fun HomeScreen(vm: Vm) {
    val tab = vm.tab
    val type = Type.values().getOrNull(tab - 1)
    val favMode = vm.favMode && type != null
    val base = remember(vm.index, vm.recents, vm.favs, tab, favMode) {
        when {
            type == null -> vm.recents
            favMode -> vm.favs.filter { it.type == type }
            else -> vm.index.cards(type!!)
        }
    }
    val rawGroups = remember(vm.index, tab) {
        if (type == null) emptyList<Pair<String, List<Item>>>() else vm.index.groups(type)
    }
    val groups = remember(rawGroups, vm.sort) {
        when (vm.sort) {
            "az" -> rawGroups.sortedWith(byGroup)
            "za" -> rawGroups.sortedWith(byGroup.reversed())
            else -> rawGroups
        }
    }
    val q = vm.query
    val grp = if (vm.query.isBlank()) vm.group else null
    val sort = vm.sort
    val counts = vm.playCounts
    val sortable = type != null && !favMode
    val ordered: List<Item> = if (favMode) {
        remember(base, q, vm.favSort, vm.favTs) { orderFavs(base, q, vm.favSort, vm.favTs) }
    } else if (base.size <= 3000) {
        remember(base, grp, q, sort, counts, sortable) { orderItems(base, grp, q, sort, counts, sortable) }
    } else {
        produceState(emptyList<Item>(), base, grp, q, sort, counts, sortable) {
            value = withContext(Dispatchers.Default) { orderItems(base, grp, q, sort, counts, sortable) }
        }.value
    }
    var confirmClear by remember(tab) { mutableStateOf(false) }
    var editing by remember(tab, favMode) { mutableStateOf(false) }
    var grab by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<Item?>(null) }
    var menuDownAt by remember { mutableStateOf(0L) }
    var menuFired by remember { mutableStateOf(false) }
    BackHandler(favMode) { vm.favMode = false }
    BackHandler(vm.group != null) { vm.group = null }
    BackHandler(vm.query.isNotBlank()) { vm.query = "" }
    BackHandler(grab != null) { grab = null }
    val tabs = listOf("Recenti") + Type.values().map { it.label }
    val listMode = vm.listView || (favMode && editing)
    val favLabel = when (type) {
        Type.LIVE -> "♥ Canali TV preferiti"
        Type.FILM -> "♥ Film preferiti"
        else -> "♥ Serie TV preferiti"
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)
            .onPreviewKeyEvent { e ->
                // Tieni premuto Menu (≥ 1 secondo) su un titolo: aggiunge/toglie dai preferiti senza aprirlo
                if (e.key != Key.Menu) return@onPreviewKeyEvent false
                val now = System.currentTimeMillis()
                if (e.type == KeyEventType.KeyDown) {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        menuDownAt = now
                        menuFired = false
                    } else if (!menuFired && menuDownAt > 0L && now - menuDownAt >= 1000L) {
                        menuFired = true
                        val f = focused
                        if (f != null) vm.toggleFavToast(f)
                    }
                } else if (e.type == KeyEventType.KeyUp) {
                    if (!menuFired && menuDownAt > 0L && now - menuDownAt >= 1000L) {
                        menuFired = true
                        val f = focused
                        if (f != null) vm.toggleFavToast(f)
                    }
                    menuDownAt = 0L
                }
                true
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GG", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Cyan)
            Text("PLAY", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.width(16.dp))
            tabs.forEachIndexed { i, t ->
                Chip(t, tab == i) { vm.tab = i; vm.group = null; vm.query = ""; vm.favMode = false }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (vm.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Cyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Focusable(onClick = { vm.navigate(Screen.Settings) }) { f ->
                Box(Modifier.background(if (f) Accent else CardBg).padding(12.dp)) {
                    Icon(Icons.Default.Settings, "Impostazioni", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (vm.items.isEmpty() && (vm.listLoading || vm.loading)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text("Carico la lista…", fontSize = 22.sp, color = Color.White)
                    if (vm.progressCount > 0) Text("${vm.progressCount} titoli", fontSize = 14.sp, color = Color.Gray)
                }
            }
            return@Column
        }
        if (vm.items.isEmpty() && vm.recents.isEmpty() && vm.favs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nessuna lista caricata", fontSize = 28.sp, color = Color.White)
                    Spacer(Modifier.height(18.dp))
                    PhoneUpload(vm)
                    Spacer(Modifier.height(18.dp))
                    Chip("Apri impostazioni", false) { vm.navigate(Screen.Settings) }
                }
            }
            return@Column
        }

        vm.recents.firstOrNull()?.let { r ->
            val fr = remember { FocusRequester() }
            ResumeCard(vm, r, Modifier.fillMaxWidth().focusRequester(fr))
            Spacer(Modifier.height(10.dp))
            LaunchedEffect(Unit) { delay(80); try { fr.requestFocus() } catch (_: Exception) {} }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.group != null) { Chip("← Categorie", false) { vm.group = null }; Spacer(Modifier.width(10.dp)) }
            TvTextField(vm.query, { vm.query = it }, "Cerca…", Modifier.weight(1f), icon = true)
            if (type != null) {
                Spacer(Modifier.width(10.dp))
                Chip(favLabel, favMode) { vm.favMode = !vm.favMode; vm.group = null }
                Spacer(Modifier.width(10.dp))
                if (favMode) {
                    Chip("Ordine: ${favSortLabel(vm.favSort)}", false) { vm.cycleFavSort() }
                } else {
                    Chip("Ordina: ${sortLabel(vm.sort)}", false) { vm.cycleSort() }
                }
                if (favMode && vm.favSort == "manual") {
                    Spacer(Modifier.width(10.dp))
                    Chip(if (editing) "✓ Fine modifica" else "Modifica ordine", editing) { editing = !editing; grab = null }
                }
            }
            if (tab == 0 && vm.recents.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Chip(if (confirmClear) "Premi ancora per confermare" else "Cancella cronologia", confirmClear) {
                    if (confirmClear) { vm.clearRecents(); confirmClear = false } else confirmClear = true
                }
            }
            Spacer(Modifier.width(10.dp))
            Chip(if (vm.listView) "▦ Griglia" else "☰ Lista", false) { vm.changeListView(!vm.listView) }
        }
        Text(
            if (favMode && editing) "Premi OK su un titolo per afferrarlo, poi ▲ ▼ per spostarlo; OK per lasciarlo"
            else "Tieni premuto Menu (≡) per 1 secondo su un titolo per aggiungerlo ai preferiti ♥",
            fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
        )

        val inGroups = type != null && !favMode && vm.group == null && vm.query.isBlank()
        if (base.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        tab == 0 -> "Qui compariranno gli ultimi titoli guardati"
                        favMode -> "Nessun preferito in questa categoria: tieni premuto Menu (≡) per 1 secondo su un titolo"
                        vm.items.isEmpty() -> "Lista non caricata: apri ⚙ > Lista e aggiornamento"
                        else -> "Nessun elemento in questa sezione"
                    },
                    fontSize = 20.sp, color = Color.Gray
                )
            }
        } else if (inGroups) {
            if (vm.listView) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups.size) { i ->
                        val (name, list) = groups[i]
                        Focusable(Modifier.fillMaxWidth(), onClick = { vm.group = name }) { f ->
                            Row(
                                Modifier.fillMaxWidth().background(if (f) Color(0xFF2A3350) else CardBg).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${list.size} ${if (type == Type.SERIE) "serie" else "elementi"}", fontSize = 14.sp, color = Color.LightGray)
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(groups.size) { i ->
                        val (name, list) = groups[i]
                        Focusable(onClick = { vm.group = name }) {
                            Column(
                                Modifier.fillMaxWidth().height(100.dp)
                                    .background(Brush.linearGradient(listOf(Accent.copy(alpha = .55f), CardBg)))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${list.size} ${if (type == Type.SERIE) "serie" else "elementi"}", fontSize = 14.sp, color = Color.LightGray)
                            }
                        }
                    }
                }
            }
        } else {
            // Canali: la lista di riproduzione contiene tutti i canali visibili, così su/giù cambiano canale
            val openAt = { i: Int ->
                val e = ordered[i]
                if (e.type == Type.LIVE && !e.isSeriesCard) {
                    val lives = ordered.filter { it.type == Type.LIVE }
                    vm.play(lives, lives.indexOfFirst { it.url == e.url }.coerceAtLeast(0))
                } else {
                    vm.open(listOf(e), 0)
                }
            }
            val onFocusOf: (Item, Boolean) -> Unit = { m, got ->
                if (got) { focused = m } else if (focused === m) { focused = null }
            }
            if (listMode) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ordered.size, key = if (favMode) favKey(ordered) else null) { i ->
                        val m = ordered[i]
                        val isGrab = editing && grab == m.url
                        ItemRow(
                            vm, m,
                            onFocus = { got -> onFocusOf(m, got) },
                            modifier = if (isGrab) Modifier.onPreviewKeyEvent { e -> grabKey(e, vm, m.url) } else Modifier,
                            number = if (favMode && editing) i + 1 else null,
                            grabbed = isGrab
                        ) {
                            if (favMode && editing) {
                                grab = if (isGrab) null else m.url
                            } else {
                                openAt(i)
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(if (type == Type.LIVE) 190.dp else 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(ordered.size) { i ->
                        val m = ordered[i]
                        ItemCard(vm, m, onFocus = { got -> onFocusOf(m, got) }) { openAt(i) }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesScreen(vm: Vm, s: Screen.Series) {
    val all = remember(s) { s.eps.sortedWith(compareBy<Item>({ it.season }, { it.episode })) }
    val seasons = remember(all) { all.map { it.season }.distinct().sorted() }
    var season by remember { mutableStateOf(seasons.firstOrNull() ?: 1) }
    val eps = remember(all, season) { all.filter { it.season == season } }
    val first = all.firstOrNull()
    val card = Item(s.name, first?.logo, first?.group ?: "", "series:${s.name}", Type.SERIE, s.name)

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Box(Modifier.width(200.dp).height(300.dp).clip(RoundedCornerShape(14.dp)).background(CardBg)) {
            if (first?.logo != null) AsyncImage(first.logo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(s.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("← Indietro", false) { vm.back() }
                Chip(if (vm.isFav(card)) "♥ Nei preferiti" else "♡ Aggiungi ai preferiti", vm.isFav(card)) { vm.toggleFav(card) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons.size) { i ->
                    Chip("Stagione ${seasons[i]}", seasons[i] == season) { season = seasons[i] }
                }
            }
            Text("${eps.size} episodi", fontSize = 14.sp, color = Color.Gray)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(eps.size) { i ->
                    val e = eps[i]
                    val p = vm.posOf(e)
                    Focusable(Modifier.fillMaxWidth(), onClick = {
                        vm.play(all, all.indexOfFirst { it.url == e.url }.coerceAtLeast(0))
                    }) { f ->
                        Row(
                            Modifier.fillMaxWidth().background(if (f) Color(0xFF2A3350) else CardBg).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Cyan)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                (if (e.episode > 0) "Ep. ${e.episode}  ·  " else "") + e.name,
                                color = Color.White, fontSize = 18.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            if (p != null && p.pos > 15_000) {
                                Text("visto fino a ${fmtClock(p.pos)}", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- IMPOSTAZIONI ----------------

@Composable
fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Cyan)
        content()
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(200.dp), fontSize = 16.sp, color = Color.Gray)
        Text(value, Modifier.weight(1f), fontSize = 16.sp, color = Color.White)
    }
}

@Composable
fun OptionRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    note: String? = null,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, label) -> Chip(label, selected == k) { onSelect(k) } }
        }
        if (note != null) Text(note, fontSize = 13.sp, color = Color.Gray)
    }
}

@Composable
fun GeneralSection(vm: Vm, showPw: Boolean, onTogglePw: () -> Unit) {
    val a = vm.account
    val live = vm.index.cards(Type.LIVE).size
    val film = vm.index.cards(Type.FILM).size
    val serie = vm.index.cards(Type.SERIE).size
    InfoCard("Informazioni provider") {
        InfoLine("Provider (server)", a?.host ?: hostOf(vm.url).ifEmpty { "—" })
        InfoLine("Messaggio del provider", a?.message?.ifEmpty { "—" } ?: "—")
    }
    InfoCard("Informazioni dispositivo e lista") {
        InfoLine("Stato lista", a?.status ?: "—")
        InfoLine("Scadenza", a?.expiry ?: "—")
        InfoLine("Connessioni (attive/max)", if (a != null) "${a.activeConn} / ${a.maxConn}" else "—")
        InfoLine("Elementi caricati", "${vm.items.size} voci  (TV $live · Film $film · $serie serie)")
        InfoLine("Ultimo esito", vm.status.ifEmpty { "—" })
    }
    InfoCard("Informazioni account") {
        InfoLine("Nome utente (username)", a?.user ?: "—")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Password", Modifier.width(200.dp), fontSize = 16.sp, color = Color.Gray)
            Text(
                if (a == null) "—" else if (showPw) a.pass else "••••••••",
                Modifier.weight(1f), fontSize = 16.sp, color = Color.White
            )
            if (a != null) Chip(if (showPw) "Nascondi" else "Mostra", false) { onTogglePw() }
        }
        InfoLine("Scadenza", a?.expiry ?: "—")
        InfoLine("Creato il", a?.created ?: "—")
    }
    if (a == null) Text(if (vm.accountLoading) "Carico le informazioni…" else vm.accountMsg, fontSize = 15.sp, color = Color.LightGray)
    Chip("Aggiorna informazioni", false) { vm.loadAccount() }
}

@Composable
fun ListSection(vm: Vm, link: String, onLink: (String) -> Unit) {
    InfoCard("QR code per il telefono") { PhoneUpload(vm, onLink) }
    Text("Oppure scrivi il link con il telecomando", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    TvTextField(link, onLink, "Premi OK e scrivi il link M3U", Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("Salva e carica", true) { vm.saveUrlAndRefresh(link) }
        Chip("Aggiorna da internet", false) { vm.refresh() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("Ricarica dal salvataggio", false) { vm.reloadSaved() }
        Chip("Cancella lista", false) { vm.clear() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("Svuota recenti", false) { vm.clearRecents() }
        Chip("Azzera «Più visti»", false) { vm.clearCounts() }
    }
    if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(vm.status, fontSize = 16.sp, color = Color.LightGray)
}

@Composable
fun PlaybackSection(vm: Vm) {
    OptionRow(
        "Player",
        listOf("native" to "Integrato (Native)", "vlc" to "VLC"),
        vm.playerPref,
        "VLC deve essere installato sul Fire Stick. Con VLC non ci sono zapping dei canali né episodio successivo automatico."
    ) { vm.changePlayerPref(it) }
    OptionRow(
        "Formato di uscita",
        listOf("default" to "Predefinito", "m3u8" to "M3U8", "ts" to "TS"),
        vm.output,
        "Cambia l'output del link Xtream e ricarica la lista. Non vale per liste caricate da file."
    ) { vm.changeOutput(it) }
    OptionRow(
        "Lingua audio preferita",
        listOf("auto" to "Automatica", "it" to "Italiano", "en" to "Inglese"),
        vm.audioLang
    ) { vm.setAudio(it) }
    OptionRow(
        "Sottotitoli",
        listOf("off" to "Disattivati", "it" to "Italiano", "en" to "Inglese"),
        vm.subLang,
        "Vale dalla prossima riproduzione. Durante un film o una serie usa i pulsanti Audio e Sottotitoli."
    ) { vm.setSub(it) }
    OptionRow(
        "Dimensione sottotitoli",
        listOf("s" to "Piccola", "m" to "Media", "l" to "Grande"),
        vm.subSize
    ) { vm.changeSubSize(it) }
    OptionRow(
        "Sfondo sottotitoli",
        listOf("none" to "Trasparente", "dark" to "Scuro"),
        vm.subBg
    ) { vm.changeSubBg(it) }
}

@Composable
fun AppearanceSection(vm: Vm) {
    OptionRow(
        "Ordinamento titoli",
        listOf("az" to "A → Z", "za" to "Z → A", "provider" to "Ordine provider", "views" to "Più visti"),
        vm.sort,
        "«Ordine provider» di solito mostra prima i più recenti. «Più visti» conta le tue visioni su questo dispositivo."
    ) { vm.changeSort(it) }
    OptionRow(
        "Vista",
        listOf("grid" to "Griglia", "list" to "Lista (colonna)"),
        if (vm.listView) "list" else "grid"
    ) { vm.changeListView(it == "list") }
}

@Composable
fun SettingsScreen(vm: Vm) {
    var sec by remember { mutableStateOf(if (vm.items.isEmpty()) 1 else 0) }
    var link by remember { mutableStateOf(vm.url) }
    var showPw by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadAccount() }

    Row(Modifier.fillMaxSize().padding(40.dp)) {
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("⚙ Impostazioni", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            listOf("Informazioni generali", "Lista e aggiornamento", "Riproduzione", "Aspetto").forEachIndexed { i, t ->
                Chip(t, sec == i, wide = true) { sec = i }
            }
            Spacer(Modifier.height(10.dp))
            Chip("← Indietro", false, wide = true) { vm.back() }
        }
        Spacer(Modifier.width(32.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (sec) {
                0 -> GeneralSection(vm, showPw) { showPw = !showPw }
                1 -> ListSection(vm, link) { link = it }
                2 -> PlaybackSection(vm)
                else -> AppearanceSection(vm)
            }
        }
    }
}
