package de.tbrbd.onradiotv.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CastDiscovery"
private const val SERVICE_TYPE = "_googlecast._tcp"

/** Discovers Cast devices via plain Android NsdManager (mDNS/DNS-SD) - a
 * stock AOSP API available on every device, unlike the official Cast SDK's
 * MediaRouter-based discovery which needs Play Services' Cast framework
 * module (see CastV2Client's doc comment for why that's not usable here). */
class CastDiscoveryManager(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val found = LinkedHashMap<String, CastDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

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
                synchronized(this@CastDiscoveryManager) {
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

    // NsdManager only reliably handles one outstanding resolveService() call
    // at a time - firing several concurrently (easy to trigger when a few
    // Cast devices answer onServiceFound in a burst) silently drops some of
    // them, so queue and resolve strictly one by one instead.
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
                    synchronized(this@CastDiscoveryManager) { resolving = false }
                    processResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        val name = friendlyName(serviceInfo) ?: serviceInfo.serviceName
                        synchronized(this@CastDiscoveryManager) {
                            found[serviceInfo.serviceName] = CastDevice(
                                routeId = serviceInfo.serviceName,
                                name = name,
                                host = host,
                                port = serviceInfo.port,
                            )
                            _devices.value = found.values.toList()
                        }
                    }
                    synchronized(this@CastDiscoveryManager) { resolving = false }
                    processResolveQueue()
                }
            },
        )
    }

    /** The "fn" TXT record is the device's actual display name (e.g. "Living
     * Room Speaker") - the mDNS service name itself is just an opaque id. */
    private fun friendlyName(serviceInfo: NsdServiceInfo): String? =
        try {
            serviceInfo.attributes["fn"]?.let { String(it, Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        }
}
