# 🎮 Prompt — Fase 14: Melhorias Específicas do Diagnóstico

Copie e cole no chat do Claude Code, dentro da pasta do projeto (recomendado depois que as Fases 8 e 9 já estiverem prontas):

---

Antes de escrever qualquer código, leia por completo o arquivo `NITRO-BOOST-fase14-melhorias-diagnostico.md`, na raiz do projeto.

## Parte 1 — Ajustes em itens já existentes (Fases 8/9)

1. No `knowledge-base.json`, confirme/ajuste o `valor_recomendado` do item de Game DVR (Fase 9, `GamingScanner`) para `0`, e adicione a verificação complementar da política `AllowGameDVR` (`HKLM\SOFTWARE\Policies\Microsoft\Windows\GameDVR`) no `GamingScanner`.
2. **Atenção especial:** no item de Modo de Jogo, **inverta** o `valor_recomendado` para `1` (ativado) — este é o único item do projeto onde a sugestão de melhoria é "ativar", não "desativar". Ajuste também o texto do botão de ação na UI para esse item específico, para dizer "Ativar Modo de Jogo" em vez do texto genérico usado nos itens que desativam.
3. Confirme/ajuste o `valor_recomendado` do item de Delivery Optimization (`DODownloadMode`, `PerformanceScanner` da Fase 9) para `0`.
4. Confirme que o item "Chat/Continuar" (`TaskbarMn`, já existente desde a Fase 8) está com a classificação e valor recomendado corretos.

## Parte 2 — Novo recurso: Taxa de Atualização da Tela

1. Criar `DisplayScanner.java` (`core/`) com os métodos `getCurrentRefreshRate()` e `getAvailableRefreshRates()`, usando JNA para chamar `EnumDisplaySettingsEx` da `user32.dll`, seguindo o mesmo padrão defensivo dos outros scanners (nunca lançar exceção, retornar valor desconhecido/lista vazia se a leitura falhar).
2. Testar isoladamente via console antes de conectar à UI, comparando o resultado com o que aparece em Configurações → Sistema → Tela → Exibição avançada na minha máquina real.
3. Adicionar a entrada correspondente na base de conhecimento, com lógica de comparação entre taxa atual e taxa máxima disponível (não um valor fixo).
4. Implementar o botão "Abrir Configurações de Tela", usando `ms-settings:display` (via `ProcessBuilder("cmd", "/c", "start", "ms-settings:display")` ou equivalente).
5. **Não implemente troca automática de taxa de atualização nesta fase** — o risco de tela preta em caso de taxa não suportada exige uma lógica de confirmação com timeout que fica fora do escopo desta fase. Documente isso como melhoria futura opcional em `PROGRESS.md`.

## Parte 3 — Novo recurso: Limpeza de Ícones da Barra de Tarefas

1. Adicionar ao `ConsumerFeatureScanner` (Fase 8) a leitura das chaves `SearchboxTaskbarMode`, `ShowTaskViewButton` e `TaskbarDa`, com os valores recomendados descritos na seção E do documento.
2. Expandir `ActionExecutor` com os métodos de ação correspondentes, seguindo o contrato de sempre (lock → backup → ação → histórico).
3. Confirme na `AuditView` (Fase 9) que esses 4 itens (incluindo o já existente `TaskbarMn`) aparecem agrupados como "Limpeza da Barra de Tarefas", com opção de ação em lote.

## Regras que continuam valendo (reforço)

- O item de Modo de Jogo é uma exceção à regra geral do projeto — a sugestão de melhoria é ativar, não desativar. Teste esse caso com atenção especial para não inverter a lógica sem querer.
- Nenhuma troca automática de taxa de atualização de tela deve ser implementada nesta fase — só detecção e link para a tela nativa de configurações do Windows.
- Todos os itens continuam passando pelo fluxo padrão de segurança (lock → backup → ação → histórico), exceto a abertura de configurações do Windows (que não é uma "ação" no sentido de modificar algo, só abre uma tela).

Comece pela Parte 1 e me avise ao final de cada parte antes de seguir para a próxima.
