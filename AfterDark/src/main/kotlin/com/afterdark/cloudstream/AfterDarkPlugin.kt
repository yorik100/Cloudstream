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

        // Delete values written by older resolver versions. The current
        // domain is deliberately cached in RAM only.
        context.getSharedPreferences(
            AfterDarkDomainResolver.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().apply()

        val provider = AfterDarkProvider()
        registerMainAPI(provider)

        // Initial discovery is HTTP-only and never displays UI. If it fails,
        // home-page and search requests will retry it themselves.
        discoveryScope.launch {
            provider.prepareDomainInBackground()
        }
    }
}
