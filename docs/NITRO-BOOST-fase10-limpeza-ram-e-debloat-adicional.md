# 🧠 NITRO BOOST — Fase 10: Limpeza de RAM (estilo RAMMap) + Debloat Adicional

> Esta fase adiciona a função de limpeza pontual de memória RAM (inspirada no RAMMap da Sysinternals) e fecha uma leva de itens clássicos de debloat que ainda não haviam entrado nas fases anteriores — a maioria bem conhecida de scripts de debloat sérios (o mesmo tipo de item que aparece no WinUtil, O&O ShutUp10++, etc.).

---

## 1. Limpeza de RAM (estilo RAMMap) — recurso principal desta fase

Diferente de todos os itens das fases anteriores (que são **configurações permanentes**, ligadas/desligadas e mantidas), este é um **botão de ação pontual** — "limpar agora", sem estado persistente para salvar/reverter.

### 1.1 Mecanismo técnico

Usa a função não documentada oficialmente `NtSetSystemInformation` da `ntdll.dll`, com a classe de informação `SystemMemoryListInformation` (valor `80`) — a mesma técnica usada internamente pelo RAMMap/EmptyStandbyList da Sysinternals. Chamada via **JNA** (já usado no projeto desde a Fase 0, nenhuma dependência nova).

| Ação | Comando interno | Efeito |
|---|---|---|
| Esvaziar Working Sets | `MemoryEmptyWorkingSets` (2) | Força processos a devolver memória física não essencial ao sistema |
| Esvaziar Lista de Páginas Modificadas | `MemoryFlushModifiedList` (3) | Grava em disco páginas pendentes e libera a memória delas |
| **Esvaziar Standby List** (a opção mais parecida com "Empty List" do RAMMap) | `MemoryPurgeStandbyList` (4) | Libera o cache de arquivos que o Windows guarda "por precaução" — a memória que aparece como "em uso" mas é só cache reaproveitável |
| Esvaziar Standby List de Baixa Prioridade | `MemoryPurgeLowPriorityStandbyList` (5) | Mesmo efeito, só na lista de prioridade mais baixa |

### 1.2 Pré-requisitos técnicos

- Rodar como Administrador (já é o caso do app).
- Habilitar dois privilégios do processo antes de chamar a função: `SeProfileSingleProcessPrivilege` e `SeIncreaseQuotaPrivilege`, via `AdjustTokenPrivileges` (JNA/`jna-platform`, mesma biblioteca já usada).

### 1.3 Estrutura técnica

- **`MemoryCleaner.java`** (novo, `core/` ou `system/`): mapeamento manual da função `NtSetSystemInformation` via JNA, com os 4 comandos da tabela acima expostos como métodos (`emptyWorkingSets()`, `flushModifiedPageList()`, `purgeStandbyList()`, `purgeLowPriorityStandbyList()`). Cada método deve:
  - Habilitar os privilégios necessários antes de chamar.
  - Nunca lançar exceção para fora — capturar falha (ex: app não elevado, privilégio negado) e devolver um resultado claro.
  - Registrar a ação no histórico (`actions_history`, tipo `memory_cleanup`) **mesmo sem ter backup/reversão** (não existe "desfazer" para isso — é só um registro informativo de quando foi usado, não uma ação reversível como as demais).
- **`ui/MemoryCleanupView.java`** (novo) ou uma seção dedicada dentro do `DashboardView` existente: botão único e chamativo ("🧹 LIMPAR CACHE DE RAM AGORA"), com:
  - Aviso curto e claro antes de executar: "Isso libera memória em cache que o Windows guarda por precaução. É seguro, mas o ganho é temporário e pode causar uma pequena lentidão momentânea logo depois, enquanto o Windows relê do disco o que foi descartado."
  - Exibição do "antes e depois" (RAM livre antes vs. depois da limpeza), reaproveitando a leitura de memória já existente via OSHI no `DashboardView`.

### 1.4 Regra de UX importante (diferente das demais fases)

Este recurso **nunca** deve ser oferecido como sugestão automática dentro do módulo de Diagnóstico do Sistema (Fase 9) — ele não é uma "configuração a corrigir", é uma ferramenta manual de uso pontual. Deve viver separado, como uma ação que o usuário decide usar quando quiser (ex: antes de abrir um jogo pesado).

---

## 2. Itens Adicionais de Debloat (fechando lacunas das fases anteriores)

### 2.1 Serviços clássicos de telemetria/bloat (faltavam da Fase 3/8)

| Item | Tipo | O que é | Classificação sugerida |
|---|---|---|---|
| `DiagTrack` (Connected User Experiences and Telemetry) | Serviço | O serviço mais conhecido de coleta de telemetria do Windows — o "carro-chefe" de qualquer script de debloat | 🟢 Seguro desativar (para quem prioriza privacidade) |
| `dmwappushservice` (WAP Push Message Routing Service) | Serviço | Serviço legado de roteamento de mensagens, raramente necessário hoje, tradicionalmente desativado junto com o DiagTrack | 🟢 Seguro desativar |
| `PcaSvc` (Program Compatibility Assistant) | Serviço | Monitora programas em busca de problemas de compatibilidade; consome recursos para um cenário raro no dia a dia | 🟡 Depende (útil se você roda muito programa antigo) |
| `RetailDemo` (Retail Demo Service) | Serviço | Usado só em PCs de demonstração de loja; inútil em uso pessoal | 🟢 Seguro desativar |
| `MapsBroker` (Downloaded Maps Manager) | Serviço | Gerencia mapas offline do app Mapas; inútil se você não usa esse app | 🟡 Depende |
| `WerSvc` (Windows Error Reporting Service) | Serviço | Envia relatórios de erro para a Microsoft (complementa a tarefa agendada já coberta na Fase 3) | 🟡 Depende (útil para diagnosticar travamentos, mas é telemetria) |
| `Fax` | Serviço | Serviço de fax — praticamente nunca usado em 2026 | 🟢 Seguro desativar |
| `TabletInputService` | Serviço | Painel de entrada por toque/caneta; inútil sem tela touch | 🟡 Depende (checar se a máquina tem touch) |
| `WbioSrvc` (Windows Biometric Service) | Serviço | Suporte a leitor biométrico (digital/rosto); inútil sem esse hardware | 🟡 Depende (checar se a máquina tem biometria) |
| `XblAuthManager`, `XblGameSave`, `XboxNetApiSvc`, `XboxGipSvc` | Serviços | Backend de autenticação/rede/saves do Xbox — complementam os apps Xbox já cobertos na Fase 3, mas em nível de serviço | 🟡 Depende (se não joga nada integrado ao Xbox/Game Pass) |

### 2.2 Armazenamento em Disco (Reserved Storage)

| Item | Mecanismo | O que faz |
|---|---|---|
| **Armazenamento Reservado (Reserved Storage)** | Comando PowerShell | O Windows reserva ~7 GB de disco permanentemente para atualizações/cache — em SSDs pequenos, isso é sensível. Pode ser desativado, liberando o espaço | `Set-WindowsReservedStorageState -State Disabled` (requer reinício para efeito completo) |

### 2.3 Privacidade Adicional (complementa o `TelemetryScanner` da Fase 3)

| Item | Mecanismo | O que faz |
|---|---|---|
| Histórico de Atividades / Timeline (envio para a conta Microsoft) | Registro | Impede que o Windows envie seu histórico de atividades (apps abertos, documentos) para sincronizar com sua conta Microsoft na nuvem | `HKLM\SOFTWARE\Policies\Microsoft\Windows\System` → `PublishUserActivities` (DWORD) = `0` e `UploadUserActivities` (DWORD) = `0` |
| Rastreamento de Localização (Location Services) | Registro (política) | Desativa o acesso de apps à localização do dispositivo | `HKLM\SOFTWARE\Policies\Microsoft\Windows\LocationAndSensors` → `DisableLocation` (DWORD) = `1` |

### 2.4 Interface (Windows 11 — item específico bastante pedido)

| Item | Mecanismo | O que faz |
|---|---|---|
| Seção "Recomendado" no Menu Iniciar | Registro | Remove a seção de arquivos/apps "recomendados" que ocupa metade do Menu Iniciar no Windows 11 | `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced` → `Start_IrisRecommendations` (DWORD) = `0` |

---

## 3. Checklist de Implementação (Fase 10)

### Limpeza de RAM
- [x] Criar `MemoryCleaner.java` com os 4 comandos via JNA (`NtSetSystemInformation`), incluindo habilitação de privilégios
- [x] Testar isoladamente via console (`main()` próprio) — validar antes/depois da limpeza com leitura real de RAM via OSHI
- [x] Criar a tela/seção de UI com o botão de ação, aviso claro, e exibição antes/depois
- [x] Registrar a ação no histórico (tipo `memory_cleanup`, sem backup/reversão associada — documentar essa exceção à regra padrão)
- [x] Garantir que este recurso **não** apareça nas sugestões automáticas do Diagnóstico do Sistema (Fase 9)

### Debloat adicional
- [ ] Adicionar os 10 serviços da seção 2.1 à base de conhecimento (a maioria já é lida pelo `ServiceScanner` existente — só falta classificação/descrição)
- [ ] Criar `StorageOptimizer.java` (ou adicionar ao `PerformanceScanner` da Fase 9) para ler/alterar o estado do Armazenamento Reservado
- [ ] Adicionar os 2 itens de privacidade (seção 2.3) ao `TelemetryScanner` existente (mesma estrutura já usada)
- [ ] Adicionar o item de interface (seção 2.4) ao `ConsumerFeatureScanner` da Fase 8
- [ ] Expandir `ActionExecutor` com os métodos correspondentes aos itens novos
- [ ] Atualizar `PROGRESS.md` e marcar os itens no checklist principal

**Critério de conclusão:** o app tem um botão funcional de limpeza de RAM com resultado visível (antes/depois), e a base de conhecimento cobre os itens clássicos de debloat que ainda faltavam, todos seguindo o mesmo padrão de segurança já estabelecido (exceto a limpeza de RAM, que é ação pontual sem reversão, conforme documentado).

---

*Este documento complementa `NITRO-BOOST-fase9-diagnostico-e-performance.md`. Assim como nas fases anteriores, nenhum item aqui envolve desativar Windows Defender, Firewall ou UAC.*
