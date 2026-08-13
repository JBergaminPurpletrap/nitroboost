# TESTING — NITRO BOOST

Registro dos testes de validação da Fase 12 - Parte B, conforme
`docs/NITRO-BOOST-fase12-debloat-final-e-testes.md`. Cobre a suíte automatizada (JUnit 5, seção
B.1) e o checklist de teste manual/smoke test (seção B.2).

Este arquivo fica na raiz do projeto (não em `docs/`) de propósito, conforme pedido explícito da
documentação da Fase 12 - para ficar visível de imediato para quem abrir o repositório.

> **Nota:** este documento é o registro histórico da rodada de testes da Fase 12 (data abaixo). A
> suíte JUnit cresceu nas fases seguintes (Fases 14 e 15 adicionaram testes de `SystemAuditEngine`,
> `DisplayScanner`, `SystemFileRepairTool` e `NetworkRepairTool`) — o total atual é **75 testes**,
> todos passando (`.\mvnw.cmd test`). Ver `README.md` → seção "Testes" para o resumo sempre
> atualizado, e `docs/PROGRESS.md` para o detalhamento de cada fase.

**Ambiente desta rodada de testes (2026-07-26):** sessão de console **sem** privilégio de
Administrador (confirmado via `IsInRole(...Administrator)` → `False`) e **sem sessão gráfica
interativa** para abrir a UI JavaFX (`javafx:run` nunca é executado de forma síncrona neste
ambiente - mesma regra de ouro documentada desde a Fase 0). Essas duas limitações já eram
conhecidas e estão documentadas em `docs/BLOCKERS.md` (itens 2, 6, 7, 9, 10) - este documento não
repete o diagnóstico técnico de cada uma, só referencia onde relevante.

---

## B.1 — Suíte automatizada (JUnit 5)

**Resultado: 40 testes, 100% de sucesso.**

```
./mvnw test
...
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Classes de teste criadas (`src/test/java/com/nitroboost/...`):

| Classe | O que cobre |
|---|---|
| `knowledge.KnowledgeBaseTest` | Carrega o `knowledge-base.json` real do classpath; busca item existente (`Spooler`, `Fax`) confirmando classificação/descrição; busca item inexistente confirmando `Optional` vazio; tolerância a sufixo `.exe` |
| `knowledge.ItemClassificationTest` | `fromLabel()` para "seguro"/"essencial"/"depende", tolerância a espaço/maiúsculas, valor desconhecido e `null` caindo no padrão `DEPENDE` |
| `updates.VendorLinkStrategyTest` | `VendorLinkStrategy.resolve()` para ASUS/MSI/Gigabyte/ASRock a partir do texto real da OSHI, fallback `GenericLinkStrategy` para fabricante desconhecido/nulo |
| `core.ServiceScannerParsingTest` | `ServiceScanner.parseJson()` com JSON de exemplo fixo (array e objeto solto - caso especial do `ConvertTo-Json` com 1 item), JSON vazio/nulo/inválido sem lançar exceção |
| `core.TaskSchedulerScannerParsingTest` | `TaskSchedulerScanner.parseCsvOutput()` com CSV de exemplo fixo simulando `schtasks /query /fo CSV`, incluindo cabeçalho repetido (bug real da Fase 3) e cabeçalho em português |
| `core.PowerPlanScannerParsingTest` | `PowerPlanScanner.parseListOutput()` com saída de exemplo em inglês e português (regressão do bug real da Fase 3 - texto fixo "Power Scheme GUID:" só existia em inglês) |
| `audit.SystemAuditEngineTest` | `dwordValuesEqual()` (hex vs decimal, formatos inesperados como string vazia/não-numérica sem lançar exceção) |
| `audit.AuditReportTest` | `totalOptimized()`, `totalApplicable()`, `totalFindings()`, `findingsByCategory()` (agrupamento + ordem de inserção) com `AuditFinding` simulados |
| `ui.SystemScanTaskTest` | `SystemScanTask.buildItem()` (extraído como `static` desde a Fase 9) para item catalogado vs fallback "não catalogado" |

### Refatorações feitas para viabilizar os testes (sem alterar o contrato público)

- `ServiceScanner.parseJson(String)`, `TaskSchedulerScanner.parseCsvOutput(String)` (extraído de
  dentro de `scan()`) e `PowerPlanScanner.parseListOutput(String)` (idem) tiveram a visibilidade
  mudada de `private` para pacote (nenhuma delas é `public`) - permite testar o parsing puro com
  strings fixas, sem tocar em `ProcessBuilder`/comando real. `scan()` continua com a mesma
  assinatura e comportamento de antes, só delega para o método extraído.
- `pom.xml`: adicionada a dependência `org.junit.jupiter:junit-jupiter:5.11.4` (escopo `test`) e o
  plugin `maven-surefire-plugin:3.5.2` (versão explícita compatível com JUnit 5 - não havia
  nenhuma versão declarada antes).

### Bug real encontrado e corrigido durante a escrita dos testes

Ao escrever o teste de `KnowledgeBase` e escolher um item conhecido para confirmar a classificação
correta, foi encontrado que `src/main/resources/knowledge-base.json` continha **7 entradas
duplicadas** (mesmo `"nome"` aparecendo duas vezes): `DiagTrack`, `PcaSvc`, `RetailDemo`,
`MapsBroker`, `Fax`, `TabletInputService`, `XblAuthManager`. Na maioria dos casos as duas cópias
tinham a mesma classificação (só descrição reescrita com palavras diferentes - inofensivo, mas
dado morto no arquivo). Em **um caso a classificação divergia de fato**: `MapsBroker` aparecia como
`"seguro"` na primeira ocorrência e `"depende"` na segunda - como `KnowledgeBase` usa
`putIfAbsent` (primeira ocorrência vence), o comportamento real do app já era determinístico
("seguro"), mas o arquivo continha uma inconsistência de dados que poderia confundir qualquer
manutenção futura da base de conhecimento.

**Correção aplicada:** removidas as 7 entradas duplicadas de `knowledge-base.json`, mantendo a
primeira ocorrência de cada uma (a que já era efetivamente usada). Total de itens catalogados caiu
de 128 para **121** (nenhuma informação foi perdida - apenas dados redundantes/conflitantes
removidos). Confirmado com `./mvnw -q compile`/`./mvnw -q test` que nada quebrou.

---

## B.2 — Checklist de teste manual (smoke test)

Legenda: **Verificado (console)** = comparação direta entre a saída do scanner do NITRO BOOST e o
comando equivalente do Windows, rodado manualmente nesta mesma sessão. **Bloqueado (ambiente)** =
não verificável nesta sessão por falta de GUI/elevação, limitação já conhecida e documentada em
`docs/BLOCKERS.md`.

| # | Item | Resultado | Método usado | Observações |
|---|---|---|---|---|
| 1 | Processos | Verificado (console) | `ProcessScanner.topByRam(10)` (via `Phase...ProcessScanner` demo) vs `Get-Process \| Sort-Object WS -Descending \| Select-Object -First 10` | Top 10 batem em nome/PID/RAM entre as duas capturas (ex: `java` 1288 MB / PID 5048 nos dois lados; pequenas diferenças de MB entre `msedge`/`claude` são esperadas - capturas em momentos distintos, RAM varia o tempo todo) |
| 2 | Serviços | Verificado (console) | `ServiceScanner.findByName("Spooler")` vs `Get-Service Spooler` | Ambos retornam `Running`/`Automatic` (`Spooler de Impressão`) |
| 3 | Startup | Verificado (console) | `StartupScanner.scan()` vs `Get-CimInstance Win32_StartupCommand` | 7 itens dos dois lados, mesmos nomes/comandos/localização (OneDrive, Microsoft.Lists, Edge AutoLaunch, SecurityHealth, RtkAudUService, WavesSvc, KeePass 2 PreLoad) |
| 4 | Backup/Reversão | Verificado (console), caminho de falha | `ActionExecutor.disableService`/`restoreService` contra o serviço real `MapsBroker` (via `Phase12PartBConsoleDemo`, novo) | Serviço-alvo sugerido pelo documento (`Fax`) **não existe nesta máquina** (`Get-Service Fax` → "Cannot find any service") - documentado como "não aplicável, serviço ausente", substituído por `MapsBroker` (classificado "seguro" na base, já parado/Automatic). `Set-Service` falhou com "Acesso negado" (mesma classe de limitação dos itens 6/7/9/10 de `BLOCKERS.md` - exige Administrador) - confirmado via leitura direta que o serviço não mudou de estado em nenhum momento, e que o backup foi criado corretamente antes da tentativa |
| 5 | Bloqueio (Lock) | Verificado (console) | `LockManager.lockItem("MapsBroker")` → `ActionExecutor.disableService` → confirma recusa → `unlockItem` (via `Phase12PartBConsoleDemo`) | Ação recusada corretamente com mensagem "esta bloqueado (protegido)", nenhum comando executado, serviço intacto |
| 6 | Histórico | Verificado (console) | `ActionHistoryRepository.findRecent(10)` após os testes 4 e 5 | As 5 entradas esperadas (`lock`, `disable` recusado, `unlock`, `disable` falho, `enable` falho) aparecem com timestamp correto (`2026-07-26 17:57:3x`/`17:57:4x`) e `previous_state` coerente |
| 7 | Bloatware | Verificado (console) | `BloatwareScanner.scan().size()` vs `(Get-AppxPackage \| Measure-Object).Count` | Ambos retornam **134** - contagem bate exatamente |
| 8 | IA (Copilot) | Bloqueado (ambiente) | — | Não verificável visualmente nesta máquina - já documentado em `docs/BLOCKERS.md` (item 11) que esta máquina não tem pacote Appx separado do Copilot (integrado ao shell) e que as chaves HKLM de política exigem Administrador para escrever (item 7). Nenhum teste artificial foi forçado aqui |
| 9 | Diagnóstico do Sistema | Verificado (console), round-trip completo | Reexecução de `Phase9Part2ConsoleDemo` (já existente da Fase 9) | Placar antes de aplicar: **5 de 44** itens já otimizados. Aplicado o item "Botão do Copilot na Barra de Tarefas" (`SUGESTAO` → valor `0`): placar mudou para **6 de 44** (`JA_OTIMIZADO`). Revertido via `restoreFromHistory`: placar voltou para **5 de 44**, valor de volta a `(não definido)` - confirma o ciclo completo aplicar→placar muda→reverter→placar volta |
| 10 | Limpeza de RAM | Bloqueado (ambiente) | — | Requer Administrador (`SeProfileSingleProcessPrivilege`), não disponível nesta sessão - já documentado em `docs/BLOCKERS.md` (item 9). Não repetido aqui |
| 11 | BIOS/Drivers | Verificado (console) | `HardwareIdentityScanner.scanBoardIdentity()` vs `Get-CimInstance Win32_BaseBoard`/`Win32_BIOS` | Fabricante="Dell Inc." / Modelo="0XR9NX" batem exatamente dos dois lados (mesma fonte de dados SMBIOS por baixo) |
| 12a | GPU (Fase 12) | Não aplicável (documentado) | `ServiceScanner.findByName(...)` para os 4 serviços de telemetria NVIDIA/AMD (via `Phase12PartAConsoleDemo`) | Nenhum dos 4 serviços existe nesta máquina - notebook sem GPU dedicada NVIDIA/AMD, resultado esperado e já documentado desde a conclusão da Parte A |
| 12b | Edge / Driver Update (Fase 12) | Verificado (console), caminho de falha esperado | Reexecução de `Phase12PartAConsoleDemo` (chaves HKLM reais) | As 4 chaves (`edge_startup_boost`, `edge_background_mode`, `block_driver_update_search`, `block_driver_update_prompt`) falharam ao escrever com "ERRO: Acesso negado" (exigem Administrador, chaves HKLM) - confirmado via leitura direta que nada foi alterado no registro em nenhuma delas |
| 12c | OneDrive - Desinstalação Completa (Fase 12) | Verificado (console), sem executar a ação real | Reexecução de `Phase12PartAConsoleDemo` | Resolução do caminho do instalador (`C:\Windows\System32\OneDriveSetup.exe`) e fluxo de bloqueio (recusa por lock) confirmados; a desinstalação real **não foi executada** de propósito (ação irreversível) - mesma decisão de segurança da Parte A |

### Detalhe do item 9 (Diagnóstico do Sistema) — trecho da execução

```
5 de 44 itens ja otimizados (44 avaliados no total).
...
Alvo escolhido: Botao do Copilot na Barra de Tarefas | atual=null | recomendado=0
Aplicar -> sucesso=true | Valor de 'Botao do Copilot na Barra de Tarefas' alterado para 0.
Releitura apos aplicar -> status=JA_OTIMIZADO | atual=0x0
Reverter (restoreFromHistory) -> sucesso=true | Valor de 'Botao do Copilot na Barra de Tarefas' restaurado ao estado anterior.
Releitura apos reverter -> status=SUGESTAO | atual=null (esperado: igual ao valor original, antes do teste)
```

### Novidade nesta rodada: `Phase12PartBConsoleDemo`

Criado `src/main/java/com/nitroboost/Phase12PartBConsoleDemo.java` para exercitar os itens 4, 5 e 6
do checklist contra um serviço real (`MapsBroker`, já que `Fax` não existe nesta máquina). Roda via:

```
./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase12PartBConsoleDemo
```

Confirmado ao final da execução: `MapsBroker` permanece exatamente no mesmo estado
(`Stopped/Auto`) de antes do teste - nenhuma escrita real foi bem-sucedida nesta sessão sem
Administrador, e o mecanismo de backup/histórico/lock funcionou corretamente em todos os passos
(inclusive nos caminhos de falha).

---

## Resumo

- **9 itens verificados de fato via console** (comparação direta scanner vs comando real): Processos,
  Serviços, Startup, Backup/Reversão (caminho de falha), Bloqueio, Histórico, Bloatware, Diagnóstico
  do Sistema (round-trip completo com placar mudando), BIOS/Drivers.
- **2 itens bloqueados por limitação de ambiente conhecida** (já documentados em `BLOCKERS.md`): IA
  (Copilot, item 8) e Limpeza de RAM (item 10).
- **3 itens dos "novos da Fase 12"** verificados dentro do possível: GPU (não aplicável nesta
  máquina), Edge/Driver Update (falha esperada por falta de Administrador, nada alterado), OneDrive
  (lógica validada, execução real intencionalmente não testada por ser irreversível).
- **Nenhum item genuinamente falhou** (nenhum comportamento incorreto do NITRO BOOST) - todas as
  "falhas" observadas são o comportamento esperado e já documentado do Windows exigindo
  Administrador para escrever em HKLM/serviços, portanto **nenhuma entrada nova foi necessária em
  `BLOCKERS.md`** além da já existente cobertura dessa classe de limitação.
- O único problema real encontrado durante esta fase foi de **dado** (7 entradas duplicadas em
  `knowledge-base.json`, uma delas com classificação conflitante) - corrigido, ver seção B.1 acima.

---

## Rodada 2 — Validação com Administrador (Fase 16, 2026-07-30)

Cobre `docs/NITRO-BOOST-fase16-validacao-administrador.md`, gerada pelo botão "🧪 Autoteste (Fase 16)"
(`validation/Fase16ValidationRunner.java`) rodando de verdade numa máquina elevada (Administrador
confirmado), com SFC/DISM completo incluído. Relatório bruto salvo em
`docs/fase16-autoteste-2026-07-30_16-20-11.md` — esta seção resume o resultado e documenta o que foi
feito com os 2 itens que retornaram FALHOU.

**Resultado bruto do autoteste:** 17 passaram, 2 falharam, 5 pulados deliberadamente, 8 exigem
confirmação visual (32 itens no total).

| # | Item | Resultado | Observações |
|---|---|---|---|
| 1 | Privilégio de Administrador | Verificado | `ElevationChecker.isElevated()` confirmou sessão elevada |
| 2 | Serviços/Registro/Startup/Bloqueio - round trip completo | Verificado | 4 categorias, aplicar→confirmar→reverter→confirmar, todas voltaram ao estado original (`MapsBroker`, sugestão do Menu Iniciar, item de teste próprio de Startup, bloqueio recusado corretamente) |
| 3 | Limpeza de Cache de RAM (`purgeStandbyList`) | Verificado | RAM livre (OSHI) subiu de 7148.3 MB para 7674.8 MB, comando nativo retornou sucesso |
| 3 | Limpeza de RAM registrada no histórico (tipo `memory_cleanup`) | **Bug real encontrado e corrigido** | Ver "Bug encontrado" abaixo |
| 4 | Copilot - Desativar/Reverter | Verificado | Chave `TurnOffWindowsCopilot` (HKCU) alterada e revertida corretamente. Nenhum pacote Appx separado nesta máquina - só a ação "Desativar" se aplica |
| 5 | GPU - varredura/round-trip do item seguro | Verificado | `AMD Crash Defender Service` (seguro) desativado/revertido com sucesso; `AMD External Events Utility` (risco em overlay) corretamente **não** tocado |
| 6 | Verificação e Reparo Completo (SFC + DISM) | Falhou (ambiente, não é bug) | Ver "DISM falhou" abaixo |
| 7 | Rede - DNS/ARP/Renovar IP | Verificado | As 3 ações rápidas concluídas com sucesso; Winsock/TCP-IP Reset pulados deliberadamente (exigem reinício) |
| 8 | Modo de Jogo - lógica não invertida | Verificado | Rótulo do botão = "Ativar Modo de Jogo" (correto); aplicar realmente ativou (`0x1`), reverter voltou ao estado original |
| 9 | Taxa de atualização da tela | Detectado, aguarda confirmação visual | Atual/máxima = 240 Hz, taxas suportadas `[59,60,100,119,120,144,165,180,200,240]` |

### Bug real encontrado e corrigido: check de histórico da limpeza de RAM usava a coluna errada

`Fase16ValidationRunner.checkRamCleanupHistory()` procurava uma entrada no histórico com
`h.itemType()` igual a `"memory_cleanup"`. Mas `MemoryCleaner.recordHistoryQuiet()` sempre gravou
(desde a Fase 10) com `item_type = "memory"` e `action_type = "memory_cleanup"` — a limpeza de RAM
**estava sendo registrada corretamente no histórico o tempo todo**; o autoteste que checava a coluna
errada. Corrigido em
[`src/main/java/com/nitroboost/validation/Fase16ValidationRunner.java`](src/main/java/com/nitroboost/validation/Fase16ValidationRunner.java)
trocando a checagem para `h.actionType()`. Nenhum outro item do autoteste tinha esse mesmo padrão de
bug (os demais checks de histórico, ex. `checkSection2History()`, já comparavam pelos campos certos).
Confirmado com `./mvnw.cmd test` (**95/95 testes passando, nenhuma regressão**) que a mudança não
quebrou nada — após instalar o JDK 21 nesta sessão (ver `BLOCKERS.md` item 14 para o detalhe, o
ambiente só tinha JRE 8 até então). O teste automatizado não exercita `Fase16ValidationRunner`
contra o Windows real (só `ValidationReport`/`ValidationReportTest` com resultados simulados) —
usuário deve reexecutar o botão "🧪 Autoteste (Fase 16)" numa máquina elevada para confirmar que o
item agora retorna `PASSOU` de verdade.

### DISM falhou (código -2146498283 / 0x800F0915) — não é um bug do NITRO BOOST

`DISM /Online /Cleanup-Image /RestoreHealth` retornou o código de saída `-2146498283`
(`0x800F0915` em hexadecimal) nesta máquina. Pesquisado: esse código é um erro conhecido e
documentado do próprio Windows (não do NITRO BOOST) — geralmente indica que o DISM não conseguiu
localizar/baixar os arquivos de reparo (component store corrompido, Windows Update inacessível, ou
um deadlock conhecido entre o servicing stack e o component store que ele mesmo precisa atualizar).
`SystemFileRepairTool` capturou o código de saída corretamente e reportou a falha sem lançar exceção
nem travar a interface - comportamento correto do ponto de vista do código. O SFC rodado em seguida
não teve o resultado classificado automaticamente (`UNKNOWN`) - o log bruto completo (exibido na tela
de Reparo do Sistema e salvo no relatório) deve ser conferido manualmente.

- **Ação recomendada para o usuário:** rodar `DISM /Online /Cleanup-Image /RestoreHealth` manualmente
  com um `/Source` válido (ex: ISO do Windows montada) se a falha persistir, ou confirmar acesso à
  internet/Windows Update. Ver `BLOCKERS.md` item 13 para o detalhe completo e a fonte da pesquisa.

### Correção posterior (2026-08-07): bug de lock "órfão" de Telemetria

Durante a revisão final do projeto (após esta rodada), foi corrigido o último bug de código
genuinamente aberto, registrado em `docs/BLOCKERS.md` item **7b** desde a Fase 9 Parte 1:
`ActionExecutor.setTelemetryValue` verificava o bloqueio pelo `definition.id()`, enquanto a interface
gravava o bloqueio pelo `friendlyName()` — os dois nunca coincidiam, então **bloquear um item de
Telemetria pela tela não impedia, de fato, que ele fosse alterado**.

Vale notar por que a Rodada 1 (item 5 da tabela B.2 acima) não pegou isso: o teste de bloqueio de lá
foi feito com um **serviço** (`MapsBroker`), categoria que sempre usou o nome amigável
consistentemente. Nenhuma rodada de teste tinha exercitado o round-trip de bloqueio especificamente
em um item de **Telemetria** — a categoria mais antiga do projeto (Fase 3) e a única com essa
inconsistência.

Corrigido com teste de regressão dedicado (`ActionExecutorTelemetryLockTest`, 3 testes, confirmados
falhando antes da correção) mais validação real de bloquear → tentar alterar → recusa → desbloquear
contra o Windows e o banco reais. Suíte completa: **98/98 passando**.

### Itens que ainda exigem confirmação visual humana (não automatizáveis)

Tema visual, velocímetro/gráfico do Dashboard, barra de progresso do scan, navegação pelas 7 telas
(seção 1), indicador de limpeza de RAM não travando a UI e comparação com o Gerenciador de Tarefas
(seção 3), ícone do Copilot sumindo/voltando da barra de tarefas (seção 4), e a comparação final da
taxa de atualização com a tela nativa do Windows (seção 9) — nenhum desses foi nem pode ser marcado
como verificado automaticamente. Ficam pendentes de confirmação visual direta pelo usuário na próxima
execução da UI.
