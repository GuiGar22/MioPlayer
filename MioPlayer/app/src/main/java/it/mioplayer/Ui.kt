package it.mioplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

val Bg = Color(0xFF0B0F1A)
val Accent = Color(0xFF7C5CFF)
val Cyan = Color(0xFF22D3EE)
val CardBg = Color(0xFF151B2E)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.07f else 1f, label = "sc")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.scale(sc)
            .border(3.dp, if (f) Cyan else Color.Transparent, shape)
            .clip(shape)
            .onFocusChanged { f = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) { content(f) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(
            Modifier.background(if (selected) Accent else if (f) Color(0xFF2A3350) else CardBg)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
    }
}

@Composable
fun ItemCard(vm: Vm, m: Item, onClick: () -> Unit) {
    val live = m.type == Type.LIVE
    Focusable(onClick = onClick, onLongClick = { vm.toggleFav(m) }) {
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
                    "♥", color = Color(0xFFFF4D6D), fontSize = 26.sp,
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
fun HomeScreen(vm: Vm) {
    val tab = vm.tab
    val type = Type.values().getOrNull(tab - 2)
    val base = remember(vm.items, vm.recents, vm.favs, tab) {
        when (tab) { 0 -> vm.recents; 1 -> vm.favs; else -> cardsOf(vm.items, type!!) }
    }
    val groups = remember(base) {
        if (type == null) emptyList() else base.groupBy { it.group }.toList().sortedBy { it.first }
    }
    BackHandler(vm.group != null) { vm.group = null }
    val tabs = listOf("Recenti", "♥ Preferiti") + Type.values().map { it.label }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, null, tint = Cyan, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            tabs.forEachIndexed { i, t ->
                Chip(t, tab == i) { vm.tab = i; vm.group = null; vm.query = "" }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            Focusable(onClick = { vm.navigate(Screen.Settings) }) { f ->
                Box(Modifier.background(if (f) Accent else CardBg).padding(12.dp)) {
                    Icon(Icons.Default.Settings, "Impostazioni", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (vm.items.isEmpty() && vm.recents.isEmpty() && vm.favs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nessuna lista caricata", fontSize = 28.sp, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Apri le impostazioni ⚙ per aggiungere il tuo link M3U", fontSize = 18.sp, color = Color.Gray)
                    Spacer(Modifier.height(20.dp))
                    Chip("Apri impostazioni", true) { vm.navigate(Screen.Settings) }
                }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tab != 0) vm.recents.firstOrNull()?.let { l ->
                Chip("▶ Continua: ${l.name.take(26)}", true) { vm.play(listOf(l), 0) }
                Spacer(Modifier.width(12.dp))
            }
            if (vm.group != null) { Chip("← Categorie", false) { vm.group = null }; Spacer(Modifier.width(12.dp)) }
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Cerca…") },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Tieni premuto OK su un titolo per aggiungerlo ai preferiti ♥",
            fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
        )

        if (base.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (tab) {
                        0 -> "Qui compariranno gli ultimi titoli guardati"
                        1 -> "Nessun preferito: tieni premuto OK su un titolo"
                        else -> "Nessun elemento in questa sezione"
                    }, fontSize = 20.sp, color = Color.Gray
                )
            }
        } else if (type != null && vm.group == null && vm.query.isBlank()) {
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
                            Text(
                                "${list.size} ${if (type == Type.SERIE) "serie" else "elementi"}",
                                fontSize = 14.sp, color = Color.LightGray
                            )
                        }
                    }
                }
            }
        } else {
            val shown = remember(base, vm.group, vm.query) {
                base.filter { (vm.group == null || it.group == vm.group) && it.name.contains(vm.query, true) }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(if (type == Type.LIVE) 190.dp else 160.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(shown.size) { i ->
                    val m = shown[i]
                    ItemCard(vm, m) { if (type == null) vm.open(listOf(m), 0) else vm.open(shown, i) }
                }
            }
        }
    }
}

@Composable
fun SeriesScreen(vm: Vm, s: Screen.Series) {
    val seasons = remember(s) { s.eps.map { it.season }.distinct().sorted() }
    var season by remember { mutableStateOf(seasons.firstOrNull() ?: 1) }
    val eps = remember(s, season) { s.eps.filter { it.season == season }.sortedBy { it.episode } }
    val first = s.eps.firstOrNull()
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
                    Focusable(Modifier.fillMaxWidth(), onClick = { vm.play(eps, i) }) { f ->
                        Row(
                            Modifier.fillMaxWidth().background(if (f) Color(0xFF2A3350) else CardBg).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Cyan)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                (if (e.episode > 0) "Ep. ${e.episode}  ·  " else "") + e.name,
                                color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: Vm) {
    var link by remember { mutableStateOf(vm.url) }
    val ip = remember { UploadServer.localIp() }

    DisposableEffect(Unit) {
        val server = UploadServer(8080, { vm.saveUrlAndRefresh(it); link = it }, { vm.importText(it) })
        try { server.start() } catch (_: Exception) {}
        onDispose { server.stop() }
    }

    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("⚙ Impostazioni", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(20.dp)) {
            Text("📱 Carica dal telefono (più comodo)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                if (ip != null) "Con il telefono sulla stessa Wi-Fi apri:  http://$ip:8080" else "Collega la TV alla Wi-Fi",
                fontSize = 22.sp, color = Cyan
            )
            Text("Incolla il link oppure scegli il file .m3u", fontSize = 14.sp, color = Color.Gray)
        }

        OutlinedTextField(
            value = link, onValueChange = { link = it }, singleLine = true,
            label = { Text("Oppure scrivi qui il link M3U") }, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("Salva e carica", true) { vm.saveUrlAndRefresh(link) }
            Chip("Aggiorna lista", false) { vm.refresh() }
            Chip("Cancella lista", false) { vm.clear() }
            Chip("← Indietro", false) { vm.back() }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(vm.status, fontSize = 18.sp, color = Color.LightGray)
        if (vm.items.isNotEmpty()) Text(
            Type.values().joinToString("   ") { t -> "${t.label}: ${vm.items.count { it.type == t }}" },
            fontSize = 18.sp, color = Cyan
        )
    }
}
