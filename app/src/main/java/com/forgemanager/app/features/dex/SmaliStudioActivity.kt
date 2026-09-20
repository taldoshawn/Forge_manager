package com.forgemanager.app.features.dex

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
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.putFileLocation
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.features.editor.TextEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File

class SmaliStudioActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);private val graph by lazy{(application as ForgeApplication).graph}
    private lateinit var location:FileLocation;private lateinit var displayName:String;private lateinit var workspace:File;private lateinit var inputDex:File;private lateinit var smaliDir:File;private lateinit var rebuiltDex:File;private lateinit var status:TextView;private lateinit var filter:EditText;private lateinit var list:ListView
    private var files:List<File> = emptyList();private var shown:List<File> = emptyList();private var disassembled=false;private var rebuilt=false
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);location=intent.readFileLocation()?:run{finish();return};displayName=intent.fileDisplayName()?:location.displayPath.substringAfterLast('/').substringAfterLast("!/").ifBlank{"classes.dex"};val id=Integer.toHexString(location.displayPath.hashCode());workspace=File(cacheDir,"smali-studio/$id").apply{mkdirs()};inputDex=File(workspace,"input.dex");smaliDir=File(workspace,"smali");rebuiltDex=File(workspace,"rebuilt.dex");setContentView(buildUi());materializeAndDisassemble()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    private fun buildUi()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK);val bar=LinearLayout(this@SmaliStudioActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.rgb(8,8,8))};bar.addView(button("←"){finish()});status=TextView(this@SmaliStudioActivity).apply{setTextColor(Color.WHITE);text=displayName;setPadding(dp(6),0,dp(6),0)};bar.addView(status,LinearLayout.LayoutParams(0,-2,1f));bar.addView(button("DEX"){rebuild()});bar.addView(button("✓"){applyRebuilt()});addView(bar,LinearLayout.LayoutParams(-1,dp(54)));val actions=LinearLayout(this@SmaliStudioActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.rgb(4,4,4))};actions.addView(small("Desmontar"){materializeAndDisassemble()},LinearLayout.LayoutParams(0,dp(44),1f));actions.addView(small("Rebuild DEX"){rebuild()},LinearLayout.LayoutParams(0,dp(44),1f));actions.addView(small("Aplicar"){applyRebuilt()},LinearLayout.LayoutParams(0,dp(44),1f));addView(actions);filter=EditText(this@SmaliStudioActivity).apply{hint="Filtrar classe .smali";setSingleLine();setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setBackgroundColor(Color.rgb(12,12,12));addTextChangedListener(SimpleTextWatcher{applyFilter()})};addView(filter,LinearLayout.LayoutParams(-1,dp(46)));list=ListView(this@SmaliStudioActivity).apply{setBackgroundColor(Color.BLACK);divider=null;setOnItemClickListener{_,_,p,_->shown.getOrNull(p)?.let{startActivity(Intent(this@SmaliStudioActivity,TextEditorActivity::class.java).putFileLocation(FileLocation.Direct(it.path),it.name))}}};addView(list,LinearLayout.LayoutParams(-1,0,1f))}
    private fun button(l:String,a:()->Unit)=Button(this).apply{text=l;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{a()}}
    private fun small(l:String,a:()->Unit)=button(l,a).apply{textSize=11f}
    private fun materializeAndDisassemble(){status.text="Preparando $displayName…";scope.launch{runCatching{withContext(Dispatchers.IO){if(smaliDir.exists())smaliDir.deleteRecursively();smaliDir.mkdirs();rebuiltDex.delete();val b=graph.resolver.backendFor(location);val n=b.stat(location);require(n.size in 1..MAX_DEX_BYTES){"DEX vazio ou grande demais"};b.openInput(location).use{i->inputDex.outputStream().buffered(128*1024).use{i.copyTo(it,128*1024)}};val dex=DexFileFactory.loadDexFile(inputDex,Opcodes.getDefault());val o=BaksmaliOptions().apply{apiLevel=API_LEVEL};if(!Baksmali.disassembleDexFile(dex,smaliDir,Runtime.getRuntime().availableProcessors().coerceIn(1,4),o))error("Baksmali encontrou erros");smaliDir.walkTopDown().filter{it.isFile&&it.extension.equals("smali",true)}.take(MAX_LISTED_FILES).toList()}}.onSuccess{files=it;disassembled=true;rebuilt=false;applyFilter();status.text="$displayName • ${files.size} classes Smali"}.onFailure{error(it.message?:"Falha no baksmali")}}}
    private fun rebuild(){if(!disassembled){toast("Desmonte o DEX primeiro");return};status.text="Recompilando Smali…";scope.launch{runCatching{withContext(Dispatchers.IO){rebuiltDex.delete();val o=SmaliOptions().apply{outputDexFile=rebuiltDex.path;apiLevel=API_LEVEL;jobs=Runtime.getRuntime().availableProcessors().coerceIn(1,4)};if(!Smali.assemble(o,listOf(smaliDir.path)))error("Smali encontrou erros");require(rebuiltDex.isFile&&rebuiltDex.length()>0);rebuiltDex.length()}}.onSuccess{rebuilt=true;status.text="$displayName • rebuild OK • ${it/1024} KB";toast("DEX recompilado")}.onFailure{error(it.message?:"Falha ao recompilar DEX")}}}
    private fun applyRebuilt(){if(!rebuilt||!rebuiltDex.isFile){toast("Faça o rebuild antes");return};AlertDialog.Builder(this).setTitle("Aplicar DEX recompilado?").setMessage("O DEX será gravado no local original. Em APK/ZIP a entrada será reescrita.").setPositiveButton("Aplicar"){_,_->scope.launch{runCatching{withContext(Dispatchers.IO){graph.resolver.backendFor(location,write=true).openOutput(location,true).use{o->rebuiltDex.inputStream().use{it.copyTo(o,128*1024)}}}}.onSuccess{toast("DEX aplicado");status.text="$displayName • salvo"}.onFailure{error(it.message?:"Falha ao gravar DEX")}}}.setNegativeButton("Cancelar",null).show()}
    private fun applyFilter(){if(!::list.isInitialized)return;val q=filter.text?.toString().orEmpty();shown=if(q.isBlank())files else files.filter{it.relativeTo(smaliDir).path.contains(q,true)};list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,shown.map{it.relativeTo(smaliDir).path})}
    private fun error(m:String)=AlertDialog.Builder(this).setTitle("Smali/DEX Studio").setMessage(m).setPositiveButton("OK",null).show();private fun toast(m:String)=Toast.makeText(this,m,Toast.LENGTH_SHORT).show();private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private class SimpleTextWatcher(val cb:()->Unit):android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=cb();override fun afterTextChanged(s:android.text.Editable?)=Unit}
    companion object{private const val API_LEVEL=30;private const val MAX_DEX_BYTES=256L*1024*1024;private const val MAX_LISTED_FILES=50_000}
}
