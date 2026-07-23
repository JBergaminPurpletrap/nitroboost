# 🧹 NITRO BOOST — Fase 8: Debloat Real Completo do Windows 11 (incluindo IAs)

> Este documento estende o escopo original do projeto com uma lista técnica completa e real de itens de debloat do Windows 11 — incluindo os recursos de Inteligência Artificial (Copilot, Recall, Click to Do, Cocreator) e demais funções de "enfeite"/telemetria/anúncios que consomem recursos ou tiram a experiência "limpa" do sistema. Cada item aqui tem um mecanismo real e verificável (chave de registro, pacote Appx, política de grupo) — nada é fictício.

⚠️ **Nota de manutenção:** chaves de registro e nomes de política da Microsoft mudam entre builds/versões do Windows 11 (23H2, 24H2, 25H2...). Os caminhos abaixo são os documentados publicamente e usados por ferramentas de debloat conhecidas (O&O ShutUp10++, Chris Titus Tech's WinUtil, PrivacySexy) no momento da criação deste documento. **Sempre implementar com leitura defensiva antes de alterar** (ler o valor atual, verificar se a chave existe, nunca assumir).

---

## 1. Categoria Nova: 🤖 IA / Inteligência Artificial

| Item | Mecanismo | O que faz | Comando/Chave |
|---|---|---|---|
| **Windows Copilot** | Registro (política) | Remove o botão/painel do Copilot da barra de tarefas e desativa o recurso | `HKCU\Software\Policies\Microsoft\Windows\WindowsCopilot` → `TurnOffWindowsCopilot` (DWORD) = `1`. Versão para todos os usuários: mesma chave em `HKLM`. Ícone na barra de tarefas: `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `ShowCopilotButton` (DWORD) = `0` |
| **Windows Recall** (Copilot+ PCs, Windows 11 24H2+) | Recurso opcional do Windows (Optional Feature) + política | Desativa a captura periódica de "snapshots" da tela para busca por IA | Via política: `HKLM\SOFTWARE\Policies\Microsoft\Windows\WindowsAI` → `DisableAIDataAnalysis` (DWORD) = `1`. Via feature opcional: `DISM /Online /Disable-Feature /FeatureName:Recall` (requer reinício) |
| **Click to Do** | Política (mesma família do Recall) | Desativa o recurso de IA que sugere ações contextuais sobre o que está na tela | `HKLM\SOFTWARE\Policies\Microsoft\Windows\WindowsAI` → `DisableClickToDo` (DWORD) = `1` |
| **Cocreator / Image Creator (Paint e Fotos)** | Política (mesma família WindowsAI) | Restringe os recursos de geração de imagem por IA nos apps Paint e Fotos | Mesma chave `DisableAIDataAnalysis`; observação: não existe uma chave 100% exclusiva e isolada só para Cocreator — a Microsoft agrupa vários recursos de IA sob a mesma política. Documentar essa limitação na interface (transparência com o usuário). |
| **Copilot no Microsoft Edge (barra lateral/Bing Chat)** | Política do Edge | Remove a barra lateral de IA e integração com Bing Chat no navegador | `HKLM\SOFTWARE\Policies\Microsoft\Edge` → `HubsSidebarEnabled` (DWORD) = `0` |
| **Recall — desativação alternativa via app (24H2)** | Configurações nativas | Caso a política não esteja disponível na edição do Windows, orientar o usuário via tutorial (Configurações → Privacidade e segurança → Recall e instantâneos) | Sem comando direto — **tutorial**, não ação automática (documentar em `tutorials/`) |

---

## 2. Categoria Nova: 🎯 Recursos de Consumidor / Anúncios / Sugestões

| Item | Mecanismo | O que faz | Comando/Chave |
|---|---|---|---|
| Sugestões e anúncios no Menu Iniciar | Registro | Remove apps sugeridos/patrocinados que aparecem no Menu Iniciar | `HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager` → `SubscribedContent-338388Enabled` (DWORD) = `0` |
| Dicas e sugestões do Windows (notificações "Experimente isso") | Registro | Remove notificações promocionais do próprio Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager` → `SubscribedContent-338389Enabled` (DWORD) = `0` |
| Instalação silenciosa de apps sugeridos | Registro | Impede que o Windows instale sozinho apps "recomendados" (ex: Candy Crush, TikTok) | `HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager` → `SilentInstalledAppsEnabled` (DWORD) = `0` |
| Sugestões no painel de configurações/sistema | Registro | Remove sugestões dentro do próprio app Configurações | `HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager` → `SystemPaneSuggestionsEnabled` (DWORD) = `0` |
| Tela de bloqueio com conteúdo dinâmico/anúncios (Windows Spotlight) | Registro | Desativa imagens/dicas promocionais na tela de bloqueio | `HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager` → `RotatingLockScreenEnabled` (DWORD) = `0` |
| Pesquisa do Bing integrada ao Menu Iniciar/Pesquisa do Windows | Registro (política) | Faz a pesquisa do Windows buscar só localmente, sem resultados da web | `HKLM\SOFTWARE\Policies\Microsoft\Windows\Windows Search` → `BingSearchEnabled` (DWORD) = `0` (política) ou `HKCU\Software\Microsoft\Windows\CurrentVersion\Search` → `BingSearchEnabled` = `0` |
| Notificações de provedor de sincronização (OneDrive "Faça backup dos seus arquivos") | Registro | Remove pop-ups insistentes pedindo para configurar backup no OneDrive | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `ShowSyncProviderNotifications` (DWORD) = `0` |
| Ícone/recurso "Chat" (Teams consumidor) na barra de tarefas | Registro | Remove o ícone de Chat/Teams pessoal da barra de tarefas | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `TaskbarMn` (DWORD) = `0` |

---

## 3. Categoria Nova: ⚙️ Apps em Segundo Plano e Recursos do Sistema

| Item | Mecanismo | O que faz | Comando/Chave |
|---|---|---|---|
| Apps em segundo plano (geral) | Registro (política) | Impede que apps UWP fiquem rodando em segundo plano consumindo bateria/RAM mesmo fechados | `HKLM\SOFTWARE\Policies\Microsoft\Windows\AppPrivacy` → `LetAppsRunInBackground` (DWORD) = `2` (nega globalmente) |
| Storage Sense (limpeza automática, pode remover arquivos sem aviso claro) | Registro | Controla a limpeza automática de arquivos temporários/lixeira | `HKCU\Software\Microsoft\Windows\CurrentVersion\StorageSense\Parameters\StoragePolicy` → `01` (DWORD) = `0` (desativa) |
| Diagnóstico e feedback (nível de telemetria) | Já coberto pelo `TelemetryScanner` existente (Fase 3) | — | — |
| Assistência de digitação/sugestões de texto (typing insights) | Registro | Desativa coleta de dados de digitação para "melhorar sugestões" | `HKCU\Software\Microsoft\Input\TIPC` → `Enabled` (DWORD) = `0` |

---

## 4. Correção do Bug Já Identificado (prioridade antes de expandir)

Antes de adicionar as categorias novas, corrigir o bug já diagnosticado:

- `SystemScanTask.scanBloatware()` está chamando `BloatwareScanner.knownBloatware()`, que **descarta silenciosamente** qualquer app com categoria `UNKNOWN` — mostrando só ~15 dos ~134 apps instalados.
- **Correção:** trocar para `BloatwareScanner.scan()` (retorna todos os apps), mantendo a classificação (`UNKNOWN` vira `ItemClassification.DEPENDE` com descrição genérica via `KnowledgeBase`, que já funciona assim para outros tipos).
- **Melhoria de UX recomendada:** adicionar um `CheckBox`/`ToggleButton` "Mostrar apenas itens conhecidos" na `ScanResultsView`, ligado por padrão (reduz ruído visual), mas que o usuário pode desligar para ver tudo — em vez do filtro ser fixo e invisível como está hoje.

---

## 5. Checklist de Implementação (Fase 8)

- [ ] Corrigir `scanBloatware()` para usar `.scan()` em vez de `.knownBloatware()`
- [ ] Adicionar toggle "Mostrar apenas conhecidos / Mostrar todos" na `ScanResultsView`
- [ ] Expandir `BloatwareScanner.classify()` com novas categorias: `AI_COPILOT`, `AI_RECALL`, `AI_CLICK_TO_DO`, `AI_COCREATOR` (quando o pacote Appx corresponder), mantendo `OTHER_KNOWN_BLOAT`/`UNKNOWN` para o restante
- [ ] Criar `AiFeatureScanner.java` (novo, em `core/`) — lê o estado atual das políticas de IA (`WindowsAI`, `WindowsCopilot`, Edge `HubsSidebarEnabled`) via `reg query`, seguindo exatamente o mesmo padrão defensivo dos scanners existentes (nunca lançar exceção, chave ausente = estado "não definido", não erro)
- [ ] Criar `ConsumerFeatureScanner.java` (novo, em `core/`) — lê as chaves de `ContentDeliveryManager`, `Explorer\Advanced` e `Windows Search` listadas nas seções 2 e 3
- [ ] Expandir `ActionExecutor` com os pares de métodos correspondentes (`disableAiFeature`/`restoreAiFeature`, `disableConsumerFeature`/`restoreConsumerFeature`), seguindo o contrato já estabelecido (lock check → backup → ação → histórico)
- [ ] Expandir `knowledge-base.json` com uma entrada por item das 3 tabelas acima (nome, tipo, classificação, descrição, impacto de desativar, impacto de manter — em português claro, sem jargão)
- [ ] Adicionar as 2 novas categorias (`CATEGORY_AI`, `CATEGORY_CONSUMER`) no `SystemScanTask` e no filtro da `ScanResultsView`
- [ ] Testar cada novo scanner isoladamente via console antes de conectar à UI (regra de ouro #9 já estabelecida)
- [ ] Atualizar `PROGRESS.md` com o resumo da Fase 8 e marcar os itens no checklist principal

**Critério de conclusão:** a categoria Bloatware mostra todos os apps instalados (não só os conhecidos), e existem duas novas categorias na tela (IA e Recursos de Consumidor) cobrindo os itens reais listados neste documento, todas seguindo o mesmo padrão de segurança (lock/backup/histórico) já validado nas fases anteriores.

---

## 6. Classificação Sugerida (para a base de conhecimento)

Como orientação de classificação padrão a usar ao preencher o `knowledge-base.json`:

- 🟢 **Seguro desativar:** anúncios/sugestões (Menu Iniciar, tela de bloqueio, notificações), Copilot (se o usuário não usa IA no dia a dia)
- 🟡 **Depende do uso:** Recall (útil para quem quer buscar coisas que viu na tela, mas tem implicação de privacidade relevante — vale um destaque especial de aviso na descrição), Click to Do, Cocreator, apps em segundo plano (alguns apps precisam disso para notificações funcionarem, ex: apps de mensagem)
- 🔴 **Essencial/cuidado:** nenhum item desta lista é essencial ao funcionamento do Windows — mas o item Storage Sense merece uma descrição de alerta reforçada, já que mexe em limpeza automática de arquivos (risco de exclusão indesejada mal compreendida, não de travar o sistema)

---

*Este documento complementa `NITRO-BOOST-documentacao-completa.md` (adicionando a Fase 8 ao roadmap) e `NITRO-BOOST-skills-tecnicas.md` (os novos scanners seguem exatamente as mesmas skills já documentadas — nada de biblioteca nova, só mais chaves de registro e políticas mapeadas).*
