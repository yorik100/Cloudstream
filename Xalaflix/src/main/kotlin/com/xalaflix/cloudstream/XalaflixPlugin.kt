package com.xalaflix.cloudstream

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class XalaflixPlugin : Plugin() {
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        Log.i("XalaflixDebug", "Extension Xalaflix v29 chargée")
        val provider = XalaflixProvider()
        registerMainAPI(provider)
        discoveryScope.launch { runCatching { provider.prepareDomain() } }
    }
}
