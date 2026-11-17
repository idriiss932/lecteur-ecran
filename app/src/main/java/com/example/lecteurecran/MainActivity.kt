package com.example.lecteurecran

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQ_OVERLAY = 1
        const val REQ_PROJECTION = 2
        const val REQ_NOTIF = 3
        const val REPO = "idriiss932/lecteur-ecran"
    }

    private val languages = listOf(
        "Français" to "fr-FR",
        "English" to "en-US",
        "Español" to "es-ES",
        "Deutsch" to "de-DE",
        "Italiano" to "it-IT",
        "Português" to "pt-PT"
    )

    private val samples = mapOf(
        "fr" to "Bonjour, voici un exemple de lecture à voix haute.",
        "en" to "Hello, this is a sample of the reading voice.",
        "es" to "Hola, este es un ejemplo de lectura en voz alta.",
        "de" to "Hallo, das ist ein Beispiel für die Vorlesestimme.",
        "it" to "Ciao, questo è un esempio di lettura ad alta voce.",
        "pt" to "Olá, este é um exemplo de leitura em voz alta."
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var voiceSpinner: Spinner
    private var notifAsked = false
    private var tts: TextToSpeech? = null
    private var voiceList: List<Voice> = emptyList()
    private lateinit var engineSpinner: Spinner
    private var engineList: List<TextToSpeech.EngineInfo> = emptyList()
    private lateinit var updateBtn: Button
    private var pendingUrl: String? = null
    private var downloading = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun langTag() = prefs.getString("lang", "fr-FR") ?: "fr-FR"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "Lecteur d'écran"
            textSize = 26f
        })
        col.addView(TextView(this).apply {
            text = "1. Appuie sur « Activer » (autorise l'affichage par-dessus les autres applis et la capture d'écran).\n" +
                "2. Ouvre ton comics. Une bulle 🔊 flotte à l'écran (tu peux la déplacer).\n" +
                "3. Touche la bulle : l'écran se fige et le texte détecté s'entoure en bleu.\n" +
                "4. Touche un bloc de texte pour l'entendre. Tu peux aussi glisser le doigt pour entourer une zone.\n" +
                "5. « Tout lire » lit tous les blocs, « Fermer » revient à la lecture normale."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(20))
        })

        // ---- langue
        col.addView(TextView(this).apply { text = "Langue de lecture"; textSize = 14f })
        val langSpinner = Spinner(this)
        langSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first }
        )
        langSpinner.setSelection(languages.indexOfFirst { it.second == langTag() }.coerceAtLeast(0))
        langSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val tag = languages[pos].second
                if (tag != langTag()) {
                    prefs.edit().putString("lang", tag).apply()
                    refreshVoices()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(langSpinner)

        // ---- moteur vocal
        col.addView(TextView(this).apply {
            text = "Moteur vocal (conseillé : Synthèse vocale Google)"; textSize = 14f
            setPadding(0, dp(16), 0, 0)
        })
        engineSpinner = Spinner(this)
        engineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val wanted = if (pos == 0) null else engineList.getOrNull(pos - 1)?.name
                if (wanted != prefs.getString("engine", null)) {
                    val e = prefs.edit()
                    if (wanted == null) e.remove("engine") else e.putString("engine", wanted)
                    e.remove("voice").apply()
                    createTts()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(engineSpinner)

        // ---- voix
        col.addView(TextView(this).apply {
            text = "Voix (★ = haute qualité)"; textSize = 14f
            setPadding(0, dp(16), 0, 0)
        })
        voiceSpinner = Spinner(this)
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                voiceList.getOrNull(pos)?.let { prefs.edit().putString("voice", it.name).apply() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(voiceSpinner)

        col.addView(Button(this).apply {
            text = "▶ Écouter un exemple"
            setOnClickListener { speakSample() }
        })
        col.addView(Button(this).apply {
            text = "Installer / améliorer les voix"
            setOnClickListener { openTtsSettings() }
        })

        // ---- lecture par IA
        col.addView(TextView(this).apply {
            text = "Lecture du texte par IA (recommandé pour les BD)"
            textSize = 16f
            setPadding(0, dp(24), 0, 0)
        })
        val providers = listOf(
            "Groq (Llama) — gratuit" to "groq",
            "Gemini (Google) — gratuit" to "gemini",
            "Claude (Anthropic) — payant" to "claude"
        )
        var curProv = prefs.getString("ai_provider", "gemini") ?: "gemini"
        val helpView = TextView(this).apply { textSize = 12f }
        val keyEdit = EditText(this).apply {
            hint = "Clé API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        fun loadProvider() {
            helpView.text = if (curProv == "groq") {
                "Clé gratuite, sans carte bancaire : console.groq.com (connexion par e-mail ou Google) > API Keys > " +
                    "Create API Key. Quota gratuit d'environ 30 requêtes par minute."
            } else if (curProv == "gemini") {
                "Clé gratuite, sans carte bancaire : aistudio.google.com > Get API key. " +
                    "Quota gratuit limité (quelques requêtes par minute). Google peut utiliser les images envoyées."
            } else {
                "Clé payante : console.anthropic.com > API keys (ajouter du crédit)."
            }
            keyEdit.setText(prefs.getString("key_$curProv", ""))
            modelEdit.setText(
                prefs.getString("model_$curProv", "")?.takeIf { it.isNotBlank() } ?: AiOcr.defaultModel(curProv)
            )
        }
        val provSpinner = Spinner(this)
        provSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.first }
        )
        provSpinner.setSelection(providers.indexOfFirst { it.second == curProv }.coerceAtLeast(0))
        provSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                curProv = providers[pos].second
                prefs.edit().putString("ai_provider", curProv).apply()
                loadProvider()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(provSpinner)
        col.addView(helpView)
        col.addView(keyEdit)
        col.addView(TextView(this).apply { text = "Modèle (laisse tel quel sauf problème)"; textSize = 12f })
        col.addView(modelEdit)
        keyEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                prefs.edit().putString("key_$curProv", e?.toString()?.trim() ?: "").apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        modelEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                prefs.edit().putString("model_$curProv", e?.toString()?.trim() ?: "").apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        loadProvider()
        val testView = TextView(this).apply { textSize = 13f }
        col.addView(Button(this).apply {
            text = "Tester la clé"
            setOnClickListener {
                val key = keyEdit.text.toString().trim()
                val model = modelEdit.text.toString().trim().ifEmpty { AiOcr.defaultModel(curProv) }
                if (key.isEmpty()) {
                    toast("Colle d'abord ta clé")
                } else {
                    toast("Test en cours…")
                    testView.text = "Test en cours… (peut prendre jusqu'à une minute, reste dans l'appli)"
                    val cfg = AiOcr.Cfg(curProv, key, model)
                    Thread {
                        val msg = try {
                            var fixed: String? = null
                            val r = AiOcr.ping(cfg) { m -> fixed = m }
                            fixed?.let { m -> runOnUiThread { modelEdit.setText(m) } }
                            "Clé valable ✔ (modèle ${fixed ?: model}) : l'IA répond « $r »"
                        } catch (e: Exception) {
                            "Échec : ${e.message}"
                        }
                        runOnUiThread {
                            toast(msg)
                            testView.text = msg
                        }
                    }.start()
                }
            }
        })
        col.addView(testView)

        // ---- vitesse
        col.addView(TextView(this).apply {
            text = "Vitesse de la voix"; textSize = 14f
            setPadding(0, dp(16), 0, 0)
        })
        col.addView(SeekBar(this).apply {
            max = 150 // 0.5x à 2.0x
            progress = ((prefs.getFloat("rate", 1f) - 0.5f) * 100).toInt().coerceIn(0, 150)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putFloat("rate", 0.5f + p / 100f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(TextView(this).apply {
            text = "Ignorer le haut de l'écran (barre d'adresse du navigateur) : ${prefs.getInt("ignore_top_dp", 0)} dp"
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
            tag = "ignoreLabel"
        })
        col.addView(SeekBar(this).apply {
            max = 200
            progress = prefs.getInt("ignore_top_dp", 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putInt("ignore_top_dp", p).apply()
                    col.findViewWithTag<TextView>("ignoreLabel")?.text =
                        "Ignorer le haut de l'écran (barre d'adresse du navigateur) : $p dp"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        updateBtn = Button(this).apply {
            text = "Rechercher une mise à jour"
            setOnClickListener { onUpdateClick() }
        }
        col.addView(updateBtn)
        col.addView(TextView(this).apply {
            text = "Version installée : v${localVersion()}"
            textSize = 12f
        })

        col.addView(Button(this).apply {
            text = "Activer"
            setOnClickListener { begin() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })

        col.addView(Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Lecteur arrêté", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })

        createTts()
        checkUpdate(false)
    }

    override fun onResume() {
        super.onResume()
        // au retour des réglages de synthèse vocale : nouvelles voix éventuelles
        refreshVoices()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- voix

    private fun createTts() {
        tts?.shutdown()
        tts = null
        val engine = prefs.getString("engine", null)
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                refreshEngines()
                refreshVoices()
            } else if (engine != null) {
                prefs.edit().remove("engine").remove("voice").apply()
                toast("Ce moteur vocal ne répond pas, retour au moteur par défaut")
                createTts()
            }
        }
        tts = if (engine == null) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
    }

    private fun refreshEngines() {
        val t = tts ?: return
        engineList = try { t.engines } catch (e: Exception) { emptyList() }
        val labels = listOf("Moteur par défaut du téléphone") + engineList.map { it.label }
        engineSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val cur = prefs.getString("engine", null)
        val idx = engineList.indexOfFirst { it.name == cur }
        engineSpinner.setSelection(if (idx >= 0) idx + 1 else 0)
    }

    private fun refreshVoices() {
        val t = tts ?: return
        val lang = Locale.forLanguageTag(langTag())
        val all: List<Voice> = try {
            t.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        voiceList = all
            .filter { it.locale.language == lang.language }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == lang.country }
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
            )
        val labels = if (voiceList.isEmpty()) {
            listOf("Voix par défaut (aucune autre voix trouvée)")
        } else {
            voiceList.mapIndexed { i, v ->
                val region = v.locale.displayCountry.ifEmpty { v.locale.displayLanguage }
                "Voix ${i + 1} · $region" +
                    (if (v.quality >= Voice.QUALITY_HIGH) " ★" else "") +
                    (if (v.isNetworkConnectionRequired) " · en ligne" else "")
            }
        }
        voiceSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val saved = prefs.getString("voice", null)
        val idx = voiceList.indexOfFirst { it.name == saved }
        if (idx < 0 && voiceList.isNotEmpty()) {
            prefs.edit().putString("voice", voiceList[0].name).apply()
        }
        voiceSpinner.setSelection(if (idx >= 0) idx else 0)
    }

    private fun speakSample() {
        val t = tts
        if (t == null) {
            Toast.makeText(this, "La voix n'est pas encore prête", Toast.LENGTH_SHORT).show()
            return
        }
        val lang = Locale.forLanguageTag(langTag())
        t.setLanguage(lang)
        voiceList.getOrNull(voiceSpinner.selectedItemPosition)?.let { t.setVoice(it) }
        t.setSpeechRate(prefs.getFloat("rate", 1f))
        t.speak(samples[lang.language] ?: samples.getValue("en"), TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- mise à jour

    @Suppress("DEPRECATION")
    private fun localVersion(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Interroge la dernière « Release » GitHub (le dépôt doit être public). */
    private fun checkUpdate(manual: Boolean) {
        if (manual) updateBtn.text = "Recherche en cours…"
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest")
                    .openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000
                c.readTimeout = 15000
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode} (dépôt public ?)")
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val remote = json.getString("tag_name").trim().removePrefix("v").toLongOrNull() ?: 0L
                val assets = json.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                runOnUiThread {
                    if (url != null && remote > localVersion()) {
                        pendingUrl = url
                        updateBtn.text = "⬇ Installer la mise à jour (v$remote)"
                    } else {
                        pendingUrl = null
                        updateBtn.text = "Rechercher une mise à jour"
                        if (manual) toast("Tu as déjà la dernière version (v${localVersion()})")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateBtn.text = "Rechercher une mise à jour"
                    if (manual) toast("Vérification impossible : ${e.message}")
                }
            }
        }.start()
    }

    private fun onUpdateClick() {
        if (downloading) return
        val url = pendingUrl
        if (url == null) {
            checkUpdate(true)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Autorise l'installation depuis cette appli, puis reviens et réappuie sur le bouton")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        downloading = true
        Thread {
            try {
                val file = File(getExternalFilesDir(null), "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val total = c.contentLength.toLong()
                var done = 0L
                var lastPct = -1
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread { updateBtn.text = "Téléchargement… $pct %" }
                                }
                            }
                        }
                    }
                }
                runOnUiThread {
                    downloading = false
                    updateBtn.text = "Installation…"
                    installApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    updateBtn.text = "⬇ Installer la mise à jour"
                    toast("Téléchargement impossible : ${e.message}")
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ---------------------------------------------------------------- démarrage

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise « Affichage par-dessus les autres applis »", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> if (Settings.canDrawOverlays(this)) begin()
            REQ_PROJECTION -> if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, OverlayService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(svc)
                Toast.makeText(this, "Actif : cherche la bulle 🔊", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Capture d'écran refusée", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) begin()
    }
}
