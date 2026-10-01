package com.example.hcbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.telephony.CellIdentityLte
import android.telephony.CellInfoLte
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor

class RadioWatchdogService : Service() {
    companion object {
        const val CHANNEL_ID = "radio_watchdog"
        const val NOTIFICATION_ID = 2208
        const val ACTION_STOP = "com.example.hcbridge.STOP_RADIO_WATCHDOG"
        private const val LOG_NAME = "radio_watchdog.csv"

        fun logFile(context: Context): File = File(context.filesDir, LOG_NAME)
    }

    private lateinit var telephony: TelephonyManager
    private lateinit var connectivity: ConnectivityManager
    private val executor: Executor by lazy { mainExecutor }

    private var registered = false
    private var rat = "UNKNOWN"
    private var rsrp: Int? = null
    private var rsrq: Int? = null
    private var rssi: Int? = null
    private var sinr: Int? = null
    private var pci: Int? = null
    private var earfcn: Int? = null
    private var ci: Long? = null
    private var tac: Int? = null
    private var cellularAvailable = false
    private var cellularValidated = false
    private var lossStartedAt: Long? = null

    private val telephonyCallback = object : TelephonyCallback(),
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.CellInfoListener,
        TelephonyCallback.DataConnectionStateListener {

        override fun onServiceStateChanged(state: ServiceState) {
            registered = state.state == ServiceState.STATE_IN_SERVICE
            rat = networkTypeName(telephony.dataNetworkType)
            handleState("service:${serviceStateName(state.state)}")
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            val lte = signalStrength.cellSignalStrengths
                .filterIsInstance<android.telephony.CellSignalStrengthLte>()
                .firstOrNull()
            if (lte != null) {
                rsrp = lte.rsrp
                rsrq = lte.rsrq
                rssi = lte.rssi
                sinr = lte.rssnr
            }
            handleState("signal")
        }

        override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) {
            val lte = cellInfo.filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered }
            if (lte != null) {
                val id: CellIdentityLte = lte.cellIdentity
                pci = id.pci
                earfcn = id.earfcn
                ci = id.ci.toLong()
                tac = id.tac
            }
            handleState("cell")
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            rat = networkTypeName(networkType)
            handleState("data:$state")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetwork(network, "net_available")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellularAvailable = true
                cellularValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                handleState("net_caps")
            }
        }

        override fun onLost(network: Network) {
            cellularAvailable = connectivity.allNetworks.any { n ->
                connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
            if (!cellularAvailable) cellularValidated = false
            handleState("net_lost")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting LTE watchdog…"))
        telephony = getSystemService(TelephonyManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)
        ensureHeader()
        registerCallbacks()
        log("watchdog_start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { telephony.unregisterTelephonyCallback(telephonyCallback) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        log("watchdog_stop")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerCallbacks() {
        runCatching { telephony.registerTelephonyCallback(executor, telephonyCallback) }
            .onFailure { log("telephony_callback_error:${it.javaClass.simpleName}") }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { log("network_callback_error:${it.javaClass.simpleName}") }
    }

    private fun updateNetwork(network: Network, reason: String) {
        val caps = connectivity.getNetworkCapabilities(network) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            cellularAvailable = true
            cellularValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            handleState(reason)
        }
    }

    private fun handleState(reason: String) {
        val now = System.currentTimeMillis()
        val radioLost = !registered || !cellularAvailable
        if (radioLost) {
            if (lossStartedAt == null) {
                lossStartedAt = now
                log("LOSS_START:$reason")
            } else {
                log(reason)
            }
        } else {
            val start = lossStartedAt
            if (start != null) {
                val seconds = (now - start) / 1000
                log("RECOVERED:${seconds}s:$reason")
                lossStartedAt = null
            } else {
                log(reason)
            }
        }
        val lossText = lossStartedAt?.let { " | lost ${(now - it) / 1000}s" } ?: ""
        val text = "$rat reg=$registered cell=$cellularAvailable validated=$cellularValidated$lossText"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    @Synchronized
    private fun log(event: String) {
        val file = logFile(this)
        val row = listOf(
            Instant.now().toString(), csv(event), rat, registered, cellularAvailable, cellularValidated,
            pci ?: "", earfcn ?: "", ci ?: "", tac ?: "",
            rsrp ?: "", rsrq ?: "", rssi ?: "", sinr ?: ""
        ).joinToString(",")
        runCatching { file.appendText(row + "\n") }
    }

    private fun ensureHeader() {
        val file = logFile(this)
        if (!file.exists() || file.length() == 0L) {
            file.writeText("time,event,rat,registered,cellularAvailable,validated,pci,earfcn,ci,tac,rsrp,rsrq,rssi,sinr\n")
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("S22 LTE Watchdog")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "S22 LTE Watchdog", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""

    private fun serviceStateName(state: Int) = when (state) {
        ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
        ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
        ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
        ServiceState.STATE_POWER_OFF -> "POWER_OFF"
        else -> state.toString()
    }

    private fun networkTypeName(type: Int) = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> type.toString()
    }
}
