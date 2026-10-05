package it.mioplayer

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.NetworkInterface

class UploadServer(
    port: Int,
    private val onUrl: (String) -> Unit,
    private val onFile: (String) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val path = files["file"]
                val link = session.parms["url"]?.trim().orEmpty()
                val f = path?.let { File(it) }
                when {
                    f != null && f.exists() && f.length() > 0 -> onFile(f.readText())
                    link.isNotEmpty() -> onUrl(link)
                }
                html("<h2>✅ Fatto! Guarda la TV.</h2>")
            } catch (e: Exception) {
                html("<h2>Errore: ${e.message}</h2>")
            }
        }
        return html(
            """<h2>📺 Mio Player</h2>
            <form method="post" enctype="multipart/form-data">
            <p>Link della lista:<br><input name="url" style="width:100%;font-size:18px;padding:10px"></p>
            <p>oppure file .m3u:<br><input type="file" name="file"></p>
            <button style="font-size:20px;padding:12px 28px">Invia alla TV</button></form>"""
        )
    }

    private fun html(body: String) = newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8",
        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='font-family:sans-serif;max-width:560px;margin:24px auto;padding:0 16px'>$body</body></html>"
    )

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }
    }
}
