package com.forgemanager.app.features.explorer

enum class FileKind {
    DIRECTORY, APK, ARCHIVE, IMAGE, VIDEO, AUDIO, CODE, SCRIPT, WEB, MARKDOWN, TEXT, DOCUMENT, PDF,
    DEX, XML, BINARY_RESOURCE, DISK_IMAGE, DATABASE, FONT, SPREADSHEET, PRESENTATION, CONFIG,
    CERTIFICATE, EXECUTABLE, GENERIC
}

object FileTypeClassifier {
    private val archives=setOf("zip","jar","aar","7z","rar","tar","gz","tgz","bz2","xz","zst","epub")
    private val images=setOf("png","jpg","jpeg","jpe","webp","gif","bmp","wbmp","heic","heif","avif","svg","ico","tif","tiff")
    private val videos=setOf("mp4","mkv","webm","avi","mov","m4v","3gp","ts","m2ts","flv","mpeg","mpg")
    private val audio=setOf("mp3","m4a","aac","flac","wav","ogg","opus","amr","mid","midi","aiff","ape")
    private val scripts=setOf("sh","bash","zsh","fish","bat","cmd","ps1")
    private val web=setOf("html","htm","xhtml","css","scss","sass","less","vue","svelte")
    private val markdown=setOf("md","markdown","mdown","mkd")
    private val code=setOf("kt","kts","java","smali","js","mjs","cjs","ts","tsx","jsx","py","rb","php","go","rs","c","cc","cpp","cxx","h","hpp","cs","swift","dart","lua","sql","r","scala","groovy","gradle")
    private val text=setOf("txt","log","csv","ini","cfg","conf","properties","prop")
    private val documents=setOf("doc","docx","odt","rtf","pages")
    private val xml=setOf("xml","xsl","xslt","plist")
    private val binaryResources=setOf("arsc","axml")
    private val diskImages=setOf("img","iso","simg","raw")
    private val databases=setOf("db","sqlite","sqlite3","realm","mdb","accdb")
    private val fonts=setOf("ttf","otf","woff","woff2")
    private val spreadsheets=setOf("xls","xlsx","ods","numbers")
    private val presentations=setOf("ppt","pptx","odp","key")
    private val configs=setOf("json","json5","yaml","yml","toml","env","editorconfig","lock")
    private val certificates=setOf("pem","cer","crt","der","p12","pfx","jks","bks","keystore")
    private val executables=setOf("so","elf","bin","exe","msi","appimage")
    fun classify(name:String,isDirectory:Boolean):FileKind{if(isDirectory)return FileKind.DIRECTORY;val ext=extensionOf(name);return when{ext in setOf("apk","apks","xapk","apkm","aab")->FileKind.APK;ext in setOf("dex","vdex","odex")->FileKind.DEX;ext=="pdf"->FileKind.PDF;ext in binaryResources->FileKind.BINARY_RESOURCE;ext in diskImages->FileKind.DISK_IMAGE;ext in archives->FileKind.ARCHIVE;ext in images->FileKind.IMAGE;ext in videos->FileKind.VIDEO;ext in audio->FileKind.AUDIO;ext in scripts->FileKind.SCRIPT;ext in web->FileKind.WEB;ext in markdown->FileKind.MARKDOWN;ext in code->FileKind.CODE;ext in xml->FileKind.XML;ext in databases->FileKind.DATABASE;ext in fonts->FileKind.FONT;ext in spreadsheets->FileKind.SPREADSHEET;ext in presentations->FileKind.PRESENTATION;ext in documents->FileKind.DOCUMENT;ext in configs->FileKind.CONFIG;ext in certificates->FileKind.CERTIFICATE;ext in executables->FileKind.EXECUTABLE;ext in text||name.equals("README",true)||name.equals("LICENSE",true)||name.equals("NOTICE",true)||name.equals("CHANGELOG",true)->FileKind.TEXT;else->FileKind.GENERIC}}
    fun extensionOf(name:String):String{if(name.startsWith('.')&&name.count{it=='.'}==1)return"";return name.substringAfterLast('.',"").lowercase()}
    fun shortLabel(kind:FileKind,name:String)=when(kind){FileKind.DIRECTORY->"Pasta";FileKind.APK->"Pacote Android";FileKind.ARCHIVE->extensionOf(name).uppercase().ifBlank{"Compactado"};FileKind.IMAGE->extensionOf(name).uppercase().ifBlank{"Imagem"};FileKind.VIDEO->"Vídeo";FileKind.AUDIO->"Áudio";FileKind.CODE->extensionOf(name).uppercase().ifBlank{"Código"};FileKind.SCRIPT->extensionOf(name).uppercase().ifBlank{"Script"};FileKind.WEB->extensionOf(name).uppercase().ifBlank{"Web"};FileKind.MARKDOWN->"Markdown";FileKind.TEXT->"Texto";FileKind.DOCUMENT->"Documento";FileKind.PDF->"PDF";FileKind.DEX->"DEX / Smali";FileKind.XML->extensionOf(name).uppercase().ifBlank{"XML"};FileKind.BINARY_RESOURCE->"Android Resource";FileKind.DISK_IMAGE->"Imagem de disco";FileKind.DATABASE->"Banco";FileKind.FONT->"Fonte";FileKind.SPREADSHEET->"Planilha";FileKind.PRESENTATION->"Apresentação";FileKind.CONFIG->extensionOf(name).uppercase().ifBlank{"Config"};FileKind.CERTIFICATE->"Certificado";FileKind.EXECUTABLE->"Binário";FileKind.GENERIC->extensionOf(name).uppercase().ifBlank{"Arquivo"}}
}
