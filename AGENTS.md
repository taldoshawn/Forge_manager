# Regras do projeto

## Arquitetura

- UI em `features/`; acesso a arquivo somente por `FileBackend` e `AccessResolver`.
- Nunca espalhar `File(...)` por novas telas para operações gerenciadas.
- Backends privilegiados são opcionais e nunca aparecem como “níveis”.
- Shizuku usa `UserService` e AIDL; root somente após consentimento explícito.
- Arquivos ZIP são entrada não confiável: toda entrada passa por `ZipSecurity`.

## Build e testes

```bash
./gradlew testDebugUnitTest assembleDebug
```

Antes de marcar uma feature como concluída: código integrado, erros tratados, build verde e teste razoável.

## Segurança

- Não concatenar caminho cru em shell. Use `ShellEscaper.quote` e comandos fixos.
- Nunca seguir symlink durante exclusão recursiva ou extração.
- Nunca substituir sem política explícita.
- Nunca registrar caminhos sensíveis, conteúdo, credenciais ou tokens em produção.
- Componentes Android ficam `exported=false`, exceto launcher e o provider Shizuku protegido pela permissão oficial.
- Não adicionar TrustManager permissivo, HTTP claro, `file://` ou segredo no APK.

## Partes frágeis

- Mudanças no AIDL do Shizuku exigem teste em Shizuku iniciado por ADB e por root/Sui.
- SAF varia por DocumentsProvider/OEM; preserve as URI permissions persistentes.
- Root shell varia entre implementações de `su` e toybox; mantenha timeout e escaping.
- Android/data é bloqueado pela plataforma em vários cenários; nunca simule acesso.
