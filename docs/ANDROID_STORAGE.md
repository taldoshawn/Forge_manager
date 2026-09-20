# Armazenamento Android

O Forge Manager usa `compileSdk 37` e `targetSdk 37`.

Acesso é resolvido progressivamente conforme a autorização disponível:

1. acesso direto permitido pelo Android;
2. árvore SAF explicitamente escolhida pelo usuário;
3. Shizuku autorizado;
4. root autorizado.

`MANAGE_EXTERNAL_STORAGE` pode ser solicitado para o caso de uso de gerenciador de arquivos, mas não ignora todas as proteções de dados privados de outros apps. `Android/data` e `Android/obb` continuam sujeitos às regras da versão do Android/OEM; o app não promete bypass sem autorização apropriada.

Root e Shizuku são opcionais. Ações destrutivas continuam exigindo confirmação/seleção no app, e o backend faz validação de nomes/caminhos antes da escrita.
