# 🛠️ Prompt de Correção + Expansão — Debloat Real Completo (Fase 8)

Copie e cole o texto abaixo no chat do Claude Code, dentro da pasta do projeto:

---

Vamos corrigir um bug encontrado e depois expandir o escopo de debloat do NITRO BOOST. Leia primeiro o novo documento `NITRO-BOOST-fase8-debloat-completo.md`, que está na raiz do projeto, por completo antes de mexer em qualquer código.

## Parte 1 — Correção de bug (prioridade máxima, fazer antes de tudo)

A categoria "Bloatware" na tela de resultados está mostrando poucos itens porque `SystemScanTask.scanBloatware()` chama `BloatwareScanner.knownBloatware()`, método que descarta silenciosamente qualquer app com categoria `UNKNOWN`. Isso faz o usuário ver só ~15 apps de ~134 instalados de verdade.

1. Troque a chamada em `SystemScanTask.scanBloatware()` para usar `BloatwareScanner.scan()` (retorna todos os apps).
2. Confirme que o fluxo de classificação (`SystemScanTask.build()` → `KnowledgeBase.find()`) já trata apps não catalogados corretamente (deve continuar mostrando "Item ainda não catalogado" para o que não está no JSON — não precisa mudar essa parte se já funciona).
3. Adicione um `CheckBox` ou `ToggleButton` na `ScanResultsView`, na área de filtros, com o texto "Mostrar apenas itens conhecidos" — **ligado por padrão** (reduz ruído visual mostrando só os catalogados), mas que o usuário pode desligar para ver a lista completa de todos os apps instalados.
4. Teste isoladamente antes de seguir: rode a tela de resultados e confirme que, com o toggle desligado, aparecem próximo dos ~134 apps reais da máquina na categoria Bloatware.
5. Registre essa correção no `PROGRESS.md` como "Fase 8 - Correção".

## Parte 2 — Expansão: Debloat Real Completo incluindo IAs do Windows 11

Depois da correção acima validada, siga a checklist da seção 5 do documento `NITRO-BOOST-fase8-debloat-completo.md`, na ordem apresentada:

1. Criar `AiFeatureScanner.java` (Copilot, Recall, Click to Do, Cocreator, Copilot no Edge) conforme a tabela da seção 1.
2. Criar `ConsumerFeatureScanner.java` (anúncios, sugestões, pesquisa Bing, notificações) conforme a tabela da seção 2.
3. Expandir `ActionExecutor` com os métodos de ação para os novos itens, seguindo exatamente o mesmo padrão de segurança já usado (checar `LockManager.isLocked` → `BackupManager.snapshotBeforeAction` → aplicar → registrar em `actions_history`) — **nenhuma exceção a essa regra**, mesmo para os itens novos.
4. Expandir `knowledge-base.json` com uma entrada por item das tabelas do documento, usando a orientação de classificação da seção 6 (atenção especial ao Recall — a descrição deve mencionar a implicação de privacidade de forma clara e neutra, sem alarmismo, para o usuário decidir com informação).
5. Adicionar as duas novas categorias (IA / Recursos de Consumidor) no `SystemScanTask` e no filtro de categoria da `ScanResultsView`.
6. Testar cada scanner novo isoladamente via console antes de conectar à UI (regra de ouro #9 do projeto).
7. Ao final, marcar os itens da checklist da Fase 8 e atualizar `PROGRESS.md` com o resumo completo, incluindo quais chaves de registro podem variar entre versões do Windows (documentar essa limitação, não escondê-la).

## Regras que continuam valendo (reforço)

- Nunca implemente uma ação de desativar/desinstalar sem o backup correspondente já funcionando para aquele tipo de item.
- Se qualquer chave de registro documentada no arquivo não existir na sua máquina de teste (por causa de versão/build diferente do Windows), trate como "não definido" (não é erro) e registre a divergência em `BLOCKERS.md` para eu saber que aquela chave específica pode precisar de ajuste na minha versão do Windows.
- Novos itens no `knowledge-base.json` seguem o mesmo formato exato já usado (nome, tipo, classificação, descrição, impacto_desativar, impacto_manter), em português claro.

Comece pela Parte 1 (correção do bug) e me avise quando estiver validada antes de seguir para a Parte 2.
