package it.mioplayer

import org.json.JSONObject

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

object M3u {
    private val sxe = Regex("""^(.*?)[\s._\-|]*[Ss](\d{1,2})\s*[._-]?\s*[Ee](\d{1,3}).*$""")
    private val nxm = Regex("""^(.*?)[\s._\-|]*(\d{1,2})x(\d{2,3}).*$""")

    private fun attr(line: String, key: String): String =
        Regex(key + "=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.trim() ?: ""

    private fun classify(group: String, url: String): Type {
        val u = url.lowercase()
        val g = group.lowercase()
        return when {
            "/series/" in u -> Type.SERIE
            "/movie/" in u -> Type.FILM
            "/live/" in u -> Type.LIVE
            g.contains("serie") && !g.contains("serie a") && !g.contains("serie b") -> Type.SERIE
            g.contains("film") || g.contains("movie") || g.contains("vod") -> Type.FILM
            u.endsWith(".mkv") || u.endsWith(".mp4") || u.endsWith(".avi") -> Type.FILM
            else -> Type.LIVE
        }
    }

    private fun episodeInfo(name: String): Triple<String, Int, Int>? {
        val m = sxe.matchEntire(name) ?: nxm.matchEntire(name) ?: return null
        val title = m.groupValues[1].trim().trim('-', '|', ' ', '.', '_').ifEmpty { name }
        return Triple(title, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    fun parse(lines: Sequence<String>): List<Item> {
        val out = ArrayList<Item>()
        var name: String? = null
        var logo: String? = null
        var group = ""
        for (raw in lines) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) {
                group = attr(l, "group-title")
                logo = attr(l, "tvg-logo").ifEmpty { null }
                name = l.substringAfterLast(',').trim().ifEmpty { attr(l, "tvg-name") }
            } else if (l.isNotEmpty() && !l.startsWith("#") && name != null) {
                val type = classify(group, l)
                val g = group.ifEmpty { "Altro" }
                if (type == Type.SERIE) {
                    val p = episodeInfo(name)
                    out.add(Item(name, logo, g, l, type, p?.first ?: name, p?.second ?: 1, p?.third ?: 0))
                } else out.add(Item(name, logo, g, l, type))
                name = null
            }
        }
        return out
    }
}
