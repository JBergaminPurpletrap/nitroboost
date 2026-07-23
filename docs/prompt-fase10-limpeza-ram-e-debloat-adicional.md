# 🧠 Prompt — Fase 10: Limpeza de RAM + Debloat Adicional

Copie e cole no chat do Claude Code, dentro da pasta do projeto (depois de confirmar que a Fase 9 já foi concluída e validada):

---

Antes de escrever qualquer código, leia por completo o arquivo `NITRO-BOOST-fase10-limpeza-ram-e-debloat-adicional.md`, na raiz do projeto.

## Parte 1 — Limpeza de RAM (estilo RAMMap)

1. Criar `MemoryCleaner.java` (pacote `core/`), mapeando via JNA a função `NtSetSystemInformation` da `ntdll.dll` com a classe `SystemMemoryListInformation`, expondo os 4 comandos da tabela da seção 1.1 do documento. Antes de qualquer chamada, habilitar os privilégios `SeProfileSingleProcessPrivilege` e `SeIncreaseQuotaPrivilege` via `AdjustTokenPrivileges` (JNA).
2. Cada método deve nunca lançar exceção para fora (mesma regra de sempre) e registrar a ação em `actions_history` com tipo `memory_cleanup` — **deixe claro no código e no Javadoc que esta é uma exceção deliberada à regra de "backup antes de ação destrutiva": não existe estado para reverter aqui, é uma ação pontual, não uma configuração persistente.**
3. Testar isoladamente via console antes de conectar à UI: ler RAM livre via OSHI, rodar a limpeza (priorize `purgeStandbyList`, a mais parecida com o "Empty List" do RAMMap), ler RAM livre de novo, e imprimir a diferença.
4. Criar a UI: um botão destacado "🧹 Limpar Cache de RAM Agora" (pode ser uma seção nova dentro do `DashboardView` existente, reaproveitando o tema visual já implementado), com o aviso exato descrito na seção 1.3 do documento antes de executar, e exibição do antes/depois da RAM livre.
5. **Importante:** este recurso não deve aparecer como sugestão automática dentro da tela de Diagnóstico do Sistema (Fase 9) — é uma ferramenta manual separada, não uma "configuração a corrigir".

## Parte 2 — Debloat Adicional (fechamento de lacunas)

Só comece depois da Parte 1 validada.

1. Adicionar os 10 serviços da seção 2.1 do documento à base de conhecimento (`knowledge-base.json`) — a maioria já é lida pelo `ServiceScanner` existente, então é só classificação e descrição novas, sem código novo de scanner para eles.
2. Criar (ou expandir, se fizer mais sentido dentro do `PerformanceScanner` da Fase 9) a leitura/alteração do estado de Armazenamento Reservado (`Set-WindowsReservedStorageState`), conforme seção 2.2.
3. Adicionar os 2 itens de privacidade da seção 2.3 (Histórico de Atividades, Localização) ao `TelemetryScanner` já existente, seguindo o mesmo padrão de leitura defensiva.
4. Adicionar o item de interface da seção 2.4 (seção "Recomendado" do Menu Iniciar) ao `ConsumerFeatureScanner` da Fase 8.
5. Expandir `ActionExecutor` com os métodos correspondentes, seguindo o contrato de sempre (lock → backup → ação → histórico) — **essa parte da fase usa o fluxo normal com backup/reversão, diferente da Parte 1 (limpeza de RAM), que é a única exceção do projeto.**
6. Atualizar `PROGRESS.md` com o resumo completo da Fase 10 e marcar os itens no checklist principal.

## Regras que continuam valendo (reforço)

- Nenhum item desta fase deve envolver desativar Windows Defender, Firewall do Windows ou UAC.
- A limpeza de RAM é a única ação do projeto inteiro sem backup/reversão — isso deve ficar bem documentado no código (Javadoc) e no `PROGRESS.md`, para não virar um precedente confuso para futuras fases.
- Qualquer chave/serviço que não existir na sua máquina de teste deve ser tratado como "não aplicável", nunca como erro.

Comece pela Parte 1 e me avise quando estiver pronta para eu validar antes de seguir para a Parte 2.
