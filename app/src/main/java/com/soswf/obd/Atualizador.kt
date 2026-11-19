package com.soswf.obd

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Atualização a partir das Releases públicas do GitHub (o CI publica uma por compilação,
 * com tag v0.2.<execução>; o versionCode do app é o mesmo número).
 * Bloqueante: chamar de Dispatchers.IO.
 */
object Atualizador {

    private const val API = "https://api.github.com/repos/WarlemFreire/obd2/releases/latest"

    data class Disponivel(val versao: String, val codigo: Long, val url: String, val tamanho: Long, val sha256: String?, val notas: String)

    fun codigoInstalado(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Última versão publicada, ou null se não houver nenhuma. */
    fun consultar(): Disponivel? {
        val con = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000; readTimeout = 15_000
        }
        try {
            when (con.responseCode) {
                200 -> Unit
                404 -> throw IOException("Nenhuma versão publicada (o repositório precisa estar público)")
                else -> throw IOException("GitHub respondeu ${con.responseCode}")
            }
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val tag = j.getString("tag_name")
            val codigo = tag.substringAfterLast('.').toLongOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return Disponivel(
                versao = tag.removePrefix("v"),
                codigo = codigo,
                url = apk.getString("browser_download_url"),
                tamanho = apk.optLong("size", -1),
                sha256 = apk.optString("digest", "").removePrefix("sha256:").ifEmpty { null },
                notas = j.optString("body", "").lineSequence().firstOrNull().orEmpty(),
            )
        } finally {
            con.disconnect()
        }
    }

    /** Baixa e confere tamanho e SHA-256. [progresso] recebe 0..100. */
    fun baixar(ctx: Context, d: Disponivel, progresso: (Int) -> Unit): File {
        val pasta = File(ctx.cacheDir, "atualizacao").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val destino = File(pasta, "obd-etios-${d.versao}.apk")
        val con = (URL(d.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 15_000; readTimeout = 30_000
        }
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            if (con.responseCode != 200) throw IOException("Download respondeu ${con.responseCode}")
            val total = if (d.tamanho > 0) d.tamanho else con.contentLengthLong
            var lido = 0L
            con.inputStream.use { ent ->
                destino.outputStream().use { sai ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ent.read(buf)
                        if (n < 0) break
                        sai.write(buf, 0, n); hash.update(buf, 0, n); lido += n
                        if (total > 0) progresso((lido * 100 / total).toInt())
                    }
                }
            }
            if (d.tamanho > 0 && lido != d.tamanho) throw IOException("Download incompleto ($lido de ${d.tamanho} bytes)")
        } finally {
            con.disconnect()
        }
        val obtido = hash.digest().joinToString("") { "%02x".format(it) }
        if (d.sha256 != null && !obtido.equals(d.sha256, ignoreCase = true)) {
            destino.delete()
            throw IOException("Arquivo baixado não confere com o publicado (SHA-256)")
        }
        return destino
    }

    /** Android 8+: o usuário precisa permitir uma vez "instalar apps desconhecidos" para este app. */
    fun podeInstalar(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun abrirPermissaoInstalar(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Abre o instalador do Android; a confirmação final é sempre do usuário. */
    fun instalar(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
