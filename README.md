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

## Status atual

Projeto em desenvolvimento — **Fase 0 (Setup e Fundamentos)** concluída. Veja `PROGRESS.md` para o
histórico de progresso e `BLOCKERS.md` para bloqueios técnicos conhecidos.

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

- **Java 21** instalado e disponível no `PATH` (testado com Temurin 21).
- **Não é necessário instalar o Maven** — o projeto usa o Maven Wrapper (`mvnw` / `mvnw.cmd`), que
  baixa automaticamente a versão correta do Maven na primeira execução.
- Windows 11 (a maioria das funcionalidades do app interage diretamente com APIs/serviços do
  Windows).

## Como rodar

Na raiz do projeto (PowerShell ou `cmd`):

```powershell
.\mvnw.cmd clean javafx:run
```

Isso deve:
1. Compilar o projeto.
2. Inicializar/validar o banco SQLite local em `%USERPROFILE%\.nitroboost\nitroboost.db`.
3. Imprimir no console o uso atual de CPU e RAM (via OSHI).
4. Abrir uma janela JavaFX vazia com o título **"NITRO BOOST"**.

Em Git Bash / Linux / macOS, use `./mvnw clean javafx:run`.

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
