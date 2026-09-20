# Pesquisa

Data: 20/09/2026.

## MT Manager

O manual oficial confirma: dois painéis; copiar/mover/extrair/adicionar diretamente para o painel oposto; sincronização; seleção por deslize, faixa contínua, selecionar tudo/inverter/tipo; bookmarks; filtros comuns, negados e regex; salto de caminho; ZIP incremental; editor de texto multi-arquivo; Hex; comparadores texto/DEX/ARSC/AXML/ZIP/pasta; DEX multi-file, Smali, navegação, análise de registradores, Java aproximado e buscas; AXML; ARSC; assinatura, tradução e plugins.

Fontes principais:

- [Manual rápido oficial](https://mt.cc/guide/)
- [ZIP oficial](https://mt.cc/guide/file/archive-file.html)
- [Editor de texto oficial](https://mt.cc/guide/file/text-editor.html)
- [Hex oficial](https://mt.cc/guide/file/hex-editor.html)
- [Comparadores oficiais](https://mt.cc/guide/file/file-comparator.html)
- [APK oficial](https://mt.cc/guide/reverse/apk.html)
- [DEX oficial](https://mt.cc/guide/reverse/dex.html)
- [AXML oficial](https://mt.cc/guide/reverse/xml.html)
- [ARSC oficial](https://mt.cc/guide/reverse/arsc.html)

O projeto não copia código, ícones ou assets do MT.

## Shizuku

A API oficial 13.1.5 documenta binder listeners, `checkSelfPermission`, `requestPermission`, UID 2000/0 e UserService. Também alerta que shell não acessa automaticamente `/data/user/0/<package>` e que o privilégio varia por Android/SELinux.

Fonte: [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API).

Implementação: provider oficial, listeners de binder/death, permissão contextual, rebind de UserService e AIDL. Incerteza que exige teste físico: disponibilidade de caminhos específicos sob `Android/data` varia por OEM, versão e forma de inicialização do Shizuku.

## Dependências e engenharia reversa

Foi evitada uma dependência grande de decompilação nesta primeira entrega. O parser DEX próprio implementa apenas estruturas documentadas simples (header, string/type/field/method/class IDs), com validação e limites. Não é marcado como Smali/decompilador.

JADX/dexlib2/apksig/aapt2 continuam candidatos para fases posteriores, após validar licença, footprint Android e execução em PHONE AS.
