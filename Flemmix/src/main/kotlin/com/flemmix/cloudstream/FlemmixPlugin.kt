package com.flemmix.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@CloudstreamPlugin
class FlemmixPlugin : Plugin() {
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(context: Context) {
        FlemmixRuntime.init(context)
        val provider = FlemmixProvider()
        registerMainAPI(provider)

        discoveryScope.launch {
            runCatching { provider.prepareDomain() }
        }
    }
}

object FlemmixRuntime {
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context)
    }

    fun currentActivity(): Activity? {
        var current: Context? = contextRef?.get()
        val visited = HashSet<Context>()
        while (current != null && visited.add(current)) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }
}
