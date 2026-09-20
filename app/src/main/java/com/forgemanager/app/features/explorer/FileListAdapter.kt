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

class FileListAdapter(private val context:Context,private val selected:(FileNode)->Boolean):BaseAdapter(){
 private val inflater=LayoutInflater.from(context);private var items:List<FileNode> = emptyList();fun submitList(value:List<FileNode>){items=value;notifyDataSetChanged()};override fun getCount()=items.size;override fun getItem(position:Int)=items[position];override fun getItemId(position:Int)=items[position].location.displayPath.hashCode().toLong()
 override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{val holder:Holder;val view=if(convertView==null)inflater.inflate(R.layout.item_file,parent,false).also{holder=Holder(it.findViewById(R.id.icon),it.findViewById(R.id.name),it.findViewById(R.id.details));it.tag=holder}else{holder=convertView.tag as Holder;convertView};val item=getItem(position);val kind=FileTypeClassifier.classify(item.name,item.isDirectory);val icon=iconFor(kind,item.name);holder.name.text=item.name;holder.icon.setImageResource(icon.res);holder.icon.imageTintList=icon.tintColor?.let{ColorStateList.valueOf(ContextCompat.getColor(context,it))};holder.icon.contentDescription=FileTypeClassifier.shortLabel(kind,item.name);holder.icon.alpha=1f;val date=if(item.modified>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(item.modified))else"";val type=FileTypeClassifier.shortLabel(kind,item.name);holder.details.text=if(item.isDirectory)listOf(type,date).filter(String::isNotBlank).joinToString("  •  ") else listOf(type,formatBytes(item.size),date).filter(String::isNotBlank).joinToString("  •  ");view.setBackgroundResource(if(selected(item))R.drawable.bg_file_item_selected else R.drawable.bg_file_item);return view}
 private fun iconFor(kind:FileKind,name:String):IconSpec{val ext=FileTypeClassifier.extensionOf(name);return when(kind){FileKind.DIRECTORY->IconSpec(R.drawable.fm_icon_folder);FileKind.IMAGE->IconSpec(if(ext=="svg")R.drawable.fm_icon_svg else R.drawable.fm_icon_image);FileKind.ARCHIVE->IconSpec(if(ext=="rar")R.drawable.fm_icon_rar else R.drawable.fm_icon_zip);FileKind.AUDIO->IconSpec(R.drawable.fm_icon_audio);FileKind.VIDEO->IconSpec(R.drawable.fm_icon_video);FileKind.PDF->IconSpec(R.drawable.fm_icon_pdf);FileKind.CODE,FileKind.SCRIPT,FileKind.WEB,FileKind.XML->IconSpec(R.drawable.fm_icon_code);FileKind.TEXT,FileKind.MARKDOWN,FileKind.DOCUMENT,FileKind.CONFIG->IconSpec(R.drawable.fm_icon_text);FileKind.APK->IconSpec(R.drawable.ic_file_apk,R.color.fm_apk);FileKind.DEX->IconSpec(R.drawable.ic_file_dex,R.color.fm_dex);FileKind.BINARY_RESOURCE->IconSpec(R.drawable.ic_file_xml,R.color.fm_resource);FileKind.DISK_IMAGE->IconSpec(R.drawable.ic_file_executable,R.color.fm_disk_image);FileKind.DATABASE->IconSpec(R.drawable.ic_file_database,R.color.fm_database);FileKind.FONT->IconSpec(R.drawable.ic_file_font,R.color.fm_font);FileKind.SPREADSHEET->IconSpec(R.drawable.ic_file_sheet,R.color.fm_sheet);FileKind.PRESENTATION->IconSpec(R.drawable.ic_file_presentation,R.color.fm_presentation);FileKind.CERTIFICATE->IconSpec(R.drawable.ic_file_certificate,R.color.fm_certificate);FileKind.EXECUTABLE->IconSpec(R.drawable.ic_file_executable,R.color.fm_executable);FileKind.GENERIC->IconSpec(R.drawable.ic_file_generic,R.color.fm_text_file)}}
 private data class IconSpec(val res:Int,val tintColor:Int?=null);private data class Holder(val icon:ImageView,val name:TextView,val details:TextView)
 companion object{fun formatBytes(value:Long):String{if(value<1024)return"$value B";val units=arrayOf("KB","MB","GB","TB");var size=value.toDouble();var index=-1;do{size/=1024;index++}while(size>=1024&&index<units.lastIndex);return String.format(Locale.US,"%.1f %s",size,units[index])}}
}
