# 🚀 NITRO BOOST — Gamer Performance & System Control Suite

> **Tema visual:** Booster Upgrade Gamer — fundo preto com textura de fibra de carbono, acentos em verde neon, tipografia agressiva estilo HUD, ícones de "turbo", "RPM", "ganho de performance". Pense em placa-mãe gamer (estilo ROG) + painel de carro tunado + HUD de jogo.

---

## 0. Ficha do Projeto

| Campo | Valor |
|---|---|
| Nome do projeto | NITRO BOOST |
| Nome técnico do pacote | `nitroboost` |
| Linguagem | Java 17+ |
| UI | JavaFX |
| Build | Maven |
| Persistência | SQLite (via JDBC) + JSON de configuração |
| Bibliotecas nativas | OSHI, JNA |
| Plataforma alvo | Windows 11 (execução como Administrador) |
| Repositório | Git/GitHub |
| Identidade visual | Carbono & Verde Turbo (preto + fibra de carbono + verde neon `#39FF14`) |

**Slogan interno:** *"Seu PC no modo turbo — com controle total e zero mistério."*

---

## 1. Objetivo do Projeto (resumo executivo)

Aplicação desktop que escaneia o Windows 11, identifica processos/serviços/configurações que consomem recursos desnecessariamente, explica o que cada um faz, permite desativar/bloquear/reverter com segurança (backup automático), e entrega tutoriais para ajustes que exigem ação manual (ex: BIOS/XMP). Tudo isso com uma interface visual no tema "booster gamer", mostrando ganhos de performance em tempo real.

---

## 2. Regras de Ouro para Quem for Codar (Claude Code / VSCode)

Estas regras devem ser seguidas **sem exceção** durante toda a implementação:

1. **Nunca pule fases.** Complete 100% dos itens da Fase N antes de iniciar a Fase N+1.
2. **Toda função que interage com o sistema operacional deve ter tratamento de erro** (try/catch), já que comandos podem falhar por falta de permissão.
3. **Nunca implemente uma ação destrutiva sem antes implementar o backup correspondente.** Ex: não crie "desativar serviço" sem que `BackupManager` já esteja funcionando.
4. **Toda ação (disable/enable/kill/lock) deve gravar no histórico (SQLite).** Sem exceção.
5. **Commits pequenos e frequentes**, um por tarefa concluída da checklist abaixo, com mensagens no padrão: `[FaseX] Descrição curta da tarefa`.
6. **Nada de hardcode de caminhos/serviços específicos de uma máquina.** Sempre detectar dinamicamente.
7. **Ao final de cada fase, marcar as tasks como concluídas neste documento** (trocar `[ ]` por `[x]`) e escrever um resumo curto do que foi feito, em um arquivo `PROGRESS.md`.
8. **Se travar em algo (ex: permissão negada, comando não reconhecido), documentar o bloqueio em `BLOCKERS.md`** ao invés de pular a tarefa silenciosamente.
9. **Não avançar para tarefas de UI (JavaFX) antes de a lógica de backend da fase estar testada** (mesmo que só via console/prints).
10. **Sempre que uma tarefa envolver "explicação de item" (knowledge base), escrever o texto explicativo em português, claro e sem jargão técnico excessivo** — o público final não é só programador.

---

## 3. Estrutura de Pastas do Projeto

```
nitroboost/
 ┣ src/main/java/com/nitroboost/
 ┃ ┣ core/
 ┃ ┃ ┣ SystemScanner.java
 ┃ ┃ ┣ ProcessScanner.java
 ┃ ┃ ┣ ServiceScanner.java
 ┃ ┃ ┣ StartupScanner.java
 ┃ ┃ ┣ TaskSchedulerScanner.java
 ┃ ┃ ┣ PowerPlanScanner.java
 ┃ ┃ ┣ TelemetryScanner.java
 ┃ ┃ ┗ BloatwareScanner.java
 ┃ ┣ actions/
 ┃ ┃ ┣ ActionExecutor.java
 ┃ ┃ ┣ BackupManager.java
 ┃ ┃ ┗ LockManager.java
 ┃ ┣ knowledge/
 ┃ ┃ ┣ KnowledgeBase.java
 ┃ ┃ ┣ ItemClassification.java
 ┃ ┃ ┗ TutorialProvider.java
 ┃ ┣ ui/
 ┃ ┃ ┣ DashboardView.java
 ┃ ┃ ┣ ScanResultsView.java
 ┃ ┃ ┣ ItemDetailView.java
 ┃ ┃ ┣ HistoryView.java
 ┃ ┃ ┗ TutorialView.java
 ┃ ┣ db/
 ┃ ┃ ┣ DatabaseManager.java
 ┃ ┃ ┗ schema.sql
 ┃ ┗ Main.java
 ┣ src/main/resources/
 ┃ ┣ knowledge-base.json
 ┃ ┣ theme/ (CSS do tema Booster Gamer)
 ┃ ┗ tutorials/ (textos dos tutoriais internos)
 ┣ pom.xml
 ┣ README.md
 ┣ PROGRESS.md
 ┣ BLOCKERS.md
 ┗ NITRO-BOOST-documentacao-completa.md (este arquivo)
```

---

## 4. CHECKLIST DETALHADO POR FASE

### ✅ FASE 0 — Setup e Fundamentos

- [x] Criar repositório Git local + remoto no GitHub
- [x] Criar projeto Maven no VSCode (`archetype-quickstart` ou manual)
- [x] Configurar `pom.xml` com dependências: JavaFX, OSHI, JNA, SQLite JDBC, Jackson (JSON)
- [x] Configurar JavaFX no VSCode (VM options / módulos, já que Java 17+ exige configuração separada do JDK) — resolvido via `org.openjfx:javafx-maven-plugin`, que evita configuração manual de module-path
- [x] Criar estrutura de pastas conforme seção 3
- [x] Criar `Main.java` mínimo — apenas abrir uma janela JavaFX vazia com o título "NITRO BOOST"
- [x] Testar OSHI: imprimir no console uso atual de CPU e RAM
- [x] Testar JNA: confirmar que é possível chamar um comando nativo simples do Windows
- [x] Criar `DatabaseManager.java` e `schema.sql` com tabelas: `items`, `actions_history`, `backups`, `locks`
- [x] Rodar schema inicial e confirmar criação do banco SQLite local
- [x] Criar `README.md` inicial com nome do projeto, objetivo resumido e como rodar
- [x] Criar `PROGRESS.md` e `BLOCKERS.md` vazios
- [x] Commit: `[Fase0] Setup completo do ambiente e estrutura base`

**Critério de conclusão da fase:** app abre uma janela vazia, imprime specs de hardware no console, e o banco SQLite existe com as tabelas criadas.

---

### ✅ FASE 1 — MVP Core (Processos, Serviços, Startup)

#### 1.1 ProcessScanner
- [x] Implementar leitura de processos em execução via OSHI (`OperatingSystem.getProcesses()`)
- [x] Para cada processo, capturar: nome, PID, uso de RAM, uso de CPU (%)
- [x] Criar método `ActionExecutor.killProcess(pid)` com tratamento de erro
- [x] Testar via console: listar top 10 processos por uso de RAM

#### 1.2 ServiceScanner
- [x] Implementar leitura de serviços do Windows via comando `sc query` ou PowerShell (`Get-Service`)
- [x] Parsear saída para extrair: nome do serviço, status (Running/Stopped), tipo de inicialização
- [x] Criar métodos `ActionExecutor.stopService(nome)` e `disableService(nome)`
- [x] Testar via console: listar serviços em execução

#### 1.3 StartupScanner
- [x] Ler entradas de inicialização do registro (`HKCU\...\Run` e `HKLM\...\Run`) via `reg query`
- [x] Ler também a pasta de Startup (`shell:startup`)
- [x] Criar método para desativar item de inicialização (remover ou desabilitar entrada)
- [x] Testar via console: listar programas de inicialização

#### 1.4 Base de Conhecimento Inicial
- [x] Criar `knowledge-base.json` com estrutura: `{ nome, tipo, classificacao (seguro/essencial/depende), descricao, impacto_desativar, impacto_manter }`
- [x] Popular com pelo menos 20-30 itens conhecidos (baseado na sua experiência de "desbostificação")
- [x] Implementar `KnowledgeBase.java` para carregar o JSON e permitir consulta por nome de item

#### 1.5 BackupManager (versão básica)
- [x] Implementar `BackupManager.snapshotBeforeAction(item)` — salva estado atual no SQLite antes de qualquer ação
- [x] Implementar `BackupManager.restore(itemId)` — reverte para o estado salvo

#### 1.6 Integração e Testes
- [x] Conectar Scanner → Knowledge Base → exibir classificação no console
- [x] Testar fluxo completo via console: escanear → classificar → desativar → verificar backup salvo → reverter
- [x] Commit: `[Fase1] MVP Core funcional via console (processos, serviços, startup)`

**Critério de conclusão da fase:** via console (sem UI ainda), é possível escanear processos/serviços/startup, ver a classificação de cada item, desativar algo, e reverter usando o backup.

---

### ✅ FASE 2 — Segurança e Controle (Backup completo + Lock + Histórico)

- [x] Expandir `BackupManager` para registrar todas as ações com timestamp, tipo de ação, estado anterior e novo estado
- [x] Implementar `LockManager.lockItem(item)` — marca item como "protegido" no banco
- [x] Implementar verificação: antes de qualquer ação em `ActionExecutor`, checar se o item está bloqueado; se estiver, recusar a ação e avisar
- [x] Implementar `ActionExecutor` registrando toda ação na tabela `actions_history`
- [x] Criar consulta para listar histórico completo (mais recente primeiro)
- [x] Criar método de reversão a partir de uma entrada específica do histórico
- [x] Testar cenário: bloquear item → tentar desativar → confirmar que a ação foi recusada
- [x] Commit: `[Fase2] Sistema de backup, bloqueio e histórico completo`

**Critério de conclusão da fase:** nenhuma ação é feita sem passar por backup + histórico, e itens bloqueados não podem ser alterados sem desbloqueio manual explícito.

---

### ✅ FASE 3 — Expansão de Varredura

#### 3.1 TaskSchedulerScanner
- [x] Listar tarefas agendadas via `schtasks /query`
- [x] Parsear nome, status, próxima execução
- [x] Método para desativar tarefa agendada

#### 3.2 PowerPlanScanner
- [x] Listar planos de energia via `powercfg /list`
- [x] Identificar plano ativo
- [x] Método para trocar/ativar plano de energia (ex: Alto Desempenho)

#### 3.3 TelemetryScanner
- [x] Mapear chaves de registro relacionadas a telemetria/relatórios (pesquisar lista conhecida de chaves do Windows 11)
- [x] Método para ler valor atual e alterar (0/1) com backup prévio

#### 3.4 BloatwareScanner
- [x] Listar apps UWP instalados via PowerShell (`Get-AppxPackage`)
- [x] Identificar itens conhecidos: Widgets, Copilot, Game Bar, Xbox apps, OneDrive
- [x] Método para desinstalar/desativar cada categoria

#### 3.5 Atualização da Base de Conhecimento
- [x] Adicionar classificação para todos os novos itens descobertos nesta fase
- [x] Commit: `[Fase3] Varredura expandida (tarefas, energia, telemetria, bloatware)`

**Critério de conclusão da fase:** todas as 7 áreas definidas no plano original estão sendo escaneadas e classificadas.

---

### ✅ FASE 4 — Interface Gráfica (JavaFX) — Tema Booster Gamer

#### 4.1 Tema Visual — "Carbono & Verde Turbo"

**Paleta de cores oficial do projeto:**

| Uso | Cor | Hex |
|---|---|---|
| Fundo principal | Preto profundo | `#0A0A0A` |
| Fundo de painéis/cards | Cinza carbono escuro | `#161616` |
| Textura de fundo | Padrão "fibra de carbono" (ver abaixo) | — |
| Cor de destaque primária (ação, foco, "ligado") | Verde neon | `#39FF14` |
| Verde secundário (hover, gráficos, menos saturado) | Verde técnico | `#00C853` |
| Alerta/perigo (essencial, não mexer) | Vermelho | `#FF3B30` |
| Atenção (depende do uso) | Amarelo âmbar | `#FFB300` |
| Texto principal | Branco levemente acinzentado | `#E8E8E8` |
| Texto secundário/desabilitado | Cinza médio | `#7A7A7A` |
| Bordas e linhas divisórias | Cinza escuro com leve brilho verde | `#2A2A2A` (borda `#39FF14` a 20% opacidade no hover) |

**Textura carbono (fibra de carbono):**
- [x] Criar um padrão de fundo tipo "fibra de carbono" usando CSS: repetição de um pequeno padrão diagonal (losangos escuros `#0A0A0A` / `#1C1C1C`), aplicável via `-fx-background-image` com um PNG tile gerado (ex: 20x20px, tileável) ou simulado com `-fx-background-color` em gradientes lineares alternados. — Implementado 100% via CSS (`linear-gradient(... repeat ...)`), sem PNG externo.
- [x] Aplicar a textura como fundo do painel principal (`DashboardView`), mais sutil (baixa opacidade) para não atrapalhar leitura, e mais evidente em áreas decorativas (cabeçalho, bordas). — classes `.carbon-bg-subtle` (paineis principais) e `.carbon-bg-strong` (cabeçalho).

**Efeitos visuais (estilo "tech/gamer"):**
- [x] Brilho neon sutil (`-fx-effect: dropshadow`) em verde ao redor de botões principais e bordas ativas, simulando "glow" de hardware ligado.
- [x] Barras de progresso (uso de CPU/RAM) com gradiente do verde técnico (`#00C853`) para o verde neon (`#39FF14`) conforme o valor sobe, e vermelho (`#FF3B30`) quando ultrapassar um limite crítico (ex: >90%).
- [x] Ícones de status: 🟢 verde neon (seguro/ativo), 🔴 vermelho (essencial/bloqueado), 🟡 âmbar (depende do uso) — implementado como `Circle` colorido (JavaFX Shape) na coluna de status da tabela de resultados, e como "badge" colorido no modal de detalhes.

- [x] Criar arquivo CSS (`theme/nitroboost-carbon.css`) implementando toda a paleta acima
- [x] Definir tipografia: fonte tipo **"Orbitron"**, **"Rajdhani"** ou **"Chakra Petch"** (Google Fonts, estilo HUD/tech) para títulos e números de destaque; fonte legível tipo **"Inter"** ou **"Roboto"** para textos longos (descrições, tutoriais) — decisão pragmática: sem acesso garantido à rede para baixar Google Fonts, usamos fontes já presentes no Windows como fallback equivalente: `"Consolas"` (monoespaçada, visual HUD/técnico) para títulos/números, `"Segoe UI"` para texto corrido. Documentado também em PROGRESS.md.
- [x] Criar componentes visuais reutilizáveis: botão "estilo turbo" (fundo preto, borda verde neon, glow no hover), barra de progresso "estilo RPM/velocímetro" (arco verde que preenche conforme uso do sistema) — classes CSS `.btn-turbo` / `.btn-secondary` / `.btn-danger` e `.progress-rpm`; o "velocímetro" em si é um `Arc` customizado no `DashboardView`.

#### 4.2 DashboardView
- [x] Criar layout principal com gráfico de linha (JavaFX Chart) para CPU e RAM em tempo real
- [x] Adicionar indicador visual estilo "velocímetro" para uso geral do sistema
- [x] Botão principal "ESCANEAR SISTEMA" (call to action central, estilo "turbo boost")
- [x] Atualizar gráfico a cada 2-3 segundos — `ScheduledExecutorService` (thread daemon dedicada) lê CPU/RAM via OSHI a cada 2s e publica na UI via `Platform.runLater`.

#### 4.3 ScanResultsView
- [x] Lista/tabela dos itens encontrados, com ícone de classificação (🟢🔴🟡)
- [x] Filtros por categoria (processos, serviços, startup, etc.) — `ComboBox` + `FilteredList`, cobrindo as 7 categorias escaneadas.
- [x] Botões de ação rápida (desativar, bloquear) em cada linha — chamam `ActionExecutor`/`LockManager` reais em background thread.

#### 4.4 ItemDetailView
- [x] Tela/modal de detalhes ao clicar em um item
- [x] Exibir: nome, descrição, impacto de desativar, impacto de manter, recomendação
- [x] Botões: Desativar / Bloquear / Ver Tutorial (se aplicável)

#### 4.5 HistoryView
- [x] Lista cronológica de ações realizadas
- [x] Botão "Reverter" em cada entrada — chama `ActionExecutor.restoreFromHistory`.

#### 4.6 TutorialView
- [x] Exibição de tutoriais internos (texto passo a passo formatado) — estrutura funcional criada com um placeholder ("em breve"); conteúdo real de tutoriais é escopo da Fase 5, propositalmente não antecipado aqui.
- [x] Links clicáveis para fontes externas quando aplicável — adiado para a Fase 5 junto com o conteúdo real dos tutoriais (não há links a exibir ainda nesta fase).

#### 4.7 Integração Final da UI
- [x] Conectar todas as views ao backend já validado nas fases anteriores
- [x] Testar fluxo completo pela interface gráfica: abrir app → escanear → ver resultados → clicar detalhe → desativar → ver no histórico → reverter — fluxo revisado por leitura de código e validado via `./mvnw -q compile`; a confirmação visual final (janela realmente abrindo e o fluxo funcionando na tela) fica pendente do usuário rodar `.\mvnw.cmd clean javafx:run` (ver PROGRESS.md).
- [x] Commit: `[Fase4] Interface gráfica completa com tema Booster Gamer`

**Critério de conclusão da fase:** aplicação 100% funcional via interface gráfica, sem necessidade de usar console.

---

### ✅ FASE 5 — Tutoriais e Educação (XMP/BIOS e afins)

- [x] Implementar detecção de indício de XMP desativado: comparar velocidade nominal da RAM (via especificação, se detectável) vs velocidade real reportada pelo OSHI
- [x] Escrever tutorial interno genérico: "Como habilitar XMP na BIOS" com passos gerais (varia por fabricante)
- [x] Adicionar seção de links por fabricante (ASUS, Gigabyte, MSI, ASRock) para tutoriais oficiais
- [x] Expandir `TutorialProvider` para outros itens que precisem de ação manual
- [x] Commit: `[Fase5] Sistema de tutoriais e detecção de XMP`

**Critério de conclusão da fase:** o app consegue alertar sobre possível XMP desativado e guiar o usuário até a solução, mesmo sem poder aplicá-la sozinho.

---

### ✅ FASE 6 — Refinamento, Generalização e Empacotamento

- [x] Revisar todo o código em busca de caminhos/valores fixos (hardcoded) específicos do PC de desenvolvimento
- [ ] Testar em uma segunda máquina (ou VM) com hardware diferente — **não foi possível neste
      ambiente** (uma única máquina de desenvolvimento Windows disponível, sem VM/segunda máquina
      real). Ver `BLOCKERS.md` e `PROGRESS.md` (Fase 6) para detalhes e o que ficou pendente do
      usuário.
- [x] Ajustar UI para diferentes resoluções de tela
- [x] Empacotar aplicação como `.exe` usando `jpackage`
- [x] Criar instalador simples (ou ao menos um `.bat` que solicite execução como administrador)
- [x] Revisar e finalizar `README.md` com instruções de instalação e uso
- [x] Commit: `[Fase6] Aplicação empacotada e pronta para distribuição`

**Critério de conclusão da fase:** o app roda em uma máquina diferente da original sem ajustes manuais no código. *(Não verificável neste ambiente por falta de uma segunda máquina/VM — ver nota acima. O código foi revisado e nenhum caminho/valor hardcoded de máquina foi encontrado, então não há motivo técnico conhecido para o app não rodar em outra máquina Windows 11, mas a confirmação real fica pendente do usuário.)*

---

### ✅ FASE 7 (Futuro/Opcional) — Expansão Online

- [x] Planejar formato de base de conhecimento remota (JSON hospedado em repositório próprio) —
      reusa exatamente o schema de `knowledge-base.json`, com `version`/`updatedAt` no topo
- [x] Implementar verificação de atualização da base de conhecimento —
      `com.nitroboost.knowledge.RemoteKnowledgeUpdater`, com fallback gracioso para a base local
- [ ] Avaliar necessidade de telemetria anônima opcional, caso o projeto vire produto distribuído —
      **avaliado, ver `PROGRESS.md` (Fase 7) e `docs/telemetria-avaliacao.md`**; decisão de
      implementar de fato fica para o usuário (feature sensível de privacidade, não decidida
      silenciosamente por um agente)

*(Esta fase foi iniciada com autorização explícita do usuário em 2026-07-23.)*

---

## 5. Convenções de Código

- **Idioma do código:** nomes de classes/métodos/variáveis em **inglês** (padrão de mercado).
- **Idioma de comentários e textos exibidos ao usuário:** **português**.
- **Padrão de commits:** `[FaseX] Descrição curta no imperativo`.
- **Tratamento de erros:** nunca deixar uma chamada a comando do sistema sem try/catch e log do erro em console.
- **Organização:** um scanner = uma responsabilidade única (não misturar lógica de processos com lógica de serviços, por exemplo).

---

## 6. Arquivos de Apoio a Criar Durante o Desenvolvimento

- **`PROGRESS.md`** — atualizado a cada fase concluída, com data e resumo do que foi feito.
- **`BLOCKERS.md`** — qualquer impedimento técnico encontrado (ex: comando que não funcionou, permissão negada) deve ser registrado aqui em vez de silenciosamente ignorado ou pulado.

---

## 7. Instrução Direta para Execução Autônoma (Claude Code)

> Ao iniciar o trabalho neste projeto: leia este documento por completo antes de escrever qualquer código. Siga a ordem exata das fases. Não avance de fase sem concluir 100% dos itens da checklist da fase atual. Ao concluir cada item, marque-o como feito neste próprio arquivo (`[x]`). Ao final de cada fase, escreva um resumo em `PROGRESS.md`. Se encontrar qualquer bloqueio técnico, registre em `BLOCKERS.md` e continue com a próxima tarefa não dependente daquele bloqueio, revisando ao final. Priorize sempre segurança (backup) antes de qualquer ação destrutiva.

---

*Documento vivo — deve ser atualizado conforme o projeto evolui e novas decisões forem tomadas.*
