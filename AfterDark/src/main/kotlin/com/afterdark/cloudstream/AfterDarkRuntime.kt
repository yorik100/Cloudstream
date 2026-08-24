package com.afterdark.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference

object AfterDarkRuntime {
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
