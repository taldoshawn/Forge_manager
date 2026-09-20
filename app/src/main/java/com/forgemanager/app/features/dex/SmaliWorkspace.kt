package com.forgemanager.app.features.dex

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.Closeable
import java.io.File

class SmaliWorkspace private constructor(
    val source: File,
    val root: File,
    val dexUnits: List<DexUnit>
) : Closeable {
    data class DexUnit(
        val entryName: String,
        val smaliDirectory: File,
        val rebuiltDex: File
    )

    fun allSmaliFiles(): List<File> = dexUnits.flatMap { unit ->
        unit.smaliDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("smali", true) }
            .toList()
    }.sortedBy { it.relativeTo(root).path.lowercase() }

    fun rebuild(): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>(dexUnits.size)
        for (unit in dexUnits) {
            unit.rebuiltDex.parentFile?.mkdirs()
            if (unit.rebuiltDex.exists()) unit.rebuiltDex.delete()
            val options = SmaliOptions().apply {
                apiLevel = 35
                jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
                outputDexFile = unit.rebuiltDex.path
                verboseErrors = true
            }
            val ok = Smali.assemble(options, listOf(unit.smaliDirectory.path))
            check(ok && unit.rebuiltDex.isFile) { "Falha ao reconstruir ${unit.entryName}" }
            result += unit.entryName to unit.rebuiltDex
        }
        return result
    }

    override fun close() {
        runCatching { root.deleteRecursively() }
    }

    companion object {
        fun open(source: File, cacheDir: File): SmaliWorkspace {
            require(source.isFile) { "Arquivo DEX/APK não encontrado" }
            val root = File(cacheDir, "smali-${source.nameWithoutExtension}-${System.nanoTime()}")
            require(root.mkdirs()) { "Não foi possível criar workspace Smali" }
            try {
                val container = DexFileFactory.loadDexContainer(source, null)
                val names = container.dexEntryNames
                require(names.isNotEmpty()) { "Nenhum DEX encontrado" }
                require(names.size <= 64) { "Quantidade de DEX excessiva" }
                val units = ArrayList<DexUnit>(names.size)
                for ((index, entryName) in names.withIndex()) {
                    val entry = container.getEntry(entryName) ?: error("DEX ausente: $entryName")
                    val safeBase = entryName.substringAfterLast('/').removeSuffix(".dex").ifBlank { "classes${index + 1}" }
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val smaliDir = File(root, "smali/$safeBase")
                    val rebuilt = File(root, "rebuilt/${entryName.substringAfterLast('/').ifBlank { "classes.dex" }}")
                    require(smaliDir.mkdirs()) { "Não foi possível criar diretório Smali" }
                    disassemble(entry.dexFile, smaliDir)
                    units += DexUnit(entryName, smaliDir, rebuilt)
                }
                return SmaliWorkspace(source, root, units)
            } catch (error: Throwable) {
                root.deleteRecursively()
                throw error
            }
        }

        private fun disassemble(dexFile: DexFile, output: File) {
            val options = BaksmaliOptions().apply {
                apiLevel = 35
                debugInfo = true
                parameterRegisters = true
                localsDirective = false
                sequentialLabels = false
                codeOffsets = false
            }
            val ok = Baksmali.disassembleDexFile(
                dexFile,
                output,
                Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
                options,
                null
            )
            check(ok) { "Baksmali não conseguiu desmontar o DEX" }
        }
    }
}
