package org.zwylair.nfldex

import android.content.Context
import com.exteragram.messenger.utils.AppUtils
import de.robv.android.xposed.XC_MethodHook

object PluginCore {
    const val DEX_VERSION = "1.0.4"

    var appContext: Context? = null
    var unhookObjs: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private var pluginId: String? = null

    val isAyuBased = runCatching {
        Class.forName("com.radolyn.ayugram.database.AyuDatabase", false, javaClass.classLoader)
    }.isSuccess

    @JvmField
    val selectorHook = SelectorManager()

    @JvmField
    val smartForward = SmartForwardManager()

    @Suppress("unused")
    @JvmStatic
    fun init(context: Context, pluginId: String) {
        appContext = context
        this.pluginId = pluginId

        selectorHook.setupHook(checkPluginId(pluginId))
        if (!isAyuBased) smartForward.setupHook()
        log("DEX v$DEX_VERSION loaded.")
    }

    @Suppress("unused")
    @JvmStatic
    fun setScrollSpeed(value: Double) {
        selectorHook.scrollSpeed = value
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

    @JvmStatic
    fun log(vararg args: String, exception: Throwable? = null) =
        AppUtils.log("[NoForwardLimitDEX] ${args.joinToString()}", exception)
}
