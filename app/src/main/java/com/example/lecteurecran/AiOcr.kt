package com.example.lecteurecran

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Lecture du texte d'une image par une IA de vision.
 *  - "gemini" : API Gemini de Google (offre gratuite, clé sur aistudio.google.com)
 *  - "claude" : API Claude d'Anthropic (payante, clé sur console.anthropic.com)
 * Appels bloquants : à lancer hors du thread principal.
 */
object AiOcr {

    class Cfg(val provider: String, val key: String, val model: String)

    private class ModelNotFound(msg: String) : RuntimeException(msg)
    private class Overloaded(msg: String, val retry: Boolean = true) : RuntimeException(msg)

    /**
     * Gemini : réessaie si Google est surchargé, puis essaie d'autres modèles Flash.
     * Si le modèle réglé n'existe plus, le premier modèle qui marche est mémorisé (onFixed).
     */
    private fun <T> withModelFix(cfg: Cfg, onFixed: (String) -> Unit, block: (Cfg) -> T): T {
        if (cfg.provider != "gemini") return block(cfg)
        var lastErr: RuntimeException? = null
        for (attempt in 0..2) {
            try {
                return block(cfg)
            } catch (e: ModelNotFound) {
                lastErr = e
                break
            } catch (e: Overloaded) {
                lastErr = e
                if (!e.retry) break
                if (attempt < 2) Thread.sleep(2000L * (attempt + 1))
            }
        }
        val others = try {
            discoverGeminiModels(cfg.key).filter { it != cfg.model }
        } catch (e: Exception) {
            emptyList()
        }
        for (m in others.take(3)) {
            try {
                val r = block(Cfg(cfg.provider, cfg.key, m))
                if (lastErr is ModelNotFound) onFixed(m)
                return r
            } catch (e: ModelNotFound) {
                // modèle suivant
            } catch (e: Overloaded) {
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("aucun modèle Gemini disponible avec cette clé")
    }

    /** Modèles Flash pouvant générer du texte, du meilleur au moins bon (version haute, stable, pas « lite » d'abord). */
    private fun discoverGeminiModels(key: String): List<String> {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("x-goog-api-key", key)
        if (c.responseCode !in 200..299) return emptyList()
        val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models")
            ?: return emptyList()
        val rx = Regex("^gemini-(\\d+(?:\\.\\d+)?)-flash(.*)$")
        val bad = listOf("image", "tts", "live", "audio", "embedding", "native", "robotics", "computer")
        val scored = mutableListOf<Pair<Double, String>>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var ok = false
            for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
            if (!ok) continue
            val name = m.getString("name").removePrefix("models/")
            val match = rx.find(name) ?: continue
            if (bad.any { name.contains(it) }) continue
            val score = match.groupValues[1].toDouble() +
                (if (name.contains("preview") || name.contains("exp")) 0.0 else 0.002) -
                (if (name.contains("lite")) 0.001 else 0.0)
            scored.add(score to name)
        }
        return scored.sortedByDescending { it.first }.map { it.second }
    }

    fun defaultModel(provider: String) =
        if (provider == "claude") "claude-sonnet-5-5" else "gemini-2.5-flash"

    fun transcribe(
        cfg: Cfg, bmp: Bitmap, hint: String, whole: Boolean, langName: String,
        onModelFixed: (String) -> Unit = {}
    ): String {
        var img = bmp
        val maxSide = 1600
        if (max(img.width, img.height) > maxSide) {
            val k = maxSide.toFloat() / max(img.width, img.height)
            img = Bitmap.createScaledBitmap(img, (img.width * k).toInt(), (img.height * k).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (img !== bmp) img.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val target = if (whole) {
            "Transcris tout le texte des bulles et des cases de texte visibles dans cette image, " +
                "dans l'ordre de lecture d'une BD (haut vers bas, gauche vers droite). " +
                "Sépare les bulles par un saut de ligne."
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

        return withModelFix(cfg, onModelFixed) { c ->
            if (c.provider == "claude") claudeImage(c, b64, prompt) else geminiImage(c, b64, prompt)
        }
    }

    /** Petit appel de test pour vérifier la clé et le modèle. */
    fun ping(cfg: Cfg, onModelFixed: (String) -> Unit = {}): String =
        withModelFix(cfg, onModelFixed) { c -> pingOnce(c) }

    private fun pingOnce(cfg: Cfg): String =
        if (cfg.provider == "claude") {
            val body = JSONObject().put("model", cfg.model).put("max_tokens", 10).put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "Réponds seulement : OK"))
            )
            claudeText(post("https://api.anthropic.com/v1/messages", claudeHeaders(cfg), body, cfg))
        } else {
            val body = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", "Réponds seulement : OK"))
                    )
                )
            )
            geminiText(post(geminiUrl(cfg), geminiHeaders(cfg), body, cfg))
        }

    // ------------------------------------------------------------------ Claude

    private fun claudeHeaders(cfg: Cfg) = mapOf(
        "x-api-key" to cfg.key,
        "anthropic-version" to "2023-06-01"
    )

    private fun claudeImage(cfg: Cfg, b64: String, prompt: String): String {
        val content = JSONArray()
            .put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                )
            )
            .put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return claudeText(post("https://api.anthropic.com/v1/messages", claudeHeaders(cfg), body, cfg))
    }

    private fun claudeText(json: String): String {
        val content = JSONObject(json).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.getString("text"))
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ Gemini

    private fun geminiUrl(cfg: Cfg) =
        "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent"

    private fun geminiHeaders(cfg: Cfg) = mapOf("x-goog-api-key" to cfg.key)

    private fun geminiImage(cfg: Cfg, b64: String, prompt: String): String {
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                )
            )
            .put(JSONObject().put("text", prompt))
        val gen = JSONObject().put("temperature", 0.1).put("maxOutputTokens", 8000)
        // Modèles 2.5 Flash : pas de « réflexion » (plus rapide, et ne mange pas la limite de sortie)
        if (cfg.model.contains("2.5") && cfg.model.contains("flash")) {
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
        return geminiText(post(geminiUrl(cfg), geminiHeaders(cfg), body, cfg))
    }

    private fun geminiText(json: String): String {
        val cands = JSONObject(json).optJSONArray("candidates")
        if (cands == null || cands.length() == 0) throw RuntimeException("réponse bloquée par Google")
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw RuntimeException("réponse vide")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ réseau

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, cfg: Cfg): String {
        val code: Int
        val text: String
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            code = c.responseCode
            text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText() ?: ""
        } catch (e: java.net.SocketTimeoutException) {
            throw Overloaded("Google ne répond pas (délai dépassé) : connexion lente ou serveur occupé")
        } catch (e: java.net.UnknownHostException) {
            throw RuntimeException("pas d'accès à internet pour l'appli (Wi-Fi, VPN, DNS privé ou économiseur de données ?)")
        } catch (e: java.io.IOException) {
            throw RuntimeException("erreur réseau : ${e.message}")
        }
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                "HTTP $code"
            }
            val free = cfg.provider == "gemini"
            throw RuntimeException(
                when {
                    code == 401 || (code == 400 && msg.contains("API key", true)) -> "clé API invalide"
                    code == 403 -> "accès refusé : $msg"
                    code == 503 || code == 529 -> throw Overloaded("le serveur IA est surchargé en ce moment, réessaie dans un instant")
                    code == 404 -> throw ModelNotFound("modèle « ${cfg.model} » introuvable")
                    code == 429 && free && msg.contains("limit: 0") ->
                        throw Overloaded("le modèle « ${cfg.model} » n'est pas gratuit sur ton compte", false)
                    code == 429 && free ->
                        throw Overloaded("limite gratuite atteinte, réessaie dans une minute", false)
                    code == 429 -> "trop de requêtes ou crédit épuisé"
                    else -> msg
                }
            )
        }
        return text
    }
}
