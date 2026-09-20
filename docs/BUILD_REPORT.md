# Relatório de build — 20/09/2026

Ambiente usado: Linux x64, Temurin/OpenJDK 17.0.20.1, Android SDK 36, Build Tools 35.0.0, AGP 8.10.1 e Gradle 8.11.1.

Comando executado:

```bash
gradle testDebugUnitTest assembleDebug lintDebug
```

Resultado: `BUILD SUCCESSFUL`.

- testes JVM: 9 executados, 0 falhas, 0 erros, 0 ignorados;
- lint: concluído sem erro fatal; avisos não bloqueantes permanecem documentados pelo relatório local de build;
- APK debug: 2,6 MiB;
- `apksigner verify`: válido com APK Signature Scheme v2, um certificado Android Debug;
- `aapt dump badging`: `com.forgemanager.app`, versionCode 1, versionName 0.1.0, minSdk 26, targetSdk 36, compileSdk 36.

Não executado: instalação/teste instrumentado, pois não havia dispositivo ou emulador conectado. SAF por OEM, Shizuku ADB/Sui e root/Magisk precisam de validação física antes de release.
