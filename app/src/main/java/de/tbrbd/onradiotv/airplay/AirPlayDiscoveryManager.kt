package de.tbrbd.onradiotv.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AirPlayDiscovery"
private const val SERVICE_TYPE = "_raop._tcp"

data class AirPlayDevice(val routeId: String, val name: String, val host: String, val port: Int)

/** Discovers classic AirPlay (RAOP) receivers via plain Android NsdManager
 * (mDNS/DNS-SD) - the same approach as CastDiscoveryManager, just a
 * different service type. RAOP service names are formatted
 * "<hex-id>@<Device Name>._raop._tcp.local." - the friendly name is the
 * part after '@', not in a TXT record like Cast's "fn" key. */
class AirPlayDiscoveryManager(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val found = LinkedHashMap<String, AirPlayDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices.asStateFlow()

    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed: $errorCode")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(this@AirPlayDiscoveryManager) {
                    found.remove(serviceInfo.serviceName)
                    _devices.value = found.values.toList()
                }
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (exc: Exception) {
                Log.w(TAG, "stopServiceDiscovery failed: $exc")
            }
        }
        discoveryListener = null
    }

    @Synchronized
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.addLast(serviceInfo)
        processResolveQueue()
    }

    @Synchronized
    private fun processResolveQueue() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsdManager.resolveService(
            next,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@AirPlayDiscoveryManager) { resolving = false }
                    processResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        val name = friendlyName(serviceInfo.serviceName)
                        synchronized(this@AirPlayDiscoveryManager) {
                            found[serviceInfo.serviceName] = AirPlayDevice(
                                routeId = serviceInfo.serviceName,
                                name = name,
                                host = host,
                                port = serviceInfo.port,
                            )
                            _devices.value = found.values.toList()
                        }
                    }
                    synchronized(this@AirPlayDiscoveryManager) { resolving = false }
                    processResolveQueue()
                }
            },
        )
    }

    private fun friendlyName(serviceName: String): String {
        val atIndex = serviceName.indexOf('@')
        return if (atIndex in 0 until serviceName.lastIndex) serviceName.substring(atIndex + 1) else serviceName
    }
}
