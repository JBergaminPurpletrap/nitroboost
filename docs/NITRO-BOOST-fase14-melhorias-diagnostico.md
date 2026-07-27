# 🎮 NITRO BOOST — Fase 14: Melhorias Específicas do Diagnóstico

> Esta fase adiciona/ajusta itens específicos pedidos para o módulo de Diagnóstico do Sistema (Fase 9): Xbox Game Bar (gravação em segundo plano), confirmação de Modo de Jogo ativo, Otimização de Entrega do Windows Update, taxa de atualização da tela, e limpeza de ícones da barra de tarefas.

---

## A. Xbox Game Bar — "Gravar o que aconteceu" (captura em segundo plano)

Este item **já existe** como conceito no `GamingScanner` da Fase 9 (Game DVR), mas vamos reforçá-lo especificamente como sugestão de diagnóstico, já que é um dos maiores consumidores de RAM "escondidos" em segundo plano.

| Item | Mecanismo | Valor recomendado |
|---|---|---|
| "Gravar o que aconteceu" / captura em segundo plano (Configurações → Jogos → Capturas) | `HKCU\System\GameConfigStore` → `GameDVR_Enabled` (DWORD) | `0` (desativado) |
| Política equivalente (reforça o efeito, cobre todos os usuários) | `HKLM\SOFTWARE\Policies\Microsoft\Windows\GameDVR` → `AllowGameDVR` (DWORD) | `0` (desativado) |

**Ação no Diagnóstico do Sistema:** marcar como 🟡 sugestão de melhoria quando `GameDVR_Enabled` estiver diferente de `0`, com a descrição: *"O Windows grava automaticamente o que acontece na tela em segundo plano, mesmo sem gravação manual ativa. Isso consome RAM e um pouco de CPU/disco continuamente enquanto você joga. Desativar não impede gravações manuais via Win+Alt+R."*

---

## B. Modo de Jogo — verificar se está ATIVADO (diferente da maioria dos itens!)

⚠️ **Atenção na implementação:** ao contrário de quase todos os outros itens de debloat (onde o recomendado é desativar), aqui o **recomendado é MANTER ATIVADO**. O `valor_recomendado` deste item na base de conhecimento deve ser `1`, não `0`.

| Item | Mecanismo | Valor recomendado |
|---|---|---|
| Modo de Jogo (Configurações → Jogos → Modo de Jogo) | `HKCU\Software\Microsoft\GameBar` → `AllowAutoGameMode` (DWORD) | `1` (**ativado**) |

**Ação no Diagnóstico do Sistema:** marcar como 🟡 sugestão de melhoria quando `AllowAutoGameMode` for `0` (ou a chave não existir, o que geralmente significa o padrão do Windows, verificar caso a caso), com a descrição: *"O Modo de Jogo prioriza recursos do sistema para o jogo em primeiro plano, reduzindo interrupções de outros processos. Recomendado manter ativado para melhor desempenho em jogos."* A ação sugerida deve ser **"Ativar"**, não "Desativar" — reforçar isso na UI (o botão de ação deve dizer "Ativar Modo de Jogo", não usar o texto genérico "Aplicar correção" usado nos itens que desativam).

---

## C. Otimização de Entrega — "Permitir downloads de outros dispositivos"

Também já existe como conceito na Fase 9 (Delivery Optimization), mas vamos deixar o mapeamento exato de acordo com o texto literal do toggle do Windows.

| Item | Mecanismo | Valor recomendado |
|---|---|---|
| "Permitir downloads de outros PCs" (Configurações → Windows Update → Opções avançadas → Otimização de entrega) | `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\DeliveryOptimization\Config` → `DODownloadMode` (DWORD) | `0` (desligado — só baixa da Microsoft, nunca compartilha nem recebe de outros PCs) |

**Ação no Diagnóstico do Sistema:** marcar como 🟡 sugestão quando `DODownloadMode` for diferente de `0`, com a descrição: *"Por padrão, o Windows pode usar sua internet para enviar atualizações para outros PCs (seus ou de terceiros na internet), consumindo banda de upload sem que você perceba. Desligar restringe as atualizações a virem só da Microsoft."* Botão de ação: "Desligar".

---

## D. Taxa de Atualização da Tela (recurso novo)

### D.1 O que detectar

| Dado | Fonte | Mecanismo |
|---|---|---|
| Taxa de atualização atual configurada | `Win32_VideoController.CurrentRefreshRate` (WMI) | Via PowerShell `Get-CimInstance Win32_VideoController` ou `EnumDisplaySettings` (user32.dll via JNA) |
| Todas as taxas de atualização suportadas pelo monitor atual | `EnumDisplaySettingsEx` (user32.dll, iterando `iModeNum` até esgotar os modos) | Via JNA — coletar todos os valores distintos de `dmDisplayFrequency` disponíveis para a resolução atual |
| Taxa máxima suportada | Cálculo: maior valor entre os coletados acima | — |

### D.2 Estrutura técnica

- **`DisplayScanner.java`** (novo, `core/`): expõe `getCurrentRefreshRate()` e `getAvailableRefreshRates()`, seguindo o mesmo padrão defensivo dos demais scanners (se a leitura falhar, retornar lista vazia/valor desconhecido, nunca lançar exceção para fora).

### D.3 Ação — duas opções, pelo mesmo padrão de "Nível 1 / Nível 2" já usado na Fase 11

- **Nível 1 (sempre seguro):** o app detecta e informa "Sua tela está configurada para X Hz, mas suporta até Y Hz" e oferece um botão **"Abrir Configurações de Tela"**, que abre diretamente a página do Windows via `ms-settings:display` (usando `Desktop.getDesktop().open()` ou `ProcessBuilder("cmd", "/c", "start", "ms-settings:display")`) — o usuário troca manualmente com segurança total, já que é a própria tela de configuração nativa do Windows.
- **Nível 2 (opcional, mais avançado, maior risco):** tentar aplicar a troca diretamente via `ChangeDisplaySettingsEx` (user32.dll, JNA). **Cuidados obrigatórios se implementado:**
  - Sempre pedir confirmação explícita antes.
  - Implementar uma "janela de confirmação com timeout": aplicar a mudança e mostrar um popup "Manter essa configuração? Revertendo em 15 segundos..." — se o usuário não confirmar, reverter automaticamente (mesma lógica de segurança que o próprio Windows usa ao trocar resolução/taxa manualmente). Isso evita que uma taxa não suportada pelo monitor deixe a tela preta sem chance de reverter.
  - Recomendação: **implementar só o Nível 1 nesta fase**; o Nível 2 fica marcado como melhoria futura opcional, dado o risco de tela preta se mal implementado.

### D.4 Ação no Diagnóstico do Sistema

Marcar como 🟡 sugestão quando a taxa atual for menor que a máxima suportada detectada, com a descrição: *"Sua tela suporta até X Hz, mas está configurada para Y Hz. Taxas mais altas deixam o movimento mais fluido, principalmente em jogos."* Botão de ação: "Abrir Configurações de Tela" (Nível 1).

---

## E. Ícones e Itens da Barra de Tarefas (Configurações → Personalização → Barra de tarefas)

| Item | Mecanismo | Valor recomendado |
|---|---|---|
| Caixa de Pesquisa (Search) — modo "Oculto" | `HKCU\Software\Microsoft\Windows\CurrentVersion\Search` → `SearchboxTaskbarMode` (DWORD) | `0` (oculto) |
| Botão "Visão de Tarefas" (Task View) | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `ShowTaskViewButton` (DWORD) | `0` (desativado) |
| Ícone de Widgets na barra de tarefas | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `TaskbarDa` (DWORD) | `0` (desativado) — **nota: isso só esconde o ícone da barra; a desinstalação completa do app Widgets já está coberta separadamente pelo `ConsumerFeatureScanner`/`BloatwareScanner` desde as Fases 3/8** |
| Ícone "Chat"/"Continuar" (Meet Now/Teams consumidor) | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `TaskbarMn` (DWORD) | `0` (desativado) — **este item já existe desde a Fase 8, seção 2 ("Recursos de Consumidor"); só confirmar que está com o valor recomendado certo na base de conhecimento** |

**Ação no Diagnóstico do Sistema:** cada um desses 4 itens aparece agrupado como "Limpeza da Barra de Tarefas", cada um com seu próprio botão de ação individual, mas também elegíveis para a ação em lote "Aplicar todas as sugestões desta categoria" já existente (Fase 9).

---

## Checklist de Implementação (Fase 14)

### Reforços em scanners existentes (sem código novo, só dados)
- [x] Confirmar/ajustar `valor_recomendado` do item Game DVR no `knowledge-base.json` para `0` (Fase 9 → `GamingScanner`) — já estava `0`, confirmado
- [x] Adicionar a política `AllowGameDVR` como verificação complementar no `GamingScanner` — novo item "Game DVR - Politica (Todos os Usuarios)" (`game_dvr_policy`, HKLM)
- [x] **Inverter** o `valor_recomendado` do item Modo de Jogo para `1` (ativado) no `knowledge-base.json` — já estava `1` (tanto no JSON quanto em `GamingScanner.recommendedValue`), confirmado; texto do botão agora diz "Ativar Modo de Jogo" (`ItemActionDispatcher.primaryActionLabel`, checagem especial pelo `id` do item)
- [x] Confirmar/ajustar `valor_recomendado` do item Delivery Optimization (`DODownloadMode`) para `0` no `knowledge-base.json` (Fase 9 → `PerformanceScanner`) — **estava `1` (incorreto), corrigido para `0`** em ambas as fontes (JSON e `PerformanceScanner.recommendedValue`)
- [x] Confirmar que o item "Chat/Continuar" (`TaskbarMn`) já está corretamente classificado desde a Fase 8 — confirmado, `valor_recomendado="0"`, correto

### Recurso novo: Taxa de Atualização da Tela
- [x] Criar `DisplayScanner.java` (`core/`) com `getCurrentRefreshRate()` e `getAvailableRefreshRates()` via JNA (`EnumDisplaySettingsA` — struct `DEVMODE` mapeada manualmente, layout oficial `DEVMODEA`; `jna-platform` 5.19.1 não tem binding pronto para isso, só `EnumDisplayMonitors`)
- [x] Testar isoladamente via console (`Phase14Part2ConsoleDemo`) — nesta máquina de desenvolvimento: taxa atual = 60 Hz, taxas suportadas = [25, 29, 30, 50, 59, 60] Hz, máxima = 60 Hz (usuário deve conferir contra Configurações → Sistema → Tela → Exibição avançada na própria máquina)
- [x] Adicionar a entrada correspondente na base de conhecimento (tipo `display`, sem `valor_recomendado` fixo) com a lógica de comparação (atual vs. máxima) implementada em `SystemAuditEngine.auditDisplay`/`resolveDisplayStatus` — comparação dinâmica, não um valor fixo
- [x] Implementar o botão "Abrir Configurações de Tela" (Nível 1) usando `ms-settings:display` (`ActionExecutor.openDisplaySettings`, via `ProcessBuilder("cmd", "/c", "start", "ms-settings:display")`)
- [ ] **Não implementar o Nível 2 (troca automática) nesta fase** — deixar documentado como melhoria futura opcional, dado o risco de tela preta (documentado em `docs/PROGRESS.md`, deliberadamente não implementado)

### Recurso novo: Ícones da Barra de Tarefas
- [x] Adicionar `SearchboxTaskbarMode`, `ShowTaskViewButton` e `TaskbarDa` como itens verificáveis no `ConsumerFeatureScanner` (Fase 8), com os valores recomendados da seção E — 3 entradas novas em `KNOWN_KEYS` (ids `taskbar_search_box`, `taskbar_task_view_button`, `taskbar_widgets_icon`), mesmo padrão das chaves já existentes; entradas correspondentes adicionadas em `knowledge-base.json` (tipo `consumer`, `valor_recomendado="0"`); confirmado que `TaskbarMn` (Chat, Fase 8) já tinha `valor_recomendado="0"` correto — nenhuma duplicata criada
- [x] Expandir `ActionExecutor` com os métodos correspondentes, seguindo o contrato de sempre (lock → backup → ação → histórico) — nenhum método novo necessário: as 3 chaves reaproveitam `setConsumerFeatureValue`/`restoreConsumerFeatureValue` já existentes (mesmo mecanismo genérico `applyRegistryDwordChange`/`restoreRegistryDwordChange` usado por todas as chaves DWORD de registro desde a Fase 9)
- [x] Confirmar visualmente que os 4 itens aparecem agrupados como "Limpeza da Barra de Tarefas" na `AuditView` (Fase 9), com opção de ação em lote — `SystemAuditEngine.auditConsumer` agora atribui a categoria de EXIBIÇÃO "Limpeza da Barra de Tarefas" a esses 4 itens especificamente (por nome, via `TASKBAR_CLEANUP_ITEM_NAMES`), categoria independente de `SystemScanTask.CATEGORY_CONSUMER` (usada normalmente pela tabela de varredura geral, onde os mesmos 4 itens continuam aparecendo em "Recursos de Consumidor e Segundo Plano" sem duplicação); validado via `Phase14Part3ConsoleDemo` — `AuditView` não precisou de nenhum código novo, o agrupamento por `category`/ação em lote já era genérico o suficiente

### Fechamento
- [x] Atualizar `PROGRESS.md` com o resumo da Fase 14

**Critério de conclusão:** o Diagnóstico do Sistema mostra corretamente as sugestões para Game DVR (desativar), Modo de Jogo (ativar — direção invertida, testar com atenção), Delivery Optimization (desligar), taxa de atualização da tela (informar + botão pra configurações nativas), e os 4 itens de barra de tarefas — todos com o texto de ação correto na UI (alguns dizem "ativar", outros "desativar", conforme o caso).

---

*Esta fase reforça e completa itens que já existiam parcialmente nas Fases 8 e 9, além de adicionar o recurso novo de taxa de atualização de tela. Nenhum item aqui exige código totalmente novo de scanner, exceto o `DisplayScanner`.*
