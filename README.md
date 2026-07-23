# 🚀 NITRO BOOST

Aplicação desktop (Java + JavaFX) para otimização e controle de performance do **Windows 11**.

O NITRO BOOST escaneia o sistema, identifica processos, serviços, itens de inicialização e
configurações que consomem recursos desnecessariamente, explica em português simples o que cada
item faz, e permite desativar/bloquear/reverter cada ajuste com segurança (backup automático antes
de qualquer ação destrutiva). Tema visual: **Carbono & Verde Turbo** — preto com textura de fibra
de carbono e acentos em verde neon, estilo HUD gamer.

> Consulte `NITRO-BOOST-documentacao-completa.md` para a documentação completa do projeto
> (objetivo, arquitetura, checklist detalhado por fase) e `NITRO-BOOST-skills-tecnicas.md` para o
> guia de bibliotecas/comandos usados na implementação.
>
> ⚠️ **Projeto pessoal e privado.** Feito para uso individual, sem garantias, sem suporte e sem
> distribuição pública planejada. Ele mexe em processos, serviços, registro, tarefas agendadas,
> plano de energia e apps do Windows — use por sua conta e risco, de preferência lendo o que cada
> ação faz antes de confirmar (é exatamente para isso que existe a tela de detalhes de cada item).

## Status atual

Todas as fases "core" do projeto (0 a 6) estão concluídas — o app escaneia o sistema, executa e
reverte ações com backup automático, tem interface gráfica completa com o tema Carbono & Verde
Turbo, tutoriais (incluindo detecção de XMP/EXPO desativado), e já pode ser empacotado como um
executável standalone via `jpackage` (veja "Empacotamento" abaixo). Veja `PROGRESS.md` para o
histórico detalhado de cada fase e `BLOCKERS.md` para bloqueios técnicos conhecidos. A Fase 7
(expansão online) é opcional/futura e não foi iniciada.

## Stack técnica

| Item | Tecnologia |
|---|---|
| Linguagem | Java 21 (LTS) |
| UI | JavaFX 21 (via `javafx-maven-plugin`) |
| Build | Maven (via Maven Wrapper — não é necessário ter o Maven instalado) |
| Persistência | SQLite (JDBC) + JSON de configuração |
| Bibliotecas nativas | OSHI (hardware/SO), JNA (acesso nativo ao Windows) |
| Serialização JSON | Jackson |
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
3. Abrir a janela principal do NITRO BOOST (Dashboard, Resultados do Scan, Histórico, Tutoriais)
   com o tema Carbono & Verde Turbo.

Em Git Bash / Linux / macOS, use `./mvnw clean javafx:run` (embora o app em si só funcione de
verdade no Windows, já que a maioria das ações chama comandos/registro do Windows).

Como boa parte das ações (parar serviço, editar registro, trocar plano de energia, desinstalar
bloatware) exige privilégios de Administrador, para testar o fluxo completo abra o terminal
("PowerShell" ou "Prompt de Comando") **como Administrador** antes de rodar o comando acima.

### Rodar apenas o build (sem abrir a janela)

Útil para validar que tudo compila, em ambientes sem sessão gráfica interativa:

```powershell
.\mvnw.cmd -q compile
```

### Testar módulos isoladamente (sem UI)

Essas classes utilitárias imprimem no console e terminam sozinhas — não abrem janela:

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

Ver seção 3 de `NITRO-BOOST-documentacao-completa.md` para a estrutura completa e o racional de
cada pacote (`core/`, `actions/`, `knowledge/`, `ui/`, `db/`).

## Convenções do projeto

- Nomes de classes/métodos/variáveis em **inglês**.
- Comentários e textos exibidos ao usuário em **português**.
- Toda função que interage com o sistema operacional tem tratamento de erro (try/catch).
- Nenhum caminho ou nome de serviço específico de uma máquina é fixado no código — tudo é
  detectado dinamicamente em tempo de execução.
- Commits seguem o padrão `[FaseX] Descrição curta no imperativo`.

## Licença

Projeto pessoal, uso individual por enquanto.
