package com.afterdark.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class AfterDarkPlugin : Plugin() {
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        AfterDarkRuntime.init(context)
        val preferences = context.getSharedPreferences(
            AfterDarkDomainResolver.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val provider = AfterDarkProvider(preferences)
        registerMainAPI(provider)

        discoveryScope.launch {
            runCatching { provider.prepareDomain() }
        }
    }
}
