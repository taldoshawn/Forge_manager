package com.forgemanager.app.features.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class ForgeBrowserActivity:Activity(){
 private lateinit var web:WebView;private lateinit var address:EditText;private var desktop=false
 override fun onCreate(s:Bundle?){super.onCreate(s);setContentView(ui());if(s==null)load(intent.getStringExtra(EXTRA_URL)?:"https://www.google.com")}
 @Deprecated("back") override fun onBackPressed(){if(web.canGoBack())web.goBack()else super.onBackPressed()}
 override fun onDestroy(){web.stopLoading();web.destroy();super.onDestroy()}
 private fun ui()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK);val bar=LinearLayout(this@ForgeBrowserActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.BLACK)};bar.addView(btn("←"){if(web.canGoBack())web.goBack()else finish()});bar.addView(btn("→"){if(web.canGoForward())web.goForward()});bar.addView(btn("↻"){web.reload()});address=EditText(this@ForgeBrowserActivity).apply{setSingleLine();setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);hint="https://";imeOptions=EditorInfo.IME_ACTION_GO;setOnEditorActionListener{_,_,_->load(text.toString());true}};bar.addView(address,LinearLayout.LayoutParams(0,-2,1f));bar.addView(btn("⋮"){menu()});addView(bar,LinearLayout.LayoutParams(-1,dp(54)));web=WebView(this@ForgeBrowserActivity).apply{setBackgroundColor(Color.BLACK);settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW;settings.setSupportZoom(true);settings.builtInZoomControls=true;settings.displayZoomControls=false;webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(v:WebView?,r:WebResourceRequest?):Boolean{val u=r?.url?:return false;if(u.scheme=="http"||u.scheme=="https")return false;runCatching{startActivity(Intent(Intent.ACTION_VIEW,u))};return true};override fun onPageFinished(v:WebView?,url:String?){address.setText(url.orEmpty())};override fun onReceivedSslError(v:WebView?,h:SslErrorHandler?,e:SslError?){h?.cancel();Toast.makeText(this@ForgeBrowserActivity,"Certificado HTTPS inválido — conexão bloqueada",Toast.LENGTH_LONG).show()}}};addView(web,LinearLayout.LayoutParams(-1,0,1f))}
 private fun btn(l:String,a:()->Unit)=Button(this).apply{text=l;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{a()}}
 private fun load(raw:String){var u=raw.trim();if(u.isBlank())return;if(!u.contains("://"))u="https://$u";if(u.startsWith("http://"))u="https://"+u.removePrefix("http://");runCatching{web.loadUrl(u)}.onFailure{Toast.makeText(this,"URL inválida",Toast.LENGTH_SHORT).show()}}
 private fun menu(){val items=arrayOf("Compartilhar","Copiar URL","Localizar na página","Modo ${if(desktop)"mobile" else "desktop"}","Abrir externamente");AlertDialog.Builder(this).setTitle("Forge Web").setItems(items){_,w->when(w){0->startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,web.url),"Compartilhar"));1->{getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL",web.url));Toast.makeText(this,"URL copiada",Toast.LENGTH_SHORT).show()};2->{val i=EditText(this);AlertDialog.Builder(this).setTitle("Localizar").setView(i).setPositiveButton("Buscar"){_,_->web.findAllAsync(i.text.toString())}.setNegativeButton("Cancelar",null).show()};3->{desktop=!desktop;val ua=web.settings.userAgentString;web.settings.userAgentString=if(desktop)ua.replace("Mobile","Desktop").replace("Android","X11; Linux x86_64") else android.webkit.WebSettings.getDefaultUserAgent(this);web.reload()};4->web.url?.let{runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it)))}}}}.show()}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt();companion object{const val EXTRA_URL="url"}
}
