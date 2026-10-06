package it.mioplayer

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class Type(val label: String) { LIVE("TV in diretta"), FILM("Film"), SERIE("Serie TV") }

data class Item(
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val type: Type,
    val series: String? = null,
    val season: Int = 0,
    val episode: Int = 0
) {
    val isSeriesCard get() = url.startsWith("series:")

    fun toJson(): JSONObject = JSONObject().put("n", name).put("l", logo ?: "").put("g", group)
        .put("u", url).put("t", type.name).put("s", series ?: "").put("se", season).put("e", episode)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            o.getString("n"), o.optString("l").ifEmpty { null }, o.optString("g"), o.getString("u"),
            Type.valueOf(o.getString("t")), o.optString("s").ifEmpty { null }, o.optInt("se"), o.optInt("e")
        )
    }
}

/** Per le serie: una scheda per serie (non per episodio). */
fun cardsOf(items: List<Item>, type: Type): List<Item> =
    if (type != Type.SERIE) items.filter { it.type == type }
    else items.filter { it.type == Type.SERIE }.groupBy { it.series ?: it.name }.map { (s, eps) ->
        val f = eps.first()
        Item(s, f.logo, f.group, "series:$s", Type.SERIE, s)
    }

/** Indici precalcolati (fuori dal thread principale) per tenere l'interfaccia fluida. */
class Index(
    private val cards: Map<Type, List<Item>>,
    private val groups: Map<Type, List<Pair<String, List<Item>>>>
) {
    fun cards(t: Type): List<Item> = cards[t] ?: emptyList()
    fun groups(t: Type): List<Pair<String, List<Item>>> = groups[t] ?: emptyList()

    companion object { val EMPTY = Index(emptyMap(), emptyMap()) }
}

fun buildIndex(items: List<Item>): Index {
    val cards = HashMap<Type, List<Item>>()
    val groups = HashMap<Type, List<Pair<String, List<Item>>>>()
    for (t in Type.values()) {
        val c = cardsOf(items, t)
        cards[t] = c
        groups[t] = c.groupBy { it.group }.toList()
    }
    return Index(cards, groups)
}

/** Cache binaria della lista: si legge in pochi secondi anche con decine di migliaia di titoli. */
object ListCache {
    private const val MAGIC = 0x47475032

    private fun cut(s: String) = if (s.length > 20000) s.substring(0, 20000) else s

    fun write(file: File, items: List<Item>) {
        val tmp = File(file.path + ".tmp")
        val pool = LinkedHashMap<String, Int>()
        fun idx(s: String): Int = pool.getOrPut(s) { pool.size }
        val gi = IntArray(items.size)
        val si = IntArray(items.size)
        for (n in items.indices) {
            val m = items[n]
            gi[n] = idx(m.group)
            val s = m.series
            si[n] = if (s != null) idx(s) else -1
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 65536)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(pool.size)
            for (k in pool.keys) out.writeUTF(cut(k))
            out.writeInt(items.size)
            for (n in items.indices) {
                val m = items[n]
                out.writeUTF(cut(m.name))
                out.writeUTF(cut(m.logo ?: ""))
                out.writeInt(gi[n])
                out.writeUTF(cut(m.url))
                out.writeByte(m.type.ordinal)
                out.writeInt(si[n])
                out.writeShort(m.season)
                out.writeShort(m.episode)
            }
        }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
    }

    fun read(file: File, onProgress: (Int) -> Unit): List<Item>? {
        val inp = DataInputStream(BufferedInputStream(FileInputStream(file), 65536))
        try {
            if (inp.readInt() != MAGIC) return null
            val n = inp.readInt()
            val pool = Array(n) { inp.readUTF() }
            val count = inp.readInt()
            val types = Type.values()
            val out = ArrayList<Item>(count)
            for (i in 0 until count) {
                val name = inp.readUTF()
                val logo = inp.readUTF()
                val g = inp.readInt()
                val url = inp.readUTF()
                val t = inp.readUnsignedByte()
                val s = inp.readInt()
                val season = inp.readShort().toInt()
                val ep = inp.readShort().toInt()
                out.add(Item(name, if (logo.isEmpty()) null else logo, pool[g], url, types[t], if (s >= 0) pool[s] else null, season, ep))
                if (i % 5000 == 0) onProgress(i)
            }
            return out
        } finally {
            inp.close()
        }
    }
}

object M3u {
    private val sxe = Regex("""^(.*?)[\s._\-|]*[Ss](\d{1,2})\s*[._-]?\s*[Ee](\d{1,3}).*$""")
    private val nxm = Regex("""^(.*?)[\s._\-|]*(\d{1,2})x(\d{2,3}).*$""")

    private fun attr(line: String, key: String): String {
        val k = "$key=\""
        val s = line.indexOf(k)
        if (s < 0) return ""
        val st = s + k.length
        val e = line.indexOf('"', st)
        return if (e < 0) "" else line.substring(st, e).trim()
    }

    private fun classify(group: String, url: String): Type {
        return when {
            url.contains("/series/", true) -> Type.SERIE
            url.contains("/movie/", true) -> Type.FILM
            url.contains("/live/", true) -> Type.LIVE
            else -> {
                val g = group.lowercase()
                when {
                    g.contains("serie") && !g.contains("serie a") && !g.contains("serie b") -> Type.SERIE
                    g.contains("film") || g.contains("movie") || g.contains("vod") -> Type.FILM
                    url.endsWith(".mkv", true) || url.endsWith(".mp4", true) || url.endsWith(".avi", true) -> Type.FILM
                    else -> Type.LIVE
                }
            }
        }
    }

    private fun episodeInfo(name: String): Triple<String, Int, Int>? {
        val m = sxe.matchEntire(name) ?: nxm.matchEntire(name) ?: return null
        val title = m.groupValues[1].trim().trim('-', '|', ' ', '.', '_').ifEmpty { name }
        return Triple(title, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    fun parse(lines: Sequence<String>, onProgress: ((Int) -> Unit)? = null): List<Item> {
        val out = ArrayList<Item>()
        val pool = HashMap<String, String>()
        fun intern(s: String): String = pool.getOrPut(s) { s }
        var name: String? = null
        var logo: String? = null
        var group = ""
        for (raw in lines) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                group = intern(attr(l, "group-title"))
                logo = attr(l, "tvg-logo").ifEmpty { null }
                name = l.substringAfterLast(',').trim().ifEmpty { attr(l, "tvg-name") }
            } else if (l.isNotEmpty() && l[0] != '#' && name != null) {
                val type = classify(group, l)
                val g = group.ifEmpty { "Altro" }
                if (type == Type.SERIE) {
                    val p = episodeInfo(name)
                    out.add(Item(name, logo, g, l, type, intern(p?.first ?: name), p?.second ?: 1, p?.third ?: 0))
                } else out.add(Item(name, logo, g, l, type))
                name = null
                if (onProgress != null && out.size % 5000 == 0) onProgress(out.size)
            }
        }
        return out
    }
}
