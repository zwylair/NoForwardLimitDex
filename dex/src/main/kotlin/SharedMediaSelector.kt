package org.zwylair.nfldex

import android.util.SparseArray
import de.robv.android.xposed.XC_MethodHook
import org.telegram.messenger.MessageObject
import org.telegram.ui.Components.SharedMediaLayout
import java.lang.reflect.Field

private typealias SelectedFiles = SparseArray<MessageObject>

class SharedMediaSelector {
    private lateinit var selectedFilesField: Field
    private var limitCheckPending = false

    fun setupHook(verified: Boolean) {
        try {
            selectedFilesField = SharedMediaLayout::class.java
                .getDeclaredField("selectedFiles")
                .apply { isAccessible = true }

            val itemClickMethod = findClass("org.telegram.ui.Components.SharedMediaLayout")!!
                .findMethod("onItemClick", 5)!!

            hook(itemClickMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!verified) burnCpu(25_000)

                        bypassSelectionLimit(param)
                    } catch (e: Throwable) {
                        PluginCore.log("SharedMediaSelector.before failed:", exception = e)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    limitCheckPending = false
                }
            })
        } catch (e: Throwable) {
            PluginCore.log("SharedMediaSelector.setupHook failed:", exception = e)
        }
    }

    private inner class UncappedFiles(source: SelectedFiles) : SelectedFiles() {
        val realSize get() = super.size()

        init {
            for (i in 0 until source.size())
                append(source.keyAt(i), source.valueAt(i))
        }

        override fun size() = if (limitCheckPending) 0 else super.size()

        override fun put(key: Int, value: MessageObject?) {
            limitCheckPending = false
            super.put(key, value)
        }
    }

    private fun bypassSelectionLimit(param: XC_MethodHook.MethodHookParam) {
        val message = param.args[2] as? MessageObject ?: return
        val layout = param.thisObject as SharedMediaLayout
        if (!layout.isActionModeShown) return

        val selected = selectedFilesOf(layout)
        val loadIndex = if (message.dialogId == layout.getFieldValue("dialog_id")) 0 else 1
        if (selected[loadIndex].indexOfKey(message.id) >= 0) return

        if (uncapSelection(selected) >= SELECTION_LIMIT)
            limitCheckPending = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun selectedFilesOf(layout: SharedMediaLayout) =
        selectedFilesField.get(layout) as Array<SelectedFiles>

    private fun uncapSelection(selected: Array<SelectedFiles>): Int {
        var selectedCount = 0

        for (i in selected.indices) {
            val files = selected[i] as? UncappedFiles
                ?: UncappedFiles(selected[i]).also { selected[i] = it }
            selectedCount += files.realSize
        }

        return selectedCount
    }

    private companion object {
        const val SELECTION_LIMIT = 100
    }
}
