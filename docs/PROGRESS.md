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

---

## 2026-07-23 — Fase 5 concluída (Tutoriais e Educação: XMP/BIOS e afins)

O NITRO BOOST agora consegue alertar sobre possível XMP/EXPO desativado e guiar o usuário até a
solução, mesmo sem poder aplicá-la sozinho (configuração de BIOS não é algo que dê para automatizar
por software). Resumo do que foi feito:

- **`MemorySpeedScanner`** (`core/`, novo): lê, via PowerShell (`Get-CimInstance
  Win32_PhysicalMemory`), a velocidade nominal/rotulada (`Speed`) e a velocidade configurada/ativa no
  momento (`ConfiguredClockSpeed`) de cada módulo de RAM instalado — mesmo padrão de temp-file +
  UTF-8 já usado em `ServiceScanner`/`BloatwareScanner`. Retorna lista vazia (nunca lança exceção)
  se o comando falhar.
- **`XmpAdvisor`** (`knowledge/`, novo): interpreta os dados do `MemorySpeedScanner` — se a
  velocidade configurada estiver abaixo de 90% da nominal, é considerado um indício (não uma
  certeza absoluta — documentado no Javadoc a limitação de que o campo SMBIOS "Speed" nem sempre
  reflete o perfil XMP mais alto do módulo) de que o XMP/EXPO está desativado na BIOS. Gera uma
  mensagem pronta em português claro para exibir na interface.
- **`src/main/resources/tutorials/xmp-bios.md`** (novo): tutorial interno "Como Habilitar o XMP (ou
  EXPO) na BIOS" — o que é, passo a passo genérico (varia por fabricante), o que fazer se algo der
  errado (Clear CMOS), e uma seção de links oficiais por fabricante (ASUS, Gigabyte, MSI, ASRock),
  tudo em português claro, sem jargão técnico excessivo (regra de ouro #10).
- **`TutorialProvider`** (`knowledge/`, novo — já previsto na estrutura de pastas da Fase 0):
  carrega os arquivos `.md` de `src/main/resources/tutorials/` a partir de um mapa simples
  chave → caminho de recurso (`Map.of("xmp-bios", "/tutorials/xmp-bios.md")`), extrai o título do
  próprio arquivo (primeira linha `# Título`) e disponibiliza por chave (`find`) ou lista completa
  (`all`) — genérico o suficiente para novos tutoriais no futuro (basta adicionar uma entrada no
  mapa), sem abstração além do necessário (YAGNI).
- **`TutorialView`** (`ui/`, reescrita): deixou de ser o placeholder "EM BREVE" da Fase 4 e passou a
  exibir o conteúdo real — lista de tutoriais (`ListView`) à esquerda, conteúdo renderizado à
  direita (renderização leve de Markdown feita à mão — títulos, subtítulos, listas e texto corrido —
  sem depender de nenhuma biblioteca externa de Markdown, desnecessária para o volume de conteúdo
  atual). Ao abrir a tela, a verificação de XMP roda em uma thread de fundo (comando PowerShell pode
  levar alguns segundos, nunca trava a UI) e, se houver indício de XMP desativado, mostra um card de
  alerta âmbar no topo com a mensagem e um botão "Ver Tutorial de XMP" que seleciona o tutorial
  correspondente na lista. Reaproveita o mesmo tema `nitroboost-carbon.css` — duas classes CSS novas
  e mínimas foram adicionadas (`.alert-card`, `.text-warning`, mais um ajuste de fundo transparente
  para `ScrollPane`, componente novo nesta fase).

### Teste da detecção de XMP via console (máquina real de desenvolvimento)

```
./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.core.MemorySpeedScanner
===== NITRO BOOST - Velocidade dos Modulos de RAM =====
Modulos detectados: 1
Fabricante=01980000802C Capacidade=16GB RatedSpeed=3200MHz ConfiguredClockSpeed=3200MHz

./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.knowledge.XmpAdvisor
===== NITRO BOOST - Indicio de XMP/EXPO Desativado =====
Dados disponiveis: true
Velocidade nominal (rated): 3200 MHz
Velocidade configurada (atual): 3200 MHz
XMP provavelmente desativado: false
Mensagem: Sua memoria RAM esta rodando a 3200 MHz, proximo da velocidade nominal do modulo
(3200 MHz). Nenhum indicio de XMP/EXPO desativado foi encontrado.
```

Nesta máquina de desenvolvimento, a velocidade configurada já é igual à nominal (3200MHz = 3200MHz)
— ou seja, nenhum indício de XMP desativado, e o card de alerta corretamente não aparece na tela de
Tutoriais. A lógica de comparação foi revisada manualmente para o caso oposto (configurada bem
abaixo da nominal, ex: 2133MHz configurado vs 3200MHz nominal), que dispararia `likelyXmpDisabled =
true` e o alerta na UI — não há como forçar esse cenário na máquina real de desenvolvimento sem
mexer fisicamente na BIOS.

### Build e testes

- `./mvnw -q compile` — OK, sem erros.
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.core.MemorySpeedScanner` — OK, testado
  isoladamente antes de conectar à UI (regra de ouro #9).
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.knowledge.XmpAdvisor` — OK, testado
  isoladamente antes de conectar à UI.
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.knowledge.TutorialProvider` — OK, carregou o
  tutorial `xmp-bios` (3078 caracteres) e extraiu o título corretamente.
- **Validação visual**: `.\mvnw.cmd clean javafx:run` rodado como processo destacado (mesma técnica
  da Fase 4 — `Start-Process` com saída redirecionada para arquivo de log, sem bloquear o terminal).
  O build recompilou os 34 arquivos-fonte sem erro, empacotou os 5 recursos (incluindo o novo
  `tutorials/xmp-bios.md`), o banco SQLite inicializou normalmente e a aplicação ficou rodando sem
  nenhuma exceção nos logs de saída/erro pelo tempo observado. O processo Java (e os processos
  filhos do Maven Wrapper) foram encerrados ao final do teste via `Stop-Process -Force` — confirmado
  via `tasklist`/`Get-CimInstance Win32_Process` que nenhum processo relacionado ao NITRO BOOST
  ficou órfão na máquina (os dois `java.exe` remanescentes são processos do VS Code — extensões de
  linguagem Java/XML —, não relacionados a este projeto).

Nenhum bloqueio técnico foi encontrado durante esta fase (ver `BLOCKERS.md` — sem itens novos da
Fase 5).

Todos os itens do checklist da Fase 5 estão marcados `[x]` em `NITRO-BOOST-documentacao-completa.md`.

Próximo passo (não iniciado): **Fase 6 — Refinamento, Generalização e Empacotamento**.

---

## 2026-07-23 — Fase 6 concluída (Refinamento, Generalização e Empacotamento)

Última fase "core" do projeto. O NITRO BOOST agora pode ser gerado como um executável standalone
(`NitroBoost.exe`, JVM embutida) via `jpackage` e rodado como Administrador com um duplo-clique.
Resumo do que foi feito:

### 6.1 Varredura de hardcode (caminhos/valores específicos da máquina de desenvolvimento)

Revisão completa do código-fonte (`grep` por padrões de caminho absoluto tipo `C:\Users\<nome>`,
nome de usuário/máquina, GUIDs fixos usados em lógica real, nomes de processo/serviço fixados fora
de contexto de teste) — **nenhum item hardcoded específico desta máquina foi encontrado**:

- Todos os caminhos de sistema (`%APPDATA%`, `%ProgramData%`, pasta do usuário para o banco SQLite)
  já eram resolvidos dinamicamente via `System.getenv()`/`System.getProperty("user.home")` desde as
  fases anteriores — confirmado revisando `DatabaseManager` e `StartupScanner`.
- A única ocorrência de `"C:\\ProgramData"` (`StartupScanner`) é um **fallback** defensivo para o
  caso raro da variável de ambiente `ProgramData` não existir — é o caminho padrão do próprio
  Windows (não específico de usuário/máquina), já comentado no código como tal.
- O único GUID de plano de energia no código (`PowerPlanScanner`) aparece apenas dentro de um
  **comentário de exemplo** explicando o formato da linha de saída do `powercfg /list` — o regex de
  parsing usado de fato (`PLAN_LINE_PATTERN`) busca o padrão genérico de GUID, nunca um valor fixo.
- Nomes de processo/entrada de teste (`ping.exe`, `NitroBoostTestEntry*`, `NitroBoostTestTask*`)
  existem apenas dentro das classes `Phase*ConsoleDemo.java` (ferramentas de teste manual, não
  fazem parte do fluxo de produção da UI) — confirmado que nenhuma classe fora desses demos
  referencia esses nomes.

Conclusão: as fases anteriores já seguiram a regra de ouro "nada de hardcode" de forma rigorosa;
esta varredura não encontrou nada a corrigir.

### 6.2 Teste em segunda máquina/VM — não realizável neste ambiente

Registrado em `BLOCKERS.md` (item 4, Fase 6): este ambiente automatizado só tem acesso à própria
máquina de desenvolvimento Windows, sem uma segunda máquina real nem um hypervisor/VM disponível
para provisionar uma instância limpa. Como mitigação parcial, a varredura de hardcode (6.1) reduz o
risco de o app depender de algo específico desta máquina, mas a confirmação real (rodar o pacote
gerado em outra máquina Windows 11 e ver funcionar sem ajustes) fica pendente do usuário.

### 6.3 Responsividade da UI para diferentes resoluções

Revisão de todas as views (`ui/*.java`) em busca de tamanhos rígidos que impediriam a UI de se
adaptar a resoluções/escalas de tela diferentes:

- **Confirmado que a estrutura principal já era responsiva** desde a Fase 4: `Main.java` usa
  `BorderPane` com `setMinWidth(1024)`/`setMinHeight(700)` (não um tamanho fixo travado), sidebar e
  header crescem/encolhem com a janela; `ScanResultsView` e `HistoryView` já usavam
  `TableView.CONSTRAINED_RESIZE_POLICY` (colunas proporcionais) e `VBox.setVgrow(tabela,
  Priority.ALWAYS)`; `DashboardView` usa `Priority.ALWAYS` no gráfico de linha. Os poucos
  `setPrefWidth`/`setMaxWidth` restantes (ex: `TutorialView` limitando a largura do texto a 720-760px
  para legibilidade, barras de progresso do dashboard com largura preferencial de 320px) são
  escolhas de legibilidade/HUD, não travas que impedem redimensionar a janela — revisados e mantidos
  como estão.
- **Ponto genuinamente rígido corrigido:** `ItemDetailView` (modal de detalhes do item) usava uma
  `Scene` de tamanho fixo exato (480x460, `stage.setResizable` nunca chamado, que por padrão do
  JavaFX deixaria a janela travada nesse tamanho) sem `ScrollPane` — em telas com escala de DPI
  maior (comum em notebooks 125%/150%) ou uma descrição de item mais longa, o conteúdo poderia ser
  cortado sem chance de rolar ou redimensionar. Corrigido: o conteúdo do modal agora fica dentro de
  um `ScrollPane` (`setFitToWidth(true)`, reaproveitando o estilo `.scroll-pane` transparente já
  existente no tema desde a Fase 5), e a `Stage` passou a ser explicitamente redimensionável
  (`setResizable(true)`) com um tamanho mínimo (`420x360`) em vez de um tamanho fixo rígido.

### 6.4 Empacotamento via `jpackage`

- **`pom.xml`:** adicionado `maven-dependency-plugin` (execução `copy-dependencies` bound à fase
  `package`) para copiar todas as dependências de runtime — incluindo os jars nativos do JavaFX com
  classifier `win` (resolvidos automaticamente pelo próprio POM do OpenJFX via profile de SO, sem
  precisar do `os-maven-plugin`) — para `target/jpackage-input`. Adicionado também
  `maven-jar-plugin` configurado para gravar o `Main-Class` correto no manifesto do jar.
- **Bug real encontrado e corrigido durante o empacotamento:** a primeira tentativa de rodar
  `jpackage`/`java -jar` com `Main` (que estende `javafx.application.Application`) diretamente como
  classe principal falhava com **"os componentes de runtime do JavaFX não foram encontrados"**,
  mesmo com todos os jars do JavaFX presentes no classpath — um comportamento conhecido do launcher
  do Java (desde o JDK 11): quando a classe principal indicada no manifesto/`--main-class` estende
  `Application` diretamente, o launcher exige o módulo `javafx.graphics` no **module-path**
  especificamente, e recusa reconhecê-lo apenas no classpath. Isso nunca apareceu antes porque
  `javafx:run` (usado em todas as fases anteriores) monta o module-path corretamente sozinho.
  Corrigido criando **`com.nitroboost.Launcher`** (novo, `src/main/java/com/nitroboost/`) — uma
  classe simples que **não** estende `Application`, cujo único `main()` chama `Main.main(args)` —
  e usando essa classe como `Main-Class` do jar (`maven-jar-plugin`) e como `--main-class` do
  `jpackage`. Solução padrão documentada pela própria comunidade OpenJFX para este problema exato.
- **`scripts/jpackage-build.bat`** (novo): script que roda `mvnw package`, copia o
  `nitroboost.jar` principal para `target/jpackage-input` e chama `jpackage --type app-image`,
  gerando `target/dist/NitroBoost/NitroBoost.exe` — pasta standalone completa (~183 MB, JVM
  embutida) pronta para copiar para outra máquina.
- **`--type app-image` escolhido como padrão** (em vez de `--type msi`) por não depender de
  ferramentas externas. Curiosamente, ao testar `jpackage --type msi` nesta máquina especificamente
  como validação extra, o comando **funcionou e gerou um `.msi` de ~87 MB sem erro** — indício de
  que o WiX Toolset já está instalado neste ambiente (não foi instalado por esta fase). Isso não
  mudou a decisão de manter `app-image` como padrão do projeto (continua funcionando em qualquer
  máquina com JDK 21, com ou sem WiX), mas o comando exato para gerar o `.msi` ficou documentado no
  `README.md` para quem quiser um instalador de verdade. Detalhes em `BLOCKERS.md` (item 5, Fase 6).
- **Validação real do executável gerado:** o `NitroBoost.exe` empacotado foi de fato lançado como
  processo destacado (mesma técnica de `Start-Process` das Fases 4/5, nunca bloqueando o terminal) —
  confirmado via `Get-CimInstance`/`Get-Process` que o processo abriu, ficou `Responding=True` e a
  janela abriu com o título **"NITRO BOOST"** corretamente (o único log de stderr foi o aviso
  inofensivo e esperado "Unsupported JavaFX configuration: classes were loaded from unnamed module",
  padrão de qualquer app JavaFX rodando via classpath em vez de module-path). O processo foi
  encerrado com `Stop-Process -Force` ao final do teste e confirmado sem nenhum processo
  `NitroBoost.exe`/`java.exe` órfão relacionado ao projeto na máquina depois.

### 6.5 Instalador simples / execução como Administrador

- **`run-as-admin.bat`** (novo, raiz do projeto): verifica se já está rodando elevado (`net
  session`); se não estiver, relança a si mesmo via UAC (`Start-Process -Verb RunAs` do
  PowerShell) e, uma vez elevado, abre `target\dist\NitroBoost\NitroBoost.exe`.

### 6.6 `README.md` finalizado

Reescrito com: aviso de projeto pessoal/privado, pré-requisitos atualizados (JDK completo, não só
JRE, por causa do `jpackage`), instruções de modo desenvolvimento (`javafx:run`, com nota sobre
rodar como Administrador para testar o fluxo completo), nova seção **"Empacotamento"** completa
(como gerar o `.exe` via `scripts\jpackage-build.bat`, explicação da classe `Launcher` e por que ela
existe, comando alternativo para gerar `.msi`) e seção "Rodar como Administrador" cobrindo o
`run-as-admin.bat`.

### Build e testes

- `./mvnw -q compile` — OK, sem erros, após todas as mudanças (Launcher, ItemDetailView,
  pom.xml).
- `./mvnw -q clean package -DskipTests` — OK, gera `target/nitroboost.jar` (com `Main-Class:
  com.nitroboost.Launcher` confirmado via `unzip -p ... META-INF/MANIFEST.MF`) e popula
  `target/jpackage-input` com as 17 dependências de runtime corretas (incluindo os 4 jars
  `-win` do JavaFX).
- `jpackage --type app-image ...` — OK, gera `target/dist/NitroBoost/NitroBoost.exe` (~183 MB).
- Execução real do `.exe` gerado validada (ver 6.4) — abriu, respondeu, título correto, encerrado
  sem deixar processo órfão.
- Teste extra de `jpackage --type msi` — gerou `.msi` de ~87 MB sem erro nesta máquina (WiX já
  instalado aqui); arquivos de teste (`target/dist-msi-test`, log) removidos após a validação, não
  fazem parte do pacote final do projeto.

Nenhum bloqueio bloqueante restou desta fase — o único item não concluído (teste em segunda
máquina/VM) é uma limitação estrutural do ambiente, documentada em `BLOCKERS.md` como pendência
explícita do usuário, não pulada silenciosamente.

Checklist da Fase 6 marcado em `NITRO-BOOST-documentacao-completa.md`: todos os itens `[x]` exceto
"testar em uma segunda máquina/VM", que permanece `[ ]` com a nota explicando o motivo.

**Esta é a conclusão do escopo "core" do projeto (Fases 0 a 6).** A Fase 7 (expansão online) é
opcional/futura e **não foi iniciada**, conforme instrução explícita de aguardar validação do
usuário antes de começá-la.

---

## 2026-07-23 — Fase 7 concluída (Expansão Online)

Com autorização explícita do usuário, a última fase do roadmap original (opcional/futura) foi
implementada. Diferente das Fases 0-6, o texto original da Fase 7 é escrito com verbos de
planejamento/avaliação, não uma checklist rígida — dois dos três itens foram implementados de
verdade (formato + verificação de atualização), o terceiro (telemetria) foi tratado como uma
avaliação por escrito, não como uma ordem para construir coleta de dados de usuários. Resumo do
que foi feito:

### 7.1/7.2 Formato de base remota + verificação de atualização

- **Schema da base remota**: reusa exatamente o mesmo schema de `knowledge-base.json` local
  (`nome`, `tipo`, `classificacao`, `descricao`, `impacto_desativar`, `impacto_manter` por item) —
  nenhum formato novo foi inventado. O topo do JSON ganhou dois campos novos, tanto no arquivo
  local (`src/main/resources/knowledge-base.json`, agora com `"version": "1.0"` e `"updatedAt":
  "2026-07-23"`) quanto no remoto, para permitir comparar se há uma versão mais nova disponível.
- **`KnowledgeBase.java`** (`knowledge/`, refatorado): a lógica de parse (antes só
  `loadFromClasspath()`) virou um método estático reutilizável `parse(InputStream)` que devolve um
  `LoadedData` (entradas + `version` + `updatedAt`) — usado tanto para carregar do classpath quanto
  para inspecionar um JSON remoto já baixado, sem duplicar o parser (YAGNI: um schema, um parser).
  Ganhou também um construtor novo `KnowledgeBase(Path cacheFilePath)` que tenta carregar
  primeiro de um arquivo de cache local; se o arquivo não existir ou falhar ao parsear (por
  qualquer motivo), cai de volta para o recurso embutido no classpath sem lançar exceção. O
  construtor sem argumentos continua exatamente como antes (usado por toda a UI e pelos demos
  anteriores — nenhum código existente foi quebrado).
- **`RemoteKnowledgeUpdater.java`** (`knowledge/`, novo): usa `java.net.http.HttpClient`
  (biblioteca padrão do Java 11+, nenhuma dependência nova adicionada) com timeout curto (5s de
  conexão + 5s de request) para baixar o JSON remoto de uma URL HTTP(S) configurável.
  `checkForUpdate(url)` baixa, faz o parse (reusando `KnowledgeBase.parse`), compara
  `version`/`updatedAt` remoto com o local e devolve um `UpdateCheckResult` (record) — nunca lança
  exceção: qualquer falha (sem internet, host fora do ar, timeout, JSON malformado, status HTTP
  != 200) é capturada e devolvida como `available=false` com uma mensagem clara em português,
  mantendo a base local intacta como fallback. `applyUpdate(result)` salva o JSON bruto em
  `%USERPROFILE%\.nitroboost\remote-knowledge-base-cache.json` — **nunca** sobrescreve o recurso
  original do classpath (`src/main/resources/knowledge-base.json`), só o arquivo de cache no
  perfil do usuário, seguindo o mesmo padrão de pasta já usado pelo `DatabaseManager` desde a
  Fase 0.
- **Configuração da URL sem precisar de UI nova**: `resolveConfiguredUrl()` lê
  `%USERPROFILE%\.nitroboost\remote-config.properties` (chave `remoteKnowledgeBaseUrl`); se o
  arquivo não existir ou a chave não estiver definida, usa `RemoteKnowledgeUpdater.DEFAULT_REMOTE_URL`
  (uma constante documentada, apontando hoje para o Gist de exemplo público usado no teste real —
  ver seção 7.3). Não há URL de produção "oficial" definida ainda; essa é uma decisão de deploy
  deliberadamente deixada para depois, conforme a tarefa pediu.

### 7.3 Teste real via console (`Phase7ConsoleDemo`)

Como o repositório GitHub do projeto é **privado**, `raw.githubusercontent.com` exigiria um token
de autenticação para servir o JSON — o que inviabilizaria um cliente simples sem exigir que todo
usuário final tivesse credenciais do GitHub do desenvolvedor. Para testar de ponta a ponta com uma
URL de verdade, publicado um **Gist público** (via `gh gist create`, já autenticado neste
ambiente) com um `remote-knowledge-base.json` de exemplo (3 itens, `version: "1.1"`, `updatedAt:
"2026-07-24"` — deliberadamente mais novo que a base local):

**URL usada no teste:**
`https://gist.githubusercontent.com/JBergaminPurpletrap/e9690bc766d352089d1ff46f95a3534c/raw/remote-knowledge-base.json`
(Gist: `https://gist.github.com/JBergaminPurpletrap/e9690bc766d352089d1ff46f95a3534c`)

`./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase7ConsoleDemo` — dois cenários, ambos com
sucesso:

1. **Caminho de sucesso** (URL pública real): baixou o JSON, comparou versão/data corretamente
   (`remoteVersion=1.1` vs local `1.0`, `remoteUpdatedAt=2026-07-24` vs local `2026-07-23` →
   `newerThanLocal=true`), aplicou (`applyUpdate` salvou o cache local) e confirmou que uma
   `KnowledgeBase` recarregada a partir do cache (`new KnowledgeBase(cacheFilePath)`) reflete o
   conteúdo remoto — inclusive um item fabricado que só existe na base remota
   (`ExemploItemNovoRemoto`), encontrado com sucesso após a atualização.
2. **Caminho de falha** (URL inválida/inacessível, `https://este-host-nao-existe.invalido
   .nitroboost-teste/base.json`): `checkForUpdate` devolveu `available=false` com mensagem clara
   (`ConnectException` capturada, sem propagar exceção), e a base local (`new KnowledgeBase()`)
   continuou intacta e idêntica (`versao=1.0`, 66 itens) após a tentativa.

O arquivo de cache de teste (`%USERPROFILE%\.nitroboost\remote-knowledge-base-cache.json`) foi
removido manualmente após a validação, para não deixar resíduo de teste na máquina.

### 7.4 Avaliação de telemetria (sem implementação de coleta)

Escrita em `docs/telemetria-avaliacao.md`: o que seria coletado, por que alguém pediria, riscos de
privacidade específicos deste tipo de app (combinações de bloatware/processos instalados já são
quase uma impressão digital da máquina), esforço de implementação (exigiria infraestrutura de
servidor nova, que o projeto não tem hoje), e três alternativas comparadas. **Recomendação:** não
implementar telemetria agora — a tabela `actions_history` já existente (SQLite local, desde a Fase
2) já cobre a maior parte do valor prático ("quais ações eu mais uso") sem nenhum dado saindo da
máquina; revisitar telemetria de verdade (opt-in, com infraestrutura própria) só se o projeto
ganhar uma base de usuários real fora do desenvolvedor. Nenhuma coleta de dados foi implementada
nesta fase.

### Build e testes

- `./mvnw -q compile` — OK, sem erros, após todas as mudanças (`KnowledgeBase` refatorado,
  `RemoteKnowledgeUpdater` novo, `Phase7ConsoleDemo` novo, `knowledge-base.json` com campos novos).
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.Phase7ConsoleDemo` — roda até o fim, os dois
  cenários (sucesso contra Gist público real + falha contra URL inválida) confirmados com sucesso
  (ver saída completa na seção 7.3).
- `./mvnw -q exec:java -Dexec.mainClass=com.nitroboost.knowledge.KnowledgeBase` e
  `...RemoteKnowledgeUpdater` — testados isoladamente antes da integração no demo, seguindo o
  mesmo padrão das fases anteriores (regra de ouro #9).

Nenhum bloqueio técnico foi encontrado durante esta fase (ver `BLOCKERS.md` — sem itens novos da
Fase 7).

Checklist da Fase 7 marcado em `NITRO-BOOST-documentacao-completa.md`: os dois itens de
implementação (formato + verificação de atualização) `[x]`; o item de telemetria permanece `[ ]`
(não é uma tarefa de implementação, é avaliação) com uma nota apontando para este documento e para
`docs/telemetria-avaliacao.md`.

**Esta é a conclusão de todo o roadmap original do projeto (Fases 0 a 7).** Todo o escopo
planejado em `NITRO-BOOST-documentacao-completa.md` está implementado.

---

## 2026-07-23 — Fase 8 - Correção (bug do Bloatware mostrando poucos itens)

Corrigido bug diagnosticado em `NITRO-BOOST-fase8-debloat-completo.md`: a categoria "Bloatware"
na tela de resultados mostrava só ~15 apps de ~134 instalados de verdade.

- **Causa raiz:** `SystemScanTask.scanBloatware()` chamava `BloatwareScanner.knownBloatware()`,
  que descarta silenciosamente qualquer app com `Category.UNKNOWN`.
- **Correção:** trocado para `BloatwareScanner.scan()` (retorna todos os apps). A classificação
  de itens não catalogados já caía graciosamente em `ItemClassification.DEPENDE` com a descrição
  padrão "Item ainda não catalogado" (não precisou de mudança nessa parte).
- **Novo campo `ScannedItem.catalogued`**: `true` quando o item tem uma entrada real na
  `KnowledgeBase` (não a classificação/descrição de fallback). Setado em `SystemScanTask.build()`.
- **Novo `CheckBox` "Mostrar apenas itens conhecidos"** na `ScanResultsView`, ligado por padrão
  (reduz ruído visual mostrando só os catalogados), combinado com os filtros de categoria e status
  já existentes. O usuário pode desligar para ver a lista completa de todos os apps instalados,
  em qualquer categoria (não só Bloatware).

**Validado via console** (`./mvnw exec:java -Dexec.mainClass=com.nitroboost.Phase3ConsoleDemo`,
seção 3.4, que já chamava `BloatwareScanner.scan()` diretamente): confirmado **134 apps UWP
instalados** nesta máquina, dos quais **16 reconhecidos** como bloatware conhecido — bate
exatamente com o diagnóstico do documento (~15 de ~134).

`./mvnw -q compile` — OK, sem erros.

Parte 1 concluída. Aguardando validação do usuário antes de seguir para a Parte 2 (novas
categorias IA e Recursos de Consumidor), conforme instruído em
`prompt-fase8-debloat-completo.md`.
