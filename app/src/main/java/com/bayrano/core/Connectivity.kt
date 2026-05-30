package com.bayrano.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Cheap "do we have usable internet right now?" check. Needs ACCESS_NETWORK_STATE. */
class Connectivity(context: Context) {

    private val cm =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
