package com.forgemanager.app.core.shell

object ShellEscaper {
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "NUL não é permitido" }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
