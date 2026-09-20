package com.forgemanager.app.features.dex

import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File

object SmaliEngine {
    fun disassemble(dexFile: File, outputDir: File, apiLevel: Int): Int {
        require(dexFile.isFile) { "DEX não encontrado" }
        if (outputDir.exists()) outputDir.deleteRecursively()
        require(outputDir.mkdirs() || outputDir.isDirectory) { "Não foi possível criar o workspace smali" }
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.forApi(apiLevel))
        val options = BaksmaliOptions().apply {
            debugInfo = true
            parameterRegisters = true
            localsDirective = false
            sequentialLabels = false
            codeOffsets = false
            implicitReferences = false
            allowOdex = false
        }
        val jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        check(Baksmali.disassembleDexFile(dex, outputDir, jobs, options)) { "Baksmali não conseguiu desmontar o DEX" }
        return outputDir.walkTopDown().count { it.isFile && it.extension.equals("smali", true) }
    }

    fun assemble(smaliDir: File, outputDex: File, apiLevel: Int): File {
        require(smaliDir.isDirectory) { "Workspace smali não encontrado" }
        outputDex.parentFile?.mkdirs()
        if (outputDex.exists()) outputDex.delete()
        val options = SmaliOptions().apply {
            this.apiLevel = apiLevel
            this.outputDexFile = outputDex.path
            this.jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            this.verboseErrors = true
            this.allowOdexOpcodes = false
        }
        check(Smali.assemble(options, listOf(smaliDir.path))) { "Smali encontrou erros; corrija os arquivos antes de reconstruir" }
        require(outputDex.isFile && outputDex.length() > 0) { "DEX reconstruído vazio" }
        return outputDex
    }
}
