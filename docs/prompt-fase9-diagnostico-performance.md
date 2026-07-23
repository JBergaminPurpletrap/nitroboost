# 🩺 Prompt — Fase 9: Debloat/Performance Completo + Diagnóstico do Sistema

Copie e cole no chat do Claude Code, dentro da pasta do projeto (depois de confirmar que a Fase 8 já foi concluída e validada):

---

Antes de escrever qualquer código, leia por completo o arquivo `NITRO-BOOST-fase9-diagnostico-e-performance.md`, na raiz do projeto.

## Ordem de execução

### Parte 1 — Novos scanners de Performance e Jogos

1. Criar `PerformanceScanner.java` e `GamingScanner.java` (pacote `core/`), cobrindo exatamente os itens das seções 1 e 2 do documento — mesmo padrão defensivo já usado nos scanners existentes (nunca lançar exceção, chave ausente = "não definido").
2. Adicionar `SysMain`, `WSearch`, `Spooler` e `bthserv` à base de conhecimento (`knowledge-base.json`) com tipo `service` — esses serviços já são lidos pelo `ServiceScanner` existente, só precisam de classificação e descrição.
3. Expandir `ActionExecutor` com os métodos de ação correspondentes aos novos itens, seguindo o mesmo contrato de sempre (checar bloqueio → backup → ação → histórico).
4. Adicionar as novas categorias (Performance, Jogos) ao `SystemScanTask` e ao filtro da `ScanResultsView`.
5. Testar cada scanner isoladamente via console antes de conectar à UI.
6. Atualizar `PROGRESS.md` e marcar os itens correspondentes da checklist.

### Parte 2 — Módulo de Diagnóstico do Sistema

Só começe esta parte depois que a Parte 1 estiver validada.

1. Adicionar o campo `valor_recomendado` na base de conhecimento para todos os itens que têm uma verificação objetiva possível (ver seção 3.2 do documento).
2. Criar o pacote `audit/` com `SystemAuditEngine.java`, reaproveitando todos os scanners já existentes (não duplicar lógica de leitura).
3. Criar `AuditReport.java` (record) representando o relatório: lista de `AuditFinding` com item, categoria, status (✅ já otimizado / 🟡 sugestão / ⚪ não aplicável), valor atual e valor recomendado.
4. Criar `ui/AuditView.java`: tela nova acessível pelo menu lateral, mostrando:
   - Um placar geral (ex: "42 de 58 itens já otimizados")
   - Lista agrupada por categoria (Performance, Privacidade/IA, Limpeza/Anúncios, Jogos)
   - Botão de ação individual por item
   - Botão de "aplicar todas as sugestões seguras desta categoria", que abre um modal de confirmação listando cada item que será alterado antes de aplicar
5. Adicionar `AuditView` à navegação principal em `Main.java`.
6. Testar `SystemAuditEngine` isoladamente via console antes de conectar à UI.
7. Atualizar `PROGRESS.md` com o resumo completo da Fase 9 e marcar todos os itens da checklist.

## Regras que continuam valendo (reforço obrigatório)

- **Nunca** implemente qualquer sugestão de desativar Windows Defender, Firewall do Windows ou UAC — isso está explicitamente fora do escopo do projeto (ver seção 5 do documento). Se em algum momento a IA identificar uma configuração desse tipo, ela deve ser ignorada/pulada, e o motivo documentado em `BLOCKERS.md` para eu saber que foi uma decisão consciente, não um esquecimento.
- Ação em lote nunca pode aplicar mudanças sem o usuário ver a lista completa e confirmar antes.
- Cada item do lote continua tendo seu próprio backup individual (nunca um backup único para várias mudanças ao mesmo tempo) — isso é importante para eu poder reverter só um item específico depois, sem precisar desfazer tudo.
- Toda chave de registro que não existir na minha máquina (por causa de versão/edição do Windows) deve ser tratada como "não definido"/"não aplicável", nunca como erro que trava o app.

Comece pela Parte 1 e me avise quando estiver pronta para eu validar antes de seguir para a Parte 2 (Diagnóstico do Sistema).
