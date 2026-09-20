# Changelog

## 0.3.1

- Android Gradle Plugin 9.4.0, Gradle 9.6.1 e CI com Temurin JDK 25 LTS.
- Kotlin integrado do AGP 9.x; bytecode Android mantido em JVM 17.
- compileSdk/targetSdk 37, AndroidX Core KTX 1.19.0 e coroutines 1.11.0.
- bookmarks persistentes para localizações Direct, SAF e Archive, com migração do formato legado.
- edição de permissões Unix `chmod` via root autorizado, modo octal validado e informações owner/group.
- comparador de texto, ícones por tipo, ZIP seguro, APK/DEX e demais melhorias acumuladas das revisões anteriores.
- GitHub Actions executa testes unitários, gera APK debug e publica artifact.

## 0.2.0

- interface dual-pane redesenhada, classificação visual de arquivos e barra inferior com ícones;
- criação/renomeação/exclusão/duplicação/troca de nomes e operações painel-a-painel;
- compactação ZIP, hashes MD5/SHA-1/SHA-256/CRC32, ordenação e atalhos de armazenamento;
- backends direto, SAF, Shizuku, root e archive; editores de texto/hex; inspeção APK/DEX.
