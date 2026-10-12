package fr.sanimarc.pontbalance

import android.content.Context

/** Réglages saisis par l'utilisateur, conservés sur la tablette. */
class Reglages(context: Context) {
    private val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    var nomBalance: String
        get() = prefs.getString("nomBalance", "") ?: ""
        set(v) = prefs.edit().putString("nomBalance", v.trim()).apply()

    var urlFlow: String
        get() = prefs.getString("urlFlow", "") ?: ""
        set(v) = prefs.edit().putString("urlFlow", v.trim()).apply()

    /** Vitesse choisie dans la liste (doit correspondre au menu de la balance). */
    var vitesse: Int
        get() = prefs.getInt("vitesse", VITESSES[0])
        set(v) = prefs.edit().putInt("vitesse", v).apply()

    /** "8N1", "7O1" ou "7E1". */
    var format: String
        get() = prefs.getString("format", FORMATS[0]) ?: FORMATS[0]
        set(v) = prefs.edit().putString("format", v).apply()

    val estComplet: Boolean get() = nomBalance.isNotBlank() && urlFlow.startsWith("https://")

    companion object {
        /** Vitesses standard d'une liaison série. */
        val VITESSES = listOf(9600, 1200, 2400, 4800, 19200, 38400, 57600, 115200)
        val FORMATS = listOf("8N1", "7O1", "7E1")
    }
}
