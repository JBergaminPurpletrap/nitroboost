# 🚀 NITRO BOOST

Aplicação desktop (Java + JavaFX) para otimização e controle de performance do **Windows 11**.

O NITRO BOOST escaneia o sistema, identifica processos, serviços, itens de inicialização e
configurações que consomem recursos desnecessariamente, explica em português simples o que cada
item faz, e permite desativar/bloquear/reverter cada ajuste com segurança (backup automático antes
de qualquer ação destrutiva). Tema visual: **Carbono & Verde Turbo** — preto com textura de fibra
de carbono e acentos em verde neon, estilo HUD gamer.

> Consulte `docs/NITRO-BOOST-documentacao-completa.md` para a documentação completa do projeto
> (objetivo, arquitetura, checklist detalhado por fase) e `docs/NITRO-BOOST-skills-tecnicas.md` para
> o guia de bibliotecas/comandos usados na implementação. Cada fase posterior (8 em diante) tem seu
> próprio documento `docs/NITRO-BOOST-faseN-*.md`.
>
> ⚠️ **Projeto pessoal e privado.** Feito para uso individual, sem garantias, sem suporte e sem
> distribuição pública planejada. Ele mexe em processos, serviços, registro, tarefas agendadas,
> plano de energia, apps do Windows, e comandos de reparo de sistema/rede — use por sua conta e
> risco, de preferência lendo o que cada ação faz antes de confirmar (é exatamente para isso que
> existe a tela de detalhes de cada item).

## Status atual

Todo o roadmap planejado (Fases 0 a 15) está concluído. O app escaneia **12 categorias** do
sistema (~126 itens catalogados na base de conhecimento), executa e reverte ações com backup
automático, tem um módulo de Diagnóstico que compara a configuração atual com valores recomendados,
ferramentas de reparo de sistema/rede, verificação de BIOS/drivers da placa-mãe, limpeza pontual de
RAM, e uma suíte de **75 testes automatizados (JUnit 5)**. Veja `docs/PROGRESS.md` para o histórico
detalhado de cada fase, `docs/BLOCKERS.md` para bloqueios técnicos conhecidos, e `TESTING.md` para o
checklist de testes manuais executados na máquina real.

## Funcionalidades

### Telas principais

| Tela | O que faz | Como funciona | Resultado esperado |
|---|---|---|---|
| **Painel de Controle** (Dashboard) | Monitoramento de CPU/RAM em tempo real + limpeza pontual de RAM | Lê CPU/RAM via OSHI a cada 2s numa thread de fundo; velocímetro (arco) e gráfico de linha mostram os últimos 30 pontos. O botão "Limpar Cache de RAM" chama `NtSetSystemInformation` (`ntdll.dll`, técnica do RAMMap/EmptyStandbyList) via JNA, após habilitar os privilégios `SeProfileSingleProcessPrivilege`/`SeIncreaseQuotaPrivilege` | Gráfico/velocímetro atualizam a cada 2s sem travar a UI. A limpeza de RAM mostra RAM livre antes/depois; **é a única ação do projeto sem backup/reversão** (não há "estado" para desfazer), registrada no histórico só como informação |
| **Resultados da Varredura** (Scan Results) | Tabela unificada com os itens de todas as 12 categorias | Cada categoria tem um scanner próprio em `core/` que lê o sistema (via OSHI, `reg query`, PowerShell, WMI ou JNA) e devolve uma lista tipada; a `KnowledgeBase` classifica cada item (🟢 seguro / 🟡 depende / 🔴 essencial) | Tabela com ícone de status, filtro por categoria e por status, toggle "mostrar apenas itens conhecidos" (ligado por padrão), botão de ação individual por linha, e "Fechar todos os itens verdes" (ação em lote com confirmação) |
| **Diagnóstico do Sistema** (Audit) | Compara a configuração atual com valores recomendados e devolve um placar | `SystemAuditEngine` reaproveita os scanners de Telemetria/Performance/IA/Consumidor/Tela (os que têm um valor objetivamente comparável) e cruza com o campo `valor_recomendado` da base de conhecimento | Placar tipo "X de Y itens já otimizados", lista agrupada por categoria (✅ já otimizado / 🟡 sugestão / ⚪ não aplicável), ação individual ou em lote por categoria (com modal listando cada item antes de aplicar) |
| **Reparo do Sistema** (System Repair) | Verificação/reparo de arquivos do Windows (SFC + DISM) e diagnóstico de rede | `SystemFileRepairTool` roda `DISM /Online /Cleanup-Image /RestoreHealth` seguido de `sfc /scannow` via `ProcessBuilder`, lendo a saída linha a linha em tempo real e extraindo percentual via regex; `NetworkRepairTool` expõe `flushDns`/`resetWinsock`/`resetTcpIp`/`renewIp`/`clearArpCache` | Log ao vivo estilo terminal (fundo preto, texto verde) + barra de progresso proporcional; resultado do SFC classificado em 3 estados (nenhum problema / corrigido / não foi possível corrigir tudo). Ações de rede que exigem reinício (Winsock/TCP-IP) ou derrubam a conexão (renovar IP) pedem confirmação extra explícita |
| **BIOS / Drivers** (Hardware Update) | Detecta fabricante/modelo/versão da BIOS e verifica se há atualização | **Nível 1** (sempre funciona): lê fabricante/modelo/BIOS via OSHI e monta um link de suporte em 3 camadas (link direto do fabricante → busca no site do fabricante → busca externa via Google, que nunca falha). **Nível 2** (melhor esforço, só ASUS por enquanto): baixa a página de suporte real (respeitando `robots.txt`, com cache de 24h) e tenta extrair a versão mais recente listada | Card com fabricante/modelo/BIOS atual + botão "Abrir Página de Suporte" (sempre funciona) e botão "Verificar Atualização Online" (Nível 2 — se falhar por qualquer motivo, cai de volta pro Nível 1 silenciosamente, nunca trava a tela) |
| **Histórico** (History) | Lista cronológica de todas as ações realizadas | Consulta a tabela `actions_history` do SQLite (data/hora, tipo de ação, item, sucesso/falha) | Lista das últimas 100 ações, com botão "Reverter" em cada uma (chama `restoreFromHistory`, que localiza o backup vinculado e aplica o tipo de reversão certo) |
| **Tutoriais** (Tutorials) | Guias para ajustes que exigem ação manual do usuário | `TutorialProvider` carrega arquivos Markdown de `resources/tutorials/`; a tela mostra um alerta automático quando `XmpAdvisor` detecta indício de XMP/EXPO desativado (RAM rodando abaixo da velocidade nominal) | Lista de tutoriais (hoje: XMP/BIOS por fabricante, Recall via Configurações) com renderização leve de Markdown e alerta contextual quando aplicável |

### As 12 categorias de varredura

| Categoria | Scanner | Mecanismo | O que a ação faz |
|---|---|---|---|
| Processos | `ProcessScanner` | OSHI (`OperatingSystem.getProcesses()`) | Finaliza o processo (`ProcessHandle.destroy`) |
| Serviços | `ServiceScanner` | PowerShell `Get-Service`/`Get-CimInstance` | Para/desativa o serviço do Windows |
| Inicialização | `StartupScanner` | `reg query` (`HKCU`/`HKLM`\...\Run) + pasta Startup | Remove/desabilita a entrada de inicialização |
| Tarefas Agendadas | `TaskSchedulerScanner` | `schtasks /query` | Desativa a tarefa agendada |
| Planos de Energia | `PowerPlanScanner` | `powercfg /list` | Troca o plano de energia ativo |
| Telemetria | `TelemetryScanner` | `reg query` em chaves de telemetria/privacidade conhecidas | Altera o valor da chave (ex: desativa telemetria, histórico de atividades, localização) |
| Bloatware | `BloatwareScanner` | PowerShell `Get-AppxPackage` (traz **todos** os apps instalados) | Desinstala o pacote UWP (`Remove-AppxPackage`) |
| Performance e Energia | `PerformanceScanner` | `reg query`/PowerShell (efeitos visuais, GPU scheduling, hibernação, Armazenamento Reservado, bloqueio de driver update, etc.) | Aplica a configuração recomendada de desempenho |
| Otimizações para Jogos | `GamingScanner` | `reg query` (Game DVR, Modo de Jogo, prioridade de CPU) | Ativa/desativa conforme o item (Modo de Jogo é o único caso onde a recomendação é **ativar**) |
| IA / Inteligência Artificial | `AiFeatureScanner` | `reg query` em políticas de Copilot/Recall/Click to Do/Cocreator | Desativa via política; Copilot e Recall também têm uma segunda ação real de **desinstalar** (Appx/DISM) |
| Recursos de Consumidor e Segundo Plano | `ConsumerFeatureScanner` | `reg query` (anúncios, Bing Search, apps em segundo plano, ícones da barra de tarefas) | Desativa a chave correspondente |
| Taxa de Atualização da Tela | `DisplayScanner` | JNA (`EnumDisplaySettingsEx`/`DEVMODE`, `user32.dll`) | Não altera nada sozinho — abre a tela nativa de Configurações do Windows (`ms-settings:display`) |

### Segurança — mecanismo comum a quase toda ação

Toda ação de desativar/alterar (exceto as 3 explicitamente documentadas como exceção) passa pelo
mesmo fluxo, sem exceção:

1. **`LockManager.isLocked()`** — se o item estiver bloqueado pelo usuário, a ação é recusada.
2. **`BackupManager.snapshotBeforeAction()`** — salva o estado atual no SQLite antes de qualquer mudança.
3. Aplica a ação.
4. **Registra em `actions_history`** — mesmo quando a ação falha.

**Exceções documentadas** (ações pontuais, sem "estado" para reverter): Limpeza de RAM (Fase 10),
Reparo de Arquivos SFC/DISM e Reparo de Rede (Fase 15) — todas registradas no histórico como
informação, mas sem backup associado.

### Base remota e atualização da base de conhecimento

`RemoteKnowledgeUpdater` verifica (sob demanda) se existe uma versão mais nova da base de
conhecimento publicada numa URL configurável, comparando `version`/`updatedAt`; se a verificação
falhar por qualquer motivo (sem internet, formato inválido), o app cai de volta para a base local
embutida, sem quebrar.

## Testes

### Automatizados (JUnit 5) — `src/test/java/com/nitroboost/...`

**75 testes**, cobrindo lógica pura (sem chamar comando/rede real): parsers de saída de comando
(`ServiceScanner`, `TaskSchedulerScanner`, `PowerPlanScanner`, `SystemFileRepairTool`) com strings
fixas simulando a saída real do Windows, `KnowledgeBase`, `ItemClassification`, `VendorLinkStrategy`
(Fase 11), comparação de valores do `SystemAuditEngine` (Fase 9), montagem de comandos do
`NetworkRepairTool` e detecção de erro por falta de elevação (Fase 15), entre outros.

```powershell
.\mvnw.cmd test
```

### Manual (smoke test) — `TESTING.md`

Roteiro fixo de verificação na máquina real (processos/serviços/startup batendo com o Gerenciador
de Tarefas, backup/reversão, bloqueio, histórico, contagem de bloatware, etc.), documentado com
data e resultado de cada execução.

## Stack técnica

| Item | Tecnologia |
|---|---|
| Linguagem | Java 21 (LTS) |
| UI | JavaFX 21 (via `javafx-maven-plugin`) |
| Build | Maven (via Maven Wrapper — não é necessário ter o Maven instalado) |
| Persistência | SQLite (JDBC) + JSON de configuração |
| Bibliotecas nativas | OSHI (hardware/SO), JNA (acesso nativo ao Windows) |
| Serialização JSON | Jackson |
| Testes | JUnit 5 (`junit-jupiter`) |
| Plataforma alvo | Windows 11 (idealmente executado como Administrador) |

## Pré-requisitos

- **JDK 21** (não só o JRE) instalado e disponível no `PATH` (testado com Temurin 21) — o JDK
  completo é necessário porque `jpackage` (usado para gerar o `.exe`, ver "Empacotamento" abaixo)
  não existe no JRE.
- **Não é necessário instalar o Maven** — o projeto usa o Maven Wrapper (`mvnw` / `mvnw.cmd`), que
  baixa automaticamente a versão correta do Maven na primeira execução.
- Windows 11 (a maioria das funcionalidades do app interage diretamente com APIs/serviços do
  Windows).

## Como rodar (modo desenvolvimento)

Na raiz do projeto (PowerShell ou `cmd`):

```powershell
.\mvnw.cmd clean javafx:run
```

Isso deve:
1. Compilar o projeto.
2. Inicializar/validar o banco SQLite local em `%USERPROFILE%\.nitroboost\nitroboost.db`.
3. Abrir a janela principal do NITRO BOOST (Painel de Controle, Resultados do Scan, Diagnóstico,
   Reparo do Sistema, BIOS/Drivers, Histórico, Tutoriais) com o tema Carbono & Verde Turbo.

Em Git Bash / Linux / macOS, use `./mvnw clean javafx:run` (embora o app em si só funcione de
verdade no Windows, já que a maioria das ações chama comandos/registro do Windows).

Como boa parte das ações (parar serviço, editar registro, trocar plano de energia, desinstalar
bloatware, reparo de sistema/rede) exige privilégios de Administrador, para testar o fluxo completo
abra o terminal ("PowerShell" ou "Prompt de Comando") **como Administrador** antes de rodar o
comando acima.

### Rodar apenas o build (sem abrir a janela)

Útil para validar que tudo compila, em ambientes sem sessão gráfica interativa:

```powershell
.\mvnw.cmd -q compile
```

### Rodar a suíte de testes

```powershell
.\mvnw.cmd test
```

### Testar módulos isoladamente (sem UI)

Essas classes utilitárias imprimem no console e terminam sozinhas — não abrem janela. Além das
listadas abaixo, cada fase do projeto tem uma classe `PhaseXConsoleDemo`/`PhaseXPartYConsoleDemo`
na raiz do pacote `com.nitroboost` com o mesmo propósito:

```powershell
# Testa a leitura de hardware via OSHI (CPU/RAM)
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=com.nitroboost.core.HardwareInfoPrinter"

# Testa chamadas nativas ao Windows via JNA
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=com.nitroboost.core.JnaNativeTest"

# Cria/valida o schema do banco SQLite e lista as tabelas criadas
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=com.nitroboost.db.DatabaseManager"
```

## Empacotamento (gerar o `.exe` standalone)

O NITRO BOOST pode ser empacotado como um aplicativo standalone (com a JVM embutida — quem for
rodar **não precisa ter Java instalado**) via `jpackage`, ferramenta que já vem junto do JDK 21.

```powershell
scripts\jpackage-build.bat
```

O script faz tudo sozinho:

1. `mvnw package` — compila, empacota `target\nitroboost.jar` e copia todas as dependências de
   runtime (JavaFX nativo para Windows, OSHI, JNA, SQLite, Jackson) para `target\jpackage-input`
   (via `maven-dependency-plugin`, configurado no `pom.xml`).
2. Copia o `nitroboost.jar` principal para essa mesma pasta.
3. Roda `jpackage --type app-image`, gerando `target\dist\NitroBoost\NitroBoost.exe` — uma pasta
   standalone completa, pronta para copiar para outra máquina Windows e rodar diretamente.

**Nota técnica:** o ponto de entrada usado pelo jar/`jpackage` é `com.nitroboost.Launcher`, não
`com.nitroboost.Main` diretamente — `Main` estende `javafx.application.Application`, e o launcher
padrão do Java recusa iniciar uma classe assim fora do module-path com o erro *"os componentes de
runtime do JavaFX não foram encontrados"* (mesmo com os jars presentes no classpath). `Launcher` é
uma classe simples que apenas chama `Main.main(args)`, contornando essa checagem — ver o Javadoc de
`src/main/java/com/nitroboost/Launcher.java` para os detalhes. Isso só afeta a execução empacotada;
`javafx:run` (modo desenvolvimento) não precisa dessa classe.

`--type app-image` (em vez de `--type msi`) foi escolhido como padrão porque não depende de
ferramentas externas (um instalador `.msi` de verdade normalmente exige o **WiX Toolset**
instalado). Se o WiX estiver disponível na máquina, também é possível gerar um `.msi` diretamente:

```powershell
jpackage --type msi --input target\jpackage-input --dest target\dist --name NitroBoost --main-jar nitroboost.jar --main-class com.nitroboost.Launcher --app-version 1.0.0 --vendor "NITRO BOOST"
```

### Rodar como Administrador

A maioria das ações do NITRO BOOST exige privilégios elevados. Depois de gerar o pacote acima, use
o `run-as-admin.bat` na raiz do projeto — ele verifica se já está elevado, senão pede a elevação via
UAC (prompt padrão do Windows) e abre o `NitroBoost.exe`:

```powershell
.\run-as-admin.bat
```

## Estrutura de pastas

Ver seção 3 de `docs/NITRO-BOOST-documentacao-completa.md` para a estrutura original e o racional
de cada pacote. Pacotes adicionados nas fases posteriores: `repair/` (reparo de sistema/rede, Fase
15), `updates/` (verificação de BIOS/drivers, Fase 11), `audit/` (Diagnóstico do Sistema, Fase 9),
`ui/components/` (componentes reutilizáveis como `NitroProgressBar`, Fase 12). Toda a documentação
do projeto (incluindo os prompts de início de fase) fica em `docs/`; `README.md` e `TESTING.md` são
os únicos `.md` que permanecem na raiz, por convenção.

## Convenções do projeto

- Nomes de classes/métodos/variáveis em **inglês**.
- Comentários e textos exibidos ao usuário em **português**.
- Toda função que interage com o sistema operacional tem tratamento de erro (try/catch).
- Nenhum caminho ou nome de serviço específico de uma máquina é fixado no código — tudo é
  detectado dinamicamente em tempo de execução.
- Commits seguem o padrão `[FaseX] Descrição curta no imperativo` (ou `[FaseX-ParteY]` para fases
  divididas em partes).

## Licença

Projeto pessoal, uso individual por enquanto.
