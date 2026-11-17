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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQ_OVERLAY = 1
        const val REQ_PROJECTION = 2
        const val REQ_NOTIF = 3
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

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) refreshVoices()
        }
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
