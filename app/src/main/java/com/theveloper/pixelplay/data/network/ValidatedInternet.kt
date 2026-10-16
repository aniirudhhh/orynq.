package com.theveloper.pixelplay.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** App-lifetime default-route observer; no polling, Wi-Fi assumptions or settings changes. */
@Singleton
class ValidatedInternet @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private fun validated(capabilities: NetworkCapabilities?) =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    private fun current() = runCatching { manager?.let { validated(it.getNetworkCapabilities(it.activeNetwork)) } ?: false }.getOrDefault(false)
    private val mutableOnline = MutableStateFlow(current())
    val online = mutableOnline.asStateFlow()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { mutableOnline.value = current() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            mutableOnline.value = if (network == manager?.activeNetwork) validated(capabilities) else current()
        }
        override fun onLost(network: Network) {
            mutableOnline.value = if (network == manager?.activeNetwork) false else current()
        }
        override fun onUnavailable() { mutableOnline.value = false }
    }
    init {
        try { manager?.registerDefaultNetworkCallback(callback) }
        catch (_: SecurityException) { mutableOnline.value = false }
    }
}
