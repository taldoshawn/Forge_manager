# Revisão 0.3.1 — GitHub CI + paridade adicional

- base moderna mantida em Android Gradle Plugin 9.4.0 e Gradle 9.6.1;
- GitHub Actions usa Temurin JDK 25 LTS para executar o Gradle;
- bytecode do app permanece JVM 17, seguindo o alvo Android estável e o Kotlin integrado do AGP 9.x;
- AndroidX Core KTX atualizado para 1.19.0;
- bookmarks agora persistem `Direct`, `SAF` e `Archive`, com migração dos bookmarks antigos baseados apenas em caminho;
- edição `chmod` via root adicionada com autorização explícita, validação estrita de modo octal e escaping de caminho;
- informações root exibem permissões simbólicas, modo octal e owner/group;
- workflow de CI executa testes unitários e `assembleDebug`, publicando o APK como artifact.
