# Changelog

## 0.3.0

- AGP 9.4.0, Gradle 9.6.1, SDK 37 e CI em JDK 25 LTS.
- Migração para Kotlin integrado do AGP 9.x.
- AndroidX Core 1.18.0 e coroutines 1.11.0.
- Comparador de texto com diff por linhas e fallback seguro para entradas grandes.
- Workflow de GitHub Actions gera APK debug como artifact.

## 0.2.1

- Compatibilidade do wrapper com PHONE AS usando JDK 18 quando o ambiente ainda aponta para Java 8.
- Detecção segura de JDK 17+ e override `FORGEMANAGER_JAVA_HOME`.
- Bytecode Java/Kotlin mantido em 17.

## 0.2.0

- interface dual-pane redesenhada e preparada para edge-to-edge, com painel ativo destacado;
- ícones vetoriais e classificação visual para pastas e principais categorias de arquivo;
- barra inferior convertida para ações com ícones, sem botões de texto improvisados;
- criação de arquivo/pasta corrigida para um fluxo em duas etapas;
- abertura inteligente de código/texto, DEX, mídia/PDF e contêineres ZIP compatíveis;
- compactação ZIP para o outro painel com proteção contra saída dentro da própria árvore de origem;
- hashes MD5, SHA-1, SHA-256 e CRC32;
- duplicação no mesmo painel, troca segura de nomes entre dois itens e cópia de nome/caminho;
- ordenação por tipo, atalhos para Downloads/Android/obb/raiz e melhorias de informações de arquivo;
- teste unitário adicionado para classificação de tipos de arquivo.

## 0.1.0

- primeira base compilável do Forge Manager;
- explorer dual-pane e backends direto/SAF/Shizuku/root/archive;
- operações entre painéis, editores de texto/hex, inspeção APK/DEX e apps instalados;
- documentação de armazenamento, segurança, pesquisa e paridade.
- build debug, lint e 9 testes unitários validados; APK conferido com apksigner/aapt.
