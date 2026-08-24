package com.afterdark.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference

/**
 * Garde une référence faible au contexte reçu par CloudStream.
 *
 * Le fallback par réflexion n'est utilisé que pour retrouver l'Activity
 * courante de CloudStream si le plugin a été chargé avec un Context non-Activity.
 * Il ne touche ni au réseau ni à la vérification AfterDark.
 */
object AfterDarkRuntime {
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context)
    }

    fun currentActivity(): Activity? {
        contextRef?.get()?.findActivity()?.let { return it }

        // CommonActivity appartient à l'application CloudStream et non à la
        // librairie d'extensions, donc on évite une dépendance de compilation.
        return runCatching {
            val clazz = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val instance = clazz.getField("INSTANCE").get(null)
            clazz.getMethod("getActivity").invoke(instance) as? Activity
        }.getOrNull()
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        val seen = HashSet<Context>()

        while (current != null && seen.add(current)) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }
}
