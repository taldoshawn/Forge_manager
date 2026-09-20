package com.forgemanager.app.features.explorer

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.forgemanager.app.R
import com.forgemanager.app.core.file.FileNode
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class FileListAdapter(
    private val context: Context,
    private val selected: (FileNode) -> Boolean
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private var items: List<FileNode> = emptyList()

    fun submitList(value: List<FileNode>) {
        items = value
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].location.displayPath.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: Holder
        val view = if (convertView == null) {
            inflater.inflate(R.layout.item_file, parent, false).also {
                holder = Holder(it.findViewById(R.id.icon), it.findViewById(R.id.name), it.findViewById(R.id.details))
                it.tag = holder
            }
        } else {
            holder = convertView.tag as Holder
            convertView
        }

        val item = getItem(position)
        val kind = FileTypeClassifier.classify(item.name, item.isDirectory)
        val icon = iconFor(kind, item.name)
        holder.name.text = item.name
        holder.icon.setImageResource(icon.resource)
        holder.icon.imageTintList = if (icon.rich) null else ColorStateList.valueOf(ContextCompat.getColor(context, colorFor(kind)))
        holder.icon.contentDescription = FileTypeClassifier.shortLabel(kind, item.name)
        holder.icon.alpha = 1f

        val date = if (item.modified > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modified))
        } else ""
        val type = FileTypeClassifier.shortLabel(kind, item.name)
        holder.details.text = if (item.isDirectory) {
            listOf(type, date).filter { it.isNotBlank() }.joinToString("  •  ")
        } else {
            listOf(type, formatBytes(item.size), date).filter { it.isNotBlank() }.joinToString("  •  ")
        }
        view.setBackgroundResource(if (selected(item)) R.drawable.bg_file_item_selected else R.drawable.bg_file_item)
        return view
    }

    private data class IconSpec(val resource: Int, val rich: Boolean)

    private fun iconFor(kind: FileKind, name: String): IconSpec {
        val ext = FileTypeClassifier.extensionOf(name)
        return when (kind) {
            FileKind.DIRECTORY -> IconSpec(R.drawable.ic_file_folder_rich, true)
            FileKind.ARCHIVE -> if (ext in setOf("rar", "7z")) IconSpec(R.drawable.ic_file_rar_rich, true)
                else IconSpec(R.drawable.ic_file_zip_rich, true)
            FileKind.IMAGE -> if (ext == "svg") IconSpec(R.drawable.ic_file_vector_rich, true)
                else IconSpec(R.drawable.ic_file_image_rich, true)
            FileKind.VIDEO -> IconSpec(R.drawable.ic_file_video_rich, true)
            FileKind.AUDIO -> IconSpec(R.drawable.ic_file_audio_rich, true)
            FileKind.PDF -> IconSpec(R.drawable.ic_file_pdf_rich, true)
            FileKind.CODE, FileKind.SCRIPT, FileKind.WEB, FileKind.MARKDOWN -> IconSpec(R.drawable.ic_file_code_rich, true)
            FileKind.TEXT, FileKind.DOCUMENT -> IconSpec(R.drawable.ic_file_text_rich, true)
            FileKind.DISK_IMAGE -> IconSpec(R.drawable.ic_file_disk_image, false)
            FileKind.APK -> IconSpec(R.drawable.ic_file_apk, false)
            FileKind.DEX -> IconSpec(R.drawable.ic_file_dex, false)
            FileKind.XML -> IconSpec(R.drawable.ic_file_xml, false)
            FileKind.DATABASE -> IconSpec(R.drawable.ic_file_database, false)
            FileKind.FONT -> IconSpec(R.drawable.ic_file_font, false)
            FileKind.SPREADSHEET -> IconSpec(R.drawable.ic_file_sheet, false)
            FileKind.PRESENTATION -> IconSpec(R.drawable.ic_file_presentation, false)
            FileKind.CONFIG -> IconSpec(R.drawable.ic_file_config, false)
            FileKind.CERTIFICATE -> IconSpec(R.drawable.ic_file_certificate, false)
            FileKind.EXECUTABLE -> IconSpec(R.drawable.ic_file_executable, false)
            FileKind.GENERIC -> IconSpec(R.drawable.ic_file_generic, false)
        }
    }

    private fun colorFor(kind: FileKind): Int = when (kind) {
        FileKind.DIRECTORY -> R.color.fm_folder
        FileKind.APK -> R.color.fm_apk
        FileKind.ARCHIVE -> R.color.fm_archive
        FileKind.DISK_IMAGE -> R.color.fm_disk_image
        FileKind.IMAGE -> R.color.fm_image
        FileKind.VIDEO -> R.color.fm_video
        FileKind.AUDIO -> R.color.fm_audio
        FileKind.CODE -> R.color.fm_code
        FileKind.SCRIPT -> R.color.fm_script
        FileKind.WEB -> R.color.fm_web
        FileKind.MARKDOWN -> R.color.fm_markdown
        FileKind.TEXT -> R.color.fm_text_file
        FileKind.DOCUMENT -> R.color.fm_document
        FileKind.PDF -> R.color.fm_pdf
        FileKind.DEX -> R.color.fm_dex
        FileKind.XML -> R.color.fm_xml
        FileKind.DATABASE -> R.color.fm_database
        FileKind.FONT -> R.color.fm_font
        FileKind.SPREADSHEET -> R.color.fm_sheet
        FileKind.PRESENTATION -> R.color.fm_presentation
        FileKind.CONFIG -> R.color.fm_config
        FileKind.CERTIFICATE -> R.color.fm_certificate
        FileKind.EXECUTABLE -> R.color.fm_executable
        FileKind.GENERIC -> R.color.fm_text_file
    }

    private data class Holder(val icon: ImageView, val name: TextView, val details: TextView)

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
