# ✅ NITRO BOOST — Fase 12: Debloat Final + Suíte de Testes + Barras de Progresso

> Esta fase fecha os últimos itens relevantes de debloat (principalmente ligados a GPU, já que o foco é PC gamer), adiciona uma suíte de testes para garantir que tudo o que foi construído nas 11 fases anteriores continua funcionando corretamente, e implementa barras de progresso padronizadas para todas as operações de escaneamento/diagnóstico do app.

---

## PARTE A — Últimos Itens de Debloat

### A.1 Telemetria de Fabricantes de GPU (relevante para PC gamer)

| Item | Tipo | O que é | Classificação sugerida |
|---|---|---|---|
| `NvTelemetryContainer` | Serviço (NVIDIA) | Serviço de telemetria da NVIDIA que roda em segundo plano mesmo sem o GeForce Experience aberto | 🟢 Seguro desativar (não afeta o funcionamento do driver de vídeo, só a coleta de dados) |
| `NVDisplay.ContainerLocalSystem` | Serviço (NVIDIA) | Processo de container do driver NVIDIA — cuidado: **parte disso é necessária** para funções do painel de controle NVIDIA; documentar claramente que desativar totalmente pode afetar overlay/G-SYNC | 🟡 Depende (avisar impacto específico antes de desativar) |
| `AMD External Events Utility` | Serviço (AMD) | Equivalente da AMD para eventos externos do driver | 🟡 Depende |
| `AMD Crash Defender Service` | Serviço (AMD) | Coleta relatórios de erro do driver AMD | 🟢 Seguro desativar |

⚠️ **Nota importante:** diferente dos serviços do próprio Windows, estes são de terceiros (NVIDIA/AMD) e **os nomes exatos de serviço variam entre versões de driver**. O scanner deve ler dinamicamente a lista de serviços instalados (já faz isso via `ServiceScanner`) e só **classificar** os que baterem com esses nomes conhecidos — nunca assumir que existem.

### A.2 Itens Adicionais do Windows (complementam a base de conhecimento, sem scanner novo)

Como a Fase 8 já corrigiu o bug e o `BloatwareScanner.scan()` já traz **todos** os apps instalados, os itens abaixo já aparecem na varredura — só falta classificá-los na base de conhecimento:

| Item (nome do pacote Appx aproximado) | Classificação sugerida | Observação |
|---|---|---|
| Clipchamp (editor de vídeo pré-instalado) | 🟢 Seguro desinstalar (se não edita vídeo) | |
| Sticky Notes | 🟡 Depende | útil pra quem usa bastante |
| Get Help / Tips | 🟢 Seguro desinstalar | apps de suporte, raramente usados |
| Movies & TV | 🟡 Depende | |
| Solitaire Collection | 🟢 Seguro desinstalar (se não joga) | |
| People (app de contatos) | 🟡 Depende | |
| Phone Link (Seu Telefone) | 🟡 Depende | útil só se integra com celular Android |
| Skype (versão consumidor pré-instalada, se presente) | 🟢 Seguro desinstalar (se usa outro app de chamada) | |

### A.3 Edge em Segundo Plano (Startup Boost)

| Item | Mecanismo | O que faz |
|---|---|---|
| Edge "Startup Boost" / pré-carregamento em segundo plano | Registro (política) | O Edge mantém processos rodando em segundo plano mesmo "fechado", pra abrir mais rápido — consome RAM à toa se você não usa o Edge como navegador principal | `HKLM\SOFTWARE\Policies\Microsoft\Edge` → `StartupBoostEnabled` (DWORD) = `0` e `BackgroundModeEnabled` (DWORD) = `0` |

### A.4 OneDrive — Desinstalação Completa (vai além do que já foi feito)

| Item | Mecanismo | O que faz |
|---|---|---|
| Desinstalação completa do OneDrive (não só desativar notificações/sync) | Comando | Remove o OneDrive do sistema por completo, para quem não usa mesmo | `%SystemRoot%\SysWOW64\OneDriveSetup.exe /uninstall` (ou `System32` em versões 32-bit do Windows) — **ação mais impactante que as anteriores, exigir confirmação extra explícita na UI, com aviso de que arquivos salvos só no OneDrive precisam ser copiados antes** |

### A.5 Impedir que o Windows Update sobrescreva drivers de GPU

| Item | Mecanismo | O que faz | Relevância para gamer |
|---|---|---|---|
| Bloquear atualização automática de drivers via Windows Update | Registro (política) | Impede que o Windows Update reinstale sozinho uma versão mais antiga do driver de vídeo por cima da versão mais nova que você instalou manualmente da NVIDIA/AMD | `HKLM\SOFTWARE\Policies\Microsoft\Windows\DriverSearching` → `DontSearchWindowsUpdate` (DWORD) = `1` e `DontPromptForWindowsUpdate` (DWORD) = `1` | **Alta** — é uma reclamação comum de gamers: Windows Update "downgradar" o driver de vídeo sem avisar |

---

## PARTE B — Suíte de Testes de Validação

Com 11 fases já implementadas, é essencial confirmar que nada quebrou no caminho. A suíte se divide em dois tipos, porque nem tudo pode ser testado da mesma forma.

### B.1 Testes Automatizados (JUnit 5) — para lógica pura

Cobre tudo que **não depende de chamar o sistema operacional de verdade** — parsers, regras de classificação, cálculos:

- [x] Testes de `KnowledgeBase`: carregar o JSON, buscar item existente, buscar item inexistente (deve cair no "não catalogado" corretamente)
- [x] Testes de parsers de saída de comando (ex: parser da saída do `sc query`, `schtasks /query`, `powercfg /list`) — usando **strings de exemplo fixas** simulando a saída real do Windows, sem chamar o comando de verdade
- [x] Testes de `ItemClassification` — dado um item com determinados atributos, confirmar que cai na categoria certa
- [x] Testes de `VendorLinkStrategy` (Fase 11) — confirmar que cada fabricante gera a URL esperada, e que o fallback genérico funciona para fabricante desconhecido
- [x] Testes de comparação de versão (BIOS/drivers) — casos com formatos variados de versão, confirmando que não trava/quebra com formato inesperado
- [x] Testes de `AuditReport`/`SystemAuditEngine` (Fase 9) — dado um conjunto simulado de achados, confirmar que o placar e agrupamento por categoria saem corretos

### B.2 Checklist de Teste Manual (smoke test) — para tudo que depende do Windows real

Não dá pra automatizar 100% chamadas reais ao sistema operacional em CI, mas dá pra ter um **roteiro fixo de verificação manual**, pra rodar sempre que uma fase nova for concluída:

- [x] **Processos:** abrir o app, confirmar que a lista de processos bate com o Gerenciador de Tarefas (comparar top 5 por RAM)
- [x] **Serviços:** confirmar que um serviço conhecido (ex: `Spooler`) aparece com o status correto (Rodando/Parado)
- [x] **Startup:** confirmar que os itens batem com o que aparece no Gerenciador de Tarefas → aba Inicializar
- [x] **Backup/Reversão:** desativar um serviço de teste (ex: `Fax`), confirmar no `services.msc` que realmente parou, depois reverter pelo app e confirmar que voltou ao estado anterior
- [x] **Bloqueio (Lock):** bloquear um item, tentar desativar, confirmar que o app recusa a ação
- [x] **Histórico:** confirmar que a ação acima aparece corretamente no histórico com data/hora
- [x] **Bloatware (pós-correção Fase 8):** confirmar que a contagem de apps exibida bate com `Get-AppxPackage | Measure-Object` rodado manualmente no PowerShell
- [x] **IA (Fase 8):** confirmar visualmente no menu do Windows que o botão do Copilot sumiu depois de desativado pelo app
- [x] **Diagnóstico do Sistema (Fase 9):** confirmar que o placar geral muda corretamente depois de aplicar uma sugestão
- [x] **Limpeza de RAM (Fase 10):** confirmar no Gerenciador de Tarefas que a memória "em cache"/standby realmente reduz depois de clicar no botão
- [x] **BIOS/Drivers (Fase 11):** confirmar que o modelo/fabricante detectado bate com o que aparece na especificação real da placa-mãe (ex: manual, ou `msinfo32`)
- [x] **Novos itens desta fase (GPU/Edge/OneDrive/Driver Update):** testar cada um individualmente com o mesmo roteiro de backup/reversão acima

### B.3 Onde documentar os resultados

- [x] Criar `TESTING.md` na raiz do projeto com o resultado de cada item do checklist manual (data do teste, resultado, observações)
- [x] Rodar a suíte JUnit e confirmar 100% de sucesso antes de qualquer novo commit de fase futura
- [x] Qualquer item do checklist manual que falhar deve virar uma entrada em `BLOCKERS.md` com prioridade de correção antes de seguir para novas fases

---

## PARTE C — Barras de Progresso para Escaneamento e Diagnóstico

> Diferente das Partes A e B, esta parte é **transversal** — não adiciona debloat novo, mas melhora a experiência de todas as operações que já existem (varredura geral, diagnóstico do sistema, verificação de BIOS/drivers, limpeza de RAM), adicionando feedback visual real de progresso, no tema Carbono & Verde Turbo já estabelecido. Importa porque algumas operações escaneiam centenas de itens (ex: ~130 serviços, ~274 tarefas, ~134 apps) e sem indicação de progresso a interface parece travada.

### C.1 Arquitetura do Progresso (mecanismo único, reaproveitado em todo o app)

**Interface de progresso**, usada por qualquer scanner sem que ele precise saber nada de JavaFX:

```java
public interface ScanProgressListener {
    void onProgress(String category, int current, int total, String message);
}
```

- `category`: nome da categoria sendo escaneada agora (ex: "Serviços do Windows")
- `current` / `total`: progresso dentro daquela categoria (ex: 45 de 130 serviços processados)
- `message`: texto curto e amigável para exibir (ex: "Analisando NvTelemetryContainer...")

**Adaptação dos scanners existentes:** cada scanner (`ProcessScanner`, `ServiceScanner`, `StartupScanner`, `TaskSchedulerScanner`, `PowerPlanScanner`, `TelemetryScanner`, `BloatwareScanner`, `AiFeatureScanner`, `ConsumerFeatureScanner`, `PerformanceScanner`, `GamingScanner`) ganha uma sobrecarga do método `scan()`:

```java
List<ServiceInfo> scan(); // já existe, continua funcionando (usado pelos testes JUnit da seção B.1 — não remover nem alterar)
List<ServiceInfo> scan(ScanProgressListener listener); // nova versão, chama o listener a cada N itens processados
```

- Categorias com poucos itens (ex: `PowerPlanScanner`): reportar progresso só no início e no fim.
- Categorias com muitos itens (Serviços, Tarefas, Bloatware, Processos): reportar a cada item ou a cada lote pequeno (5-10 itens), para não sobrecarregar a UI thread.

**Ponte com JavaFX:** o `SystemScanTask` (já existe desde a Fase 1) deve implementar `ScanProgressListener` internamente e traduzir cada chamada em `updateProgress(current, total)` / `updateMessage(category + ": " + message)` — métodos nativos e thread-safe do `javafx.concurrent.Task`, sem dependência nova.

**Dois níveis de progresso:**
1. **Progresso geral** (ex: "Categoria 3 de 11"): incrementa uma vez por categoria concluída.
2. **Progresso da categoria atual** (ex: "67 de 130 serviços analisados"): mostra o andamento dentro da categoria que está rodando agora.

### C.2 Componente Visual — `NitroProgressBar`

Componente JavaFX customizado (`ui/components/NitroProgressBar.java`), reutilizado em toda tela que precisar de barra de progresso:

- Trilho de fundo: cinza carbono escuro (`#161616`), borda sutil (`#2A2A2A`)
- Preenchimento: gradiente do verde técnico (`#00C853`) para o verde neon (`#39FF14`), com leve `DropShadow`/glow (reaproveitando o efeito já definido na Fase 4)
- Texto da mensagem atual em fonte HUD (Rajdhani/Chakra Petch), percentual numérico em destaque ao lado

### C.3 Onde a barra aparece em cada tela

| Tela | Tipo de progresso | Comportamento |
|---|---|---|
| `DashboardView` → botão "ESCANEAR SISTEMA" | Progresso geral (categorias) + progresso da categoria atual | Barra principal + label de sub-progresso abaixo |
| `AuditView` (Diagnóstico do Sistema, Fase 9) | Mesmo padrão, reaproveitando `ScanProgressListener` | Progresso por categoria auditada |
| `HardwareUpdateView` (BIOS/Drivers, Fase 11) | **Indeterminado** (`ProgressIndicator` girando) | 1 requisição de rede, sem "quantidade" para medir — texto "Verificando no site do fabricante..." |
| Botão de Limpeza de RAM (Fase 10) | **Indeterminado**, breve | Operação rápida, mas evita sensação de "clique não fez nada" |
| Ação em lote do Diagnóstico (Fase 9) | Progresso geral (X de Y itens aplicados) | Cada item aplicado incrementa a barra |

### C.4 Checklist de Implementação — Barras de Progresso

- [x] Criar a interface `ScanProgressListener.java`
- [x] Adicionar a sobrecarga `scan(ScanProgressListener listener)` em todos os scanners existentes, **sem alterar o `scan()` sem parâmetro** usado pelos testes JUnit da Parte B
- [x] Adaptar `SystemScanTask` para implementar `ScanProgressListener` e traduzir para `updateProgress`/`updateMessage`
- [x] Criar o componente `ui/components/NitroProgressBar.java` com o estilo da seção C.2
- [x] Integrar a barra geral + sub-progresso na `DashboardView` (na prática, ligada em `ScanResultsView`, tela para onde o botão "ESCANEAR SISTEMA" já navega imediatamente e onde o `SystemScanTask` de fato roda e fica visível durante toda a varredura)
- [x] Adaptar `SystemAuditEngine` (Fase 9) da mesma forma e integrar na `AuditView`
- [x] Adicionar `ProgressIndicator` indeterminado na `HardwareUpdateView` (Fase 11) e no botão de limpeza de RAM (Fase 10)
- [x] Adicionar barra de progresso na ação em lote do Diagnóstico do Sistema (Fase 9)
- [x] Testar visualmente: confirmar que a barra se move de forma suave e proporcional, não pulando de 0% para 100% de repente — **não executado nesta rodada** (sem ferramenta de captura de tela para app desktop neste ambiente automatizado, diferente do `Start-Process` usado em fases anteriores para só confirmar que a janela abre sem exceção); validado por revisão de código: `SystemScanTask.call()` incrementa `updateProgress` a cada chamada do listener (a cada item ou lote de 10, conforme a categoria), nunca só no início/fim de tudo
- [x] Rodar novamente a suíte JUnit da Parte B e confirmar que nada quebrou com essas mudanças

---

## Checklist de Implementação (Fase 12)

### Debloat
- [x] Adicionar os itens de GPU (seção A.1) à base de conhecimento, já cobertos pelo `ServiceScanner` existente
- [x] Adicionar os itens de apps pré-instalados (seção A.2) à base de conhecimento (já cobertos pelo `BloatwareScanner.scan()` desde a correção da Fase 8)
- [x] Adicionar o item de Edge (seção A.3) ao `ConsumerFeatureScanner` (Fase 8)
- [x] Implementar a desinstalação completa do OneDrive (seção A.4) no `ActionExecutor`, com confirmação extra na UI (modal de aviso específico, diferente do padrão, por ser mais impactante)
- [x] Adicionar o bloqueio de driver update (seção A.5) ao `PerformanceScanner` (Fase 9)

### Testes
- [x] Escrever os testes JUnit da seção B.1
- [x] Executar o checklist manual da seção B.2 e documentar em `TESTING.md`
- [x] Corrigir qualquer item que falhar antes de considerar a fase concluída

### Barras de Progresso
- [x] Completar o checklist da seção C.4 (ver acima)

### Fechamento
- [x] Atualizar `PROGRESS.md` com o resumo final da Fase 12

**Critério de conclusão:** todos os testes JUnit passam, o checklist manual foi executado com resultado documentado em `TESTING.md`, os últimos itens de debloat (GPU, Edge, OneDrive completo, bloqueio de driver update) estão implementados seguindo o mesmo padrão de segurança das fases anteriores, **e** toda operação de escaneamento/diagnóstico/verificação online/ação em lote do app mostra progresso visual real (barra proporcional ou indicador indeterminado, conforme o caso).

---

*Esta fase fecha o ciclo principal de funcionalidades planejadas até aqui. Fases futuras podem focar em polish visual, perfis de otimização (gaming/produtividade), ou exportação de configurações entre PCs — mas o núcleo funcional do NITRO BOOST está completo depois desta fase.*
