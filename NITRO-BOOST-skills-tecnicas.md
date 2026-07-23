# 🛠️ NITRO BOOST — Guia de Skills Técnicas para a IA

> Este documento lista as tecnologias, bibliotecas, comandos e boas práticas que a IA (Claude Code ou similar) deve dominar e utilizar durante o desenvolvimento do projeto. Serve como referência rápida para não fugir do escopo técnico definido e evitar soluções improvisadas ou fora do padrão.

---

## 1. Java (Core)

**Por que importa:** é a base de toda a aplicação.

- Java 17+ (LTS) — usar recursos modernos: `record`, `var`, `switch` expressions, `Optional`, streams (`.stream()`, `.filter()`, `.map()`)
- Programação orientada a objetos consistente: uma classe = uma responsabilidade (ver `ProcessScanner`, `ServiceScanner`, etc.)
- Tratamento de exceções específico (nunca `catch (Exception e)` genérico sem log — sempre capturar exceções específicas quando possível: `IOException`, `SQLException`, etc.)
- Uso de `Optional<T>` para métodos que podem não retornar valor (ex: buscar um item que pode não existir na base)
- Interfaces funcionais e lambdas para callbacks de UI (ex: ação de botão)

---

## 2. JavaFX (Interface Gráfica)

**Por que importa:** é o coração visual do "Booster Gamer" — precisa ser fluido e bem estilizado.

- **FXML vs Java puro:** para este projeto, recomenda-se **Java puro** (sem FXML) nas telas mais dinâmicas (Dashboard com gráficos em tempo real), e **FXML** é opcional para telas mais estáticas (ex: TutorialView), se facilitar organização.
- **JavaFX Charts** (`LineChart`, `AreaChart`) para gráficos de CPU/RAM em tempo real
- **CSS em JavaFX:** dominar seletores `-fx-*` (ex: `-fx-background-color`, `-fx-effect`, `-fx-background-image`) para aplicar o tema Carbono & Verde Turbo
- **Timeline / AnimationTimer:** para atualizar dashboard periodicamente (ex: a cada 2-3 segundos) sem travar a UI (nunca rodar polling pesado na Application Thread — usar `Task`/`Service` do JavaFX para trabalho em background)
- **Property Binding** (`SimpleDoubleProperty`, `Bindings`) para atualizar gráficos e barras de progresso de forma reativa
- **Modularização JavaFX:** conhecer a configuração de VM args (`--module-path`, `--add-modules`) necessária a partir do Java 11+, já que JavaFX não vem mais embutido no JDK

---

## 3. Concorrência e Threads

**Por que importa:** varredura de sistema e monitoramento em tempo real não podem travar a interface.

- `javafx.concurrent.Task` e `Service` — para rodar scanners em background sem congelar a UI
- `Platform.runLater()` — para atualizar componentes JavaFX a partir de threads secundárias
- `ScheduledExecutorService` — para rotinas periódicas (ex: atualização do dashboard a cada X segundos)
- Cuidado com **race conditions** ao gravar no SQLite a partir de múltiplas threads (usar conexões/transações adequadas)

---

## 4. OSHI (Operating System and Hardware Information)

**Por que importa:** é a principal biblioteca para captar dados reais de hardware sem precisar escrever código nativo do zero.

- `SystemInfo` → ponto de entrada da biblioteca
- `HardwareAbstractionLayer` → acesso a CPU, memória, sensores, discos
- `OperatingSystem.getProcesses()` → lista de processos com uso de CPU/RAM
- `GlobalMemory` → memória total/disponível
- `CentralProcessor` → uso de CPU, número de núcleos, frequência (útil para detectar XMP indiretamente comparando frequência de RAM nominal vs relatada)
- `Sensors` → temperatura (quando suportado pelo hardware/driver)

---

## 5. JNA (Java Native Access)

**Por que importa:** para os casos em que OSHI não cobre (chamadas mais específicas à API do Windows), sem precisar escrever JNI em C.

- Usar apenas quando OSHI não resolver — não duplicar funcionalidade
- Mapeamento de funções da `user32.dll`, `kernel32.dll` quando necessário
- Sempre documentar no código **por que** uma chamada JNA específica foi necessária (evitar "mágica" difícil de manter)

---

## 6. Integração com Comandos do Sistema (Windows)

**Por que importa:** boa parte das ações (serviços, tarefas, registro, energia) só é acessível via linha de comando/PowerShell, não via API Java direta.

- `ProcessBuilder` (Java) para executar comandos e capturar saída (stdout/stderr)
- Comandos principais a dominar:
  - `sc query` / `sc stop` / `sc config` → serviços do Windows
  - `Get-Service` / `Stop-Service` / `Set-Service` (PowerShell) → alternativa mais moderna a `sc`
  - `schtasks /query` / `schtasks /change` → tarefas agendadas
  - `powercfg /list` / `powercfg /setactive` → planos de energia
  - `reg query` / `reg add` / `reg delete` → registro do Windows (startup, telemetria)
  - `Get-AppxPackage` / `Remove-AppxPackage` (PowerShell) → apps UWP (Widgets, Xbox, Copilot)
  - `tasklist` / `taskkill` → alternativa nativa para processos (complementar ao OSHI)
- **Sempre parsear a saída dos comandos de forma defensiva** (formato pode variar entre versões do Windows) — nunca assumir posição fixa de coluna sem validar.
- **Executar sempre com verificação de código de retorno (`exitValue()`)** para saber se o comando teve sucesso.

---

## 7. Persistência de Dados

**Por que importa:** histórico, backups e bloqueios precisam sobreviver ao fechamento do app.

- **SQLite via JDBC** (`org.xerial:sqlite-jdbc`) — banco leve, arquivo único, sem necessidade de servidor
- Uso de **PreparedStatement** sempre (nunca concatenar strings SQL — evitar SQL Injection mesmo sendo app local)
- Desenho de schema simples e normalizado: tabelas `items`, `actions_history`, `backups`, `locks`
- **Jackson** ou **Gson** para ler/escrever `knowledge-base.json` (configuração externa e editável)

---

## 8. Testes

**Por que importa:** ações que mexem no sistema operacional real precisam de confiança antes de ir para produção.

- **JUnit 5** para testes unitários da lógica pura (parsers, classificação de itens, regras de negócio)
- Testes de integração com o sistema operacional devem ser **isolados e opcionais** (marcados para não rodar automaticamente em qualquer ambiente, já que dependem do Windows real)
- Mockar chamadas a `ProcessBuilder` sempre que possível para testar lógica sem executar comandos reais

---

## 9. Controle de Versão

**Por que importa:** rastreabilidade do progresso, principalmente para sessões autônomas (madrugada).

- Git com commits pequenos e frequentes, um por tarefa da checklist
- `.gitignore` adequado para projetos Java/Maven (ignorar `target/`, `.idea/`, `*.class`, banco SQLite local se for gerado em runtime)
- Mensagens de commit no padrão definido: `[FaseX] Descrição curta`

---

## 10. Empacotamento e Distribuição

**Por que importa:** o app final precisa ser fácil de instalar/rodar, inclusive por outras pessoas no futuro.

- **jpackage** (ferramenta nativa do JDK 14+) para gerar `.exe` standalone com JVM embutida
- Manifesto de aplicação exigindo elevação de administrador (necessário para a maioria das ações do app)
- Considerar assinatura de código no futuro, caso o app seja distribuído amplamente (Windows SmartScreen alerta para executáveis não assinados)

---

## 11. Design de Interface (Tema Carbono & Verde Turbo)

**Por que importa:** a identidade visual é parte importante da proposta do projeto.

- CSS puro do JavaFX (não é CSS de navegador — algumas propriedades são específicas, prefixo `-fx-`)
- Uso de gradientes (`-fx-background-color: linear-gradient(...)`) para simular textura carbono
- `DropShadow` e `Glow` (efeitos nativos do JavaFX) para o efeito neon
- Fontes customizadas: carregar fontes externas (Google Fonts baixadas localmente, ex: Orbitron/Rajdhani) via `Font.loadFont()`

---

## 12. Segurança e Permissões

**Por que importa:** o app mexe em configurações sensíveis do sistema — erro aqui pode travar o Windows do usuário.

- Sempre validar se a aplicação está rodando como Administrador antes de tentar ações que exigem elevação (detectar via tentativa de acesso e captura de erro de permissão)
- Nunca aplicar uma ação em lote sem confirmação explícita do usuário na primeira vez
- Log detalhado de erros de permissão em `BLOCKERS.md` (conforme já definido no documento principal do projeto)

---

## 13. Documentação e Organização (Soft Skills da IA)

**Por que importa:** o projeto será construído de forma autônoma e precisa ser compreensível depois.

- Comentários claros em português explicando o "porquê" de decisões não óbvias (não é preciso comentar o óbvio)
- Atualização constante de `PROGRESS.md` e `BLOCKERS.md` conforme definido no documento principal
- Nomes de variáveis e métodos descritivos (`disableUnnecessaryService()` ao invés de `doStuff()`)

---

## 14. Ordem de Prioridade das Skills (o que dominar primeiro)

1. **Java Core + Maven** — base de tudo
2. **OSHI** — primeira fonte de dados real do sistema
3. **ProcessBuilder + comandos do Windows** — essencial para serviços/startup/registro
4. **SQLite/JDBC** — necessário desde a Fase 1 (backup)
5. **JavaFX básico** — só entra com força na Fase 4, mas vale configurar cedo (Fase 0)
6. **JNA** — só quando OSHI/comandos não resolverem algo específico
7. **CSS JavaFX (tema visual)** — Fase 4
8. **jpackage** — só na Fase 6 (empacotamento final)

---

*Este guia deve ser consultado antes de iniciar cada fase da checklist principal (`NITRO-BOOST-documentacao-completa.md`), para garantir que a ferramenta/técnica certa seja usada em cada etapa.*
