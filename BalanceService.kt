package fr.sanimarc.pontbalance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * Service qui tourne en arrière-plan (même quand le MES Power Apps est au premier plan) :
 * lit la balance en USB et envoie chaque pesée au flow.
 */
class BalanceService : Service(), SerialInputOutputManager.Listener {

    private var port: UsbSerialPort? = null
    private var lecteur: SerialInputOutputManager? = null
    private lateinit var reglages: Reglages
    private lateinit var file: FileEnvoi

    private val fil = HandlerThread("balance").apply { start() }
    private val gestion = Handler(fil.looper)
    private val tampon = StringBuilder()
    private val lignesRecues = mutableListOf<String>()

    // Une pression sur Print envoie plusieurs lignes d'affilée :
    // on attend un court silence avant de considérer la pesée complète.
    private val delaiRegroupementMs = 400L
    private val finDePesee = Runnable { traiterPesee() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reglages = Reglages(this)
        file = FileEnvoi(this, reglages)
        demarrerPremierPlan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEMANDER -> demanderPoids()
            else -> gestion.post { connecter() }
        }
        return START_STICKY
    }

    private fun demarrerPremierPlan() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("balance", "Pont balance", NotificationManager.IMPORTANCE_LOW))
        }
        val ouvrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, "balance")
            .setContentTitle("Pont balance actif")
            .setContentText("Les pesées sont envoyées au MES")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    private fun trouverPilote(manager: UsbManager): UsbSerialDriver? {
        // 1) pilotes connus (FTDI, CP210x, CH34x, Prolific, CDC standard…)
        UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()?.let { return it }
        // 2) sinon on tente le mode "port COM virtuel" standard (CDC) sur le 1er appareil USB
        val appareil: UsbDevice = manager.deviceList.values.firstOrNull() ?: return null
        return try { CdcAcmSerialDriver(appareil).takeIf { it.ports.isNotEmpty() } } catch (e: Exception) { null }
    }

    private fun connecter() {
        fermer()
        val manager = getSystemService(USB_SERVICE) as UsbManager
        Etat.journaliser("Appareils USB vus : " + manager.deviceList.values.joinToString {
            "%04X:%04X %s".format(it.vendorId, it.productId, it.productName ?: "")
        }.ifEmpty { "aucun" })

        val pilote = trouverPilote(manager)
        if (pilote == null) {
            majStatut("Aucune balance USB détectée — vérifier le câble OTG")
            return
        }
        if (!manager.hasPermission(pilote.device)) {
            majStatut("Autorisation USB nécessaire — appuyer sur Connecter")
            val intent = Intent(MainActivity.ACTION_PERMISSION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(pilote.device, PendingIntent.getBroadcast(this, 0, intent, flags))
            return
        }
        val connexion = manager.openDevice(pilote.device)
        if (connexion == null) { majStatut("Impossible d'ouvrir la balance"); return }

        try {
            val p = pilote.ports[0]
            p.open(connexion)
            val (bits, parite) = when (reglages.format) {
                "7O1" -> 7 to UsbSerialPort.PARITY_ODD
                "7E1" -> 7 to UsbSerialPort.PARITY_EVEN
                else -> 8 to UsbSerialPort.PARITY_NONE
            }
            try { p.setParameters(reglages.vitesse, bits, UsbSerialPort.STOPBITS_1, parite) }
            catch (e: UnsupportedOperationException) { /* port virtuel : réglage ignoré, normal */ }
            try { p.dtr = true; p.rts = true } catch (e: Exception) { }
            port = p
            lecteur = SerialInputOutputManager(p, this).also { it.start() }
            majStatut("✔ ${reglages.nomBalance} connectée (${pilote.javaClass.simpleName.removeSuffix("SerialDriver")})")
        } catch (e: Exception) {
            majStatut("Erreur de connexion : ${e.message}")
            fermer()
        }
    }

    /** Commande SBI "ESC P" : demande d'impression du poids (balances Minebea/Sartorius). */
    private fun demanderPoids() {
        gestion.post {
            val p = port
            if (p == null) { majStatut("Balance non connectée"); return@post }
            try {
                p.write(byteArrayOf(0x1B, 'P'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte()), 1000)
                Etat.journaliser("→ demande de poids envoyée (ESC P)")
            } catch (e: Exception) { Etat.journaliser("Demande impossible : ${e.message}") }
        }
    }

    override fun onNewData(data: ByteArray) {
        val texte = String(data, Charsets.ISO_8859_1)
        gestion.post {
            tampon.append(texte)
            while (true) {
                val i = tampon.indexOfFirst { it == '\n' || it == '\r' }
                if (i < 0) break
                val ligne = tampon.substring(0, i)
                tampon.delete(0, i + 1)
                if (ligne.isNotBlank()) {
                    lignesRecues.add(ligne)
                    Etat.journaliser("← « ${ligne.replace("\u001B", "<ESC>")} »")
                }
            }
            gestion.removeCallbacks(finDePesee)
            gestion.postDelayed(finDePesee, delaiRegroupementMs)
        }
    }

    private fun traiterPesee() {
        if (tampon.isNotBlank()) { lignesRecues.add(tampon.toString()); tampon.clear() }
        if (lignesRecues.isEmpty()) return
        val pesee = Analyseur.analyser(lignesRecues.toList())
        lignesRecues.clear()
        if (pesee == null) {
            Etat.journaliser("Trame sans poids reconnu — non envoyée")
            return
        }
        Etat.dernierPoids = when {
            pesee.poids != null -> "${formater(pesee.poids)} ${pesee.unite}"
            else -> "${formater(pesee.quantite!!)} pcs"
        }
        Etat.dernierEnvoi = "Envoi en cours…"
        Etat.notifier()
        file.ajouter(pesee)
    }

    private fun formater(v: Double) =
        if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

    override fun onRunError(e: Exception) {
        majStatut("Balance déconnectée (${e.message ?: "câble retiré ?"})")
        gestion.post { fermer() }
    }

    private fun majStatut(s: String) { Etat.statut = s; Etat.journaliser(s) }

    private fun fermer() {
        try { lecteur?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        lecteur = null; port = null
    }

    override fun onDestroy() {
        fermer(); file.arreter(); fil.quitSafely()
        majStatut("Arrêté")
        super.onDestroy()
    }

    companion object {
        const val ACTION_DEMANDER = "fr.sanimarc.pontbalance.DEMANDER"
    }
}
