package com.forgemanager.app.features.resources

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.features.dex.SmaliStudioActivity
import com.forgemanager.app.features.editor.HexViewerActivity
import com.forgemanager.app.features.editor.TextEditorActivity
import com.forgemanager.app.features.explorer.FileKind
import com.forgemanager.app.features.explorer.FileTypeClassifier
import com.forgemanager.app.features.viewer.ImageViewerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class ApkResourceStudioActivity:Activity(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);private val graph by lazy{(application as ForgeApplication).graph};private lateinit var apk:File;private lateinit var list:ListView;private lateinit var status:TextView;private lateinit var filter:EditText;private var entries:List<Entry> = emptyList();private var shown:List<Entry> = emptyList();private var pending:Entry?=null
 data class Entry(val name:String,val size:Long,val compressedSize:Long)
 override fun onCreate(s:Bundle?){super.onCreate(s);val p=intent.getStringExtra(EXTRA_APK_PATH)?:intent.getStringExtra("path");apk=p?.let(::File)?:run{finish();return};if(!apk.isFile){finish();return};setContentView(ui());refresh()}
 override fun onDestroy(){scope.cancel();super.onDestroy()}
 private fun ui()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK);val b=LinearLayout(this@ApkResourceStudioActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.rgb(8,8,8))};b.addView(btn("←"){finish()});status=TextView(this@ApkResourceStudioActivity).apply{setTextColor(Color.WHITE);text=apk.name;setPadding(dp(6),0,dp(6),0)};b.addView(status,LinearLayout.LayoutParams(0,-2,1f));b.addView(btn("↻"){refresh()});addView(b,LinearLayout.LayoutParams(-1,dp(54)));filter=EditText(this@ApkResourceStudioActivity).apply{hint="Filtrar res/, assets/, lib/, manifest…";setSingleLine();setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setBackgroundColor(Color.rgb(12,12,12));addTextChangedListener(W{applyFilter()})};addView(filter,LinearLayout.LayoutParams(-1,dp(48)));list=ListView(this@ApkResourceStudioActivity).apply{setBackgroundColor(Color.BLACK);divider=null;setOnItemClickListener{_,_,p,_->shown.getOrNull(p)?.let(::openEntry)};setOnItemLongClickListener{_,_,p,_->shown.getOrNull(p)?.let(::actions);true}};addView(list,LinearLayout.LayoutParams(-1,0,1f))}
 private fun btn(l:String,a:()->Unit)=Button(this).apply{text=l;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{a()}}
 private fun refresh(){scope.launch{runCatching{withContext(Dispatchers.IO){ZipFile(apk).use{z->z.entries().asSequence2().filterNot{it.isDirectory}.map{Entry(it.name,it.size.coerceAtLeast(0),it.compressedSize.coerceAtLeast(0))}.filter{it.name=="AndroidManifest.xml"||it.name=="resources.arsc"||it.name.startsWith("res/")||it.name.startsWith("assets/")||it.name.startsWith("lib/")||it.name.matches(Regex("classes\\d*\\.dex"))}.sortedBy{it.name.lowercase()}.toList()}}}.onSuccess{entries=it;applyFilter();status.text="${apk.name} • ${it.size} recursos"}.onFailure{err(it.message?:"Falha ao ler APK")}}}
 private fun applyFilter(){if(!::list.isInitialized)return;val q=filter.text?.toString().orEmpty();shown=if(q.isBlank())entries else entries.filter{it.name.contains(q,true)};list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,shown.map{"${it.name}  •  ${it.size/1024} KB"})}
 private fun openEntry(e:Entry){val loc=FileLocation.Archive(apk.path,e.name);val n=e.name.substringAfterLast('/');val ext=FileTypeClassifier.extensionOf(n);when{e.name=="resources.arsc"||ext=="arsc"->startActivity(Intent(this,BinaryResourceEditorActivity::class.java).putFileLocation(loc,n));ext=="xml"&&(e.name=="AndroidManifest.xml"||e.name.startsWith("res/"))->AlertDialog.Builder(this).setTitle(n).setItems(arrayOf("Editor AXML binário","Editor de texto","Hexadecimal")){_,w->when(w){0->startActivity(Intent(this,BinaryResourceEditorActivity::class.java).putFileLocation(loc,n));1->startActivity(Intent(this,TextEditorActivity::class.java).putFileLocation(loc,n));2->startActivity(Intent(this,HexViewerActivity::class.java).putFileLocation(loc,n))}}.show();ext=="dex"->startActivity(Intent(this,SmaliStudioActivity::class.java).putFileLocation(loc,n));FileTypeClassifier.classify(n,false)==FileKind.IMAGE->startActivity(Intent(this,ImageViewerActivity::class.java).putFileLocation(loc,n));FileTypeClassifier.classify(n,false) in setOf(FileKind.CODE,FileKind.SCRIPT,FileKind.WEB,FileKind.MARKDOWN,FileKind.TEXT,FileKind.CONFIG)->startActivity(Intent(this,TextEditorActivity::class.java).putFileLocation(loc,n));else->startActivity(Intent(this,HexViewerActivity::class.java).putFileLocation(loc,n))}}
 private fun actions(e:Entry){AlertDialog.Builder(this).setTitle(e.name).setItems(arrayOf("Abrir","Substituir por arquivo…","Excluir entrada")){_,w->when(w){0->openEntry(e);1->choose(e);2->delete(e)}}.show()}
 @Suppress("DEPRECATION") private fun choose(e:Entry){pending=e;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},REQ_REPLACE)}
 @Deprecated("picker") override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r!=REQ_REPLACE||c!=RESULT_OK)return;val e=pending?:return;val uri=d?.data?:return;scope.launch{runCatching{withContext(Dispatchers.IO){contentResolver.openInputStream(uri)!!.use{i->graph.archive.openOutput(FileLocation.Archive(apk.path,e.name),true).use{i.copyTo(it,128*1024)}}}}.onSuccess{Toast.makeText(this@ApkResourceStudioActivity,"Entrada substituída",Toast.LENGTH_SHORT).show();refresh()}.onFailure{err(it.message?:"Falha ao substituir")}}}
 private fun delete(e:Entry){AlertDialog.Builder(this).setTitle("Excluir recurso?").setMessage(e.name).setPositiveButton("Excluir"){_,_->scope.launch{runCatching{withContext(Dispatchers.IO){graph.archive.delete(FileLocation.Archive(apk.path,e.name))}}.onSuccess{refresh()}.onFailure{err(it.message?:"Falha ao excluir")}}}.setNegativeButton("Cancelar",null).show()}
 private fun err(m:String)=AlertDialog.Builder(this).setTitle("Resource Studio").setMessage(m).setPositiveButton("OK",null).show();private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private class W(val cb:()->Unit):android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=cb();override fun afterTextChanged(s:android.text.Editable?)=Unit}
 companion object{const val EXTRA_APK_PATH="apk_path";private const val REQ_REPLACE=410}
}
private fun<T> java.util.Enumeration<T>.asSequence2():Sequence<T> = sequence{while(hasMoreElements())yield(nextElement())}
