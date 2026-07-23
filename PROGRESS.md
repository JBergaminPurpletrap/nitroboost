# 📈 PROGRESS — NITRO BOOST

Histórico de progresso do projeto, atualizado ao final de cada fase concluída.

---

## 2026-07-23 — Fase 0 concluída (Setup e Fundamentos)

Setup inicial completo do projeto. Resumo do que foi feito:

- **Git:** repositório local já existia (`git init` prévio); foi criado o repositório remoto
  privado no GitHub (`gh repo create nitroboost --private --source=. --remote=origin`) e o branch
  `master` foi enviado para `origin`.
- **Maven:** `mvn` não estava instalado globalmente no ambiente — o **Maven Wrapper** (`mvnw` /
  `mvnw.cmd`) foi baixado/configurado manualmente (aponta para Maven 3.9.9 e maven-wrapper 3.3.2,
  ambos resolvidos do Maven Central). Ver `BLOCKERS.md` item 1.
- **`pom.xml`:** projeto Maven criado com Java 21 (`maven.compiler.release=21`) e dependências:
  `javafx-controls`/`javafx-fxml` 21.0.12 (via `org.openjfx:javafx-maven-plugin` 0.0.8, que resolve
  os jars nativos pelo classifier do SO automaticamente), `oshi-core` 7.4.1, `jna`/`jna-platform`
  5.19.1, `org.xerial:sqlite-jdbc` 3.53.2.1, `jackson-databind` 2.22.1. Também configurado
  `exec-maven-plugin` (para rodar classes de teste isoladas sem abrir a UI) e
  `maven-compiler-plugin`.
- **Estrutura de pastas:** criada exatamente conforme a seção 3 da documentação —
  `src/main/java/com/nitroboost/{core,actions,knowledge,ui,db}` e `Main.java`, além de
  `src/main/resources/{knowledge-base.json, theme/, tutorials/}`. As pastas `actions/`,
  `knowledge/` e `ui/` ficam vazias por ora — populadas a partir da Fase 1 em diante.
- **`Main.java`:** aplicação JavaFX mínima — inicializa o banco (schema), imprime specs de
  hardware via OSHI no console, e abre uma janela vazia com o título "NITRO BOOST"
  (`StackPane` com um `Label`).
- **Teste OSHI:** `com.nitroboost.core.HardwareInfoPrinter` — lê e imprime CPU (nome, núcleos,
  % de uso) e RAM (usada/total) via `oshi.SystemInfo`. Testado isoladamente via
  `./mvnw exec:java -Dexec.mainClass=com.nitroboost.core.HardwareInfoPrinter` — funcionou,
  imprimiu specs reais da máquina de desenvolvimento.
- **Teste JNA:** `com.nitroboost.core.JnaNativeTest` — chama `Kernel32Util.getComputerName()`
  (API de alto nível do `jna-platform`) e um binding manual direto a `GetTickCount64` da
  `kernel32.dll` (demonstrando mapeamento manual de DLL nativa). Testado isoladamente — funcionou,
  retornou o nome do computador e o uptime do Windows.
- **`DatabaseManager.java` + `db/schema.sql`:** schema com as 4 tabelas pedidas (`items`,
  `actions_history`, `backups`, `locks`, com índices e foreign keys entre elas). O banco SQLite
  fica em `%USERPROFILE%\.nitroboost\nitroboost.db` (caminho dinâmico via `user.home`, nunca
  hardcoded). `initializeSchema()` lê `schema.sql` do classpath (empacotado via configuração extra
  de `<resources>` no `pom.xml`, já que o arquivo fica em `src/main/java/.../db/` e não em
  `src/main/resources/`) e executa cada `CREATE TABLE IF NOT EXISTS` / `CREATE INDEX`. Testado
  isoladamente via `./mvnw exec:java -Dexec.mainClass=com.nitroboost.db.DatabaseManager` — as 4
  tabelas foram criadas e confirmadas com uma consulta a `sqlite_master`. Um bug no parser
  simplificado do schema (semicolon dentro de um comentário quebrando o split) foi encontrado e
  corrigido nesse processo — ver `BLOCKERS.md` item 3.
- **Build:** `./mvnw -q compile` e `./mvnw -q package -DskipTests` rodam sem erros;
  `target/nitroboost.jar` gerado com sucesso.
- **`README.md`, `PROGRESS.md`, `BLOCKERS.md`:** criados.
- **`.gitignore`:** criado (ignora `target/`, `*.class`, IDEs, arquivos `.db*` soltos no projeto,
  `desktop.ini`).

### Não validado neste ambiente (ação pendente do usuário)

A abertura visual da janela JavaFX (`javafx:run`) **não foi validada de forma síncrona** neste
ambiente automatizado, pois trava o processo aguardando a janela ser fechada (sem sessão gráfica
interativa aqui). O build compila e empacota sem erros até o ponto de `primaryStage.show()`, mas a
confirmação visual final (janela abre, título correto, sem exceção) deve ser feita pelo usuário
rodando `.\mvnw.cmd clean javafx:run` na máquina real. Ver `BLOCKERS.md` item 2.

### Critério de conclusão da Fase 0 (conforme documentação)

- [x] App compila e a lógica de inicialização roda sem exceção (banco + OSHI) antes de abrir a
      janela — validado via build/testes isolados.
- [ ] Confirmação visual de que a janela JavaFX realmente abre — pendente, deve ser feita pelo
      usuário na máquina real (ver comando acima).
- [x] Specs de hardware impressas no console via OSHI — validado.
- [x] Banco SQLite existe localmente com as 4 tabelas criadas — validado.

Próximo passo (não iniciado): **Fase 1 — MVP Core (Processos, Serviços, Startup)**.
