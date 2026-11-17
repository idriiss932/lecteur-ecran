package com.example.lecteurecran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Service au premier plan :
 *  - garde une MediaProjection ouverte et conserve la dernière image de l'écran (pas de capture manuelle) ;
 *  - affiche une bulle flottante ;
 *  - au tap sur la bulle : capture + OCR (ML Kit, hors ligne) + calque de sélection ;
 *  - lit le bloc touché avec la synthèse vocale d'Android.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.lecteurecran.STOP"
        private const val CHANNEL = "lecteur_ecran"
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private var dp = 1f

    // Capture d'écran
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestImage: Image? = null
    private val imageLock = Any()
    private var capW = 0
    private var capH = 0

    // UI
    private var bubble: View? = null
    private var pickerRoot: FrameLayout? = null
    private var picker: PickerView? = null
    private var pickerBlocks: List<OcrBlock> = emptyList()
    private var pickerBitmap: Bitmap? = null
    private var busy = false

    // Voix
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val utterRects = ConcurrentHashMap<String, List<Rect>>()
    @Volatile private var lastUtteranceId: String? = null
    private var speaking = false
    private var currentText: String? = null
    private var aiBusy = false
    private val aiPool = Executors.newFixedThreadPool(4)

    private class AiJob(val hint: String, val rects: List<Rect>, val region: Rect, val whole: Boolean)

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        dp = resources.displayMetrics.density
        captureThread = HandlerThread("capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (projection == null) {
            val code = intent?.getIntExtra("code", 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra("data", Intent::class.java)
            } else {
                intent?.getParcelableExtra("data")
            }
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                setupProjection(code, data)
            } catch (e: Exception) {
                toast("Capture d'écran impossible : ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            showBubble()
            initTts()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- notification

    @Suppress("DEPRECATION")
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lecteur d'écran", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Lecteur d'écran actif")
            .setContentText("Touche la bulle 🔊 pour lire du texte")
            .addAction(Notification.Action.Builder(0, "Arrêter", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- capture

    @Suppress("DEPRECATION")
    private fun screenInfo(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        // Obligatoire depuis Android 14 avant createVirtualDisplay
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        val (w, h, dpi) = screenInfo()
        buildCapture(w, h, dpi)
    }

    /** (Re)crée l'ImageReader ; le VirtualDisplay n'est créé qu'une fois (limite Android 14). */
    private fun buildCapture(w: Int, h: Int, dpi: Int) {
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(imageLock) {
                    latestImage?.close()
                    latestImage = img
                }
            }
        }, captureHandler)

        val old = imageReader
        imageReader = reader
        capW = w
        capH = h

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "lecteur-ecran", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = reader.surface
        }
        old?.close()
    }

    /** Copie la dernière image reçue dans un Bitmap de la taille de l'écran. */
    private fun grabBitmap(): Bitmap? {
        synchronized(imageLock) {
            val img = latestImage ?: return null
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedW = rowStride / pixelStride
            val buf = plane.buffer
            buf.rewind()
            val raw = Bitmap.createBitmap(paddedW, img.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            return if (paddedW == img.width) {
                raw
            } else {
                val cropped = Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
                raw.recycle()
                cropped
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) return
        closePicker()
        main.postDelayed({
            val (w, h, dpi) = screenInfo()
            if (w != capW || h != capH) {
                try {
                    buildCapture(w, h, dpi)
                } catch (e: Exception) {
                    toast("Rotation : capture à relancer")
                }
            }
        }, 250)
    }

    // ---------------------------------------------------------------- bulle flottante

    private fun overlayParams(w: Int, h: Int, flags: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return lp
    }

    private fun showBubble() {
        val size = (56 * dp).toInt()
        val v = TextView(this).apply {
            text = "🔊"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61565C0.toInt())
            }
        }
        val lp = overlayParams(
            size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        lp.x = capW - size - (12 * dp).toInt()
        lp.y = capH / 3

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTap()
                    true
                }
                else -> false
            }
        }
        bubble = v
        wm.addView(v, lp)
    }

    private fun onBubbleTap() {
        if (busy) return
        busy = true
        stopSpeaking()
        closePicker()
        bubble?.visibility = View.INVISIBLE // pour que la bulle n'apparaisse pas dans la capture
        // Laisse le temps à une nouvelle image (sans bulle) d'arriver
        main.postDelayed({
            val bmp = try {
                grabBitmap()
            } catch (e: Exception) {
                null
            }
            bubble?.visibility = View.VISIBLE
            if (bmp == null) {
                toast("Capture indisponible, réessaie")
                busy = false
                return@postDelayed
            }
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    busy = false
                    showPicker(result, bmp)
                }
                .addOnFailureListener {
                    busy = false
                    bmp.recycle()
                    toast("Erreur de lecture du texte")
                }
        }, 300)
    }

    // ---------------------------------------------------------------- calque de sélection

    private fun showPicker(result: Text, bmp: Bitmap) {
        val cut = topCut()
        val blocks = result.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            if (b.text.isBlank()) return@mapNotNull null
            if (r.centerY() < cut) return@mapNotNull null // barre d'état / barre du navigateur
            OcrBlock(
                b.text, r,
                b.lines.mapNotNull { l -> l.boundingBox?.let { OcrLine(l.text, it) } }
            )
        }
        if (blocks.isEmpty()) {
            if (aiConfig() == null) {
                bmp.recycle()
                toast("Aucun texte détecté")
                return
            }
            toast("Touche une bulle ou entoure-la : l'IA la lira")
        }
        pickerBitmap = bmp
        pickerBlocks = blocks

        val view = PickerView(this, bmp, blocks) { hint, rects, region, whole ->
            onPickerRead(hint, rects, region, whole)
        }
        picker = view

        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bar.addView(pill("▶ Tout lire") { readAll() })
        bar.addView(pill("■ Stop") { stopSpeaking() })
        bar.addView(pill("✕ Fermer") { closePicker() })
        root.addView(
            bar,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (56 * dp).toInt()
            }
        )

        pickerRoot = root
        val lp = overlayParams(
            capW, capH,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        lp.x = 0
        lp.y = 0
        wm.addView(root, lp)
        toast("Touche un bloc de texte (ou entoure une zone)")
    }

    private fun pill(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * dp
                setColor(0xEE1565C0.toInt())
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginStart = (5 * dp).toInt()
            lp.marginEnd = (5 * dp).toInt()
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun closePicker() {
        stopSpeaking()
        pickerRoot?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        pickerRoot = null
        picker = null
        pickerBlocks = emptyList()
        pickerBitmap?.recycle()
        pickerBitmap = null
    }

    // ---------------------------------------------------------------- voix

    private fun initTts() {
        val listener = TextToSpeech.OnInitListener { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(progressListener)
            } else {
                toast("Synthèse vocale indisponible")
            }
        }
        val engine = prefs.getString("engine", null)
        tts = if (engine == null) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
    }

    private val progressListener: UtteranceProgressListener =
        object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    val rects = id?.let { utterRects[it] } ?: emptyList()
                    main.post { picker?.setHighlight(rects) }
                }

                override fun onDone(id: String?) {
                    main.post {
                        if (id == lastUtteranceId) {
                            speaking = false
                            picker?.setHighlight(emptyList())
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    onDone(id)
                }
        }

    private fun applyTtsPrefs(t: TextToSpeech) {
        val loc = Locale.forLanguageTag(prefs.getString("lang", "fr-FR") ?: "fr-FR")
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Voix ${loc.displayLanguage} non installée (Paramètres > Synthèse vocale)")
        }
        val voiceName = prefs.getString("voice", null)
        if (voiceName != null) {
            val v = try {
                t.voices?.firstOrNull { it.name == voiceName && it.locale.language == loc.language }
            } catch (e: Exception) {
                null
            }
            if (v != null) t.setVoice(v)
        }
        t.setSpeechRate(prefs.getFloat("rate", 1f))
    }

    /** Hauteur (px) en haut de l'écran à ignorer : barre d'état + réglage « ignorer le haut ». */
    private fun topCut(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (id > 0) resources.getDimensionPixelSize(id) else (28 * dp).toInt()
        return status + (prefs.getInt("ignore_top_dp", 0) * dp).toInt()
    }

    private fun aiConfig(): AiOcr.Cfg? {
        val prov = prefs.getString("ai_provider", "gemini") ?: "gemini"
        val key = prefs.getString("key_$prov", "")?.trim().orEmpty()
        if (key.isEmpty()) return null
        val model = prefs.getString("model_$prov", "")?.trim().orEmpty().ifEmpty { AiOcr.defaultModel(prov) }
        return AiOcr.Cfg(prov, key, model)
    }

    private fun onPickerRead(hint: String, rects: List<Rect>, region: Rect, whole: Boolean) {
        if (aiBusy) {
            toast("Lecture IA en cours…")
            return
        }
        val id = region.toShortString()
        if (speaking && id == currentText) { // re-toucher la même zone = stop
            stopSpeaking()
            return
        }
        currentText = id
        if (aiConfig() == null) {
            if (hint.isBlank()) toast("Aucun texte ici. Entoure la bulle avec le doigt.")
            else speakItems(listOf(hint to rects))
        } else {
            transcribeThenSpeak(listOf(AiJob(hint, rects, region, whole)))
        }
    }

    private fun readAll() {
        currentText = null
        if (aiBusy) {
            toast("Lecture IA en cours…")
            return
        }
        if (aiConfig() == null) {
            speakItems(pickerBlocks.map { it.text to listOf(it.rect) })
        } else {
            // Une seule requête pour toute la page (économise le quota gratuit)
            val bmp = pickerBitmap ?: return
            transcribeThenSpeak(
                listOf(
                    AiJob(
                        pickerBlocks.joinToString("\n") { it.text },
                        pickerBlocks.map { it.rect },
                        Rect(0, topCut().coerceAtMost(bmp.height - 1), bmp.width, bmp.height),
                        true
                    )
                )
            )
        }
    }

    /** Découpe la zone dans l'image figée (sur le thread principal). */
    private fun cropFor(job: AiJob): Bitmap? {
        val bmp = pickerBitmap ?: return null
        val r = Rect(job.region)
        if (!job.whole) {
            val dx = (r.width() * 0.35f).toInt() + (12 * dp).toInt()
            val dy = (r.height() * 0.35f).toInt() + (12 * dp).toInt()
            r.inset(-dx, -dy)
        }
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return null
        if (r.width() < 8 || r.height() < 8) return null
        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        // createBitmap peut renvoyer l'image d'origine si la zone la couvre en entier : on la copie
        return if (crop === bmp) bmp.copy(Bitmap.Config.ARGB_8888, false) else crop
    }

    private fun langName(): String {
        val tag = prefs.getString("lang", "fr-FR") ?: "fr-FR"
        return Locale.forLanguageTag(tag).getDisplayLanguage(Locale.FRENCH)
    }

    private fun transcribeThenSpeak(jobs: List<AiJob>) {
        val cfg = aiConfig() ?: return
        val lang = langName()
        val crops = jobs.map { cropFor(it) }
        stopSpeaking()
        picker?.setHighlight(jobs.flatMap { it.rects })
        toast("Lecture par l'IA…")
        aiBusy = true
        Thread {
            val results = arrayOfNulls<String>(jobs.size)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val latch = CountDownLatch(jobs.size)
            jobs.forEachIndexed { i, job ->
                aiPool.execute {
                    val crop = crops[i]
                    try {
                        if (crop != null) {
                            results[i] = AiOcr.transcribe(cfg, crop, job.hint, job.whole, lang)
                        }
                    } catch (e: Exception) {
                        errors.add(e.message ?: "erreur réseau")
                    } finally {
                        crop?.recycle()
                        latch.countDown()
                    }
                }
            }
            latch.await()
            main.post {
                aiBusy = false
                if (errors.isNotEmpty()) toast("IA : ${errors.first()} (lecture locale à la place)")
                val items = jobs.mapIndexed { i, job ->
                    val ai = results[i]?.takeIf { it.isNotBlank() && !it.contains("[vide]") }
                    (ai ?: job.hint) to job.rects
                }.filter { it.first.isNotBlank() }
                if (items.isEmpty()) toast("Aucun texte trouvé") else speakItems(items)
            }
        }.start()
    }

    private fun speakItems(items: List<Pair<String, List<Rect>>>) {
        val t = tts
        if (!ttsReady || t == null) {
            toast("La voix n'est pas encore prête")
            return
        }
        applyTtsPrefs(t)
        t.stop()
        utterRects.clear()
        var n = 0
        for ((text, rects) in items) {
            for (piece in chunk(clean(text))) {
                if (piece.isBlank()) continue
                val id = "u${n++}"
                utterRects[id] = rects
                lastUtteranceId = id
                t.speak(piece, if (n == 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
        speaking = n > 0
    }

    private fun stopSpeaking() {
        tts?.stop()
        speaking = false
        lastUtteranceId = null
        picker?.setHighlight(emptyList())
    }

    /** Prépare le texte OCR pour la voix : recolle les lignes, enlève les parasites, dé-épelle les MAJUSCULES. */
    private fun clean(s: String): String {
        var t = s.replace(Regex("(?<=\\p{L})-\\n(?=\\p{Ll})"), "")
            .replace(Regex("[|_~^<>{}\\[\\]#*\\\\]"), " ")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        // Les mots TOUT EN MAJUSCULES (typiques des BD) sont épelés lettre par lettre par certaines voix
        t = Regex("\\p{Lu}{2,}").replace(t) { it.value.lowercase() }
        return t
    }

    private fun chunk(s: String): List<String> {
        val max = 3000
        if (s.length <= max) return listOf(s)
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(". ", max)
            if (cut < 0) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    // ---------------------------------------------------------------- divers

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        closePicker()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
        tts?.shutdown()
        tts = null
        virtualDisplay?.release()
        virtualDisplay = null
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        imageReader?.close()
        imageReader = null
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (_: Exception) {
        }
        recognizer.close()
        aiPool.shutdownNow()
        captureThread.quitSafely()
        super.onDestroy()
    }
}
