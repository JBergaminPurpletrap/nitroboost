# 📈 PROGRESS — NITRO BOOST

Histórico de progresso do projeto, atualizado ao final de cada fase concluída.

---

## 2026-07-23 — Fase 0 concluída (Setup e Fundamentos)

Setup inicial completo do projeto. Resumo do que foi feito:

- **Git:** repositório local já existia (`git init` prévio); foi criado o repositório remoto
  privado no GitHub (`gh repo create nitroboost --private --source=. --remote=origin`) e o branch
  `master` foi enviado para `origin`.
- **Maven:** `mvn` não estava instalado globalmente no ambiente — o **Maven Wrapper** (`mvnw` /
  `mvnw.cmd`) foi baixado/configurado manualmente (aponta para Maven 3.9.9 e maven-wrapper 3.3.2,
  ambos resolvidos do Maven Central). Ver `BLOCKERS.md` item 1.
- **`pom.xml`:** projeto Maven criado com Java 21 (`maven.compiler.release=21`) e dependências:
  `javafx-controls`/`javafx-fxml` 21.0.12 (via `org.openjfx:javafx-maven-plugin` 0.0.8, que resolve
  os jars nativos pelo classifier do SO automaticamente), `oshi-core` 7.4.1, `jna`/`jna-platform`
  5.19.1, `org.xerial:sqlite-jdbc` 3.53.2.1, `jackson-databind` 2.22.1. Também configurado
  `exec-maven-plugin` (para rodar classes de teste isoladas sem abrir a UI) e
  `maven-compiler-plugin`.
- **Estrutura de pastas:** criada exatamente conforme a seção 3 da documentação —
  `src/main/java/com/nitroboost/{core,actions,knowledge,ui,db}` e `Main.java`, além de
  `src/main/resources/{knowledge-base.json, theme/, tutorials/}`. As pastas `actions/`,
  `knowledge/` e `ui/` ficam vazias por ora — populadas a partir da Fase 1 em diante.
- **`Main.java`:** aplicação JavaFX mínima — inicializa o banco (schema), imprime specs de
  hardware via OSHI no console, e abre uma janela vazia com o título "NITRO BOOST"
  (`StackPane` com um `Label`).
- **Teste OSHI:** `com.nitroboost.core.HardwareInfoPrinter` — lê e imprime CPU (nome, núcleos,
  % de uso) e RAM (usada/total) via `oshi.SystemInfo`. Testado isoladamente via
  `./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.HardwareInfoPrinter` — funcionou,
  imprimiu specs reais da máquina de desenvolvimento.
- **Teste JNA:** `com.nitroboost.core.JnaNativeTest` — chama `Kernel32Util.getComputerName()`
  (API de alto nível do `jna-platform`) e um binding manual direto a `GetTickCount64` da
  `kernel32.dll` (demonstrando mapeamento manual de DLL nativa). Testado isoladamente — funcionou,
  retornou o nome do computador e o uptime do Windows.
- **`DatabaseManager.java` + `db/schema.sql`:** schema com as 4 tabelas pedidas (`items`,
  `actions_history`, `backups`, `locks`, com índices e foreign keys entre elas). O banco SQLite
  fica em `%USERPROFILE%\.nitroboost\nitroboost.db` (caminho dinâmico via `user.home`, nunca
  hardcoded). `initializeSchema()` lê `schema.sql` do classpath (empacotado via configuração extra
  de `<resources>` no `pom.xml`, já que o arquivo fica em `src/main/java/.../db/` e não em
  `src/main/resources/`) e executa cada `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX`. Testado
  isoladamente via `./mvnw exec:java -Dexec.mainClass=com.nitroboost.db.DatabaseManager` — as 4
  tabelas foram criadas e confirmadas com uma consulta a `sqlite_master`. Um bug no parser
  simplificado do schema (semicolon dentro de um comentário quebrando o split) foi encontrado e
  corrigido nesse processo — ver `BLOCKERS.md` item 3.
- **Build:** `./mvnw -q compile` e `./mvnw -q package -DskipTests` rodam sem erros;
  `target/nitroboost.jar` gerado com sucesso.
- **`README.md`, `PROGRESS.md`, `BLOCKERS.md`:** criados.
- **`.gitignore`:** criado (ignora `target/`, `*.class`, IDEs, arquivos `.db*` soltos no projeto,
  `desktop.ini`).

### Não validado neste ambiente (ação pendente do usuário)

A abertura visual da janela JavaFX (`javafx:run`) **não foi validada de forma síncrona** neste
ambiente automatizado, pois trava o processo aguardando a janela ser fechada (sem sessão gráfica
interativa aqui). O build compila e empacota sem erros até o ponto de `primaryStage.show()`, mas a
confirmação visual final (janela abre, título correto, sem exceção) deve ser feita pelo usuário
rodando `.\mvnw.cmd clean javafx:run` na máquina real. Ver `BLOCKERS.md` item 2.

### Critério de conclusão da Fase 0 (conforme documentação)

- [x] App compila e a lógica de inicialização roda sem exceção (banco + OSHI) antes de abrir a
      janela — validado via build/testes isolados.
- [ ] Confirmação visual de que a janela JavaFX realmente abre — pendente, deve ser feita pelo
      usuário na máquina real (ver comando acima).
- [x] Specs de hardware impressas no console via OSHI — validado.
- [x] Banco SQLite existe localmente com as 4 tabelas criadas — validado.

Próximo passo (não iniciado): **Fase 1 — MVP Core (Processos, Serviços, Startup)**.

---

## 2026-07-23 — Fase 1 concluída (MVP Core: Processos, Serviços, Startup)

Fluxo completo escanear → classificar → agir → backup → reverter, validado via console
(`Phase1ConsoleDemo`). Resumo do que foi feito:

- **`ProcessScanner`** (`core/`): lista processos via OSHI (`OperatingSystem.getProcesses()`) com
  nome, PID, RAM e % CPU; busca por PID.
- **`ServiceScanner`** (`core/`): lista serviços do Windows via PowerShell `Get-Service`/`Get-CimInstance`,
  extraindo nome, estado (Running/Stopped) e tipo de inicialização.
- **`StartupScanner`** (`core/`): lê `HKCU\...\Run`, `HKLM\...\Run` via `reg query` e as pastas de
  Startup (usuário e "All Users"), sempre resolvendo os caminhos dinamicamente (`%APPDATA%`,
  `%PROGRAMDATA%` etc. — nunca hardcoded).
- **`ActionExecutor`** (`actions/`): `killProcess`, `restoreProcess`, `stopService`,
  `disableService`, `disableStartupItem`/`restoreStartupItem` — todos com try/catch, backup prévio
  obrigatório e registro no histórico (`actions_history`), mesmo quando a ação falha.
- **`BackupManager`** (`actions/`, versão básica): `snapshotBeforeAction` grava snapshot em JSON
  na tabela `backups` antes de qualquer ação; `restore`/métodos específicos revertem a partir do
  snapshot salvo.
- **Base de conhecimento:** `knowledge-base.json` expandido para **30 itens** (processos, serviços
  e entradas de startup comuns do Windows 11 — OneDrive, Xbox Game Bar, Widgets, telemetria etc.),
  com descrições em português claro. `KnowledgeBase.java` (`knowledge/`) carrega via Jackson e
  permite consulta por nome.
- **Persistência:** `ItemRepository` e `ActionHistoryRepository` (`db/`) para gravar itens
  escaneados e histórico de ações no SQLite.
- **Integração/teste (`Phase1ConsoleDemo`):** escaneia processos/serviços/startup, classifica cada
  item pela base de conhecimento, e roda dois round-trips reais de ação + backup + reversão:
  1. Mata e restaura um processo de teste (`ping.exe`, escolhido por ser um executável clássico
     que mantém o mesmo PID durante toda a execução — ver bug corrigido abaixo).
  2. Desativa e restaura uma entrada de registro de teste própria (`NitroBoostTestEntry` em
     `HKCU\...\Run`), nunca uma entrada real do usuário.
  Nenhuma ação foi feita em processo, serviço ou entrada de startup essencial da máquina real.

### Bug encontrado e corrigido durante o teste

O teste de kill/restore de processo usava inicialmente `notepad.exe` como processo de teste e
falhava de forma **intermitente**. Causa raiz: no Windows 11, `notepad.exe` (System32) é um stub de
app empacotada (MSIX) que repassa a execução para um processo host com outro PID e encerra sozinho
quase imediatamente — o PID capturado pelo `ProcessBuilder` às vezes já tinha morrido antes do
`killProcess` rodar. Corrigido trocando o processo de teste para `ping.exe -n 60 127.0.0.1`, um
executável clássico que mantém PID estável durante 60s. Reexecutado o teste várias vezes após a
correção — 100% de sucesso, sem processos órfãos deixados na máquina.

### Build e testes

- `./mvnw -q compile` — OK, sem erros.
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.Phase1ConsoleDemo` — roda até o fim,
  imprime top 10 processos por RAM, até 15 serviços em execução (132 no total), itens de startup
  reais da máquina, e os dois round-trips de ação/backup/reversão com sucesso.

Todos os itens do checklist da Fase 1 estão marcados `[x]` em
`NITRO-BOOST-documentacao-completa.md`.

Próximo passo (não iniciado): **Fase 2 — Segurança e Controle (Backup completo + Lock + Histórico)**.

---

## 2026-07-23 — Fase 2 concluída (Segurança e Controle: Backup completo + Lock + Histórico)

Nenhuma ação passa mais batido: todo item pode ser protegido contra alterações, e toda ação
(inclusive as recusadas) fica registrada no histórico com rastro completo até o backup que a
originou. Resumo do que foi feito:

- **`LockManager`** (`actions/`, novo): `lockItem(name, type, reason)` marca um item como
  protegido na tabela `locks` (usa `INSERT ... ON CONFLICT(item_id) DO UPDATE` para ser
  idempotente) e atualiza o flag de conveniência `items.is_locked`; `unlockItem(name, type)`
  remove a proteção; `isLocked(name, type)` é a consulta usada pelo `ActionExecutor` antes de
  agir. Cria o item no catálogo automaticamente se ainda não existir (nunca falha por FK). Toda
  chamada de `lockItem`/`unlockItem` grava uma entrada `lock`/`unlock` em `actions_history`.
- **`ActionExecutor` — verificação de bloqueio:** `killProcess`, `stopService`/`disableService`
  (via `runServiceAction`, exceto a variante `enable` usada em restores) e `disableStartupItem`
  agora chamam `LockManager.isLocked` **antes** de criar qualquer backup ou tocar no sistema
  operacional. Se o item estiver bloqueado, a ação é recusada sem exceção — devolve um
  `ActionResult(success=false, ...)` com mensagem clara e grava a recusa em `actions_history`
  (com `previous_state` preenchido e `new_state = null`), sem criar backup nenhum.
- **Rastreabilidade backup ↔ histórico:** `BackupManager` ganhou `linkToHistory(backupId,
  historyId)` e `findByActionHistoryId(historyId)`. Como o backup é criado *antes* da ação (e
  portanto antes de existir uma entrada de histórico para referenciar), o vínculo é feito logo
  depois: `ActionHistoryRepository.record(...)` agora retorna o id gerado, e o helper interno
  `recordHistory` do `ActionExecutor` usa esse id para popular `backups.action_history_id`
  (coluna que já existia no schema mas não era usada).
- **Histórico completo:** `ActionHistoryRepository` ganhou `HistoryEntry` (record tipado),
  `findById(id)` e `findRecent(limit)` (mais recente primeiro); `printRecentHistory` foi
  reescrito para reusar `findRecent` em vez de duplicar a consulta.
- **Reversão por entrada de histórico:** `ActionExecutor.restoreFromHistory(historyId)` busca a
  entrada de histórico, localiza o backup vinculado a ela (com fallback para o backup mais
  recente não restaurado do mesmo item, caso o vínculo direto não exista), e despacha para
  `restoreProcess`/`restoreService`/`restoreStartupItem` conforme o tipo do item.
- **Integração/teste (`Phase2ConsoleDemo`):** dois cenários completos via console, cada um contra
  um item de teste próprio (processo `ping.exe` de teste; entrada de registro de teste
  `NitroBoostTestEntryFase2` em `HKCU\...\Run`, nunca uma entrada real):
  1. Bloqueia o item → tenta agir (kill / disable) → confirma que a ação foi **recusada** (sem
     backup criado, item intacto) → desbloqueia → age novamente → confirma sucesso → localiza a
     entrada de histórico recém-criada e reverte via `restoreFromHistory(historyId)` (não
     `restoreProcess`/`restoreStartupItem` diretamente, para validar o caminho novo de ponta a
     ponta) → confirma que o item voltou ao estado original.
  Nenhuma ação foi feita em processo, serviço ou entrada de startup essencial da máquina real; ao
  final da execução não sobrou nenhum processo `ping` órfão nem entrada de registro de teste.

### Build e testes

- `./mvnw -q compile` — OK, sem erros.
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.Phase2ConsoleDemo` — roda até o fim. Trecho
  relevante confirmando a recusa por bloqueio:
  ```
  lockItem() -> sucesso=true | Item 'PING' (process) bloqueado com sucesso.
  Tentando matar o processo bloqueado...
  killProcess() [item bloqueado] -> sucesso=false | Acao 'kill' recusada: o item 'PING' esta
  bloqueado (protegido). Desbloqueie o item manualmente antes de tentar novamente.
  [OK] Acao recusada corretamente: nenhum backup criado, processo continua vivo.
  Processo ainda vivo apos tentativa recusada: true
  ```
  e, após desbloquear, o round-trip completo com reversão por histórico:
  ```
  killProcess() [item desbloqueado] -> sucesso=true | Processo 'PING' (PID 31564) finalizado
  com sucesso.
  Entrada de historico #16 localizada para a acao 'kill'.
  restoreFromHistory(#16) -> sucesso=true | Processo 'PING' relancado com sucesso (novo PID 7492).
  ```
  O mesmo padrão (bloqueio recusa → desbloqueio permite → reversão por histórico) foi repetido e
  validado com sucesso para o item de startup de teste.
- Verificado após a execução: nenhum processo `ping.exe` órfão (`Get-Process ping` vazio) e
  nenhuma entrada `NitroBoostTestEntryFase2` remanescente no registro.

Todos os itens do checklist da Fase 2 estão marcados `[x]` em
`NITRO-BOOST-documentacao-completa.md`.

Próximo passo (não iniciado): **Fase 3 — Expansão de Varredura**.

---

## 2026-07-23 — Fase 3 concluída (Expansão de Varredura)

As 7 áreas de varredura definidas no plano original agora estão cobertas: processos, serviços,
startup (Fase 1) + tarefas agendadas, plano de energia, telemetria e bloatware (Fase 3). Todos os
4 novos scanners e as respectivas ações seguem exatamente o mesmo padrão (lock → backup → ação →
histórico) já validado nas Fases 1 e 2. Resumo do que foi feito:

- **`TaskSchedulerScanner`** (`core/`): lista tarefas agendadas via `schtasks /query /fo CSV`
  (formato CSV simples, só com as 3 colunas necessárias - nome, próxima execução, status - mais
  fácil de parsear que o modo verboso `/v`). O parser descarta linhas de cabeçalho de forma
  robusta verificando se a primeira coluna começa com `\` (todo nome de tarefa real começa com
  `\`), em vez de assumir que só a primeira linha é cabeçalho - necessário porque o `schtasks`
  repete o cabeçalho a cada "página" interna quando há muitas tarefas (274 tarefas nesta máquina).
- **`PowerPlanScanner`** (`core/`): lista planos de energia via `powercfg /list` e identifica o
  ativo. O regex de parsing busca apenas o padrão do GUID (`xxxxxxxx-xxxx-...`) em vez do texto
  fixo `"Power Scheme GUID:"`, porque esse texto é traduzido pelo Windows conforme o idioma do
  sistema (nesta máquina, em português, a linha real é `"GUID do Esquema de Energia:"`) - bug
  encontrado e corrigido durante o teste (ver seção de bugs abaixo).
- **`TelemetryScanner`** (`core/`): mapeia 7 chaves de registro conhecidas relacionadas a
  telemetria/relatórios/personalização do Windows 11 (nível de telemetria via política e via
  configuração normal, ID de publicidade, experiências personalizadas, frequência de pedidos de
  feedback, relatório de erros do Windows, rastreamento de apps mais usados), com descrição em
  português para cada uma. `readValue()` lê o valor atual via `reg query` de forma defensiva
  (chave/valor ausente = estado válido "não definido", não é erro).
- **`BloatwareScanner`** (`core/`): lista apps UWP do usuário atual via PowerShell
  `Get-AppxPackage` (mesmo padrão de arquivo temporário + UTF-8 do `ServiceScanner`, pelo mesmo
  motivo de evitar corrupção de acentuação). Classifica por substring (case-insensitive, já que
  nomes de pacote mudam de versão para versão do Windows) nas categorias pedidas - Widgets,
  Copilot, Game Bar, Xbox (apps auxiliares e app principal), OneDrive - mais uma categoria extra
  `OTHER_KNOWN_BLOAT` para bloatware comum adicional (Cortana, Solitaire, Bing News/Weather, Zune
  Music/Video, Office Hub, Feedback Hub, People, Mail and Calendar, To Do). Na máquina de
  desenvolvimento: 134 apps UWP instalados, 16 reconhecidos como bloatware conhecido.
- **`ActionExecutor`** (`actions/`, expandido): 4 pares de métodos novos, todos seguindo o mesmo
  contrato das Fases 1/2 (checar `LockManager.isLocked` antes de agir, `BackupManager
  .snapshotBeforeAction` antes de qualquer alteração real, `recordHistory` sempre, inclusive em
  recusas por bloqueio):
  - `disableScheduledTask`/`enableScheduledTask`/`restoreScheduledTask` - via
    `schtasks /change /tn <nome> /disable|/enable`.
  - `switchPowerPlan`/`restorePowerPlan` - via `powercfg /setactive <guid>`; o backup guarda o
    GUID+nome do plano que estava ativo antes da troca. Item de catálogo usa um nome fixo
    (`ActivePowerPlan`), já que só existe "o plano ativo" como conceito, não um item por plano.
  - `setTelemetryValue`/`restoreTelemetryValue` - via `reg add .../t REG_DWORD` para alterar, e
    `reg add` (com o valor original) ou `reg delete` (se a chave não existia antes) para reverter,
    conforme o que o backup capturou.
  - `uninstallBloatwareApp`/`restoreBloatwareApp` - via PowerShell `Remove-AppxPackage -Package
    <PackageFullName>` (aceita um parâmetro `whatIf` que adiciona `-WhatIf` ao comando - ver
    decisão de segurança abaixo). O restore é "melhor esforço": tenta re-registrar o pacote a
    partir do `AppxManifest.xml` na pasta de instalação capturada no backup
    (`Add-AppxPackage -Register`) - só funciona se os arquivos ainda existirem em disco, uma
    limitação inerente da plataforma Appx (não há como "desfazer" uma desinstalação de forma
    totalmente confiável sem reinstalar pela Microsoft Store). Isso está documentado no próprio
    Javadoc do método.
  - `restoreFromHistory` (já existente desde a Fase 2) foi estendido para despachar também os
    tipos `task`, `powerplan`, `telemetry` e `bloatware`.
- **Base de conhecimento:** `knowledge-base.json` expandido de 27 para **66 itens** - 6 tarefas
  agendadas comuns do Windows (CEIP/Compatibility Appraiser/Error Reporting), 8 planos de energia
  (versões em inglês e português dos 4 planos padrão/ocultos do Windows, já que o nome retornado
  por `powercfg /list` depende do idioma do sistema), 7 chaves de telemetria (uma entrada por
  chave mapeada no `TelemetryScanner`) e 14 itens de bloatware (Widgets, Copilot, Game Bar, os dois
  grupos de apps do Xbox, OneDrive como UWP, mais bloatware comum adicional).
- **`Phase3ConsoleDemo`:** demonstração de integração via console cobrindo as 4 sub-fases, testada
  de ponta a ponta na máquina de desenvolvimento.

### Decisões de segurança tomadas para os testes (regra explícita da Fase 3)

- **Tarefas agendadas:** a listagem roda contra as 274 tarefas reais da máquina (somente leitura,
  sem risco). O teste de bloqueio/desativação/reversão roda **apenas** contra uma tarefa de TESTE
  própria (`NitroBoostTestTaskFase3`, agendada para `01/01/2099` - nunca executa de fato),
  criada e removida pelo próprio `Phase3ConsoleDemo`. Nenhuma tarefa real do sistema/usuário foi
  desativada.
- **Plano de energia:** a listagem roda contra os planos reais da máquina (leitura). Esta máquina
  de desenvolvimento expõe **apenas 1 plano visível** via `powercfg /list` (Equilibrado/Balanced -
  provavelmente por política/OEM, já que `powercfg -duplicatescheme` consegue criar planos novos
  que continuam não aparecendo em `/list`, sugerindo alguma restrição de visibilidade no sistema).
  Como a regra pede para gravar o plano original e restaurá-lo ao final "sem exceção", mas não
  havia um segundo plano real pré-existente para testar a troca sem depender do único plano real,
  o teste criou um **plano de TESTE próprio** (`powercfg -duplicatescheme <guid original>`,
  renomeado para "NitroBoostTestPlan"), trocou para ele via `ActionExecutor.switchPowerPlan`
  (o mesmo caminho de código usado em produção), confirmou a troca, **reverteu para o plano
  original via `ActionExecutor.restorePowerPlan`** e removeu o plano de teste
  (`powercfg -delete`) - com uma rede de segurança adicional no `finally` que força
  `powercfg /setactive <guid original>` novamente caso a reversão não tenha sido confirmada.
  Verificado ao final (both durante o teste automatizado e manualmente via `powercfg /list`
  depois): o plano ativo da máquina é `Equilibrado` (o mesmo de antes do teste, mesmo GUID), e o
  plano de teste foi removido sem deixar rastro.
- **Telemetria:** a leitura das 7 chaves conhecidas roda contra o registro real (somente leitura,
  sem risco - nenhum valor real foi alterado). O teste de "alterar com backup" roda **apenas**
  contra uma chave de TESTE própria e fabricada (`HKCU\Software\NitroBoostTest\TelemetryTestFlag`,
  que não corresponde a nenhuma das 7 chaves reais mapeadas), removida ao final (`reg delete`)
  independentemente do resultado do teste.
- **Bloatware:** a listagem roda contra os 134 apps UWP reais instalados (leitura via
  `Get-AppxPackage`, sem risco). **Nenhum app UWP foi desinstalado de verdade neste teste
  automatizado.** O método `uninstallBloatwareApp()` foi exercitado com o parâmetro `whatIf=true`,
  que adiciona `-WhatIf` ao comando PowerShell `Remove-AppxPackage` - o comando roda de verdade
  (validando que o nome do pacote e a sintaxe estão corretos), mas o próprio PowerShell garante que
  nada é alterado no sistema quando `-WhatIf` está presente. Confirmado após o teste: o app usado
  na validação (`Microsoft.Windows.PeopleExperienceHost`) continua instalado.

### Bug encontrado e corrigido durante o teste

O `PowerPlanScanner` inicialmente buscava o texto fixo `"Power Scheme GUID:"` na saída do
`powercfg /list`, que só existe no Windows em inglês - nesta máquina (Windows em português), a
saída real é `"GUID do Esquema de Energia:"`, então o parser retornava 0 planos. Corrigido trocando
o regex para buscar apenas o padrão do GUID em si (`xxxxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`),
ignorando o texto ao redor - funciona em qualquer idioma do Windows. O `TaskSchedulerScanner`
teve um ajuste semelhante: em vez de assumir que só a primeira linha da saída do `schtasks` é
cabeçalho, o parser agora descarta qualquer linha cuja primeira coluna não comece com `\` (todo
nome de tarefa real começa com `\`) - necessário porque o `schtasks` repete a linha de cabeçalho
a cada "página" interna quando há muitas tarefas agendadas (essa máquina tem 274).

### Build e testes

- `./mvnw -q compile` - OK, sem erros.
- `./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.TaskSchedulerScanner` /
  `...PowerPlanScanner` / `...TelemetryScanner` / `...BloatwareScanner` - os 4 scanners testados
  isoladamente contra a máquina real antes da integração, confirmando parsing correto.
- `./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase3ConsoleDemo` - roda até o fim, cobrindo
  as 4 sub-fases com sucesso: bloqueio recusa a ação em tarefa de teste → desbloqueio permite →
  desativar/reverter tarefa de teste; trocar/reverter plano de energia de teste (com o plano
  original confirmado restaurado); alterar/reverter chave de telemetria de teste; validar comando
  de desinstalação de bloatware em modo `-WhatIf` sem alterar nada. Histórico completo (30 entradas
  mais recentes) impresso ao final, mostrando todas as ações (inclusive a recusa por bloqueio) com
  seus ids de backup vinculados.
- Verificado manualmente após a execução (`powercfg /list`, `schtasks /query`, `reg query`,
  `Get-AppxPackage`): nenhum resíduo de teste ficou na máquina - plano de energia original ativo,
  tarefa de teste removida, chave de registro de teste removida, app de bloatware usado na
  validação continua instalado.

Todos os itens do checklist da Fase 3 estão marcados `[x]` em
`NITRO-BOOST-documentacao-completa.md`.

Próximo passo (não iniciado): **Fase 4 — Interface Gráfica (JavaFX) — Tema Booster Gamer**.

---

## 2026-07-23 — Fase 4 concluída (Interface Gráfica JavaFX — Tema Booster Gamer)

Toda a interface grafica descrita na Fase 4 foi implementada em Java puro (sem FXML, conforme
recomendado no guia de skills tecnicas para telas dinamicas), conectada ao backend real (nenhuma
logica de negocio foi reimplementada na UI - ela so chama `ActionExecutor`/`LockManager`/
`ActionHistoryRepository` ja validados nas Fases 1 a 3). Resumo do que foi feito:

### 4.1 Tema visual "Carbono & Verde Turbo"

- **`src/main/resources/theme/nitroboost-carbon.css`** (novo): implementa a paleta oficial exata
  (fundo `#0A0A0A`, paineis `#161616`, verde neon `#39FF14`, verde tecnico `#00C853`, vermelho
  `#FF3B30`, ambar `#FFB300`, texto `#E8E8E8`/`#7A7A7A`, bordas `#2A2A2A`).
- **Textura "fibra de carbono"**: simulada 100% via CSS puro, sem PNG externo - usa
  `linear-gradient(from Npx Npx to Mpx Mpx, repeat, ...)` (o modificador `repeat` do JavaFX CSS
  permite tilear um gradiente pequeno indefinidamente) para criar um padrao diagonal tileado.
  Classe `.carbon-bg-subtle` (baixa opacidade, paineis principais) e `.carbon-bg-strong`
  (mais evidente, cabecalho/sidebar).
- **Efeitos "tech/gamer"**: glow verde via `-fx-effect: dropshadow` em `.btn-turbo` e no titulo do
  app; barra de progresso `.progress-rpm` com gradiente verde-tecnico -> verde-neon, alternando
  para vermelho (`.progress-critical`) acima de 90% (tanto CPU quanto RAM no dashboard); icones de
  status como `Circle` (JavaFX Shape) coloridos por classificacao (verde/vermelho/ambar) na tabela
  de resultados e como "badge" no modal de detalhes.
- **Tipografia (decisao pragmatica documentada)**: sem acesso de rede garantido neste ambiente para
  baixar Google Fonts (Orbitron/Rajdhani/Chakra Petch pedidas no documento), usamos fontes ja
  presentes no Windows como fallback equivalente ao estilo pedido: `"Consolas"` (monoespacada,
  visual HUD/tecnico) para titulos e numeros de destaque, `"Segoe UI"` para texto corrido legivel
  (descricoes, tutoriais). Documentado tambem no cabecalho do proprio CSS.
- **Componentes reutilizaveis**: `.btn-turbo` (botao primario, borda verde neon + glow),
  `.btn-secondary` (acoes secundarias), `.btn-danger` (acao primaria em itens classificados como
  `essencial` - reforco visual extra de cuidado), `.progress-rpm` (barra RPM).

### 4.2 DashboardView (`ui/DashboardView.java`, novo)

- Grafico `LineChart<Number, Number>` com series de CPU e RAM (%) atualizado a cada 2 segundos.
- Leitura de hardware via OSHI (mesma API usada em `HardwareInfoPrinter`) rodando em um
  `ScheduledExecutorService` de thread unica **daemon** dedicada (nunca na JavaFX Application
  Thread) - cada leitura e protegida por try/catch (uma falha pontual nunca interrompe as proximas
  execucoes agendadas, um detalhe importante de `ScheduledExecutorService` que para de reagendar
  silenciosamente se uma excecao escapar do `Runnable`). Resultado publicado de volta com
  `Platform.runLater`.
- Velocimetro: `Arc` customizado (JavaFX Shape) cujo `length` e `stroke` sao atualizados a cada
  leitura (media CPU+RAM), virando vermelho acima do limite critico de 90%.
- Botao principal "⚡ ESCANEAR SISTEMA" (`.btn-turbo`) navega para `ScanResultsView` e dispara a
  varredura.
- `shutdown()` publico para parar o `ScheduledExecutorService` ao fechar a janela (chamado por
  `Main` no `setOnCloseRequest`).

### 4.3 ScanResultsView (`ui/ScanResultsView.java`, novo)

- `TableView<ScannedItem>` unificando os 7 scanners do backend via `SystemScanTask`
  (`ui/SystemScanTask.java`, novo - um `javafx.concurrent.Task` que roda em thread separada,
  chamando `ProcessScanner`, `ServiceScanner`, `StartupScanner`, `TaskSchedulerScanner`,
  `PowerPlanScanner`, `TelemetryScanner` e `BloatwareScanner`, cada um isolado em seu proprio
  try/catch - uma falha em um scanner nunca impede os demais). Cada item e cruzado com a
  `KnowledgeBase` para obter classificacao/descricao/impactos (`ui/ScannedItem.java`, novo record
  que unifica o resultado de qualquer scanner).
- Filtro por categoria via `ComboBox` + `FilteredList` (Processos, Servicos, Inicializacao, Tarefas
  Agendadas, Planos de Energia, Telemetria, Bloatware).
- Coluna de status com `Circle` colorido pela classificacao; coluna de acoes com 3 botoes por
  linha: "Detalhes" (abre `ItemDetailView`), acao primaria (Finalizar/Desativar/Ativar/Desinstalar,
  dependendo do tipo - ver `ui/ItemActionDispatcher.java` abaixo) e Bloquear/Desbloquear (via
  `LockManager`). Toda acao roda em uma thread de fundo separada (nao trava a UI - comandos como
  PowerShell podem levar ate 20s) e o resultado volta via `Platform.runLater`.
- **`ui/ItemActionDispatcher.java`** (novo, utilitario compartilhado com `ItemDetailView` para nao
  duplicar a logica de despacho): traduz o tipo generico do `ScannedItem` (process/service/startup/
  task/powerplan/telemetry/bloatware) para a chamada especifica correta do `ActionExecutor` (ex:
  `killProcess(pid)` para processos, `switchPowerPlan(guid, nome)` para planos de energia -
  "ativar" em vez de "desativar", ja que o conceito nao se aplica a esse tipo).

### 4.4 ItemDetailView (`ui/ItemDetailView.java`, novo)

- Modal implementado como `Stage` (`Modality.WINDOW_MODAL`) em Java puro, nao um `Dialog<>` padrao,
  para ter controle total do layout/tema (aplica a mesma folha de estilos `nitroboost-carbon.css`).
- Exibe nome, badge de classificacao colorido, categoria/estado atual, descricao, impacto de
  desativar e impacto de manter - tudo vindo do `ScannedItem` (que ja carrega os dados da
  `KnowledgeBase`).
- Botoes Desativar (rotulo adaptado via `ItemActionDispatcher`) / Bloquear-Desbloquear / Ver
  Tutorial (fecha o modal e navega ate a `TutorialView` via callback).

### 4.5 HistoryView (`ui/HistoryView.java`, novo)

- `TableView` com as ultimas 100 entradas de `actions_history` via
  `ActionHistoryRepository.findRecent` (leitura local rapida, roda direto na FX thread, protegida
  por try/catch - nao ha necessidade de thread separada para uma unica consulta SQLite indexada).
- Botao "Reverter" por linha chama `ActionExecutor.restoreFromHistory(id)` em thread de fundo
  (comandos do sistema podem demorar) e recarrega a lista ao concluir. Desabilitado para acoes que
  ja falharam ou que ja sao, elas mesmas, uma reversao (`actionType == "restore"`).

### 4.6 TutorialView (`ui/TutorialView.java`, novo)

- Estrutura funcional da tela (ja conectada a navegacao principal), com uma `ListView` de exemplo
  e um card "EM BREVE" explicando que o conteudo real (deteccao de XMP, passo a passo por
  fabricante, links oficiais) e escopo da Fase 5 - propositalmente **nao antecipado** aqui, conforme
  instrucao explicita de nao inventar conteudo elaborado demais nesta fase.

### 4.7 Integração final da UI

- **`Main.java`** (reescrito): `BorderPane` principal com cabecalho HUD (`.carbon-bg-strong`),
  menu lateral (`sidebar`) com 4 botoes de navegacao (Dashboard/Resultados do Scan/Historico/
  Tutoriais) que trocam o conteudo central - a opcao mais simples e direta pedida na fase, em vez
  de `TabPane`. As 4 views sao instanciadas uma unica vez (mantem estado - ex: itens ja
  escaneados, historico ja carregado) e apenas trocadas de lugar. `AppContext.java` (novo, record)
  agrupa as instancias unicas do backend (`DatabaseManager`, `ActionExecutor`, `LockManager`,
  `KnowledgeBase`, `ActionHistoryRepository`) e e injetado nas views que precisam dele.
- `primaryStage.setOnCloseRequest` chama `DashboardView.shutdown()` para parar a thread de
  monitoramento de hardware ao fechar a janela.
- Checklist completo da Fase 4 marcado `[x]` em `NITRO-BOOST-documentacao-completa.md`.

### Validado neste ambiente (compilacao + revisao de codigo)

- `./mvnw -q compile` - **OK, sem erros** (unicos avisos sao de IDE: deprecation de
  `TableView.CONSTRAINED_RESIZE_POLICY` desde o JavaFX 20 e "unchecked varargs" em
  `getStyleClass().addAll(...)`/`getColumns().addAll(...)` - ambos inofensivos e comuns em codigo
  JavaFX, nao impedem a compilacao nem o funcionamento).
- `./mvnw -q package -DskipTests` - **OK**, `target/nitroboost.jar` gerado com o CSS do tema
  corretamente empacotado em `theme/nitroboost-carbon.css` dentro do jar (confirmado via `jar tf`).
- Revisao manual cuidadosa de cada view: bind de dados (colunas de `TableView` via
  `ReadOnlyObjectWrapper` explicito, ja que os modelos sao `record`s e nao expoem getters no padrao
  `getXxx()` esperado por `PropertyValueFactory`), threads de fundo para toda chamada ao backend que
  possa demorar (scan completo, qualquer acao do `ActionExecutor`, reversao de historico), e
  `Platform.runLater` em todo retorno de thread de fundo para a UI.

### Não validado neste ambiente (ação pendente do usuário) — mesma limitação já documentada na Fase 0

Assim como na Fase 0, **não foi possível rodar `javafx:run` de forma síncrona** neste ambiente
automatizado (trava aguardando a janela ser fechada, sem sessão gráfica interativa aqui). A
confirmação visual final de que:

- a janela abre com o tema aplicado corretamente (cores, textura de fibra de carbono, glow dos
  botões, gráfico de linha atualizando, velocímetro se movendo);
- o fluxo completo funciona na tela de verdade (escanear → ver resultados → filtrar por categoria →
  abrir detalhe de um item → desativar/bloquear → ver a entrada nova no histórico → reverter);

fica pendente de o usuário rodar, na própria máquina Windows:

```
.\mvnw.cmd clean javafx:run
```

Qualquer ajuste fino de layout/cor que só apareça na renderização real (ex: um gradiente CSS que
não tile exatamente como esperado) deve ser reportado para correção pontual — a estrutura de código
e a lógica de integração com o backend já estão implementadas e revisadas.

Nenhum bloqueio técnico novo foi encontrado durante esta fase (ver `BLOCKERS.md` - sem itens novos
da Fase 4).

Todos os itens do checklist da Fase 4 estão marcados `[x]` em
`NITRO-BOOST-documentacao-completa.md`.

Próximo passo (não iniciado, aguardando validação visual do usuário antes de prosseguir):
**Fase 5 — Tutoriais e Educação (XMP/BIOS e afins)**.
