# 🔎 Prompt — Investigação: sugestões clicadas não aplicam mudança real

Copie e cole no chat do Claude Code, dentro da pasta do projeto **nesta máquina** (a máquina gamer,
com GPU dedicada), com o **VSCode aberto como Administrador** (Fase 16 exige elevação de verdade
para os comandos de registro/serviço funcionarem).

---

Antes de mexer em qualquer coisa, leia por completo:
- `docs/NITRO-BOOST-fase16-validacao-administrador.md` e `docs/prompt-fase16-validacao-administrador.md`
  (checklist completo da rodada de validação com Administrador — esta investigação é um bloqueio
  encontrado durante a seção 2 desse checklist).
- `docs/PROGRESS.md` e `docs/BLOCKERS.md` (estado atual do projeto).
- `src/main/java/com/nitroboost/actions/ActionExecutor.java` — arquivo central, acabou de receber
  uma mudança grande (ver abaixo).

## Contexto

NITRO BOOST é um app Java 21 + JavaFX de otimização do Windows 11. Antes desta sessão, quase toda
ação de "aplicar sugestão" decidia sucesso/falha só pelo código de saída do comando (`reg add`,
`Set-Service`, `powercfg`, etc.), sem reler o estado real depois. Um bug real desse tipo já tinha
sido encontrado antes (`arp -d *` em `NetworkRepairTool`, que sempre retorna código 0 mesmo falhando
por falta de elevação).

**Rodando `git log --oneline -5` você deve ver, no topo, estes três commits** (rode `git pull` se
não aparecerem — foram feitos na máquina de desenvolvimento, ainda não estavam aqui quando você
testou pela primeira vez):

```
11c4da7 [Confiabilidade] Documenta a verificacao pos-acao real em PROGRESS.md
b1f3e48 [Confiabilidade] Adiciona verificacao pos-acao real (releitura + comparacao)
391ba14 [Confiabilidade] Extrai RegistryValueUtils compartilhado (dwordValuesEqual/parseRegQueryValue)
```

Eles adicionam `ActionExecutor.verifyPostAction(...)`: depois que um comando reporta sucesso pelo
código de saída, o app agora relê o estado real (via os scanners já existentes) e só confirma
sucesso se o valor bater com o esperado. Isso está implementado e com 95/95 testes JUnit passando —
mas os testes usam **strings fixas simuladas**, nunca um comando `reg`/`Set-Service` real. Esta é a
primeira vez que esse caminho roda contra o Windows de verdade, numa máquina elevada.

## O problema relatado

Testando a seção 2 do checklist da Fase 16 (ações de escrita, caminho de sucesso) nesta máquina,
como Administrador: **clicar em "Aplicar" numa sugestão não aplicou a mudança de verdade** — nenhum
item testado teve o valor realmente alterado no Windows, mesmo com privilégio de admin.

Já foi feita uma revisão estática do código (sem conseguir reproduzir, porque a sessão de
desenvolvimento não é elevada) e o encadeamento **parece** correto:

```
AuditView.applySingle() / confirmAndApplyAll()
  → ItemActionDispatcher.performPrimaryAction(executor, item)
    → ActionExecutor.<metodo especifico por tipo>  (ex: setPerformanceValue, disableService, ...)
      → applyRegistryDwordChange / runServiceAction / switchPowerPlan / etc.
        → runCommand(...) (reg add / Set-Service / powercfg)
        → verifyPostAction(...) (NOVO — relê e compara)
        → recordHistory(...)
```

Não foi encontrado um bug óbvio de "botão não chama nada". O problema deve estar mais fundo — ver
hipóteses abaixo.

## Sua tarefa: seguir depuração sistemática (causa raiz antes de qualquer fix)

**Não proponha nem aplique nenhum fix antes de reproduzir e entender a causa raiz.** Siga a skill
`superpowers:systematic-debugging` se disponível neste ambiente. Passo a passo:

1. **Reproduza de forma controlada e com evidência**, um item por vez (não em lote):
   - Escolha um único item seguro e reversível — ex: o serviço `MapsBroker` (mesmo item já usado
     nos testes anteriores), ou um item de registro de Performance/Telemetria.
   - Antes de clicar "Aplicar" no app, confirme o estado atual via comando nativo do Windows
     (`Get-Service MapsBroker` ou `reg query "<caminho>" /v "<valor>"`).
   - Clique "Aplicar" na tela de Diagnóstico do Sistema (`AuditView`) ou na tabela de Resultados da
     Varredura.
   - **Anote literalmente a mensagem de status/resultado que o app mostrou** (sucesso ou falha, e o
     texto completo — isso já diferencia as duas hipóteses principais abaixo).
   - Confirme de novo via comando nativo se o valor realmente mudou.
   - Abra a tela de Histórico (`HistoryView`) e confirme se a tentativa aparece lá, e com qual
     resultado/mensagem.

2. **Duas hipóteses principais a distinguir** (a mensagem exata do passo 1 decide qual é):
   - **Hipótese A — o comando (`reg add`/`Set-Service`/`powercfg`) está de fato falhando**, mesmo
     elevado. Se a mensagem do app for do tipo "Falha ao alterar... (comum se o app nao estiver
     rodando como Administrador)", o app está reportando falha real do comando, não da releitura
     nova. Investigar: o processo Java do `javafx:run`/`.exe` está mesmo herdando a elevação do
     terminal/VSCode? (Rode `whoami /groups | findstr /i "S-1-5-32-544"` **dentro do mesmo terminal
     que lançou o app**, não em outro.) Verifique também se o token de UAC não está sendo perdido
     entre VSCode → terminal integrado → processo Java.
   - **Hipótese B — o comando teve sucesso (código de saída 0), mas a releitura nova
     (`verifyPostAction`) está relatando incompatibilidade por engano** (falso negativo introduzido
     hoje). Se a mensagem do app contiver o texto "ATENCAO: o comando reportou sucesso, mas a
     releitura do estado real mostrou um valor diferente do esperado", é isso. Nesse caso, investigar
     a função de releitura específica do tipo testado:
     - Registro: `queryRegistryDwordValue` (`ActionExecutor.java`, usa `reg query` + 
       `RegistryValueUtils.parseRegQueryValue`) — comparar a saída real de `reg query` nesta máquina
       com o formato que o parser espera.
     - Serviço: `verifyServiceAction` + `ServiceScanner.findByName` — possível problema de *timing*
       (o `Set-Service` retorna antes do estado transicionar de verdade para `Stopped`/`Disabled`;
       a releitura roda rápido demais e pega um estado intermediário).
     - Plano de energia: releitura via `PowerPlanScanner`.
   - Se a mensagem não bater com nenhum dos dois padrões acima, copie a mensagem exata e trate como
     uma terceira hipótese a investigar do zero.

3. **Repita para pelo menos 2 categorias diferentes** (ex: um serviço e um item de registro) antes
   de concluir qual hipótese é a real — pode ser que só uma categoria esteja com problema, não todas.

4. **Só depois de confirmar a causa raiz**, implemente o fix mínimo, com um teste JUnit que teria
   pego o bug (usando string fixa que reproduza o formato real observado no passo 1/2, não invente
   uma string genérica).

5. Rode `.\mvnw.cmd -q compile` e `.\mvnw.cmd test` — build limpo e nenhuma regressão nos testes
   existentes antes de qualquer commit.

## Continue o checklist da Fase 16 depois de resolver isso

Depois que a causa raiz estiver corrigida (ou, se não for corrigível na hora, documentada como
bloqueio real em `docs/BLOCKERS.md`), **continue o checklist completo de
`docs/NITRO-BOOST-fase16-validacao-administrador.md`** a partir de onde parou — seções 2 em diante
(RAM, Copilot, GPU, SFC/DISM, Reparo de Rede, Modo de Jogo, Taxa de Atualização, Empacotamento).

## Onde documentar

- **`docs/BLOCKERS.md`**: qualquer falha real confirmada (não mais "bloqueado por ambiente", já que
  agora há admin + GUI de verdade), com a causa raiz encontrada e prioridade de correção.
- **`TESTING.md`**: nova seção "Rodada 2 — Validação com Administrador" (data de hoje), no mesmo
  formato de tabela já usado nas rodadas anteriores, cobrindo todos os itens do checklist da Fase 16.
- **`docs/PROGRESS.md`**: resumo desta investigação (causa raiz encontrada, fix aplicado ou pendente)
  e da rodada de validação.
- Commits com prefixo `[Confiabilidade]` se o fix for na verificação pós-ação, ou `[Fase16]` se for
  específico do checklist de validação.

## Regras do projeto (reforço — já valem desde o início)

- Nunca pule a investigação de causa raiz para "só tentar um fix rápido".
- Backup antes de qualquer ação destrutiva/irreversível — já é o padrão do `ActionExecutor`, não
  altere esse contrato.
- Não interrompa SFC/DISM no meio se chegar a essa seção do checklist.
- Não desative `NVDisplay.ContainerLocalSystem` (ou equivalente AMD) sem ler a descrição completa.
- Commit por tarefa concluída, mensagens claras, sem pular etapas.

## Ao final

Volte com um resumo consolidado para trazer de volta à outra sessão (a que está na máquina de
desenvolvimento): qual era a causa raiz real do "nada foi aplicado", o que foi corrigido (ou por que
não), e o resultado completo do checklist da Fase 16 (quantos itens passaram de verdade, quantos
ficaram pendentes e por quê, e se algum outro bug real foi encontrado).
