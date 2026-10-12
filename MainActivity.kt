package fr.sanimarc.pontbalance

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var reglages: Reglages
    private lateinit var txtStatut: TextView
    private lateinit var txtPoids: TextView
    private lateinit var txtEnvoi: TextView
    private lateinit var txtJournal: TextView

    private val rafraichir: () -> Unit = {
        txtStatut.text = Etat.statut
        txtPoids.text = Etat.dernierPoids
        txtEnvoi.text = Etat.dernierEnvoi
        txtJournal.text = Etat.texteJournal()
    }

    // Réponse à la demande d'autorisation USB
    private val recepteurPermission = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { lancerService(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        reglages = Reglages(this)

        txtStatut = findViewById(R.id.txtStatut)
        txtPoids = findViewById(R.id.txtPoids)
        txtEnvoi = findViewById(R.id.txtEnvoi)
        txtJournal = findViewById(R.id.txtJournal)
        val edtNom = findViewById<EditText>(R.id.edtNomBalance)
        val edtUrl = findViewById<EditText>(R.id.edtUrl)
        val spnBaud = findViewById<Spinner>(R.id.spnBaud)
        val spnFormat = findViewById<Spinner>(R.id.spnFormat)

        edtNom.setText(reglages.nomBalance)
        edtUrl.setText(reglages.urlFlow)
        spnBaud.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Reglages.VITESSES)
        spnBaud.setSelection(Reglages.VITESSES.indexOf(reglages.vitesse).coerceAtLeast(0))
        spnFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Reglages.FORMATS)
        spnFormat.setSelection(Reglages.FORMATS.indexOf(reglages.format).coerceAtLeast(0))

        findViewById<Button>(R.id.btnConnecter).setOnClickListener {
            reglages.nomBalance = edtNom.text.toString()
            reglages.urlFlow = edtUrl.text.toString()
            reglages.vitesse = spnBaud.selectedItem as Int
            reglages.format = spnFormat.selectedItem as String
            if (!reglages.estComplet) {
                Toast.makeText(this, "Renseigner le nom de la balance et l'URL du flow (https://…)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            lancerService(null)
        }
        findViewById<Button>(R.id.btnDemander).setOnClickListener { lancerService(BalanceService.ACTION_DEMANDER) }
        findViewById<Button>(R.id.btnArreter).setOnClickListener {
            stopService(Intent(this, BalanceService::class.java))
        }

        ContextCompat.registerReceiver(
            this, recepteurPermission, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        // Balance branchée alors que tout est réglé : on démarre tout seul
        if (reglages.estComplet) lancerService(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (reglages.estComplet) lancerService(null)
    }

    private fun lancerService(action: String?) {
        val i = Intent(this, BalanceService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, i)
    }

    override fun onStart() { super.onStart(); Etat.ecouter(rafraichir); rafraichir() }
    override fun onStop() { Etat.oublier(rafraichir); super.onStop() }
    override fun onDestroy() { unregisterReceiver(recepteurPermission); super.onDestroy() }

    companion object {
        const val ACTION_PERMISSION = "fr.sanimarc.pontbalance.PERMISSION_USB"
    }
}
