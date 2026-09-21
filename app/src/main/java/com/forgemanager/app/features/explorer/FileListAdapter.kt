package com.forgemanager.app.features.explorer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.R
import com.forgemanager.app.core.file.FileNode
import com.forgemanager.app.features.settings.UiPreferences
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class FileListAdapter(
    private val context: Context,
    private val selected: (FileNode) -> Boolean
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val thumbnailLoader = ThumbnailLoader((context.applicationContext as ForgeApplication).graph.resolver)
    private var items: List<FileNode> = emptyList()

    fun submitList(value: List<FileNode>) {
        items = value
        notifyDataSetChanged()
    }

    fun release() = thumbnailLoader.close()

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].location.displayPath.hashCode().toLong()
    override fun hasStableIds(): Boolean = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: Holder
        val view = if (convertView == null) {
            inflater.inflate(R.layout.item_file, parent, false).also {
                holder = Holder(
                    it.findViewById(R.id.icon),
                    it.findViewById(R.id.name),
                    it.findViewById(R.id.details)
                )
                it.tag = holder
            }
        } else {
            holder = convertView.tag as Holder
            convertView
        }

        val item = getItem(position)
        val kind = FileTypeClassifier.classify(item.name, item.isDirectory)
        val compact = UiPreferences.compactRows(context)
        val largeIcons = UiPreferences.largeIcons(context)
        val rowHeight = dp(if (compact) 46 else 58)
        view.layoutParams = (view.layoutParams ?: android.widget.AbsListView.LayoutParams(-1, rowHeight)).apply {
            height = rowHeight
        }
        val iconSize = dp(if (largeIcons) { if (compact) 36 else 42 } else { if (compact) 30 else 34 })
        holder.icon.layoutParams = holder.icon.layoutParams.apply { width = iconSize; height = iconSize }
        holder.icon.contentDescription = FileTypeClassifier.shortLabel(kind, item.name)
        holder.icon.alpha = 1f
        holder.icon.scaleType = ImageView.ScaleType.CENTER_INSIDE

        if (kind == FileKind.IMAGE && FileTypeClassifier.extensionOf(item.name) != "svg") {
            thumbnailLoader.load(holder.icon, item, iconSize * 2) {
                applyFileIcon(holder.icon, item, kind)
            }
            holder.icon.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            thumbnailLoader.cancel(holder.icon)
            applyFileIcon(holder.icon, item, kind)
        }

        holder.name.text = item.name
        holder.name.textSize = if (compact) 11.5f else 12.8f
        holder.name.setTextColor(UiPreferences.textPrimary(context))
        holder.details.textSize = if (compact) 8.8f else 9.5f
        holder.details.setTextColor(UiPreferences.textSecondary(context))

        val date = if (item.modified > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modified))
        } else ""
        val type = FileTypeClassifier.shortLabel(kind, item.name)
        holder.details.text = if (item.isDirectory) {
            listOf(type, date).filter(String::isNotBlank).joinToString("  •  ")
        } else {
            listOf(type, formatBytes(item.size), date).filter(String::isNotBlank).joinToString("  •  ")
        }

        view.background = rowBackground(selected(item))
        return view
    }

    private fun rowBackground(isSelected: Boolean): GradientDrawable {
        val accent = UiPreferences.accent(context)
        val surface = UiPreferences.surface(context)
        val selectedColor = Color.argb(
            if (UiPreferences.isLight(context)) 42 else 72,
            Color.red(accent), Color.green(accent), Color.blue(accent)
        )
        return GradientDrawable().apply {
            setColor(if (isSelected) blendOver(surface, selectedColor) else surface)
            setStroke(dp(1), if (isSelected) Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)) else UiPreferences.divider(context))
        }
    }

    private fun blendOver(background: Int, overlay: Int): Int {
        val a = Color.alpha(overlay) / 255f
        return Color.rgb(
            (Color.red(overlay) * a + Color.red(background) * (1f - a)).toInt(),
            (Color.green(overlay) * a + Color.green(background) * (1f - a)).toInt(),
            (Color.blue(overlay) * a + Color.blue(background) * (1f - a)).toInt()
        )
    }

    private fun applyFileIcon(view: ImageView, item: FileNode, kind: FileKind) {
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val asset = FileIconAtlas.drawable(context, item.name, item.isDirectory, kind)
        if (asset != null) {
            view.setImageDrawable(asset)
            view.imageTintList = null
        } else {
            val icon = fallbackIcon(kind)
            view.setImageResource(icon.res)
            view.imageTintList = icon.tintColor?.let { color ->
                ColorStateList.valueOf(ContextCompat.getColor(context, color))
            }
        }
    }

    private fun fallbackIcon(kind: FileKind): IconSpec = when (kind) {
        FileKind.DIRECTORY -> IconSpec(R.drawable.ic_file_folder, R.color.fm_folder)
        FileKind.APK -> IconSpec(R.drawable.ic_file_apk, R.color.fm_apk)
        FileKind.ARCHIVE -> IconSpec(R.drawable.ic_file_archive, R.color.fm_archive)
        FileKind.IMAGE -> IconSpec(R.drawable.ic_file_image, R.color.fm_image)
        FileKind.VIDEO -> IconSpec(R.drawable.ic_file_video, R.color.fm_video)
        FileKind.AUDIO -> IconSpec(R.drawable.ic_file_audio, R.color.fm_audio)
        FileKind.CODE, FileKind.WEB -> IconSpec(R.drawable.ic_file_code, R.color.fm_code)
        FileKind.SCRIPT -> IconSpec(R.drawable.ic_file_code, R.color.fm_script)
        FileKind.MARKDOWN -> IconSpec(R.drawable.ic_file_text, R.color.fm_markdown)
        FileKind.TEXT, FileKind.SUBTITLE, FileKind.BACKUP -> IconSpec(R.drawable.ic_file_text, R.color.fm_text_file)
        FileKind.DOCUMENT -> IconSpec(R.drawable.ic_file_text, R.color.fm_document)
        FileKind.PDF -> IconSpec(R.drawable.ic_file_pdf, R.color.fm_pdf)
        FileKind.DEX -> IconSpec(R.drawable.ic_file_dex, R.color.fm_dex)
        FileKind.XML, FileKind.BINARY_RESOURCE -> IconSpec(R.drawable.ic_file_xml, R.color.fm_resource)
        FileKind.DISK_IMAGE -> IconSpec(R.drawable.ic_file_executable, R.color.fm_disk_image)
        FileKind.DATABASE -> IconSpec(R.drawable.ic_file_database, R.color.fm_database)
        FileKind.FONT -> IconSpec(R.drawable.ic_file_font, R.color.fm_font)
        FileKind.SPREADSHEET -> IconSpec(R.drawable.ic_file_sheet, R.color.fm_sheet)
        FileKind.PRESENTATION -> IconSpec(R.drawable.ic_file_presentation, R.color.fm_presentation)
        FileKind.CONFIG -> IconSpec(R.drawable.ic_file_text, R.color.fm_config)
        FileKind.CERTIFICATE, FileKind.KEY -> IconSpec(R.drawable.ic_file_certificate, R.color.fm_certificate)
        FileKind.TORRENT, FileKind.LINUX_PACKAGE, FileKind.MODEL3D, FileKind.EXECUTABLE ->
            IconSpec(R.drawable.ic_file_executable, R.color.fm_executable)
        FileKind.GENERIC -> IconSpec(R.drawable.ic_file_generic, R.color.fm_text_file)
    }

    private data class IconSpec(val res: Int, val tintColor: Int? = null)
    private data class Holder(val icon: ImageView, val name: TextView, val details: TextView)

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        fun formatBytes(value: Long): String {
            if (value < 1024) return "$value B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var size = value.toDouble()
            var index = -1
            do {
                size /= 1024
                index++
            } while (size >= 1024 && index < units.lastIndex)
            return String.format(Locale.US, "%.1f %s", size, units[index])
        }
    }
}
