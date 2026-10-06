package it.mioplayer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface Screen {
    object Home : Screen
    object Settings : Screen
    class Series(val name: String, val eps: List<Item>) : Screen
    class Player(val list: List<Item>, val index: Int) : Screen
}

data class Account(
    val user: String, val pass: String, val status: String, val expiry: String,
    val created: String, val maxConn: String, val activeConn: String,
    val host: String, val message: String
)

data class EpgEntry(val title: String, val start: Long, val end: Long, val desc: String)

class Pos(val pos: Long, val dur: Long)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

private fun fmtDate(sec: String): String {
    val e = sec.toLongOrNull() ?: return "Illimitata"
    val days = (e * 1000 - System.currentTimeMillis()) / 86_400_000
    val txt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(e * 1000))
    return if (days >= 0) "$txt  (tra $days giorni)" else "$txt  (scaduta da ${-days} giorni)"
}

private fun b64(s: String): String = try {
    val d = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    if (d.isBlank() || d.contains('\uFFFD')) s else d
} catch (e: Exception) { s }

class Vm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mioplayer", 0)
    private val cacheFile = File(app.filesDir, "cache.bin")
    private val legacyFile = File(app.filesDir, "list.m3u")
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val epgCache = ConcurrentHashMap<String, Pair<Long, List<EpgEntry>>>()
    private val stack = ArrayList<Screen>()
    private var startupJob: Job? = null
    private var lastRefreshAt = prefs.getLong("lastRefresh", 0L)
    private var bgAt = 0L
    var vlcLaunched = false

    var items by mutableStateOf<List<Item>>(emptyList())
    var index by mutableStateOf(Index.EMPTY)
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var listLoading by mutableStateOf(false)
    var url by mutableStateOf(prefs.getString("url", "") ?: "")
    var screen by mutableStateOf<Screen>(Screen.Home)
    var recents by mutableStateOf(loadList("recents"))
    var favs by mutableStateOf(loadList("favs"))
    var playCounts by mutableStateOf(loadCounts())
    var positions by mutableStateOf(loadPositions())
    var tab by mutableStateOf(0)
    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    // Avvio
    var splash by mutableStateOf(true)
    var splashMsg by mutableStateOf("Avvio…")
    var progressCount by mutableStateOf(0)

    // Impostazioni
    var output by mutableStateOf(prefs.getString("output", "default") ?: "default")
    var playerPref by mutableStateOf(prefs.getString("player", "native") ?: "native")
    var audioLang by mutableStateOf(prefs.getString("audio", "auto") ?: "auto")
    var subLang by mutableStateOf(prefs.getString("sub", "off") ?: "off")
    var subSize by mutableStateOf(prefs.getString("subSize", "m") ?: "m")
    var subBg by mutableStateOf(prefs.getString("subBg", "none") ?: "none")
    var sort by mutableStateOf(prefs.getString("sort", "az") ?: "az")
    var listView by mutableStateOf(prefs.getBoolean("listView", false))

    // Info account (Xtream)
    var account by mutableStateOf<Account?>(null)
    var accountMsg by mutableStateOf("")
    var accountLoading by mutableStateOf(false)

    init {
        tab = if (recents.isNotEmpty()) 0 else 2
        startup(true)
    }

    // ---------- avvio e ciclo di vita ----------
    fun startup(force: Boolean) {
        if (startupJob?.isActive == true) return
        startupJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            splash = true
            if (force) { stack.clear(); screen = Screen.Home }
            splashMsg = "Carico la lista salvata…"
            progressCount = 0
            withContext(Dispatchers.IO) { loadCache() }
            val canRefresh = url.isNotBlank() && System.currentTimeMillis() - lastRefreshAt > 120_000
            if (canRefresh) {
                splashMsg = "Aggiorno la lista dal provider…"
                progressCount = 0
                val job = refresh()
                while (job.isActive && System.currentTimeMillis() - t0 < 10_000) delay(100)
            }
            val spent = System.currentTimeMillis() - t0
            if (spent < 1000) delay(1000 - spent)
            splash = false
        }
    }

    fun onStopped() { bgAt = System.currentTimeMillis() }

    fun onStarted() {
        val t = bgAt
        bgAt = 0L
        val skip = vlcLaunched
        vlcLaunched = false
        if (t > 0 && !skip && System.currentTimeMillis() - t > 120_000) startup(true)
    }

    // ---------- preferenze ----------
    fun setOutput(v: String) {
        output = v
        prefs.edit().putString("output", v).apply()
        if (url.isNotBlank()) refresh()
    }
    fun setPlayerPref(v: String) { playerPref = v; prefs.edit().putString("player", v).apply() }
    fun setAudio(v: String) { audioLang = v; prefs.edit().putString("audio", v).apply() }
    fun setSub(v: String) { subLang = v; prefs.edit().putString("sub", v).apply() }
    fun setSubSize(v: String) { subSize = v; prefs.edit().putString("subSize", v).apply() }
    fun setSubBg(v: String) { subBg = v; prefs.edit().putString("subBg", v).apply() }
    fun setSort(v: String) { sort = v; prefs.edit().putString("sort", v).apply() }
    fun setListView(b: Boolean) { listView = b; prefs.edit().putBoolean("listView", b).apply() }
    fun cycleSort() {
        val order = listOf("provider", "az", "za", "views")
        setSort(order[(order.indexOf(sort) + 1) % order.size])
    }

    // ---------- recenti / preferiti / contatori / posizioni ----------
    private fun loadList(key: String): List<Item> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun saveList(key: String, list: List<Item>) {
        prefs.edit().putString(key, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun loadCounts(): Map<String, Int> = try {
        val o = JSONObject(prefs.getString("counts", "{}"))
        o.keys().asSequence().associateWith { o.getInt(it) }
    } catch (e: Exception) { emptyMap() }

    private fun loadPositions(): Map<String, Pos> = try {
        val o = JSONObject(prefs.getString("pos", "{}"))
        val m = LinkedHashMap<String, Pos>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            m[k] = Pos(a.getLong(0), a.getLong(1))
        }
        m
    } catch (e: Exception) { emptyMap() }

    fun playCount(i: Item): Int = playCounts[i.url] ?: 0

    fun posOf(i: Item): Pos? = positions[i.url]

    fun savePos(i: Item, pos: Long, dur: Long, persist: Boolean) {
        if (i.type == Type.LIVE) return
        val m = LinkedHashMap(positions)
        m.remove(i.url)
        m[i.url] = Pos(pos, dur)
        while (m.size > 400) m.remove(m.keys.first())
        positions = m
        if (persist) {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, JSONArray().put(v.pos).put(v.dur))
            prefs.edit().putString("pos", o.toString()).apply()
        }
    }

    fun saveLast(i: Item) {
        recents = (listOf(i) + recents.filter { it.url != i.url }).take(30)
        saveList("recents", recents)
        val keys = if (i.type == Type.SERIE && i.series != null) listOf(i.url, "series:${i.series}") else listOf(i.url)
        playCounts = playCounts + keys.associateWith { (playCounts[it] ?: 0) + 1 }
        prefs.edit().putString("counts", JSONObject(playCounts).toString()).apply()
    }

    fun clearRecents() { recents = emptyList(); saveList("recents", recents) }

    fun clearCounts() {
        playCounts = emptyMap()
        prefs.edit().remove("counts").apply()
    }

    fun isFav(i: Item) = favs.any { it.url == i.url }

    fun toggleFav(i: Item) {
        favs = if (isFav(i)) favs.filter { it.url != i.url } else favs + i
        saveList("favs", favs)
    }

    // ---------- navigazione e riproduzione ----------
    fun navigate(s: Screen) { stack.add(screen); screen = s }
    fun back() { screen = if (stack.isEmpty()) Screen.Home else stack.removeAt(stack.size - 1) }

    fun launchVlc(i: Item, startMs: Long = 0L): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(i.url), "video/*")
                .setPackage("org.videolan.vlc")
                .putExtra("title", i.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startMs > 0) intent.putExtra("position", startMs)
            vlcLaunched = true
            getApplication<Application>().startActivity(intent)
            saveLast(i)
            true
        } catch (e: Exception) {
            vlcLaunched = false
            Toast.makeText(getApplication(), "VLC non trovato: uso il player integrato", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun play(list: List<Item>, index: Int) {
        val i = list[index]
        if (playerPref == "vlc") {
            val st = if (i.type == Type.LIVE) 0L else (posOf(i)?.pos ?: 0L)
            if (launchVlc(i, st)) return
        }
        navigate(Screen.Player(list, index))
    }

    /** Le serie aprono il dettaglio episodi, il resto parte subito. */
    fun open(list: List<Item>, index: Int) {
        val i = list[index]
        if (i.isSeriesCard) {
            val n = i.series ?: i.name
            navigate(Screen.Series(n, items.filter { it.type == Type.SERIE && it.series == n }))
        } else play(list, index)
    }

    // ---------- lista ----------
    private fun applyItems(list: List<Item>) {
        val idx = buildIndex(list)
        items = list
        index = idx
    }

    private fun loadCache(): Boolean {
        listLoading = true
        try {
            if (cacheFile.exists()) {
                val l = ListCache.read(cacheFile) { n -> progressCount = n }
                if (l != null && l.isNotEmpty()) {
                    applyItems(l)
                    status = "Lista caricata: ${l.size} elementi"
                    return true
                }
            }
            if (legacyFile.exists()) {
                val l = legacyFile.useLines { M3u.parse(it) { n -> progressCount = n } }
                if (l.isNotEmpty()) {
                    applyItems(l)
                    ListCache.write(cacheFile, l)
                    legacyFile.delete()
                    status = "Lista caricata: ${l.size} elementi"
                    return true
                }
            }
        } catch (e: Throwable) {
            status = "Errore nel leggere la lista salvata"
        } finally {
            listLoading = false
        }
        return false
    }

    fun reloadSaved() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!loadCache()) status = "Nessuna lista salvata"
        }
    }

    private fun effectiveUrl(): String {
        if (output == "default") return url
        val re = Regex("([?&])output=[^&]*")
        return if (re.containsMatchIn(url)) url.replace(re, "\$1output=$output")
        else url + (if (url.contains("?")) "&" else "?") + "output=" + output
    }

    fun saveUrlAndRefresh(newUrl: String) {
        url = newUrl.trim()
        prefs.edit().putString("url", url).apply()
        refresh()
    }

    fun refresh(): Job {
        if (url.isBlank()) {
            status = "Nessun link salvato: aggiungilo in Lista"
            return viewModelScope.launch { }
        }
        loading = true
        status = "Scarico la lista…"
        val target = effectiveUrl()
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(target).header("User-Agent", "Mozilla/5.0").build()
                http.newCall(req).execute().use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null) { status = "Errore server: ${r.code}"; return@use }
                    val parsed = java.io.BufferedReader(java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8), 65536).useLines { lines ->
                        M3u.parse(lines) { n -> progressCount = n }
                    }
                    if (parsed.isEmpty()) {
                        status = "Il provider ha risposto con una lista vuota: tengo quella salvata"
                        return@use
                    }
                    applyItems(parsed)
                    ListCache.write(cacheFile, parsed)
                    lastRefreshAt = System.currentTimeMillis()
                    prefs.edit().putLong("lastRefresh", lastRefreshAt).apply()
                    status = "Lista aggiornata: ${parsed.size} elementi"
                }
            } catch (e: Throwable) {
                status = "Errore: ${e.message ?: "connessione"}"
            }
            loading = false
            fetchAccount()
        }
    }

    fun importText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = M3u.parse(text.lineSequence())
            if (parsed.isEmpty()) { status = "File non valido o vuoto"; return@launch }
            applyItems(parsed)
            ListCache.write(cacheFile, parsed)
            status = "Lista caricata: ${parsed.size} elementi"
        }
    }

    fun clear() {
        cacheFile.delete()
        legacyFile.delete()
        applyItems(emptyList())
        status = "Lista rimossa"
    }

    // ---------- guida TV (EPG Xtream) ----------
    suspend fun epgFor(item: Item): List<EpgEntry> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(item.url)
            val seg = uri.pathSegments
            if (seg.size < 3 || uri.host == null) return@withContext emptyList<EpgEntry>()
            val id = seg.last().substringBeforeLast('.')
            if (id.isEmpty() || !id.all { it.isDigit() }) return@withContext emptyList<EpgEntry>()
            val pass = seg[seg.size - 2]
            val user = seg[seg.size - 3]
            val key = "${uri.authority}/$id"
            val now = System.currentTimeMillis()
            val hit = epgCache[key]
            if (hit != null && now - hit.first < 120_000) return@withContext hit.second
            val api = "${uri.scheme}://${uri.authority}/player_api.php?username=${Uri.encode(user)}" +
                "&password=${Uri.encode(pass)}&action=get_short_epg&stream_id=$id&limit=4"
            val body = http.newCall(Request.Builder().url(api).header("User-Agent", "Mozilla/5.0").build())
                .execute().use { it.body?.string() ?: "" }
            val arr = JSONObject(body).optJSONArray("epg_listings") ?: return@withContext emptyList<EpgEntry>()
            val out = ArrayList<EpgEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.str("start_timestamp").toLongOrNull()
                val e = o.str("stop_timestamp").toLongOrNull()
                if (s != null && e != null) out.add(EpgEntry(b64(o.str("title")), s, e, b64(o.str("description"))))
            }
            epgCache[key] = Pair(now, out)
            out
        } catch (e: Exception) {
            emptyList<EpgEntry>()
        }
    }

    // ---------- info account Xtream ----------
    fun loadAccount() { viewModelScope.launch(Dispatchers.IO) { fetchAccount() } }

    private fun fetchAccount() {
        val uri = try { Uri.parse(url) } catch (e: Exception) { null }
        val user = uri?.getQueryParameter("username")
        val pass = uri?.getQueryParameter("password")
        if (uri == null || uri.host == null || user.isNullOrEmpty() || pass.isNullOrEmpty()) {
            account = null
            accountMsg = "Le informazioni account sono disponibili solo con un link Xtream (get.php?username=…&password=…)."
            return
        }
        accountLoading = true
        try {
            val base = "${uri.scheme}://${uri.authority}"
            val api = "$base/player_api.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}"
            val body = http.newCall(Request.Builder().url(api).header("User-Agent", "Mozilla/5.0").build())
                .execute().use { it.body?.string() ?: "" }
            val j = JSONObject(body)
            val ui = j.getJSONObject("user_info")
            val si = j.optJSONObject("server_info")
            val st = when {
                ui.optInt("auth", 1) == 0 -> "Credenziali non valide"
                else -> when (ui.str("status")) {
                    "Active" -> "Attivo"
                    "Expired" -> "Scaduto"
                    "Banned" -> "Bloccato"
                    "Disabled" -> "Disattivato"
                    else -> ui.str("status").ifEmpty { "—" }
                }
            }
            account = Account(
                user = ui.str("username").ifEmpty { user },
                pass = ui.str("password").ifEmpty { pass },
                status = st,
                expiry = fmtDate(ui.str("exp_date")),
                created = if (ui.str("created_at").isEmpty()) "—" else fmtDate(ui.str("created_at")),
                maxConn = ui.str("max_connections").ifEmpty { "—" },
                activeConn = ui.str("active_cons").ifEmpty { "0" },
                host = (si?.str("url") ?: "").ifEmpty { uri.host ?: "" },
                message = ui.str("message")
            )
            accountMsg = ""
        } catch (e: Exception) {
            account = null
            accountMsg = "Impossibile leggere l'account: ${e.message ?: "errore di rete"}"
        }
        accountLoading = false
    }
}
