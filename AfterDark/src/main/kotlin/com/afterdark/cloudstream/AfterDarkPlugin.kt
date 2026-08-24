package com.afterdark.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AfterDarkPlugin : Plugin() {
    override fun load(context: Context) {
        AfterDarkRuntime.init(context)
        registerMainAPI(AfterDarkProvider())
    }
}
