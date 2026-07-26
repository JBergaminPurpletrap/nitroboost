# ✅ Prompt — Fase 12: Debloat Final + Testes + Barras de Progresso

Copie e cole no chat do Claude Code, dentro da pasta do projeto (depois de confirmar que a Fase 11 já foi concluída e validada):

---

Antes de escrever qualquer código, leia por completo o arquivo `NITRO-BOOST-fase12-debloat-final-e-testes.md`, na raiz do projeto (agora com 3 partes: Debloat, Testes e Barras de Progresso).

## Parte 1 — Últimos itens de debloat

1. Adicionar os itens de telemetria de GPU (NVIDIA/AMD, seção A.1) à base de conhecimento — são lidos pelo `ServiceScanner` existente, só precisam de classificação. **Atenção especial ao `NVDisplay.ContainerLocalSystem`**: documente claramente na descrição que desativar pode afetar funções do painel NVIDIA (overlay, G-SYNC), não é um "seguro desativar" simples como os outros.
2. Adicionar os apps pré-instalados da seção A.2 (Clipchamp, Sticky Notes, Get Help, etc.) à base de conhecimento — já aparecem na varredura desde a correção da Fase 8, só falta classificação.
3. Adicionar o item de Edge Startup Boost/Background Mode (seção A.3) ao `ConsumerFeatureScanner` existente.
4. Implementar a desinstalação completa do OneDrive (seção A.4) no `ActionExecutor` — **esta ação precisa de um modal de confirmação diferente e mais explícito** do que o padrão usado nas outras ações, avisando que arquivos salvos só no OneDrive devem ser copiados antes de prosseguir.
5. Adicionar o bloqueio de atualização automática de driver de GPU via Windows Update (seção A.5) ao `PerformanceScanner` da Fase 9.
6. Atualizar `PROGRESS.md`.

## Parte 2 — Suíte de Testes de Validação

Só comece depois que a Parte 1 estiver implementada.

1. Escrever os testes JUnit 5 descritos na seção B.1 do documento — cobrindo `KnowledgeBase`, parsers de saída de comando (com strings de exemplo fixas, sem chamar comando real), `ItemClassification`, `VendorLinkStrategy`, comparação de versões, e `SystemAuditEngine`.
2. Rodar toda a suíte e confirmar 100% de sucesso antes de seguir.
3. Executar o checklist de teste manual da seção B.2 na minha máquina real, item por item, e criar `TESTING.md` na raiz do projeto documentando o resultado de cada um (data, resultado, observações).
4. Qualquer item do checklist manual que falhar deve virar uma entrada em `BLOCKERS.md` com prioridade de correção — não marque a fase como concluída até que os itens críticos (backup/reversão, bloqueio, histórico) estejam validados como funcionando de verdade.
5. Atualizar `PROGRESS.md`.

## Parte 3 — Barras de Progresso para Escaneamento e Diagnóstico

Só comece depois que a Parte 2 estiver implementada e todos os testes JUnit passando.

1. Criar a interface `ScanProgressListener.java` conforme a seção C.1 do documento.
2. Adicionar a sobrecarga `scan(ScanProgressListener listener)` em **todos** os scanners já existentes no projeto (processos, serviços, startup, tarefas, energia, telemetria, bloatware, IA, consumidor, performance, jogos) — **sem alterar o `scan()` sem parâmetro**, já que os testes JUnit da Parte 2 dependem dele continuar funcionando exatamente como está.
3. Para categorias com poucos itens, reportar progresso só no início e no fim. Para categorias com muitos itens (serviços, tarefas, bloatware, processos), reportar a cada item ou a cada lote pequeno (5-10 itens).
4. Adaptar `SystemScanTask` para implementar `ScanProgressListener`, traduzindo cada chamada em `updateProgress()`/`updateMessage()` nativos do `javafx.concurrent.Task`.
5. Criar o componente `ui/components/NitroProgressBar.java` com o estilo visual da seção C.2 (gradiente verde técnico → verde neon, trilho carbono, glow sutil, fonte HUD).
6. Integrar a barra de progresso (geral + sub-progresso da categoria atual) na `DashboardView`, ligada ao `SystemScanTask` existente.
7. Adaptar `SystemAuditEngine` (Fase 9) da mesma forma e integrar a barra na `AuditView`.
8. Adicionar um `ProgressIndicator` indeterminado na `HardwareUpdateView` (Fase 11) durante a checagem online de Nível 2, e outro breve no botão de limpeza de RAM (Fase 10).
9. Adicionar barra de progresso proporcional na ação em lote do Diagnóstico do Sistema (Fase 9), incrementando a cada item aplicado.
10. Testar visualmente rodando um escaneamento completo, confirmando que a barra se move de forma suave e proporcional, não pulando de 0% para 100% de repente.
11. Rodar a suíte de testes JUnit da Parte 2 novamente e confirmar que nada quebrou com essas mudanças.
12. Atualizar `PROGRESS.md` com o resumo final da Fase 12 (as 3 partes).

## Regras que continuam valendo (reforço)

- A desinstalação completa do OneDrive é a ação mais impactante já implementada no projeto — trate com um nível extra de cuidado na confirmação da UI, mesmo seguindo o fluxo padrão de backup por trás.
- Não marque nenhum item do checklist manual (Parte 2) como concluído sem realmente testar na prática.
- Não remova nem altere a assinatura do método `scan()` original de nenhum scanner na Parte 3 — só adicione a versão com listener, como sobrecarga.
- Se encontrar qualquer regressão em qualquer uma das 3 partes (algo que funcionava antes e parou), registre em `BLOCKERS.md` com prioridade alta e corrija antes de considerar a fase concluída.

Comece pela Parte 1 e me avise ao final de cada parte antes de seguir para a próxima.
