# 🧹 NITRO BOOST — Fase 8: Debloat Real Completo do Windows 11 (incluindo IAs)

> Este documento estende o escopo original do projeto com uma lista técnica completa e real de itens de debloat do Windows 11 — incluindo os recursos de Inteligência Artificial (Copilot, Recall, Click to Do, Cocreator) e demais funções de "enfeite"/telemetria/anúncios que consomem recursos ou tiram a experiência "limpa" do sistema. Cada item aqui tem um mecanismo real e verificável (chave de registro, pacote Appx, política de grupo) — nada é fictício.

⚠️ **Nota de manutenção:** chaves de registro e nomes de política da Microsoft mudam entre builds/versões do Windows 11 (23H2, 24H2, 25H2...). Os caminhos abaixo são os documentados publicamente e usados por ferramentas de debloat conhecidas (O&O ShutUp10++, Chris Titus Tech's WinUtil, PrivacySexy) no momento da criação deste documento. **Sempre implementar com leitura defensiva antes de alterar** (ler o valor atual, verificar se a chave existe, nunca assumir).

---

## 1. Categoria Nova: 🤖 IA / Inteligência Artificial

> **Requisito do usuário:** para os itens de IA, sempre que for tecnicamente possível, o app deve oferecer **as duas opções** — Desativar (reversível, some da interface mas o pacote continua instalado) e Desinstalar (remove o pacote/feature de verdade, quando o Windows permitir isso). Quando só uma das duas for possível, documentar isso claramente na interface para o usuário entender a limitação.

| Item | Ação: Desativar (reversível) | Ação: Desinstalar (remoção real) |
|---|---|---|
| **Windows Copilot** | `HKCU\Software\Policies\Microsoft\Windows\WindowsCopilot` → `TurnOffWindowsCopilot` (DWORD) = `1` (+ versão `HKLM` para todos os usuários). Ícone da barra de tarefas: `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `ShowCopilotButton` (DWORD) = `0` | Em builds do Windows 11 onde o Copilot é entregue como pacote Appx (`Microsoft.Copilot` ou equivalente): `Get-AppxPackage *Copilot* \| Remove-AppxPackage` (por usuário) e `Get-AppxProvisionedPackage -Online \| Where-Object DisplayName -like "*Copilot*" \| Remove-AppxProvisionedPackage -Online` (remove também para novos usuários criados depois). **Verificar antes se o pacote existe** (`Get-AppxPackage` retorna vazio se não existir) — nem toda build tem o Copilot como pacote separado |
| **Windows Recall** (Copilot+ PCs, 24H2+) | Política: `HKLM\SOFTWARE\Policies\Microsoft\Windows\WindowsAI` → `DisableAIDataAnalysis` (DWORD) = `1` | Recurso opcional do Windows — desinstalação real via `DISM /Online /Disable-Feature /FeatureName:Recall /Remove` (o `/Remove` de fato apaga os arquivos do componente, diferente de só desativar a feature, que mantém os arquivos para reativação rápida). Requer reinício |
| **Click to Do** | `HKLM\SOFTWARE\Policies\Microsoft\Windows\WindowsAI` → `DisableClickToDo` (DWORD) = `1` | Não tem pacote/feature separado para desinstalar — está agrupado nos componentes de "Windows AI Components" junto com o Recall. Desinstalar a feature do Recall (acima) também remove o Click to Do na prática, quando ambos dependem do mesmo componente. Documentar essa dependência compartilhada na interface, não tratar como item 100% independente |
| **Cocreator / Image Creator (Paint e Fotos)** | Mesma chave `DisableAIDataAnalysis` acima | Não é possível desinstalar só a função de IA sem desinstalar o app inteiro (Paint ou Fotos). Se o usuário quiser remover mesmo assim: `Get-AppxPackage *Paint*\|*Photos* \| Remove-AppxPackage` — **mas isso remove o app inteiro**, não só a função de IA. A UI deve deixar isso muito claro antes de confirmar (ex: "Isso vai remover o aplicativo Paint por completo, não só a IA. Continuar?") |
| **Copilot no Microsoft Edge (barra lateral/Bing Chat)** | `HKLM\SOFTWARE\Policies\Microsoft\Edge` → `HubsSidebarEnabled` (DWORD) = `0` | Não é possível desinstalar só essa função sem desinstalar o Edge inteiro (não recomendado — o Edge é usado internamente pelo Windows em vários pontos). Manter só a opção de desativar para este item específico, e documentar essa limitação |
| **Recall — fallback manual (se a política não existir na edição do Windows)** | Sem comando direto — **tutorial**: Configurações → Privacidade e segurança → Recall e instantâneos | Mesma limitação — se a política de registro não existir, a desinstalação via DISM ainda deve funcionar (é um mecanismo diferente); testar os dois caminhos separadamente |

### 1.1 Regra de UX para as duas opções

- Na `ItemDetailView`, cada item de IA deve mostrar **dois botões separados** quando ambas as ações existirem: "Desativar (reversível)" e "Desinstalar (remove de vez)" — nunca um botão único que decide sozinho qual usar.
- O botão de **Desinstalar** deve ter visual mais "sério"/de alerta (ex: borda vermelha/âmbar em vez do verde neon padrão), já que é uma ação mais permanente — mesmo tendo backup (ver abaixo).
- **Mesmo a desinstalação precisa passar pelo `BackupManager`**: antes de desinstalar, registrar o que era o pacote (nome completo, versão) no backup, para permitir reinstalação futura ao menos como instrução ("para reinstalar, rode: `Add-AppxPackage ...`" ou reverter a feature via `DISM /Enable-Feature`) — mesmo sabendo que reinstalar um Appx removido é mais limitado do que reverter uma chave de registro.

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
- [ ] Criar `AiFeatureScanner.java` (novo, em `core/`) — lê o estado atual das políticas de IA (`WindowsAI`, `WindowsCopilot`, Edge `HubsSidebarEnabled`) via `reg query`, seguindo exatamente o mesmo padrão defensivo dos scanners existentes (nunca lançar exceção, chave ausente = estado "não definido", não erro). Também deve detectar se o pacote Appx do Copilot existe (`Get-AppxPackage`) e se a feature `Recall` está instalada (`DISM /Online /Get-Features`), para saber se a opção de desinstalar deve aparecer para cada item
- [ ] Criar `ConsumerFeatureScanner.java` (novo, em `core/`) — lê as chaves de `ContentDeliveryManager`, `Explorer\Advanced` e `Windows Search` listadas nas seções 2 e 3
- [ ] Expandir `ActionExecutor` com os pares de métodos correspondentes — para os itens de IA que suportam as duas ações, criar `disableAiFeature(item)` **e** `uninstallAiFeature(item)` (com seus respectivos `restore`); para os demais itens, `disableConsumerFeature`/`restoreConsumerFeature` — seguindo o contrato já estabelecido (lock check → backup → ação → histórico) em todos os casos
- [ ] Implementar na `ItemDetailView` os **dois botões separados** (Desativar / Desinstalar) para itens de IA que suportam ambas as ações, com estilo visual diferenciado no botão de desinstalar (mais "sério"/alerta, já que é ação mais permanente)
- [ ] Garantir que o backup de uma desinstalação registre informação suficiente (nome do pacote, versão) para permitir reinstalação futura, mesmo que via instrução documentada em vez de reversão automática de um clique
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
