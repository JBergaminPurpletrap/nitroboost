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
