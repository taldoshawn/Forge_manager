package com.forgemanager.app.features.explorer

import android.content.Context
import android.database.DataSetObserver
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.widget.ListAdapter
import android.widget.ListView
import com.forgemanager.app.features.settings.UiPreferences

/**
 * ListView tuned for the dual-pane explorer. When a refresh adds exactly one
 * item to the same directory (new file/folder/ZIP), it automatically scrolls
 * that item into view instead of leaving the user hunting for it.
 */
class SmartFileListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.listViewStyle
) : ListView(context, attrs, defStyleAttr) {
    private var knownIds: Set<Long>? = null
    private var observed: ListAdapter? = null

    private val observer = object : DataSetObserver() {
        override fun onChanged() = handleChanged()
        override fun onInvalidated() {
            knownIds = null
        }
    }

    init {
        divider = ColorDrawable(UiPreferences.divider(context))
        dividerHeight = dp(1)
        setBackgroundColor(UiPreferences.surface(context))
    }

    override fun setAdapter(adapter: ListAdapter?) {
        observed?.unregisterDataSetObserver(observer)
        super.setAdapter(adapter)
        observed = adapter
        knownIds = null
        adapter?.registerDataSetObserver(observer)
    }

    override fun onDetachedFromWindow() {
        observed?.unregisterDataSetObserver(observer)
        observed = null
        super.onDetachedFromWindow()
    }

    private fun handleChanged() {
        val adapter = adapter ?: return
        divider = ColorDrawable(UiPreferences.divider(context))
        setBackgroundColor(UiPreferences.surface(context))
        val current = LinkedHashSet<Long>(adapter.count)
        for (index in 0 until adapter.count) current += adapter.getItemId(index)
        val previous = knownIds
        knownIds = current
        if (previous == null || current.size != previous.size + 1 || !current.containsAll(previous)) return
        val newId = current.firstOrNull { it !in previous } ?: return
        var target = -1
        for (index in 0 until adapter.count) {
            if (adapter.getItemId(index) == newId) { target = index; break }
        }
        if (target >= 0) post {
            setSelection(target)
            smoothScrollToPosition(target)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
