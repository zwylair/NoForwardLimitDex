package org.zwylair.nfldex

import android.content.Context
import com.exteragram.messenger.utils.AppUtils
import de.robv.android.xposed.XC_MethodHook

object PluginCore {
    const val DEX_VERSION = "1.0.0"

    var appContext: Context? = null
    var unhookObjs: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private var pluginId: String? = null

    @JvmField
    val selectorHook = SelectorManager()

    @Suppress("unused")
    @JvmStatic
    fun init(context: Context, pluginId: String) {
        appContext = context
        this.pluginId = pluginId

        selectorHook.setupHook(checkPluginId(pluginId))
        log("DEX v$DEX_VERSION loaded.")
    }

    @Suppress("unused")
    @JvmStatic
    fun unload() {
        try { unhookObjs.forEach { it.unhook() } }
        catch (error: Throwable) { log(exception = error) }

        clearReflectionCache()

        log("Unloaded.")
        appContext = null
        this.pluginId = null
    }

    @Suppress("unused")
    @JvmStatic
    fun clearSelectorStorage() = selectorHook.clearMessageStorage()

    @JvmStatic
    fun log(vararg args: String, exception: Throwable? = null) =
        AppUtils.log("[NoForwardLimitDEX] ${args.joinToString()}", exception)
}
