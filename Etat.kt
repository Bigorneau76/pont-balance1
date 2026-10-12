package fr.sanimarc.pontbalance

import android.os.Handler
import android.os.Looper

/** État partagé entre le service (arrière-plan) et l'écran. */
object Etat {
    @Volatile var statut: String = "Balance non connectée"
    @Volatile var dernierPoids: String = "—"
    @Volatile var dernierEnvoi: String = ""
    private val journal = ArrayDeque<String>()

    private val principal = Handler(Looper.getMainLooper())
    private val ecouteurs = mutableSetOf<() -> Unit>()

    fun ecouter(f: () -> Unit) { ecouteurs.add(f) }
    fun oublier(f: () -> Unit) { ecouteurs.remove(f) }

    @Synchronized fun journaliser(ligne: String) {
        val heure = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())
        journal.addFirst("$heure  $ligne")
        while (journal.size > 60) journal.removeLast()
        notifier()
    }

    @Synchronized fun texteJournal(): String = journal.joinToString("\n")

    fun notifier() { principal.post { ecouteurs.toList().forEach { it() } } }
}
