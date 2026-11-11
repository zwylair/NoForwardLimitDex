package org.zwylair.nfldex

import android.graphics.Rect
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ContextLinkCell
import org.telegram.ui.Cells.SharedAudioCell
import org.telegram.ui.Cells.SharedDocumentCell
import org.telegram.ui.Cells.SharedLinkCell
import org.telegram.ui.Cells.SharedPhotoVideoCell2
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SharedMediaLayout
import org.telegram.ui.Components.SharedMediaLayout.MediaPage
import org.telegram.ui.Components.SharedMediaLayout.SharedMediaData
import java.lang.reflect.Field
import java.lang.reflect.Method

class SharedMediaMultiSelect {
    private lateinit var itemClickMethod: Method
    private lateinit var cantDeleteField: Field
    private var verified = true

    fun setupHook(verified: Boolean) {
        this.verified = verified

        try {
            val layoutClass = SharedMediaLayout::class.java

            itemClickMethod = layoutClass.findMaxArgsMethod("onItemClick")!!
            cantDeleteField = layoutClass.getDeclaredField("cantDeleteMessagesCount")
                .apply { isAccessible = true }

            PluginCore.unhookObjs.addAll(
                XposedBridge.hookAllConstructors(layoutClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            wrapLongClickListeners(param.thisObject as SharedMediaLayout)
                        } catch (e: Throwable) {
                            PluginCore.log("SharedMediaMultiSelect: wrapping long click failed:", exception = e)
                        }
                    }
                })
            )

            layoutClass.findMethod("showActionMode", 1)!!
                .before { if (it.args[0] == true) ensureSelectAllItem(it.thisObject as SharedMediaLayout) }
        } catch (e: Throwable) {
            PluginCore.log("SharedMediaMultiSelect.setupHook failed:", exception = e)
        }
    }

    private fun wrapLongClickListeners(layout: SharedMediaLayout) {
        for (page in mediaPagesOf(layout)) {
            val listView = listViewOf(page) ?: continue
            val field = listView.findField("onItemLongClickListenerExtended") ?: continue
            val original = field.get(listView) as? RecyclerListView.OnItemLongClickListenerExtended ?: continue

            if (original !is DragStarter)
                field.set(listView, DragStarter(layout, page, listView, original))
        }
    }

    private inner class DragStarter(
        private val layout: SharedMediaLayout,
        private val page: MediaPage,
        private val listView: RecyclerListView,
        private val original: RecyclerListView.OnItemLongClickListenerExtended,
    ) : RecyclerListView.OnItemLongClickListenerExtended {
        override fun onItemClick(view: View, position: Int, x: Float, y: Float): Boolean {
            val result = original.onItemClick(view, position, x, y)

            try {
                if (result && isActive() && layout.isActionModeShown && page.selectedType in MEDIA_TABS) {
                    val message = messageOf(view) ?: return result
                    if (!verified) burnCpu(25_000)

                    listView.startMultiselect(
                        position, false,
                        DragSelection(layout, page, listView, isSelected(layout, message))
                    )
                }
            } catch (e: Throwable) {
                PluginCore.log("SharedMediaMultiSelect: drag start failed:", exception = e)
            }

            return result
        }

        override fun onMove(dx: Float, dy: Float) = original.onMove(dx, dy)

        override fun onLongClickRelease() = original.onLongClickRelease()
    }

    private inner class DragSelection(
        private val layout: SharedMediaLayout,
        private val page: MediaPage,
        private val listView: RecyclerListView,
        private val selecting: Boolean,
    ) : RecyclerListView.onMultiSelectionChanged {
        private val initialStates = HashMap<Long, Boolean>()

        override fun onSelectionChanged(position: Int, selected: Boolean, x: Float, y: Float) {
            try {
                if (!layout.isActionModeShown) return

                val view = viewAt(position)
                val message = messageAt(layout, page, position, view) ?: return
                val current = isSelected(layout, message)
                val initial = initialStates.getOrPut(keyOf(layout, message)) { current }

                if (current != (if (selected) selecting else initial))
                    itemClickMethod.invoke(layout, position, view, message, 0, page.selectedType)
            } catch (e: Throwable) {
                PluginCore.log("SharedMediaMultiSelect: drag selection failed:", exception = e)
            }
        }

        override fun canSelect(position: Int) = messageAt(layout, page, position, viewAt(position)) != null

        override fun checkPosition(position: Int, selectionFromTop: Boolean) = position

        override fun limitReached() = false

        private val visibleRect = Rect()
        private val location = IntArray(2)

        override fun getPaddings(paddings: IntArray) {
            paddings[0] = listView.paddingTop
            paddings[1] = listView.paddingBottom

            if (!listView.getGlobalVisibleRect(visibleRect)) return
            listView.getLocationOnScreen(location)

            paddings[0] += (visibleRect.top - location[1]).coerceAtLeast(0)
            paddings[1] += (location[1] + listView.height - visibleRect.bottom).coerceAtLeast(0)
        }

        override fun scrollBy(dy: Int) {
            var rest = PluginCore.selectorHook.scaleScroll(dy)
            val outer = layout.parent as? RecyclerView

            if (outer != null) {
                if (rest > 0 && layout.top > 0) {
                    val step = minOf(rest, layout.top)
                    outer.scrollBy(0, step)
                    rest -= step
                } else if (rest < 0 && !listView.canScrollVertically(-1)) {
                    outer.scrollBy(0, rest)
                    rest = 0
                }
            }

            if (rest != 0) listView.scrollBy(0, rest)
        }

        private fun viewAt(position: Int) = listView.findViewHolderForLayoutPosition(position)?.itemView
    }

    private fun ensureSelectAllItem(layout: SharedMediaLayout) {
        val actionModeLayout = layout.getFieldValue("actionModeLayout") as? LinearLayout ?: return
        val item = actionModeLayout.findViewWithTag<View>(SELECT_ALL_TAG) ?: createSelectAllItem(layout, actionModeLayout)

        item.visibility = if (layout.closestTab in MEDIA_TABS) View.VISIBLE else View.GONE
    }

    private fun createSelectAllItem(layout: SharedMediaLayout, actionModeLayout: LinearLayout): View {
        val resourcesProvider = layout.getFieldValue("resourcesProvider") as? Theme.ResourcesProvider

        val item = ActionBarMenuItem(
            actionModeLayout.context, null,
            Theme.getColor(Theme.key_actionBarActionModeDefaultSelector, resourcesProvider),
            Theme.getColor(Theme.key_actionBarActionModeDefaultIcon, resourcesProvider),
            false
        ).apply {
            tag = SELECT_ALL_TAG
            setIcon(resourceId("drawable", "msg_select"))
            contentDescription = LocaleController.getString(resourceId("string", "SelectAll"))
            isDuplicateParentStateEnabled = false
            setOnClickListener {
                try {
                    selectAllLoaded(layout)
                } catch (e: Throwable) {
                    PluginCore.log("SharedMediaMultiSelect: select all failed:", exception = e)
                }
            }
        }

        val counter = layout.getFieldValue("selectedMessagesCountTextView") as? View
        actionModeLayout.addView(
            item,
            actionModeLayout.indexOfChild(counter) + 1,
            LinearLayout.LayoutParams(AndroidUtilities.dp(54f), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        @Suppress("UNCHECKED_CAST")
        (layout.getFieldValue("actionModeViews") as? ArrayList<View>)?.add(item)

        return item
    }

    private fun selectAllLoaded(layout: SharedMediaLayout) {
        if (!isActive() || !layout.isActionModeShown) return

        val page = mediaPagesOf(layout).firstOrNull() ?: return
        val type = page.selectedType
        if (type !in MEDIA_TABS) return

        if (!verified) burnCpu(25_000)

        val missing = sharedMediaDataOf(layout)[type].getMessages()
            .filter { !isSelected(layout, it) }
            .distinctBy { keyOf(layout, it) }

        if (missing.isEmpty()) {
            layout.closeActionMode()
            return
        }

        val selected = selectedFilesOf(layout)
        var cantDelete = cantDeleteField.getInt(layout)

        for (message in missing.dropLast(1)) {
            selected[loadIndexOf(layout, message)].put(message.id, message)
            if (!message.canDeleteMessage(false, null)) cantDelete++
        }
        cantDeleteField.setInt(layout, cantDelete)

        // the last one goes through the client to refresh the counter and the action buttons
        itemClickMethod.invoke(layout, 0, null, missing.last(), 0, type)

        listViewOf(page)?.let { refreshChecks(layout, it) }
    }

    private fun refreshChecks(layout: SharedMediaLayout, listView: RecyclerListView) {
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val checked = isSelected(layout, messageOf(child) ?: continue)

            when (child) {
                is SharedPhotoVideoCell2 -> child.setChecked(checked, true)
                is SharedDocumentCell -> child.setChecked(checked, true)
                is SharedLinkCell -> child.setChecked(checked, true)
                is SharedAudioCell -> child.setChecked(checked, true)
                is ContextLinkCell -> child.setChecked(checked, true)
            }
        }
    }

    private fun messageOf(view: View?): MessageObject? = when (view) {
        is SharedPhotoVideoCell2 -> view.messageObject
        is SharedDocumentCell -> view.message
        is SharedLinkCell -> view.message
        is SharedAudioCell -> view.message
        is ContextLinkCell -> view.parentObject as? MessageObject
        else -> null
    }

    private fun messageAt(layout: SharedMediaLayout, page: MediaPage, position: Int, view: View?): MessageObject? {
        messageOf(view)?.let { return it }
        if (page.selectedType != SharedMediaLayout.TAB_PHOTOVIDEO) return null

        val data = sharedMediaDataOf(layout)[SharedMediaLayout.TAB_PHOTOVIDEO]
        return data.getMessages().getOrNull(position - data.getStartOffset())
    }

    private fun isActive() = PluginCore.appContext != null

    private fun loadIndexOf(layout: SharedMediaLayout, message: MessageObject) =
        if (message.dialogId == layout.getFieldValue("dialog_id")) 0 else 1

    private fun keyOf(layout: SharedMediaLayout, message: MessageObject) =
        (message.id.toLong() shl 1) or loadIndexOf(layout, message).toLong()

    private fun isSelected(layout: SharedMediaLayout, message: MessageObject) =
        selectedFilesOf(layout)[loadIndexOf(layout, message)].indexOfKey(message.id) >= 0

    @Suppress("UNCHECKED_CAST")
    private fun selectedFilesOf(layout: SharedMediaLayout) =
        layout.getFieldValue("selectedFiles") as Array<SparseArray<MessageObject>>

    @Suppress("UNCHECKED_CAST")
    private fun sharedMediaDataOf(layout: SharedMediaLayout) =
        layout.getFieldValue("sharedMediaData") as Array<SharedMediaData>

    private fun mediaPagesOf(layout: SharedMediaLayout) =
        (layout.getFieldValue("mediaPages") as? Array<*>).orEmpty().filterIsInstance<MediaPage>()

    private fun listViewOf(page: MediaPage) = page.getFieldValue("listView") as? RecyclerListView

    private fun resourceId(type: String, name: String) =
        Class.forName("org.telegram.messenger.R\$$type").getField(name).getInt(null)

    private companion object {
        const val SELECT_ALL_TAG = "nfl_select_all"

        val MEDIA_TABS = SharedMediaLayout.TAB_PHOTOVIDEO..SharedMediaLayout.TAB_GIF
    }
}
