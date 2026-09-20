# Arquitetura

## Fluxo de acesso

`MainActivity / editor / ferramentas` → `FileRepository conceitual (AccessResolver + FileOperations)` → backend adequado.

O `AccessResolver` escolhe:

1. local direto quando o processo possui acesso real;
2. SAF para uma URI concedida pelo usuário;
3. Shizuku se o binder, permissão e UserService estiverem ativos;
4. root somente após autorização explícita.

Não há sistema visual de níveis. Uma falha somente é mostrada depois de esgotar capacidades já autorizadas.

## Backends

- `DirectFileBackend`: I/O por streaming, exclusão sem seguir symlink.
- `SafFileBackend`: `DocumentsContract`, permissão persistente e operações do provider.
- `ShizukuFileBackend`: AIDL tipado e `ParcelFileDescriptor`; o serviço roda com UID shell/root fornecido pelo Shizuku.
- `RootFileBackend`: `su -c` opt-in, comandos fixos, escaping central, timeout, stdout/stderr separados.
- `ArchiveFileBackend`: ZIP/JAR/APK como árvore, mutação por arquivo temporário + substituição atômica.

## Estado dual-pane

`DualPaneController` mantém para cada painel: localização, pilhas voltar/avançar, itens, seleção, ordenação, filtro, ocultos e scroll. O painel tocado por último é ativo; copiar/mover usa o outro painel como destino.

## Limites deliberados

O editor de texto libera edição até 8 MiB e oferece prévia limitada acima disso. O hex trabalha em páginas de 4 KiB. DEX possui limites por entrada e total para evitar OOM/ZIP bomb. Rebuild Smali/AXML/ARSC ainda não está presente.
