package com.afterdark.cloudstream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference

internal enum class AfterDarkPlayerRequestRole {
    ACTIVE,
    NEXT_PRELOAD,
    OTHER,
    NO_PLAYER,
}

object AfterDarkRuntime {
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        contextRef = WeakReference(context)
        appContext = context.applicationContext
    }

    fun applicationContext(): Context? = appContext

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

    /**
     * CloudStream preloads the next ResultEpisode through the same provider
     * loadLinks() entry point as normal playback. No public "isPreload" flag is
     * passed to providers.
     *
     * GeneratorPlayer is not minified in CloudStream release builds. Read its
     * PlayerGeneratorViewModel reflectively so this plugin does not depend on
     * app-internal UI classes at compile time.
     */
    internal fun playerRequestRole(data: String): AfterDarkPlayerRequestRole {
        val fragment = visibleGeneratorPlayer()
            ?: return AfterDarkPlayerRequestRole.NO_PLAYER

        return runCatching {
            val viewModel = readField(fragment, "viewModel")
                ?: return@runCatching AfterDarkPlayerRequestRole.OTHER
            val state = callNoArg(viewModel, "getState")
                ?: return@runCatching AfterDarkPlayerRequestRole.OTHER
            val generatorState = callNoArg(state, "getGeneratorState")
                ?: return@runCatching AfterDarkPlayerRequestRole.OTHER

            val currentData = callNoArg(
                callNoArg(generatorState, "getMeta"),
                "getData",
            ) as? String
            val nextData = callNoArg(
                callNoArg(generatorState, "getNextMeta"),
                "getData",
            ) as? String

            when (data) {
                currentData -> AfterDarkPlayerRequestRole.ACTIVE
                nextData -> AfterDarkPlayerRequestRole.NEXT_PRELOAD
                else -> AfterDarkPlayerRequestRole.OTHER
            }
        }.getOrDefault(AfterDarkPlayerRequestRole.OTHER)
    }

    internal fun isGeneratorPlayerVisible(): Boolean =
        visibleGeneratorPlayer() != null

    private fun visibleGeneratorPlayer(): Any? {
        val activity = currentActivity() ?: return null
        if (activity.isFinishing) return null

        val fragmentManager = callNoArg(activity, "getSupportFragmentManager")
            ?: return null

        return findVisibleGeneratorPlayer(fragmentManager)
    }

    private fun findVisibleGeneratorPlayer(fragmentManager: Any?): Any? {
        val fragments = callNoArg(fragmentManager, "getFragments") as? Iterable<*>
            ?: return null

        val list = fragments.filterNotNull().toList().asReversed()

        for (fragment in list) {
            val visible = callNoArg(fragment, "isVisible") as? Boolean ?: false
            val className = fragment.javaClass.name

            if (
                visible &&
                className == "com.lagradost.cloudstream3.ui.player.GeneratorPlayer"
            ) {
                return fragment
            }

            val childManager = callNoArg(fragment, "getChildFragmentManager")
            findVisibleGeneratorPlayer(childManager)?.let { return it }
        }

        return null
    }

    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass

        while (type != null) {
            val currentType = type
            val field = runCatching {
                currentType.getDeclaredField(name)
            }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            type = currentType.superclass
        }

        return null
    }

    private fun callNoArg(target: Any?, name: String): Any? {
        if (target == null) return null

        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            val method = currentType.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(target)
                }.getOrNull()
            }
            type = currentType.superclass
        }

        return null
    }
}
