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

    private lateinit var prefs: SharedPreferences
    private var notifAsked = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
                "3. Touche la bulle : le texte détecté s'entoure en bleu.\n" +
                "4. Touche un bloc de texte pour l'entendre. Tu peux aussi glisser le doigt pour entourer une zone.\n" +
                "5. « Tout lire » lit tous les blocs, « Fermer » revient à la lecture normale."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(20))
        })

        col.addView(TextView(this).apply { text = "Langue de lecture"; textSize = 14f })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first }
        )
        val saved = prefs.getString("lang", "fr-FR")
        spinner.setSelection(languages.indexOfFirst { it.second == saved }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("lang", languages[pos].second).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(spinner)

        col.addView(TextView(this).apply {
            text = "Vitesse de la voix"; textSize = 14f
            setPadding(0, dp(16), 0, 0)
        })
        val seek = SeekBar(this).apply {
            max = 150 // 0.5x à 2.0x
            progress = ((prefs.getFloat("rate", 1f) - 0.5f) * 100).toInt().coerceIn(0, 150)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putFloat("rate", 0.5f + p / 100f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(seek)

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
    }

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
