package com.frembed.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class FrembedPlugin : Plugin() {
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        val preferences = context.getSharedPreferences(
            FrembedDomainResolver.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val provider = FrembedProvider(preferences)
        registerMainAPI(provider)

        discoveryScope.launch {
            runCatching { provider.prepareDomain() }
        }
    }
}
