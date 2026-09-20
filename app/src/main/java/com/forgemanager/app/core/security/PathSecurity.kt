package com.forgemanager.app.core.security

import java.io.File
import java.nio.file.Path

object PathSecurity {
    fun validateName(name: String) {
        require(name.isNotBlank()) { "O nome não pode estar vazio" }
        require(name != "." && name != "..") { "Nome reservado" }
        require(!name.contains('/') && !name.contains('\u0000')) { "Nome inválido" }
        require(name.length <= 255) { "Nome muito longo" }
    }

    fun resolveChild(parent: File, name: String): File {
        validateName(name)
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, name).canonicalFile
        require(child.parentFile == canonicalParent) { "Caminho fora da pasta de destino" }
        return child
    }

    fun isWithin(root: Path, candidate: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        return normalizedCandidate.startsWith(normalizedRoot)
    }
}
