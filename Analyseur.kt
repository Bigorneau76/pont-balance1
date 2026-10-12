package fr.sanimarc.pontbalance

/**
 * Transforme la trame texte envoyée par la balance (touche Print)
 * en poids + unité. Une pression sur Print peut produire plusieurs lignes
 * (poids, poids unitaire, nombre de pièces) : on les regroupe.
 */
object Analyseur {

    data class Pesee(
        val poids: Double?,       // valeur avec unité de masse (g, kg, lb, oz)
        val unite: String?,
        val quantite: Double?,    // nombre de pièces si la balance l'envoie
        val trame: String         // texte brut complet, pour la traçabilité
    )

    private val unitesMasse = setOf("g", "kg", "mg", "lb", "oz", "t")
    private val unitesPieces = setOf("pcs", "pc", "pce", "pces", "st")

    // signe éventuel (collé ou séparé), nombre (virgule ou point), unité éventuelle
    private val motif = Regex("""([+-])?\s*(\d+(?:[.,]\d+)?)\s*([A-Za-z]{1,4})?""")

    fun analyser(lignes: List<String>): Pesee? {
        var poids: Double? = null
        var unite: String? = null
        var quantite: Double? = null

        for (ligne in lignes) {
            for (m in motif.findAll(ligne)) {
                val signe = m.groupValues[1]
                val nombre = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
                val u = m.groupValues[3].lowercase()
                val valeur = if (signe == "-") -nombre else nombre
                when {
                    u in unitesMasse && poids == null -> { poids = valeur; unite = u }
                    u in unitesPieces && quantite == null -> quantite = valeur
                }
            }
        }
        if (poids == null && quantite == null) return null
        return Pesee(poids, unite, quantite, lignes.joinToString(" | ") { it.trim() })
    }
}
