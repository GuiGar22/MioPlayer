package it.mioplayer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface Screen {
    object Home : Screen
    object Settings : Screen
    class Series(val name: String, val eps: List<Item>) : Screen
    class Player(val list: List<Item>, val index: Int) : Screen
}

class Vm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("mioplayer", 0)
    private val file = File(app.filesDir, "list.m3u")
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    var items by mutableStateOf<List<Item>>(emptyList())
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var url by mutableStateOf(prefs.getString("url", "") ?: "")
    var screen by mutableStateOf<Screen>(Screen.Home)
    private val stack = ArrayList<Screen>()
    var recents by mutableStateOf(loadList("recents"))
    var favs by mutableStateOf(loadList("favs"))
    var tab by mutableStateOf(if (recents.isNotEmpty()) 0 else 2)
    var group by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    init {
        if (file.exists()) viewModelScope.launch(Dispatchers.IO) { reload() }
    }

    private fun loadList(key: String): List<Item> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun saveList(key: String, list: List<Item>) {
        prefs.edit().putString(key, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun saveLast(i: Item) {
        recents = (listOf(i) + recents.filter { it.url != i.url }).take(30)
        saveList("recents", recents)
    }

    fun isFav(i: Item) = favs.any { it.url == i.url }

    fun toggleFav(i: Item) {
        favs = if (isFav(i)) favs.filter { it.url != i.url } else favs + i
        saveList("favs", favs)
    }

    fun navigate(s: Screen) { stack.add(screen); screen = s }
    fun back() { screen = if (stack.isEmpty()) Screen.Home else stack.removeAt(stack.size - 1) }

    fun play(list: List<Item>, index: Int) = navigate(Screen.Player(list, index))

    /** Apre una scheda: le serie aprono il dettaglio episodi, il resto parte subito. */
    fun open(list: List<Item>, index: Int) {
        val i = list[index]
        if (i.isSeriesCard) {
            val n = i.series ?: i.name
            navigate(Screen.Series(n, items.filter { it.type == Type.SERIE && it.series == n }))
        } else play(list, index)
    }

    private fun reload() {
        items = file.useLines { M3u.parse(it) }
        status = if (items.isEmpty()) "Lista vuota o non valida" else "Lista caricata: ${items.size} elementi"
    }

    fun saveUrlAndRefresh(newUrl: String) {
        url = newUrl.trim()
        prefs.edit().putString("url", url).apply()
        refresh()
    }

    fun refresh() {
        if (url.isBlank()) { status = "Inserisci un link prima"; return }
        loading = true
        status = "Scarico la lista…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { status = "Errore server: ${r.code}"; return@use }
                    val tmp = File(file.path + ".tmp")
                    r.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    tmp.renameTo(file)
                    reload()
                }
            } catch (e: Exception) {
                status = "Errore: ${e.message ?: "connessione"}"
            }
            loading = false
        }
    }

    fun importText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            file.writeText(text)
            reload()
        }
    }

    fun clear() {
        file.delete(); items = emptyList(); status = "Lista rimossa"
    }
}
