package com.forgemanager.app.features.disk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.forgemanager.app.ForgeApplication
import com.forgemanager.app.MainActivity
import com.forgemanager.app.core.file.FileLocation
import com.forgemanager.app.core.file.fileDisplayName
import com.forgemanager.app.core.file.readFileLocation
import com.forgemanager.app.core.shell.ShellEscaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class DiskImageActivity:Activity(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);private val graph by lazy{(application as ForgeApplication).graph};private lateinit var location:FileLocation;private lateinit var name:String;private lateinit var info:TextView;private var mountPoint:File?=null
 override fun onCreate(s:Bundle?){super.onCreate(s);location=intent.readFileLocation()?:run{finish();return};name=intent.fileDisplayName()?:location.displayPath.substringAfterLast('/');setContentView(ui());inspect()}
 override fun onDestroy(){scope.cancel();super.onDestroy()}
 private fun ui()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK);val b=LinearLayout(this@DiskImageActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.BLACK)};b.addView(btn("←"){finish()});b.addView(TextView(this@DiskImageActivity).apply{text=name;setTextColor(Color.WHITE);setPadding(dp(8),0,0,0)},LinearLayout.LayoutParams(0,-2,1f));b.addView(btn("MONTAR RO"){mountReadOnly()});addView(b,LinearLayout.LayoutParams(-1,dp(54)));info=TextView(this@DiskImageActivity).apply{setTextColor(Color.rgb(220,226,235));typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true);setPadding(dp(12),dp(12),dp(12),dp(24))};addView(ScrollView(this@DiskImageActivity).apply{addView(info)},LinearLayout.LayoutParams(-1,0,1f))}
 private fun btn(l:String,a:()->Unit)=Button(this).apply{text=l;textSize=11f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{a()}}
 private fun inspect(){scope.launch{runCatching{withContext(Dispatchers.IO){val b=graph.resolver.backendFor(location);val n=b.stat(location);val head=ByteArrayOutputStream();b.openInput(location).use{input->val buf=ByteArray(4096);var left=64*1024;while(left>0){val c=input.read(buf,0,minOf(buf.size,left));if(c<0)break;head.write(buf,0,c);left-=c}};n.size to head.toByteArray()}}.onSuccess{(size,data)->info.text=buildString{append("Arquivo: $name\nTamanho: $size bytes\nFormato provável: ${detect(data)}\n\n");append(hex(data.take(512).toByteArray()))}}.onFailure{error(it.message?:"Falha ao ler imagem")}}}
 private fun detect(d:ByteArray):String{fun ascii(o:Int,n:Int)=if(o+n<=d.size)String(d,o,n,StandardCharsets.US_ASCII)else"";return when{d.size>=4&&le32(d,0)==0xED26FF3A.toInt()->"Android sparse image";ascii(0,8)=="ANDROID!"->"Android boot image";ascii(0,4)=="AVB0"->"Android Verified Boot";ascii(3,8).startsWith("NTFS")->"NTFS";ascii(54,3)=="FAT"||ascii(82,3)=="FAT"->"FAT";d.size>0x8006&&ascii(0x8001,5)=="CD001"->"ISO-9660";d.size>1082&&((d[1080].toInt()and255)|((d[1081].toInt()and255)shl8))==0xEF53->"EXT2/3/4";else->"RAW/desconhecida"}}
 private fun mountReadOnly(){val direct=(location as? FileLocation.Direct)?.path?.let(::File);if(direct==null){toast("Montagem exige arquivo local direto");return};scope.launch{val ok=if(graph.root.isAuthorized())true else withContext(Dispatchers.IO){runCatching{graph.root.authorize()}.getOrDefault(false)};if(!ok){toast("Root necessário para montar .img");return@launch};AlertDialog.Builder(this@DiskImageActivity).setTitle("Montar somente leitura?").setMessage("O Forge usará loop mount com -o ro. Sparse Android não é convertido automaticamente.").setPositiveButton("Montar RO"){_,_->doMount(direct)}.setNegativeButton("Cancelar",null).show()}}
 private fun doMount(file:File){scope.launch{runCatching{withContext(Dispatchers.IO){val mp=File("/data/local/tmp/forge-img-${System.nanoTime()}");val cmd="mkdir -p ${ShellEscaper.quote(mp.path)} && mount -o ro,loop ${ShellEscaper.quote(file.path)} ${ShellEscaper.quote(mp.path)}";val p=ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();val text=p.inputStream.bufferedReader().readText();if(p.waitFor()!=0)error(text.ifBlank{"mount falhou"});mp}}.onSuccess{mountPoint=it;toast("Montado RO");startActivity(Intent(this@DiskImageActivity,MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PATH,it.path))}.onFailure{error(it.message?:"Falha ao montar")}}}
 private fun le32(d:ByteArray,o:Int)=(d[o].toInt()and255)or((d[o+1].toInt()and255)shl8)or((d[o+2].toInt()and255)shl16)or((d[o+3].toInt()and255)shl24)
 private fun hex(d:ByteArray)=d.asList().chunked(16).mapIndexed{i,row->"%08x  %s".format(i*16,row.joinToString(" "){"%02x".format(it)})}.joinToString("\n")
 private fun error(m:String)=AlertDialog.Builder(this).setTitle("Disk Image").setMessage(m).setPositiveButton("OK",null).show();private fun toast(m:String)=Toast.makeText(this,m,Toast.LENGTH_SHORT).show();private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
