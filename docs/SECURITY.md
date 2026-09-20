# Segurança

## Modelo de ameaça

O app manipula conteúdo controlado pelo usuário e pode receber privilégios elevados. Arquivos, nomes, ZIPs, APKs, providers SAF e saída de shell são não confiáveis.

## Controles implementados

- nomes rejeitam vazio, `.`, `..`, `/`, NUL e mais de 255 caracteres;
- cópia por streaming e cancelamento estruturado; destino parcial é removido em erro;
- symlinks não são seguidos na exclusão e não são copiados automaticamente;
- entradas ZIP absolutas, `..`, barra invertida perigosa e saída canônica fora do destino são bloqueadas;
- mutação ZIP usa temporário, `fsync` e rename atômico quando suportado;
- root exige consentimento; cada argumento passa por quoting POSIX testado; processos têm timeout;
- Shizuku usa AIDL tipado e PFD, não concatenação shell;
- `FileProvider` usa grants temporários; nenhum `file://` é compartilhado;
- backup está desabilitado; cleartext HTTP está desabilitado;
- senha/keystore/secret não são persistidos porque assinatura ainda não foi implementada;
- erros de UI não exibem stack trace.

## Riscos residuais

- implementações de `su`, toybox e políticas SELinux variam por ROM;
- DocumentsProviders podem violar parcialmente o contrato ou revogar acesso;
- APK/DEX hostis podem consumir CPU; há limites de tamanho, mas fuzzing contínuo é recomendado;
- `QUERY_ALL_PACKAGES` e `MANAGE_EXTERNAL_STORAGE` exigem justificativa de política em distribuição pela Play.

Reporte falhas sem anexar credenciais, conteúdo privado ou logs completos de caminhos pessoais.
