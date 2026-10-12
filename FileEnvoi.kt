package fr.sanimarc.pontbalance

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Envoie les pesées au flow Power Automate.
 * Si le Wi-Fi coupe, les pesées sont gardées dans un fichier et renvoyées
 * dès que possible : aucune pesée n'est perdue.
 */
class FileEnvoi(context: Context, private val reglages: Reglages) {

    private val fichier = File(context.filesDir, "pesees_en_attente.txt")
    private val executeur = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    init {
        // Nouvelle tentative régulière pour les pesées restées en attente
        executeur.scheduleWithFixedDelay({ viderFile() }, 10, 30, TimeUnit.SECONDS)
    }

    fun ajouter(p: Analyseur.Pesee) {
        val o = JSONObject()
        o.put("balance", reglages.nomBalance)
        o.put("tablette", android.os.Build.MODEL)
        o.put("horodatage", java.time.Instant.now().toString())
        o.put("trame", p.trame)
        o.put("poids", p.poids ?: JSONObject.NULL)
        o.put("unite", p.unite ?: "")
        o.put("quantite", p.quantite ?: JSONObject.NULL)
        executeur.execute {
            synchronized(fichier) { fichier.appendText(o.toString() + "\n") }
            viderFile()
        }
    }

    private fun viderFile() {
        val url = reglages.urlFlow
        if (!url.startsWith("https://")) return
        val lignes = synchronized(fichier) { if (fichier.exists()) fichier.readLines() else emptyList() }
            .filter { it.isNotBlank() }
        if (lignes.isEmpty()) return

        val restantes = mutableListOf<String>()
        var echec = false
        for (l in lignes) {
            if (echec) { restantes.add(l); continue }
            try {
                val req = Request.Builder().url(url).post(l.toRequestBody(json)).build()
                client.newCall(req).execute().use { rep ->
                    if (rep.isSuccessful) {
                        Etat.dernierEnvoi = "Envoyé à " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())
                        Etat.journaliser("ENVOI OK (${rep.code})")
                    } else {
                        echec = true; restantes.add(l)
                        Etat.dernierEnvoi = "Refusé par le flow (code ${rep.code})"
                        Etat.journaliser("ENVOI REFUSÉ ${rep.code} : ${rep.body?.string()?.take(200)}")
                    }
                }
            } catch (e: Exception) {
                echec = true; restantes.add(l)
                Etat.dernierEnvoi = "Pas de réseau — ${restantes.size} pesée(s) en attente"
                Etat.journaliser("ENVOI IMPOSSIBLE : ${e.message}")
            }
        }
        synchronized(fichier) {
            // On réécrit ce qui reste + ce qui a pu arriver pendant l'envoi
            val arriveesPendant = (if (fichier.exists()) fichier.readLines() else emptyList())
                .filter { it.isNotBlank() }.drop(lignes.size)
            fichier.writeText((restantes + arriveesPendant).joinToString("") { it + "\n" })
        }
        Etat.notifier()
    }

    fun arreter() { executeur.shutdown() }
}
