package org.zwylair.nfldex

import android.content.Context
import com.exteragram.messenger.utils.AppUtils
import de.robv.android.xposed.XC_MethodHook

object PluginCore {
    var pyArgs: Array<out Any?>? = null
    var pyRunnable: Runnable? = null
    var appContext: Context? = null
    var unhookObjs: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private var pluginId: String? = null

    val selectorHook = SelectorManager()

    @Suppress("unused")
    @JvmStatic
    fun init(runnable: Runnable, context: Context, pluginId: String) {
        pyRunnable = runnable
        appContext = context
        this.pluginId = pluginId

        selectorHook.setupHook(checkPluginId(pluginId))
        log("NoForwardLimitDex loaded.")
    }

    @Suppress("unused")
    @JvmStatic
    fun unload() {
        try { unhookObjs.forEach { it.unhook() } }
        catch (error: Throwable) { log(error) }

        clearReflectionCache()

        log("NoForwardLimitDex unloaded.")
        pyRunnable = null
        appContext = null
        this.pluginId = null
    }

    @JvmStatic
    fun callPython(vararg args: Any?) {
        pyArgs = args
        pyRunnable?.run()
        pyArgs = null
    }

    @JvmStatic
    fun log(vararg args: Any?) = callPython("log", *args)

    @JvmStatic
    fun jLog(vararg args: String, exception: Throwable? = null) =
        AppUtils.log("[NoForwardLimitDEX] ${args.joinToString()}", exception)
}
