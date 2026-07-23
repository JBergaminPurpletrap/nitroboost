# 🩺 NITRO BOOST — Fase 9: Debloat/Performance Completo + Diagnóstico do Sistema

> Este documento fecha o escopo de melhorias reais do Windows 11 (além do que já está nas Fases 3 e 8) e adiciona um módulo novo: o **Diagnóstico do Sistema** — uma varredura que compara a configuração atual da máquina com os valores recomendados e devolve um relatório com sugestões priorizadas, em vez de só listar itens soltos para o usuário decidir um por um.

⚠️ **Fora de escopo por segurança, de propósito:** Windows Defender (antivírus), Firewall do Windows, e UAC (Controle de Conta de Usuário) **nunca** devem aparecer como sugestão de "desativar para ganhar performance". O ganho é pequeno e o risco é desproporcional — manter o projeto com foco em debloat/performance responsável, não em desmontar a segurança do sistema.

---

## 1. Categoria Nova: 🚀 Performance e Energia

| Item | Mecanismo | O que faz | Comando/Chave |
|---|---|---|---|
| **Plano de energia "Desempenho Máximo" (Ultimate Performance)** | Plano de energia oculto | Plano de energia mais agressivo que o "Alto Desempenho" padrão, desbloqueado por comando | `powercfg -duplicatescheme e9a42b02-d5df-448d-aa00-03f14749eb61` (cria o plano oculto na lista) |
| **Efeitos visuais (animações/transparência)** | Registro | Reduz efeitos visuais do Windows para priorizar desempenho sobre estética | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\VisualEffects` → `VisualFXSetting` (DWORD) = `2` (ajustar para melhor desempenho) |
| **Transparência da interface (Acrilico/Fluent)** | Registro | Desativa efeito de transparência de janelas/menu iniciar | `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize` → `EnableTransparency` (DWORD) = `0` |
| **GPU Hardware-Accelerated Scheduling** | Registro | Deixa a GPU gerenciar sua própria fila de memória de vídeo, reduzindo latência (ganho real em jogos, requer GPU/driver compatível) | `HKLM\SYSTEM\CurrentControlSet\Control\GraphicsDrivers` → `HwSchMode` (DWORD) = `2` (requer reinício) |
| **Inicialização Rápida (Fast Startup)** | Painel de controle / registro | Em desktops (não notebooks), pode causar inconsistências com dual-boot e drivers; desativar deixa o boot "mais limpo" (leve aumento no tempo de boot) | `HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Power` → `HiberbootEnabled` (DWORD) = `0` |
| **Arquivo de Hibernação** | Comando | Libera espaço em disco (SSD) desativando hibernação, se o usuário nunca usa essa função | `powercfg /hibernate off` |
| **SysMain (antigo Superfetch)** | Serviço do Windows | Pré-carrega apps usados com frequência na RAM; em SSDs modernos com bastante RAM, o ganho é discutível e pode gerar uso de disco desnecessário | Serviço `SysMain` — já coberto pelo `ServiceScanner` existente, só precisa entrar na base de conhecimento com essa explicação específica |
| **Indexação de Pesquisa do Windows (Windows Search)** | Serviço do Windows | Mantém um índice para buscas rápidas; consome CPU/disco continuamente, mais notável em HDDs | Serviço `WSearch` — já coberto pelo `ServiceScanner`, adicionar à base de conhecimento com contexto (impacto maior em HDD do que SSD) |
| **Índice de Limitação de Rede (Network Throttling Index)** | Registro | Configuração antiga que limitava a rede para priorizar áudio/multimídia; desativar pode ajudar em jogos online sensíveis a latência | `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile` → `NetworkThrottlingIndex` (DWORD) = `0xffffffff` |
| **Otimização de Entrega do Windows Update (Delivery Optimization)** | Registro/Configurações | Por padrão pode compartilhar atualizações com outros PCs pela internet (P2P), consumindo banda de upload; restringir a "só PCs na minha rede local" ou desativar economiza banda | `HKLM\SOFTWARE\Microsoft\Windows\DeliveryOptimization\Config` → `DODownloadMode` (DWORD) = `1` (só rede local) ou `0` (desativado) |
| **Spooler de Impressão (Print Spooler)** | Serviço do Windows | Se o usuário não tem/usa impressora, esse serviço só ocupa recursos à toa | Serviço `Spooler` — já coberto pelo `ServiceScanner`; adicionar à base de conhecimento com classificação "depende" (perguntar se usa impressora) |
| **Bluetooth Support Service** | Serviço do Windows | Se a máquina não tem hardware Bluetooth ou o usuário nunca usa, pode ser desativado | Serviço `bthserv` — mesma lógica acima, classificação "depende" |

---

## 2. Categoria Nova: 🖥️ Otimizações Específicas para Jogos

| Item | Mecanismo | O que faz | Comando/Chave |
|---|---|---|---|
| **Otimizações de Tela Cheia (Fullscreen Optimizations)** | Registro por jogo/global | Força modo de tela cheia exclusivo em vez do modo "borderless" do Windows, reduzindo input lag em alguns jogos | `HKCU\System\GameConfigStore` → `GameDVR_FSEBehaviorMode` (DWORD) = `2` |
| **Game DVR / Gravação em segundo plano** | Registro | Desativa a gravação em segundo plano de clipes do Xbox Game Bar, que consome recursos mesmo sem gravar ativamente | `HKCU\System\GameConfigStore` → `GameDVR_Enabled` (DWORD) = `0` |
| **Modo de Jogo (Game Mode) do Windows** | Registro | Prioriza recursos do sistema para o jogo em primeiro plano (geralmente positivo, mas alguns usuários avançados preferem controlar manualmente) | `HKCU\Software\Microsoft\GameBar` → `AllowAutoGameMode` (DWORD) = `1` (ativar) ou `0` (desativar) |
| **Prioridade de Processador para Programas (vs. Serviços em segundo plano)** | Registro | Prioriza CPU para o programa em primeiro plano (jogo) em vez de dividir igualmente com processos em segundo plano | `HKLM\SYSTEM\CurrentControlSet\Control\PriorityControl` → `Win32PrioritySeparation` (DWORD) = `38` (0x26, valor comum recomendado para jogos) |

---

## 3. Módulo Novo: 🩺 Diagnóstico do Sistema (System Health Check)

Este é o recurso mais importante desta fase — em vez do usuário precisar entender e revisar dezenas de itens soltos, o app roda um **diagnóstico completo** e devolve um relatório priorizado.

### 3.1 Como funciona

1. Roda **todos os scanners existentes** (processos, serviços, startup, tarefas, energia, telemetria, bloatware, IA, consumidor, performance/jogos — todas as categorias já implementadas + as desta fase).
2. Para cada item com **mecanismo de verificação real** (chave de registro ou configuração legível), compara o **valor atual** com o **valor recomendado** definido na base de conhecimento (novo campo `valor_recomendado` por item, onde aplicável).
3. Gera um **relatório agrupado por categoria** (Performance, Privacidade/IA, Limpeza/Anúncios, Jogos), cada item marcado como:
   - ✅ **Já otimizado** (valor atual já bate com o recomendado)
   - 🟡 **Sugestão de melhoria** (valor atual diverge do recomendado, ação seria segura)
   - ⚪ **Não aplicável** (ex: hardware não suportado, feature não presente nesta edição do Windows)
4. Exibe um **placar geral** (ex: "42 de 58 itens já otimizados") — não como "nota de saúde" alarmista, mas como progresso objetivo.
5. Permite aplicar **uma sugestão individual** ou (com confirmação explícita) **aplicar todas as sugestões seguras de uma categoria de uma vez** — sempre passando pelo fluxo já existente (lock check → backup → ação → histórico).

### 3.2 Estrutura técnica

- **`SystemAuditEngine.java`** (novo, em `core/` ou um pacote novo `audit/`): orquestra os scanners existentes + a nova comparação com valores recomendados. Reaproveita 100% dos scanners já implementados (não reimplementa nada).
- **Campo novo na base de conhecimento:** `valor_recomendado` (string ou número, quando aplicável — ex: `"0"` para uma chave DWORD, ou `"Alto Desempenho"` para um plano de energia). Itens sem verificação objetiva possível (ex: "depende do uso") ficam sem esse campo.
- **`AuditReport.java`** (novo, record): estrutura do relatório — lista de `AuditFinding` (item, categoria, status, valor atual, valor recomendado, ação sugerida).
- **`ui/AuditView.java`** (novo): tela nova, acessível pelo menu lateral, mostrando o placar geral + lista agrupada por categoria com os botões de ação (individual e em lote).

### 3.3 Regra de segurança para "aplicar em lote"

- Nunca aplicar mais de uma mudança sem que o usuário veja a lista completa do que será alterado antes de confirmar (modal de confirmação listando cada item).
- Toda ação em lote ainda passa pelo backup individual de cada item (nada de "backup único para o lote inteiro" — se o usuário quiser reverter só um item depois, precisa poder).

---

## 4. Checklist de Implementação (Fase 9)

- [x] Adicionar as novas categorias de Performance/Energia (seção 1) e Jogos (seção 2) ao `knowledge-base.json` — **sem** o campo `valor_recomendado` (reservado para a Parte 2/Diagnóstico, que usa esse campo para a comparação automática; nesta Parte 1, o "valor sugerido" de cada item vive apenas em código, no campo `recommendedValue` de `PerformanceScanner`/`GamingScanner`, usado como alvo padrão da ação "Desativar/Ajustar" da UI)
- [x] Criar `PerformanceScanner.java` (`core/`) cobrindo os itens de registro da seção 1 (efeitos visuais, transparência, GPU scheduling, fast startup, hibernação, throttling de rede, delivery optimization)
- [x] Criar `GamingScanner.java` (`core/`) cobrindo os itens da seção 2 (fullscreen optimizations, game DVR, game mode, prioridade de processador)
- [x] Adicionar `SysMain`, `WSearch`, `Spooler`, `bthserv` à base de conhecimento (esses serviços já são lidos pelo `ServiceScanner` existente — já estavam catalogados desde a Fase 1/3 com descrições adequadas; só a nuance HDD-vs-SSD do `WSearch` foi complementada nesta fase)
- [x] Expandir `ActionExecutor` com os métodos de ação para os itens novos (mesmo contrato de sempre) — `setPerformanceValue`/`restorePerformanceValue`, `setGamingValue`/`restoreGamingValue`, `setHibernationEnabled`/`restoreHibernationState`
- [x] Criar `SystemAuditEngine.java` (`audit/`, novo pacote) orquestrando os scanners com valor comparável (Telemetria/Performance/Jogos/IA/Consumidor) + comparação com `valor_recomendado`
- [x] Criar `AuditReport.java` e `AuditFinding` (record)
- [x] Criar `ui/AuditView.java` com placar geral + lista agrupada por categoria + ações individual/lote
- [x] Implementar modal de confirmação para ação em lote, listando cada item antes de aplicar
- [x] Adicionar `AuditView` ao menu lateral principal (`Main.java`)
- [x] Testar `SystemAuditEngine` isoladamente via console antes de conectar à UI (`Phase9Part2ConsoleDemo`)
- [x] Atualizar `PROGRESS.md` com o resumo da Fase 9 (Parte 1 + Parte 2) e marcar os itens correspondentes no checklist acima

**Critério de conclusão:** o usuário consegue clicar em uma tela nova ("Diagnóstico") e ver, de forma agrupada, quais das dezenas de configurações reais da máquina já estão otimizadas e quais têm sugestão de melhoria — podendo aplicar uma por uma ou em lote, sempre com backup e confirmação.

---

## 5. Observação Final sobre Escopo Responsável

Esta fase deliberadamente **não inclui**:
- Desativar Windows Defender, Firewall, ou UAC (segurança, não performance)
- Desativar Windows Update por completo (só a *forma de entrega* — Delivery Optimization — é ajustável, nunca a atualização em si)
- Qualquer modificação de baixo nível de BIOS/firmware que não seja apenas leitura/detecção (isso continua restrito ao que já foi definido na Fase 5 — detecção de indício + tutorial)

Isso mantém o NITRO BOOST fiel ao objetivo original: liberar performance e limpar o sistema de forma consciente, sem transformar o projeto em uma ferramenta que desmonta proteções básicas do usuário.

---

*Este documento complementa `NITRO-BOOST-fase8-debloat-completo.md` e `NITRO-BOOST-documentacao-completa.md` (adicionando a Fase 9 ao roadmap).*
