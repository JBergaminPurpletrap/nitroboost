# 🔑 Prompt — Fase 16: Validação Real com Administrador e Interface Gráfica

Copie e cole no chat do Claude Code, dentro da pasta do projeto, **com o terminal já aberto como Administrador** e numa sessão com interface gráfica disponível (não numa sessão remota/headless):

---

Antes de começar, leia por completo o arquivo `NITRO-BOOST-fase16-validacao-administrador.md`, na raiz do projeto (junto com `docs/`). Esta fase não adiciona código novo — é uma rodada de validação real, complementar à `TESTING.md` já existente, cobrindo os itens que a rodada anterior não pôde testar por falta de Administrador e de sessão gráfica.

## Instruções

1. Confirme que está rodando como Administrador (`whoami /groups | findstr /i "S-1-5-32-544"` deve mostrar `Enabled`).
2. Rode `.\mvnw.cmd clean javafx:run` e execute o checklist visual da seção 1 do documento — a UI deve abrir de verdade desta vez.
3. Execute o checklist da seção 2 (ações de escrita, caminho de sucesso) — desta vez as ações devem funcionar de verdade, não só cair no caminho de "acesso negado" como na rodada anterior. Para cada item: aplicar, confirmar a mudança real via comando nativo do Windows, reverter, confirmar volta ao estado original.
4. Execute a seção 3 (Limpeza de RAM) com números reais de antes/depois.
5. Execute a seção 4 (Copilot) — confirme visualmente na barra de tarefas do Windows se o botão some/reaparece corretamente.
6. Execute a seção 5 (Telemetria de GPU) — **esta máquina tem GPU dedicada de verdade**, então os itens de NVIDIA/AMD devem aparecer na varredura (diferente da rodada anterior, onde não se aplicava). Preste atenção especial ao aviso sobre o `NVDisplay.ContainerLocalSystem` (ou equivalente AMD) antes de desativar.
7. Execute a seção 6 (SFC/DISM) — **isso demora, avise antes de começar** que pode levar bastante tempo, e não interrompa o processo no meio.
8. Execute a seção 7 (Reparo de Rede) — pule Winsock/TCP-IP reset a menos que esteja disposto a reiniciar o PC logo depois; documente como "não testado nesta rodada" se pular.
9. Execute a seção 8 com atenção redobrada — é o teste da lógica invertida do Modo de Jogo (o único item do projeto onde a sugestão correta é ATIVAR). Confirme que o app não inverteu isso por engano.
10. Execute a seção 9 (Taxa de Atualização da Tela), comparando com a tela nativa do Windows.
11. Execute a seção 10 (empacotamento `.exe` + `run-as-admin.bat`).
12. Documente tudo em uma nova seção de `TESTING.md` ("Rodada 2 — Validação com Administrador", com a data de hoje), no mesmo formato de tabela já usado na rodada anterior.
13. Qualquer falha real encontrada (não mais "bloqueado por ambiente", já que agora temos admin+GUI) deve virar uma entrada nova em `BLOCKERS.md`, com prioridade de correção.
14. Atualize `PROGRESS.md` com o resumo desta rodada de validação.

## Regras que continuam valendo (reforço)

- Não pule a seção 8 (Modo de Jogo) — é o item com maior risco de estar implementado ao contrário, e a rodada anterior não testou isso especificamente.
- No teste de GPU (seção 5), não desative o item de container/display sem ler a descrição completa primeiro — ele tem impacto real em overlay/G-SYNC.
- No SFC/DISM, não interrompa o processo no meio — deixe rodar até o fim, mesmo que demore.
- Se qualquer coisa que devia funcionar (agora com admin) ainda falhar, isso é uma falha real desta vez — não assuma mais que é "esperado por falta de permissão" como na rodada passada.

Ao final, me dê um resumo consolidado: quantos itens passaram de verdade agora, quantos ainda ficaram pendentes (e por quê), e se algum bug real foi encontrado.
