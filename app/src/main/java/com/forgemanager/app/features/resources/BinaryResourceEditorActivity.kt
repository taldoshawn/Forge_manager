package com.forgemanager.app.features.resources

import com.forgemanager.app.core.ui.ForgeActivity

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.readFileLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class BinaryResourceEditorActivity : ForgeActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val graph by lazy{(application as ForgeApplication).graph}
    private lateinit var location:FileLocation; private lateinit var displayName:String; private lateinit var status:TextView; private lateinit var search:EditText; private lateinit var list:ListView; private lateinit var bytes:ByteArray
    private var entries:List<BinaryStringPools.Entry> = emptyList(); private var shown:List<BinaryStringPools.Entry> = emptyList(); private var dirty=false
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);location=intent.readFileLocation()?:run{finish();return};displayName=intent.fileDisplayName()?:location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank{"recurso"};setContentView(buildUi());load()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    @Deprecated("Android back compatibility") override fun onBackPressed(){if(dirty)AlertDialog.Builder(this).setTitle("Alterações não salvas").setMessage("Salvar as alterações em $displayName?").setPositiveButton("Salvar"){_,_->save{finish()}}.setNegativeButton("Descartar"){_,_->finish()}.setNeutralButton("Cancelar",null).show() else super.onBackPressed()}
    private fun buildUi()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK);val bar=LinearLayout(this@BinaryResourceEditorActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.rgb(8,8,8))};bar.addView(button("←"){onBackPressed()});status=TextView(this@BinaryResourceEditorActivity).apply{setTextColor(Color.WHITE);text=displayName;setPadding(dp(6),0,dp(6),0)};bar.addView(status,LinearLayout.LayoutParams(0,-2,1f));bar.addView(button("↻"){load()});bar.addView(button("✓"){save()});addView(bar,LinearLayout.LayoutParams(-1,dp(54)));search=EditText(this@BinaryResourceEditorActivity).apply{hint="Filtrar strings do AXML/ARSC";setSingleLine();setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setBackgroundColor(Color.rgb(12,12,12));addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=filter();override fun afterTextChanged(s:Editable?)=Unit})};addView(search,LinearLayout.LayoutParams(-1,dp(48)));list=ListView(this@BinaryResourceEditorActivity).apply{setBackgroundColor(Color.BLACK);divider=null;setOnItemClickListener{_,_,p,_->shown.getOrNull(p)?.let(::editEntry)}};addView(list,LinearLayout.LayoutParams(-1,0,1f))}
    private fun button(l:String,a:()->Unit)=Button(this).apply{text=l;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{a()}}
    private fun load(){status.text="Lendo $displayName…";scope.launch{runCatching{withContext(Dispatchers.IO){val b=graph.resolver.backendFor(location);val n=b.stat(location);if(n.size>MAX_BYTES)error("AXML/ARSC grande demais");val o=ByteArrayOutputStream();b.openInput(location).use{it.copyTo(o,64*1024)};o.toByteArray()}}.onSuccess{bytes=it;entries=BinaryStringPools.find(it);dirty=false;filter();status.text="$displayName • ${entries.size} strings"}.onFailure{error(it.message?:"Falha ao abrir")}}}
    private fun filter(){if(!::bytes.isInitialized)return;val q=search.text?.toString().orEmpty();shown=if(q.isBlank())entries else entries.filter{it.value.contains(q,true)};list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,shown.map{"[${it.index}] ${if(it.utf8)"UTF-8" else "UTF-16"}  ${it.value}"})}
    private fun editEntry(e:BinaryStringPools.Entry){val input=EditText(this).apply{setText(e.value);setSelection(text.length)};AlertDialog.Builder(this).setTitle("String #${e.index}").setMessage("Edição in-place: não desloca offsets do AXML/ARSC.").setView(input).setPositiveButton("Aplicar"){_,_->runCatching{BinaryStringPools.replace(bytes,e,input.text.toString())}.onSuccess{dirty=true;entries=BinaryStringPools.find(bytes);filter();status.text="$displayName • modificado"}.onFailure{error(it.message?:"String não cabe")}}.setNegativeButton("Cancelar",null).show()}
    private fun save(after:(()->Unit)?=null){if(!::bytes.isInitialized)return;scope.launch{runCatching{withContext(Dispatchers.IO){graph.resolver.backendFor(location,write=true).openOutput(location,true).use{it.write(bytes);it.flush()}}}.onSuccess{dirty=false;status.text="$displayName • salvo";Toast.makeText(this@BinaryResourceEditorActivity,"Recurso salvo",Toast.LENGTH_SHORT).show();after?.invoke()}.onFailure{error(it.message?:"Falha ao salvar")}}}
    private fun error(m:String)=AlertDialog.Builder(this).setTitle("Editor AXML/ARSC").setMessage(m).setPositiveButton("OK",null).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    companion object{private const val MAX_BYTES=128L*1024*1024}
}
