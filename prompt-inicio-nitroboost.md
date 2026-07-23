# 🚀 Prompt de Início — NITRO BOOST (enviar ao Claude Code no VSCode)

Copie e cole o texto abaixo diretamente no chat do Claude Code, dentro da pasta do projeto no VSCode:

---

Você vai iniciar o desenvolvimento do projeto **NITRO BOOST**, uma aplicação desktop em Java + JavaFX para otimização e controle de performance do Windows 11, com tema visual "Booster Upgrade Gamer" (Carbono & Verde Turbo).

Antes de escrever qualquer código, leia por completo os seguintes documentos, que estão na raiz deste projeto:

1. `NITRO-BOOST-documentacao-completa.md` — contém o objetivo do projeto, a arquitetura, a estrutura de pastas e o checklist detalhado de todas as fases de desenvolvimento (Fase 0 a Fase 6+).
2. `NITRO-BOOST-skills-tecnicas.md` — contém o guia de skills técnicas: quais bibliotecas, comandos e boas práticas usar em cada parte do projeto (OSHI, JNA, JavaFX, SQLite, comandos do Windows, etc.). Consulte este guia sempre antes de iniciar cada fase, para usar sempre a ferramenta certa.

## Instruções de execução

1. **Siga a ordem exata das fases** descritas em `NITRO-BOOST-documentacao-completa.md`. Não avance para a próxima fase sem concluir 100% dos itens da checklist da fase atual.
2. **Use as skills técnicas do guia** (`NITRO-BOOST-skills-tecnicas.md`) como referência obrigatória — não improvise soluções fora do que está documentado sem necessidade real.
3. **Utilize as ferramentas MCP disponíveis** neste ambiente sempre que forem úteis para a tarefa (ex: manipulação de arquivos, terminal, git/GitHub, ou qualquer outro servidor MCP já conectado). Antes de cada etapa, verifique quais ferramentas MCP você tem disponíveis e use a mais adequada em vez de tentar contornar manualmente algo que uma ferramenta já resolveria.
4. **Crie a estrutura de pastas e arquivos** exatamente como definida na seção 3 do documento principal.
5. **Marque cada item da checklist como concluído** (`[ ]` → `[x]`) diretamente no arquivo `NITRO-BOOST-documentacao-completa.md` conforme for terminando.
6. **Atualize `PROGRESS.md`** ao final de cada fase, com um resumo curto do que foi feito.
7. **Registre qualquer bloqueio técnico** (erro de permissão, comando não reconhecido, biblioteca com problema) em `BLOCKERS.md`, e continue com as tarefas que não dependem daquele bloqueio.
8. **Faça commits pequenos e frequentes**, um por tarefa concluída, seguindo o padrão: `[FaseX] Descrição curta da tarefa`.
9. **Nunca implemente uma ação destrutiva (desativar serviço, matar processo, alterar registro) sem que o sistema de backup (`BackupManager`) já esteja implementado e funcional.**
10. Ao concluir a Fase 0, **pare e apresente um resumo** do que foi criado antes de seguir para a Fase 1, para eu validar que o ambiente está correto.

## Objetivo desta sessão

Comece pela **Fase 0 (Setup e Fundamentos)** e siga em frente de forma autônoma pelas fases seguintes, respeitando os pontos de parada e validação indicados no documento principal. Priorize sempre segurança e reversibilidade das ações sobre velocidade de entrega.

Se tiver qualquer dúvida sobre uma decisão de arquitetura não coberta pelos documentos, registre a dúvida em `BLOCKERS.md` e siga com a alternativa mais segura e conservadora, em vez de travar o progresso.

---

## 📋 Antes de enviar, confira:

- [ ] Os arquivos `NITRO-BOOST-documentacao-completa.md` e `NITRO-BOOST-skills-tecnicas.md` estão salvos na **raiz da pasta do projeto** que você abriu no VSCode
- [ ] O MCP que você quer usar já está conectado/configurado no VSCode
- [ ] A pasta do projeto está vazia ou é um repositório Git já inicializado (evita conflito com arquivos antigos)
