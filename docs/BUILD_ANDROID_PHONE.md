# Build no Android / PHONE AS

A source principal usa toolchain moderna (AGP 9.4 + Gradle 9.6.1). O ambiente antigo do PHONE AS mostrado durante o desenvolvimento possuía apenas Java 8, portanto não é usado como ambiente oficial de compilação.

## Caminho recomendado

Edite os arquivos no celular e envie para o GitHub. O workflow `.github/workflows/android.yml` compila com JDK 25 LTS e Gradle 9.6.1 e publica o APK debug como artifact.

## Build local opcional

Se o ambiente Android tiver JDK 17+ e Gradle 9.6.1 instalados:

```bash
gradle testDebugUnitTest assembleDebug
```

Não rebaixe AGP/Gradle/Kotlin para Java 8 apenas para caber em uma IDE antiga. Isso perde compatibilidade com a toolchain Android atual e aumenta a dívida técnica.
