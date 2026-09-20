# Forge Manager

Gerenciador de arquivos Android nativo em dois painéis, inspirado no fluxo de produtividade do MT Manager, com operações painel-a-painel, edição, ferramentas para APK/DEX, arquivos compactados e acesso progressivo por armazenamento normal, SAF, Shizuku e root autorizado.

## Estado atual — 0.3.0

Implementado e integrado:

- dois painéis independentes com voltar/avançar, seleção por toque/swipe, filtro, regex e ordenação;
- copiar/mover painel-a-painel com conflito substituir/ignorar/renomear e progresso cancelável;
- criar, renomear, excluir, duplicar, trocar nomes, compartilhar, copiar nome/caminho e informações;
- hashes MD5, SHA-1, SHA-256 e CRC32 por streaming;
- acesso direto, SAF persistente, `MANAGE_EXTERNAL_STORAGE`, Shizuku UserService e root opcional;
- navegação ZIP/JAR/APK/AAB/APKS/XAPK/APKM/EPUB, criação de ZIP e mutação segura contra Zip Slip;
- editor de texto com BOM/encoding, busca, regex, substituir e undo/redo;
- visualizador/editor hexadecimal paginado;
- comparador de texto com diff de linhas e fallback limitado para entradas grandes;
- inspeção de APK (componentes, permissões, certificados, DEX e libs nativas);
- listagem de apps instalados e exportação de APK base + splits;
- parser DEX para classes, métodos, campos e strings;
- ícones por tipo de arquivo e interface dual-pane otimizada para telas Android;
- testes unitários de segurança, shell, navegação, classificação e diff.

Ainda não há paridade total com o MT Manager. Smali/rebuild DEX, AXML/ARSC editável, assinatura APK v1-v4, comparadores semânticos DEX/ARSC, 7z/tar completos, terminal/PTTY, plugins e protocolos de rede continuam listados em `docs/MT_PARITY_MATRIX.md` e não são anunciados como prontos.

## Toolchain moderna

- Android Gradle Plugin: 9.4.0
- Gradle Wrapper: 9.6.1 (SHA-256 fixado)
- CI: Temurin JDK 25 LTS
- bytecode Java/Kotlin do app: JVM 17
- compileSdk / targetSdk: 37
- AndroidX Core KTX: 1.18.0
- kotlinx.coroutines: 1.11.0
- Shizuku API: 13.1.5

O JDK usado para **executar o Gradle** pode ser mais novo que o bytecode do app. O alvo JVM 17 é mantido para compatibilidade do ecossistema Android.

## Compilar

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK debug:

```text
app/build/outputs/apk/debug/app-debug.apk
```

O workflow `.github/workflows/android.yml` executa testes e gera o APK automaticamente em pushes para `main`, pull requests e execução manual.

## Segurança

O projeto não confia apenas no frontend para operações privilegiadas. Backends validam nomes/caminhos, recusam cópia automática de symlink, aplicam escape no shell/root, usam SAF persistente quando autorizado e não expõem chaves privadas. Arquivos, ZIPs e APKs são tratados como entrada não confiável.

Veja `docs/SECURITY.md`, `docs/ANDROID_STORAGE.md`, `docs/BUILD_GITHUB.md` e `docs/MT_PARITY_MATRIX.md`.
