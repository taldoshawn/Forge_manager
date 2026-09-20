# Build no GitHub Actions

O CI oficial está em `.github/workflows/android.yml`.

Stack:

- Ubuntu latest;
- Temurin JDK 25 LTS para executar Gradle;
- Gradle 9.6.1 fixado pelo `gradle/actions/setup-gradle`;
- Android Gradle Plugin 9.4.0;
- Android SDK Platform 37;
- Build Tools 36.0.0;
- bytecode do app em JVM 17.

O workflow executa:

```bash
gradle --no-daemon --stacktrace testDebugUnitTest assembleDebug
```

Em caso de sucesso, publica `app/build/outputs/apk/debug/app-debug.apk` como artifact por 14 dias.

Permissões do `GITHUB_TOKEN` ficam em `contents: read`; a build não recebe permissão de escrita no repositório e não usa secrets para o APK debug.

Para release assinado, não commite keystore ou senhas. Use GitHub Actions Secrets/Variables e materialize a keystore apenas durante o job.
