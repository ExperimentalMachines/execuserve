package org.experimentalmachines.execuserve.app.serve

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Announces the server on the local network as `_execuserve._tcp`, so a client can find the
 * phone without typing an address. Only in network mode: advertising a loopback server
 * would tell the network about something it cannot reach.
 */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start(port: Int, version: String) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "ExecuServe on ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("path", "/v1")
            setAttribute("auth", "bearer")
            setAttribute("version", version)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onSuccess { listener = registration }
    }

    fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val SERVICE_TYPE = "_execuserve._tcp"
    }
}
