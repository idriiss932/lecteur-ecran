package com.example.lecteurecran

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlin.math.max
import kotlin.math.min

/**
 * Lecture hors ligne améliorée : on relit chaque zone avec plusieurs prétraitements
 * (agrandissement, contraste automatique, noir et blanc) et on garde la lecture la plus plausible.
 * Appels bloquants : à lancer hors du thread principal.
 */
object LocalOcr {

    private fun norm(t: String) = t.lowercase().filter { it.isLetter() }

    fun read(rec: TextRecognizer, crop: Bitmap, hint: String, centerOnly: Boolean): String {
        val cands = mutableListOf<String>()
        if (hint.isNotBlank()) cands.add(hint)
        // Les prétraitements sont lancés un par un : on s'arrête dès que deux lectures indépendantes
        // sont d'accord (rapide quand le texte est facile, plus d'essais seulement si besoin).
        val makers = listOf<() -> Bitmap>(
            { autoLevels(scaleTo(crop, 2f)) },
            { scaleTo(crop, 2f) },
            { binarize(scaleTo(crop, 3f)) }
        )
        var tries = 0
        for (mk in makers) {
            val v = mk()
            try {
                val res = Tasks.await(rec.process(InputImage.fromBitmap(v, 0)))
                val t = pick(res, v.width / 2, v.height / 2, centerOnly)
                if (t.isNotBlank()) {
                    cands.add(t)
                    val n = norm(t)
                    if (n.length >= 3 && cands.size >= 2 && cands.dropLast(1).any { norm(it) == n }) {
                        v.recycle()
                        break
                    }
                }
            } catch (e: Exception) {
                // variante ignorée
            }
            v.recycle()
            tries++
        }
        val best = cands.maxByOrNull { score(it) } ?: return ""
        return fixConfusions(best)
    }

    /** Texte à garder dans un résultat : tout, ou seulement le bloc au centre (tap sans bloc détecté). */
    private fun pick(res: Text, cx: Int, cy: Int, centerOnly: Boolean): String {
        if (!centerOnly) return res.text
        var best: Text.TextBlock? = null
        var bestD = Long.MAX_VALUE
        for (b in res.textBlocks) {
            val r = b.boundingBox ?: continue
            val dx = if (cx < r.left) r.left - cx else if (cx > r.right) cx - r.right else 0
            val dy = if (cy < r.top) r.top - cy else if (cy > r.bottom) cy - r.bottom else 0
            val d = dx.toLong() * dx + dy.toLong() * dy
            if (d < bestD) {
                bestD = d
                best = b
            }
        }
        return best?.text ?: ""
    }

    // ------------------------------------------------------------------ prétraitements

    /** Toujours une nouvelle image (jamais l'originale). */
    private fun scaleTo(src: Bitmap, k: Float): Bitmap {
        val kk = min(k, 2600f / max(src.width, src.height))
        val w = max(1, (src.width * kk).toInt())
        val h = max(1, (src.height * kk).toInt())
        if (w == src.width && h == src.height) return src.copy(Bitmap.Config.ARGB_8888, true)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun gray(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Niveaux de gris avec étirement du contraste (2 % - 98 %). */
    private fun autoLevels(src: Bitmap): Bitmap {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val hist = IntArray(256)
        for (p in px) hist[gray(p)]++
        val total = px.size
        var acc = 0
        var lo = 0
        var hi = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= total * 0.02) {
                lo = i
                break
            }
        }
        acc = 0
        for (i in 255 downTo 0) {
            acc += hist[i]
            if (acc >= total * 0.02) {
                hi = i
                break
            }
        }
        if (hi - lo < 20) return src
        val s = 255f / (hi - lo)
        val t = -lo * s
        val m = ColorMatrix(
            floatArrayOf(
                0.299f * s, 0.587f * s, 0.114f * s, 0f, t,
                0.299f * s, 0.587f * s, 0.114f * s, 0f, t,
                0.299f * s, 0.587f * s, 0.114f * s, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(m) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out
    }

    /** Noir et blanc (seuil d'Otsu), texte noir sur fond blanc dans tous les cas. */
    private fun binarize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        val g = IntArray(px.size)
        for (i in px.indices) {
            g[i] = gray(px[i])
            hist[g[i]]++
        }
        val total = px.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVar = 0.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) {
                maxVar = v
                thr = t
            }
        }
        var black = 0
        for (i in g.indices) if (g[i] <= thr) black++
        val invert = black > total - black // le fond doit être la couleur majoritaire : blanc
        for (i in px.indices) {
            val isDark = g[i] <= thr
            val dark = if (invert) !isDark else isDark
            px[i] = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        src.recycle()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ------------------------------------------------------------------ choix de la meilleure lecture

    private val okOneLetter = setOf("a", "à", "y", "ô", "o")

    /** Plus c'est haut, plus la lecture ressemble à du vrai texte. */
    private fun score(t: String): Double {
        var letters = 0
        var bad = 0
        for (c in t) {
            if (c.isLetter()) letters++
            else if (!c.isDigit() && c !in " .,;:!?'’-…«»\"()\n") bad++
        }
        var odd = 0
        for (tok in t.split(Regex("\\s+"))) {
            if (tok.isBlank()) continue
            val core = tok.trim('.', ',', ';', ':', '!', '?', '"', '«', '»', '(', ')', '…')
            if (core.length == 1 && core.lowercase() !in okOneLetter) odd++
            if (core.any { it.isLetter() } && core.any { it.isDigit() }) odd++
        }
        return letters - 3.0 * bad - 2.5 * odd
    }

    /** Corrige les confusions classiques lettre/chiffre au milieu d'un mot. */
    private fun fixConfusions(s: String): String =
        s.replace(Regex("(?<=\\p{L})0(?=\\p{L})"), "o")
            .replace(Regex("(?<=\\p{L})[1|](?=\\p{L})"), "l")
            .replace(Regex("(?<=\\p{L})5(?=\\p{L})"), "s")
}
