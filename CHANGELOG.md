# Changelog

## 0.5.0

- tema AMOLED preto real e novo ícone de aplicativo fornecido pelo autor;
- ícones gráficos por tipo de arquivo, incluindo pasta, imagem/SVG, ZIP/RAR, código, texto e PDF;
- Smali/DEX Studio: baksmali, edição `.smali`, rebuild DEX com smali e gravação pelo backend, inclusive em entradas de APK/ZIP;
- editor conservador de string pools AXML/resources.arsc com edição in-place e Resource Studio para APK;
- APK Toolbox com ZIP alignment, assinatura local v1/v2/v3/v4, `.idsig` v4 e verificação por apksig;
- terminal interativo baseado em PTY nativa, resize, sinais e teclas Esc/Tab/Ctrl-C/Ctrl-D/setas;
- Forge Web com navegação, compartilhamento, copiar URL, localizar na página e modo desktop;
- inspeção de `.img`, `.raw`, `.simg` e `.iso`, reconhecimento de formatos e montagem root somente leitura para imagens montáveis;
- GitHub Actions passa a provisionar NDK 28.2 e CMake 3.22.1 para a PTY nativa.

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
