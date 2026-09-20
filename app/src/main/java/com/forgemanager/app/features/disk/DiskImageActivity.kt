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

class DiskImageActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph by lazy { (application as ForgeApplication).graph }

    private lateinit var location: FileLocation
    private lateinit var name: String
    private lateinit var info: TextView
    private var mountPoint: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        location = intent.readFileLocation() ?: run {
            finish()
            return
        }
        name = intent.fileDisplayName()
            ?: location.displayPath.substringAfterLast('/').ifBlank { "disk.img" }
        setContentView(buildUi())
        inspect()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)

        val bar = LinearLayout(this@DiskImageActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        bar.addView(button("←") { finish() })
        bar.addView(
            TextView(this@DiskImageActivity).apply {
                text = name
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(button("MONTAR RO") { mountReadOnly() })
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)))

        info = TextView(this@DiskImageActivity).apply {
            setTextColor(Color.rgb(220, 226, 235))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        addView(
            ScrollView(this@DiskImageActivity).apply { addView(info) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun inspect() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val backend = graph.resolver.backendFor(location)
                    val node = backend.stat(location)
                    val head = ByteArrayOutputStream()
                    backend.openInput(location).use { input ->
                        val buffer = ByteArray(4096)
                        var remaining = 64 * 1024
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            head.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    node.size to head.toByteArray()
                }
            }.onSuccess { (size, data) ->
                info.text = buildString {
                    append("Arquivo: ").append(name).append('\n')
                    append("Tamanho: ").append(size).append(" bytes\n")
                    append("Formato provável: ").append(detect(data)).append("\n\n")
                    append(hex(data.copyOfRange(0, minOf(512, data.size))))
                }
            }.onFailure { showError(it.message ?: "Falha ao ler imagem") }
        }
    }

    private fun detect(data: ByteArray): String {
        fun ascii(offset: Int, length: Int): String =
            if (offset >= 0 && offset + length <= data.size) {
                String(data, offset, length, StandardCharsets.US_ASCII)
            } else {
                ""
            }

        val extMagic = if (data.size > 1081) {
            (data[1080].toInt() and 0xff) or
                ((data[1081].toInt() and 0xff) shl 8)
        } else {
            -1
        }

        return when {
            data.size >= 4 && le32(data, 0).toUInt() == 0xED26FF3Au -> "Android sparse image"
            ascii(0, 8) == "ANDROID!" -> "Android boot image"
            ascii(0, 4) == "AVB0" -> "Android Verified Boot"
            ascii(3, 8).startsWith("NTFS") -> "NTFS"
            ascii(54, 3) == "FAT" || ascii(82, 3) == "FAT" -> "FAT"
            data.size > 0x8006 && ascii(0x8001, 5) == "CD001" -> "ISO-9660"
            extMagic == 0xEF53 -> "EXT2/3/4"
            else -> "RAW/desconhecida"
        }
    }

    private fun mountReadOnly() {
        val direct = (location as? FileLocation.Direct)?.path?.let(::File)
        if (direct == null) {
            toast("Montagem exige arquivo local direto")
            return
        }

        scope.launch {
            val authorized = if (graph.root.isAuthorized()) {
                true
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { graph.root.authorize() }.getOrDefault(false)
                }
            }
            if (!authorized) {
                toast("Root necessário para montar .img")
                return@launch
            }

            AlertDialog.Builder(this@DiskImageActivity)
                .setTitle("Montar somente leitura?")
                .setMessage(
                    "O Forge usará loop mount com -o ro. Imagens sparse do Android são " +
                        "identificadas, mas não são convertidas automaticamente."
                )
                .setPositiveButton("Montar RO") { _, _ -> doMount(direct) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun doMount(file: File) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val point = File("/data/local/tmp/forge-img-${System.nanoTime()}")
                    val command =
                        "mkdir -p ${ShellEscaper.quote(point.path)} && " +
                            "mount -o ro,loop ${ShellEscaper.quote(file.path)} ${ShellEscaper.quote(point.path)}"
                    val process = ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                    val text = process.inputStream.bufferedReader().readText()
                    if (process.waitFor() != 0) error(text.ifBlank { "mount falhou" })
                    point
                }
            }.onSuccess { point ->
                mountPoint = point
                toast("Montado somente leitura")
                startActivity(
                    Intent(this@DiskImageActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_PATH, point.path)
                )
            }.onFailure { showError(it.message ?: "Falha ao montar") }
        }
    }

    private fun le32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    private fun hex(data: ByteArray): String =
        data.asList().chunked(16).mapIndexed { index, row ->
            "%08x  %s".format(
                index * 16,
                row.joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
            )
        }.joinToString("\n")

    private fun showError(message: String) = AlertDialog.Builder(this)
        .setTitle("Disk Image")
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
