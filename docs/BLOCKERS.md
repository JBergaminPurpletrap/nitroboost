# 🚧 BLOCKERS — NITRO BOOST

Registro de bloqueios técnicos encontrados durante o desenvolvimento, conforme regra de ouro do
projeto (nunca pular uma tarefa silenciosamente por causa de um bloqueio).

---

## Fase 0 — Setup e Fundamentos

### 1. Maven não instalado globalmente no ambiente de desenvolvimento
- **Status:** Resolvido.
- **Descrição:** o comando `mvn` não estava disponível no `PATH` do ambiente usado para o setup
  inicial (`mvn -version` → "command not found").
- **Solução aplicada:** foi gerado o **Maven Wrapper** manualmente — os scripts oficiais `mvnw` e
  `mvnw.cmd` foram baixados do repositório `apache/maven-wrapper` (branch `master`,
  `maven-wrapper-distribution/src/resources/`), e `.mvn/wrapper/maven-wrapper.properties` foi
  criado apontando para:
  - `distributionUrl`: Apache Maven 3.9.9 (binário oficial em `repo.maven.apache.org`)
  - `wrapperUrl`: `maven-wrapper` 3.3.2 (jar oficial em `repo.maven.apache.org`)
  O wrapper foi validado com `./mvnw.cmd -v`, que baixou o Maven 3.9.9 para
  `%USERPROFILE%\.m2\wrapper\dists\...` e reportou a versão corretamente. A partir daí, todo o
  projeto usa exclusivamente `./mvnw` / `./mvnw.cmd` — **não é necessário instalar Maven** na
  máquina de quem for rodar o projeto.
- **Nenhuma ação pendente** — documentado no `README.md` ("Pré-requisitos" / "Como rodar").

### 2. Validação da janela JavaFX travou o fluxo automatizado (stall)
- **Status:** Resolvido / contornado.
- **Descrição:** ao tentar validar visualmente o critério de conclusão da Fase 0 ("o app abre uma
  janela JavaFX vazia"), uma tentativa de rodar o goal `javafx:run` (ou uma main class que chama
  `Application.launch()`) de forma síncrona ficou aguardando a janela ser fechada pelo usuário —
  processo que nunca retorna sozinho neste ambiente sem sessão gráfica interativa/headless,
  travando o comando indefinidamente (stall de ~600s sem progresso).
- **Solução aplicada:** a validação da Fase 0 neste ambiente automatizado foi limitada a:
  - `./mvnw.cmd -q compile` e `./mvnw.cmd -q package -DskipTests` para confirmar que o projeto
    compila e empacota sem erros (retornam sozinhos, não abrem UI).
  - Classes utilitárias com `main()` próprio que imprimem no console e terminam sozinhas
    (`HardwareInfoPrinter`, `JnaNativeTest`, `DatabaseManager`), rodadas via
    `exec:java -Dexec.mainClass=...`, sempre com `timeout` no shell como rede de segurança.
  - A abertura real da janela JavaFX (`Stage.show()`) **não foi validada visualmente** neste
    ambiente — a lógica de `Main.java` foi revisada manualmente e o build compila sem erros até o
    ponto de chamar `primaryStage.show()`, mas a confirmação visual definitiva (a janela realmente
    aparece, com o título correto, sem exceção no `Application.start()`) precisa ser feita pelo
    usuário na máquina real com sessão gráfica.
- **Ação pendente:** usuário deve rodar `.\mvnw.cmd clean javafx:run` na própria máquina (Windows
  com sessão gráfica) para confirmar visualmente a janela. Comando documentado no `README.md` e no
  relatório final desta fase.
- **Regra adotada daqui para frente:** nenhum comando que inicie uma UI JavaFX bloqueante
  (`javafx:run`, `Application.launch()` síncrono) deve ser executado diretamente por automações
  neste ambiente. Sempre usar `timeout` como rede de segurança em comandos com risco de não
  retornar sozinhos.

### 3. Bug no parser simplificado do `schema.sql` (semicolon dentro de comentário)
- **Status:** Resolvido.
- **Descrição:** a primeira versão de `DatabaseManager.initializeSchema()` removia apenas linhas
  que começavam com `--` antes de fazer `split(";")` no conteúdo do `schema.sql`. Um comentário
  inline continha um `;` no meio do texto (ex: "-- flag de conveniencia; a fonte de verdade..."),
  o que quebrou uma instrução `CREATE TABLE` ao meio e gerou o erro
  `[SQLITE_ERROR] SQL error or missing database (incomplete input)`.
- **Solução aplicada:** o parser agora remove todo o conteúdo a partir de `--` em qualquer ponto da
  linha (não só quando a linha inteira é um comentário), antes de concatenar e fazer o split por
  `;`. O comentário problemático em `schema.sql` também foi reescrito sem `;`. Reexecutado o teste
  (`exec:java -Dexec.mainClass=com.nitroboost.db.DatabaseManager`) e as 4 tabelas
  (`items`, `actions_history`, `backups`, `locks`) foram criadas corretamente.
- **Nota para o futuro:** esse parser de schema é propositalmente simples (split ingênuo por `;`),
  suficiente para o `schema.sql` atual. Se o schema crescer e passar a usar triggers, strings com
  `;` dentro, ou blocos `BEGIN...END`, será necessário um parser mais robusto (ou executar o
  arquivo inteiro de uma vez, se o driver SQLite JDBC permitir múltiplas statements em uma
  chamada).

---

## Fase 6 — Refinamento, Generalização e Empacotamento

### 4. Teste em uma segunda máquina/VM com hardware diferente — impossível neste ambiente
- **Status:** Não resolvido (limitação do ambiente, não do código).
- **Descrição:** o critério de conclusão da Fase 6 pede validação em uma máquina/VM diferente da
  de desenvolvimento. Este ambiente automatizado tem acesso a **uma única máquina Windows** (a
  própria máquina de desenvolvimento) — não há uma segunda máquina real nem um hypervisor/VM
  disponível para provisionar uma instância limpa do Windows 11.
- **Mitigação aplicada:** como mitigação parcial, foi feita uma varredura completa do código em
  busca de qualquer caminho, GUID, nome de usuário ou nome de máquina fixado (hardcoded) que
  pudesse impedir o app de rodar em outra máquina — nenhum item hardcoded foi encontrado (ver
  `PROGRESS.md`, seção "Fase 6", para o detalhe da varredura). Isso reduz o risco, mas não
  substitui a validação real.
- **Ação pendente:** o usuário deve rodar o pacote gerado (`target\dist\NitroBoost\NitroBoost.exe`,
  copiando a pasta inteira) em uma segunda máquina Windows 11 real (ou uma VM criada manualmente,
  ex: Hyper-V/VirtualBox) e confirmar que o app abre e funciona sem qualquer ajuste manual no
  código/configuração.

### 5. Instalador `.msi` — WiX Toolset (achado: já está disponível nesta máquina)
- **Status:** Não é um bloqueio de fato — registrado aqui apenas para deixar claro o que foi
  testado, já que a instrução da Fase 6 pedia documentar exatamente esse tipo de obstáculo caso
  ocorresse.
- **Descrição:** a documentação do projeto antecipava que gerar um instalador `.msi` de verdade via
  `jpackage --type msi` provavelmente exigiria o WiX Toolset instalado (ferramenta que normalmente
  não vem com o JDK nem é instalada por padrão no Windows). Ao testar nesta máquina, o comando
  `jpackage --type msi ...` **funcionou e gerou `NitroBoost-1.0.0.msi` (~87 MB) sem erro**,
  indicando que o WiX Toolset já está instalado neste ambiente (não foi instalado por esta fase).
- **Decisão tomada:** mesmo com o `.msi` funcionando aqui, o empacotamento padrão do projeto
  (`scripts\jpackage-build.bat`) continua usando `--type app-image` (pasta standalone com o `.exe`,
  sem instalador), porque é o tipo mais simples e **não depende de nenhuma ferramenta externa** -
  continua funcionando em qualquer máquina com JDK 21, mesmo que o WiX não esteja instalado nela.
  O comando exato para gerar o `.msi`, para quem quiser um instalador de verdade nesta máquina (ou
  em outra que já tenha o WiX), está documentado no `README.md` (seção "Empacotamento").
- **Nenhuma ação pendente.**

---

## Fase 9 - Parte 1 (Novos scanners de Performance e Jogos)

### 6. Escrita em chaves HKLM (Performance/Jogos) e em `powercfg /hibernate` exige Administrador
- **Status:** Não é um bug - comportamento esperado do Windows, documentado aqui conforme instrução
  explícita da Fase 9.
- **Descrição:** das 6 chaves de `PerformanceScanner` e 4 chaves de `GamingScanner`, as que ficam em
  `HKLM` (GPU Hardware-Accelerated Scheduling, Fast Startup, Network Throttling Index, Delivery
  Optimization, Prioridade de Processador para Jogos) exigem privilégio de Administrador para
  **escrever** (a leitura funciona normalmente sem elevação, todas foram lidas com sucesso). O
  arquivo de hibernação (`powercfg /hibernate on|off`) também exige elevação. Testado nesta máquina
  de desenvolvimento (sessão de console **sem** privilégio de Administrador): todas as tentativas de
  escrita nessas chaves falharam com `ERRO: Acesso negado` (registro) ou erro `0x65b` (`powercfg`),
  exatamente o comportamento esperado.
- **Mitigação aplicada:** a **lógica** de cada chamada (comando montado corretamente - caminho da
  chave, nome do valor, tipo `REG_DWORD`, valor a gravar) foi validada mesmo com a escrita falhando -
  o `ActionExecutor` trata a falha de forma graciosa (sem exceção, backup permanece intacto para uma
  tentativa futura, histórico registra a falha com mensagem clara mencionando a necessidade de rodar
  como Administrador). Confirmado, para cada chave HKLM testada, que **nada foi alterado no
  registro/disco** (leitura direta pós-tentativa idêntica ao valor original) - nenhum risco à máquina
  de desenvolvimento.
- **Ação pendente:** nenhuma ação de código pendente - este é o comportamento correto e esperado. Ao
  rodar o NITRO BOOST publicado (`NitroBoost.exe`) como Administrador (via `run-as-admin.bat`, já
  existente desde a Fase 6), essas 5 ações passam a funcionar normalmente. Documentado também nas
  mensagens de erro exibidas ao usuário final na UI (via `ItemDetailView`/`ScanResultsView`, que já
  exibem a mensagem de falha completa retornada pelo `ActionExecutor`).

---

## Fase 8 - Parte 2 (Debloat Real Completo, incluindo IAs do Windows 11)

### 7. Escrita em chaves HKLM (IA/Consumidor) exige Administrador - mesma limitação do item 6
- **Status:** Não é um bug - mesmo comportamento esperado do Windows já documentado no item 6 acima
  (Fase 9 Parte 1), agora confirmado também para as categorias novas desta fase.
- **Descrição:** das 7 chaves de `AiFeatureScanner` e 12 chaves de `ConsumerFeatureScanner`, as que
  ficam em `HKLM` (Windows Copilot para Todos os Usuários, Windows Recall, Click to Do, Cocreator,
  Copilot no Edge, Pesquisa do Bing via Política, Apps em Segundo Plano - 8 chaves no total) exigem
  privilégio de Administrador para **escrever** (a leitura funciona normalmente sem elevação, todas
  as 19 chaves foram lidas com sucesso). Testado nesta máquina de desenvolvimento (sessão de console
  **sem** privilégio de Administrador, via `Phase8Part2ConsoleDemo`): todas as 8 tentativas de
  escrita nessas chaves HKLM falharam com `ERRO: Acesso negado`, exatamente o comportamento esperado.
- **Mitigação aplicada:** idêntica ao item 6 - a lógica de cada chamada foi validada mesmo com a
  escrita falhando (comando montado corretamente, backup intacto, histórico com mensagem clara).
  Confirmado, para cada chave HKLM testada, que **nada foi alterado no registro** (leitura direta
  pós-tentativa idêntica ao valor original). As **11 chaves HKCU restantes** (1 de IA + 10 de
  Consumidor) foram alteradas e revertidas com sucesso, confirmadas restauradas ao valor original via
  leitura direta pós-restore.
- **Ação pendente:** nenhuma - mesmo caminho de mitigação do item 6 (rodar como Administrador via
  `run-as-admin.bat` resolve as 8 ações que exigem HKLM).

### 8. Nenhum pacote Appx local corresponde às categorias `AI_RECALL`/`AI_CLICK_TO_DO`/`AI_COCREATOR`
- **Status:** Divergência esperada, não é um bug.
- **Descrição:** `BloatwareScanner.classify()` foi expandido com detecção por substring para esses
  três recursos, mas `scan()` nesta máquina não encontrou nenhum pacote Appx correspondente. Isso é
  esperado: diferente do Copilot (que tem apps Appx dedicados em algumas instalações), Recall/Click
  to Do/Cocreator são majoritariamente recursos inbox/políticas do Windows, não pacotes UWP
  instaláveis separadamente - por isso o mecanismo principal de controle deles é o
  `AiFeatureScanner` (políticas de registro), não o `BloatwareScanner` (desinstalação de pacote). A
  detecção por nome de pacote foi mantida no `BloatwareScanner` como cobertura defensiva/futura,
  conforme pedido explícito no documento da Fase 8, mesmo sem nenhuma correspondência nesta máquina.
- **Ação pendente:** nenhuma - comportamento correto, documentado para não ser confundido com um
  scanner quebrado caso alguém rode `Phase3ConsoleDemo`/`BloatwareScanner` e note 0 resultados nessas
  3 categorias.

### 7. Inconsistência pré-existente no nome usado para lock em `setTelemetryValue` (Fase 3) - não corrigida nesta fase
- **Status:** Registrado, não corrigido (fora do escopo desta tarefa).
- **Descrição:** `ActionExecutor.setTelemetryValue` (Fase 3) usa `definition.id()` (ex:
  `"allow_telemetry_policy"`) como nome do item para o catálogo/lock/histórico internamente, enquanto
  a UI (`SystemScanTask.scanTelemetry`, `ScanResultsView`, `ItemDetailView`) exibe e bloqueia o item
  pelo `friendlyName()` (ex: `"Nivel de Telemetria (Politica de Grupo)"`). Como
  `LockManager.isLocked` é consultado por nome exato, bloquear um item de Telemetria pela tela
  (`Bloquear`) grava o bloqueio sob o nome amigável, mas `setTelemetryValue` verifica o bloqueio sob
  o id curto - os dois nunca coincidem, então **bloquear um item de Telemetria pela UI não impede,
  de fato, a ação de alterá-lo** (o lock fica "órfão", sem nunca ser encontrado pela verificação).
- **Por que não foi corrigido agora:** fora do escopo explícito desta tarefa (Fase 9 Parte 1), que
  pediu para não alterar `setTelemetryValue`/`restoreTelemetryValue` para não arriscar uma regressão
  em código já testado desde a Fase 3. O risco prático também é baixo (o item ainda pode ser
  desbloqueado/bloqueado sem erro, e a ação de "set" real continua passando por backup+histórico
  normalmente - só a *recusa por bloqueio* específica não funciona para este tipo).
- **Mitigação aplicada nesta fase:** as categorias novas (`performance`, `gaming`) foram implementadas
  desde o início usando o **nome amigável consistentemente** em `ScannedItem`, `LockManager` e
  `ActionExecutor` (ver `PROGRESS.md`, Fase 9 Parte 1) - confirmado funcionando corretamente no teste
  de bloqueio/desbloqueio real (`Phase9Part1ConsoleDemo`, seção 9.1). O mesmo bug não foi introduzido
  nas categorias novas.
- **Ação pendente:** corrigir `setTelemetryValue`/`restoreTelemetryValue` para usar `friendlyName()`
  em vez de `id()` como nome do item (mesmo padrão agora usado em `performance`/`gaming`), com um
  teste de regressão específico para confirmar que o bloqueio de um item de Telemetria real passa a
  funcionar. Fica para uma fase de manutenção/polimento futura, fora do escopo atual.

---

## Fase 10 - Parte 1 (Limpeza de RAM, estilo RAMMap)

### 9. Validação do caminho de sucesso de `MemoryCleaner` exige elevação interativa (UAC) - mesma limitação de fundo do item 2
- **Status:** Não resolvido neste ambiente automatizado (limitação de ambiente, não do código) -
  caminho de falha totalmente validado; caminho de sucesso pendente de validação manual do usuário.
- **Descrição:** o terminal usado para rodar `./mvnw exec:java` neste ambiente **não estava elevado**
  (Administrador), então `MemoryCleaner.purgeStandbyList()` recusou o privilégio
  `SeProfileSingleProcessPrivilege` com `ERROR_NOT_ALL_ASSIGNED` (código 1300) - comportamento
  correto e esperado, tratado sem exceção e registrado no histórico como falha (ver
  `Phase10Part1ConsoleDemo`/`PROGRESS.md`). Uma tentativa de validar também o caminho de **sucesso**,
  reexecutando o mesmo comando elevado via `Start-Process -Verb RunAs`, ficou travada aguardando o
  clique interativo em "Sim" no prompt de UAC do Windows - este ambiente automatizado não tem sessão
  gráfica interativa para confirmar esse prompt (mesma causa raiz do item 2/Fase 0, que documentou a
  mesma limitação para `javafx:run`).
- **Mitigação aplicada:** a tentativa elevada foi cancelada (nenhum processo travado ficou para trás).
  Como o código de tratamento de privilégio/erro é o mesmo em ambos os caminhos (só o valor de retorno
  de `NtSetSystemInformation` muda - `0` em vez de recusa de privilégio), o caminho de erro validado
  cobre toda a lógica de `MemoryCleaner` exceto a chamada nativa bem-sucedida em si.
- **Ação pendente:** o usuário deve rodar o NITRO BOOST como Administrador (fluxo normal de produção,
  via `run-as-admin.bat` já existente desde a Fase 6, ou clicando em "🧹 LIMPAR CACHE DE RAM AGORA" no
  Dashboard) e confirmar visualmente que o resultado mostra `sucesso=true` e a RAM livre
  aumentando/mudando após o clique.

---

## Itens sem bloqueio (apenas para referência)

- Repositório GitHub remoto: criado com `gh repo create nitroboost --private --source=. --remote=origin`
  (usuário `gh` já estava autenticado com os scopes necessários — nenhum bloqueio aqui).
- Todas as dependências (JavaFX, OSHI, JNA, SQLite JDBC, Jackson) foram resolvidas normalmente do
  Maven Central — sem bloqueio de rede/proxy neste ambiente.
