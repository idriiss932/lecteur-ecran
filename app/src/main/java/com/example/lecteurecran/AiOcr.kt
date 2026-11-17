package com.example.lecteurecran

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Lecture du texte d'une image par l'API Claude (vision). Appels bloquants : à lancer hors du thread principal. */
object AiOcr {

    const val MODEL_PRECISE = "claude-sonnet-5-5"
    const val MODEL_FAST = "claude-haiku-5-5"

    private const val URL_API = "https://api.anthropic.com/v1/messages"

    /** Transcrit le texte d'une bulle (whole=false : bulle au centre) ou de toute la zone (whole=true). */
    fun transcribe(
        apiKey: String, model: String, bmp: Bitmap,
        hint: String, whole: Boolean, langName: String
    ): String {
        var img = bmp
        val maxSide = 1400
        if (max(img.width, img.height) > maxSide) {
            val k = maxSide.toFloat() / max(img.width, img.height)
            img = Bitmap.createScaledBitmap(img, (img.width * k).toInt(), (img.height * k).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (img !== bmp) img.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val target = if (whole) {
            "Transcris tout le texte visible dans cette image, dans l'ordre de lecture (haut vers bas, gauche vers droite)."
        } else {
            "Transcris uniquement le texte de la bulle (ou de la case de texte) située au centre de l'image. " +
                "Ignore les textes d'autres bulles qui apparaissent coupés sur les bords."
        }
        val prompt = "Tu es un lecteur de bandes dessinées. L'image est un extrait d'une page de BD en $langName. " +
            target + " Les polices de BD sont stylisées : lis bien chaque mot et corrige les confusions de lettres. " +
            "Réponds uniquement par le texte à lire à voix haute, en phrases normales (casse normale, " +
            "pas de MAJUSCULES inutiles), sans guillemets ni commentaire. " +
            "S'il n'y a aucun texte, réponds exactement : [vide]." +
            (if (hint.isNotBlank()) "\nLecture automatique approximative (peut contenir des erreurs) : $hint" else "")

        val content = JSONArray()
            .put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                )
            )
            .put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 700)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return extractText(post(apiKey, body))
    }

    /** Petit appel de test pour vérifier la clé et le modèle. */
    fun ping(apiKey: String, model: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 10)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Réponds seulement : OK")
                )
            )
        return extractText(post(apiKey, body))
    }

    private fun extractText(json: String): String {
        val content = JSONObject(json).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.getString("text"))
        }
        return sb.toString().trim()
    }

    private fun post(apiKey: String, body: JSONObject): String {
        val c = URL(URL_API).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                "HTTP $code"
            }
            throw RuntimeException(
                when (code) {
                    401 -> "clé API invalide"
                    403 -> "accès refusé ($msg)"
                    404 -> "modèle introuvable ($msg)"
                    429 -> "trop de requêtes ou crédit épuisé ($msg)"
                    else -> msg
                }
            )
        }
        return text
    }
}
