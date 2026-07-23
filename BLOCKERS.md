# 🚧 BLOCKERS — NITRO BOOST

Registro de bloqueios técnicos encontrados durante o desenvolvimento, conforme regra de ouro do
projeto (nunca pular uma tarefa silenciosamente por causa de um bloqueio).

---

## Fase 0 — Setup e Fundamentos

### 1. Maven não instalado globalmente no ambiente de desenvolvimento
- **Status:** Resolvido.
- **Descrição:** o comando `mvn` não estava disponível no `PATH` do ambiente usado para o setup
  inicial (`mvn -version` → "command not found").
- **Solução aplicada:** foi gerado o **Maven Wrapper** manualmente — os scripts oficiais `mvnw` e
  `mvnw.cmd` foram baixados do repositório `apache/maven-wrapper` (branch `master`,
  `maven-wrapper-distribution/src/resources/`), e `.mvn/wrapper/maven-wrapper.properties` foi
  criado apontando para:
  - `distributionUrl`: Apache Maven 3.9.9 (binário oficial em `repo.maven.apache.org`)
  - `wrapperUrl`: `maven-wrapper` 3.3.2 (jar oficial em `repo.maven.apache.org`)
  O wrapper foi validado com `./mvnw.cmd -v`, que baixou o Maven 3.9.9 para
  `%USERPROFILE%\.m2\wrapper\dists\...` e reportou a versão corretamente. A partir daí, todo o
  projeto usa exclusivamente `./mvnw` / `./mvnw.cmd` — **não é necessário instalar Maven** na
  máquina de quem for rodar o projeto.
- **Nenhuma ação pendente** — documentado no `README.md` ("Pré-requisitos" / "Como rodar").

### 2. Validação da janela JavaFX travou o fluxo automatizado (stall)
- **Status:** Resolvido / contornado.
- **Descrição:** ao tentar validar visualmente o critério de conclusão da Fase 0 ("o app abre uma
  janela JavaFX vazia"), uma tentativa de rodar o goal `javafx:run` (ou uma main class que chama
  `Application.launch()`) de forma síncrona ficou aguardando a janela ser fechada pelo usuário —
  processo que nunca retorna sozinho neste ambiente sem sessão gráfica interativa/headless,
  travando o comando indefinidamente (stall de ~600s sem progresso).
- **Solução aplicada:** a validação da Fase 0 neste ambiente automatizado foi limitada a:
  - `./mvnw.cmd -q compile` e `./mvnw.cmd -q package -DskipTests` para confirmar que o projeto
    compila e empacota sem erros (retornam sozinhos, não abrem UI).
  - Classes utilitárias com `main()` próprio que imprimem no console e terminam sozinhas
    (`HardwareInfoPrinter`, `JnaNativeTest`, `DatabaseManager`), rodadas via
    `exec:java -Dexec.mainClass=...`, sempre com `timeout` no shell como rede de segurança.
  - A abertura real da janela JavaFX (`Stage.show()`) **não foi validada visualmente** neste
    ambiente — a lógica de `Main.java` foi revisada manualmente e o build compila sem erros até o
    ponto de chamar `primaryStage.show()`, mas a confirmação visual definitiva (a janela realmente
    aparece, com o título correto, sem exceção no `Application.start()`) precisa ser feita pelo
    usuário na máquina real com sessão gráfica.
- **Ação pendente:** usuário deve rodar `.\mvnw.cmd clean javafx:run` na própria máquina (Windows
  com sessão gráfica) para confirmar visualmente a janela. Comando documentado no `README.md` e no
  relatório final desta fase.
- **Regra adotada daqui para frente:** nenhum comando que inicie uma UI JavaFX bloqueante
  (`javafx:run`, `Application.launch()` síncrono) deve ser executado diretamente por automações
  neste ambiente. Sempre usar `timeout` como rede de segurança em comandos com risco de não
  retornar sozinhos.

### 3. Bug no parser simplificado do `schema.sql` (semicolon dentro de comentário)
- **Status:** Resolvido.
- **Descrição:** a primeira versão de `DatabaseManager.initializeSchema()` removia apenas linhas
  que começavam com `--` antes de fazer `split(";")` no conteúdo do `schema.sql`. Um comentário
  inline continha um `;` no meio do texto (ex: "-- flag de conveniencia; a fonte de verdade..."),
  o que quebrou uma instrução `CREATE TABLE` ao meio e gerou o erro
  `[SQLITE_ERROR] SQL error or missing database (incomplete input)`.
- **Solução aplicada:** o parser agora remove todo o conteúdo a partir de `--` em qualquer ponto da
  linha (não só quando a linha inteira é um comentário), antes de concatenar e fazer o split por
  `;`. O comentário problemático em `schema.sql` também foi reescrito sem `;`. Reexecutado o teste
  (`exec:java -Dexec.mainClass=com.nitroboost.db.DatabaseManager`) e as 4 tabelas
  (`items`, `actions_history`, `backups`, `locks`) foram criadas corretamente.
- **Nota para o futuro:** esse parser de schema é propositalmente simples (split ingênuo por `;`),
  suficiente para o `schema.sql` atual. Se o schema crescer e passar a usar triggers, strings com
  `;` dentro, ou blocos `BEGIN...END`, será necessário um parser mais robusto (ou executar o
  arquivo inteiro de uma vez, se o driver SQLite JDBC permitir múltiplas statements em uma
  chamada).

---

## Itens sem bloqueio (apenas para referência)

- Repositório GitHub remoto: criado com `gh repo create nitroboost --private --source=. --remote=origin`
  (usuário `gh` já estava autenticado com os scopes necessários — nenhum bloqueio aqui).
- Todas as dependências (JavaFX, OSHI, JNA, SQLite JDBC, Jackson) foram resolvidas normalmente do
  Maven Central — sem bloqueio de rede/proxy neste ambiente.
