package com.afterdark.cloudstream

import android.content.Context
import android.util.Log
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
        val provider = AfterDarkProvider()
        registerMainAPI(provider)

        discoveryScope.launch {
            runCatching { provider.prepareDomain() }
                .onFailure { error ->
                    Log.e(
                        AfterDarkDomainResolver.TAG,
                        "Découverte initiale du domaine impossible",
                        error,
                    )
                }
        }
    }
}
