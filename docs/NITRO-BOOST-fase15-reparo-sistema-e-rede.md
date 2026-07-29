# 🛠️ NITRO BOOST — Fase 15: Reparo do Sistema e Limpeza de Rede

> Esta fase adiciona uma nova seção na barra lateral esquerda: **"Reparo do Sistema"** — com duas frentes: (A) verificação/reparo de arquivos corrompidos do Windows (SFC + DISM) e (B) limpeza/reset de componentes de rede (DNS e outros itens relacionados que fazem sentido no mesmo contexto).

⚠️ **Natureza desta fase, importante para a implementação:** assim como a Limpeza de RAM (Fase 10), estas são **ações de diagnóstico/reparo pontuais**, não configurações permanentes. Não existe "estado anterior" significativo para reverter (não faz sentido reverter uma correção de arquivo corrompido). Elas seguem o padrão já estabelecido na Fase 10: registradas no histórico como informação, mas sem backup/reversão associada.

---

## A. Verificação e Reparo de Arquivos do Sistema (SFC + DISM)

### A.1 Comandos e ordem correta de execução

A ordem importa: o `DISM` corrige o "armazenamento de componentes" que o `SFC` usa como referência para reparar arquivos — rodar o DISM primeiro aumenta a chance do SFC funcionar corretamente depois.

| Ordem | Comando | O que faz |
|---|---|---|
| 1º | `DISM /Online /Cleanup-Image /RestoreHealth` | Verifica e repara o armazenamento de componentes do Windows (a "fonte" que o SFC usa). Requer conexão com a internet (usa o Windows Update como fonte, por padrão) |
| 2º | `sfc /scannow` | Verifica a integridade de todos os arquivos protegidos do sistema e repara os que estiverem corrompidos, usando o armazenamento de componentes (já corrigido pelo passo 1) como referência |

### A.2 Interface — fluxo sugerido

- Botão principal: **"Verificar e Reparar Sistema"** — roda os dois comandos em sequência automaticamente (DISM → SFC), sem exigir que o usuário rode um de cada vez.
- Botões secundários (avançado/individual): "Rodar só DISM" e "Rodar só SFC", para quem já sabe o que está fazendo e quer rodar separadamente (ex: depurar um problema específico).
- **Painel de log em tempo real**, estilo terminal (fundo preto, texto verde monoespaçado — combina perfeitamente com o tema Carbono & Verde Turbo já estabelecido), mostrando a saída bruta do comando conforme ele roda.
- **Barra de progresso reaproveitada da Fase 12** (`NitroProgressBar`): tanto o `sfc /scannow` quanto o `DISM /RestoreHealth` imprimem percentual de conclusão na própria saída (ex: `"Verificação X% concluída"` no SFC, e uma barra em texto tipo `[====60.0%====]` no DISM) — extrair esse percentual via expressão regular e alimentar a barra de progresso em tempo real, em vez de deixar indeterminada.

### A.3 Interpretação do resultado final

- Ao final do `sfc /scannow`, capturar a mensagem final da saída (varia conforme o idioma do Windows) e classificar em 3 estados possíveis:
  - ✅ **Nenhum problema encontrado** (ex: contém "did not find any integrity violations" / "não encontrou nenhuma violação de integridade")
  - 🟢 **Problemas encontrados e corrigidos** (ex: "successfully repaired" / "reparou com êxito")
  - 🔴 **Problemas encontrados, mas não foi possível corrigir tudo** (ex: "was unable to fix" / "não foi possível corrigir") — neste caso, exibir uma orientação clara: "Alguns arquivos não puderam ser corrigidos automaticamente. Consulte o arquivo de log em `%windir%\Logs\CBS\CBS.log` para detalhes, ou considere rodar o comando novamente após reiniciar o computador."
- Como a mensagem exata varia por idioma do Windows, a detecção deve ser **melhor esforço** (tentar reconhecer palavras-chave em português e inglês) — e sempre exibir o log bruto completo de qualquer forma, para o usuário (ou você) poder ler na íntegra independente da detecção funcionar.

### A.4 Avisos importantes na UI antes de rodar

- "Esse processo pode levar vários minutos (o SFC sozinho pode levar 10-20 minutos). Não feche o aplicativo enquanto estiver rodando."
- "O DISM precisa de conexão com a internet para funcionar corretamente (usa os servidores da Microsoft como fonte de arquivos)."
- Desabilitar outros botões de ação/scan enquanto o reparo estiver rodando, para evitar conflito de múltiplos processos administrativos simultâneos.

---

## B. Limpeza e Reset de Componentes de Rede

Você pediu especificamente o `ipconfig /flushdns`. Aproveitando o mesmo contexto (diagnóstico de rede), seguem sugestões relacionadas que costumam resolver os mesmos tipos de problema (lentidão, sites não abrindo, DNS "grudado" em cache antigo):

| Item | Comando | O que faz | Requer reinício? |
|---|---|---|---|
| **Limpar cache de DNS** (pedido original) | `ipconfig /flushdns` | Limpa o cache local de resolução de nomes de domínio — resolve sites "não abrindo" por DNS desatualizado em cache | Não |
| Reset do Winsock | `netsh winsock reset` | Reseta o catálogo do Winsock (componente de baixo nível de rede do Windows) — resolve problemas de conexão mais teimosos, tipo "sem internet" mesmo com cabo conectado | **Sim** |
| Reset da pilha TCP/IP | `netsh int ip reset` | Restaura as configurações do protocolo TCP/IP para o padrão de fábrica | **Sim** |
| Renovar IP | `ipconfig /release` seguido de `ipconfig /renew` | Libera e solicita um novo endereço IP do roteador/DHCP — útil quando a conexão está com IP conflitante ou expirado | Não (mas derruba a conexão momentaneamente) |
| Limpar cache ARP | `arp -d *` | Limpa a tabela de endereços físicos (MAC) em cache — resolve conflitos raros de identificação de dispositivos na rede local | Não |

### B.1 Interface

- Botão principal (o que você pediu): **"Limpar Cache de DNS"** — ação rápida, sem necessidade de reinício, resultado quase instantâneo.
- Seção "Diagnóstico de Rede Avançado" (os demais itens da tabela), cada um com botão próprio e um aviso claro de **quais precisam de reinício** antes de executar.
- Assim como a Limpeza de RAM (Fase 10), usar indicador de progresso **indeterminado** (essas ações são rápidas, sem percentual disponível de forma confiável).

---

## Checklist de Implementação (Fase 15)

### Estrutura e Navegação
- [x] Adicionar "Reparo do Sistema" como novo item na barra lateral esquerda (`Main.java`)
- [x] Criar `ui/SystemRepairView.java` com as duas seções (A e B) descritas acima

### Parte A — SFC/DISM
- [x] Criar `SystemFileRepairTool.java` (`core/` ou novo pacote `repair/`), executando `DISM /Online /Cleanup-Image /RestoreHealth` seguido de `sfc /scannow` via `ProcessBuilder`, com leitura de saída em tempo real (linha a linha, em thread separada)
- [x] Implementar extração de percentual via regex na saída de ambos os comandos, alimentando o `NitroProgressBar` (reaproveitado da Fase 12) em tempo real
- [x] Implementar o painel de log em tempo real na UI (estilo terminal, fundo preto, texto verde monoespaçado)
- [x] Implementar a detecção melhor-esforço do resultado final do SFC (3 estados da seção A.3), com fallback para exibir o log bruto sempre
- [x] Adicionar os avisos da seção A.4 antes de iniciar a execução (modal de confirmação)
- [x] Desabilitar outras ações/scans do app enquanto o reparo estiver em andamento
- [x] Registrar a execução no histórico (tipo `system_repair`), sem backup/reversão associada (mesma exceção já estabelecida na Fase 10 para a limpeza de RAM)
- [x] Testar isoladamente via console antes de conectar à UI (testado via JUnit com strings fixas + mecanismo de streaming validado com comando rápido simulado - SFC/DISM reais NÃO foram executados, ver `docs/PROGRESS.md`)

### Parte B — Rede
- [x] Criar `NetworkRepairTool.java` (mesmo pacote `repair/`) com os 5 comandos da tabela B, cada um como método próprio
- [x] Implementar o botão principal "Limpar Cache de DNS" com indicador indeterminado
- [x] Implementar a seção "Diagnóstico de Rede Avançado" com os demais itens, avisando claramente quais exigem reinício antes de executar
- [x] Registrar cada execução no histórico (tipo `network_repair`), mesma lógica sem reversão

### Fechamento
- [x] Atualizar `PROGRESS.md` com o resumo da Fase 15

**Critério de conclusão:** a nova seção "Reparo do Sistema" está acessível pela barra lateral, executa DISM+SFC em sequência com log em tempo real e barra de progresso funcional, interpreta o resultado final de forma clara, e oferece as ações de rede (DNS + avançadas) com os avisos corretos sobre necessidade de reinício.

---

## Observação sobre Escopo Responsável (reforço)

Assim como nas fases anteriores, este recurso **não substitui o julgamento do usuário**: o app executa comandos nativos e documentados do próprio Windows (nenhum deles é obscuro ou não-oficial), sempre com aviso claro antes de rodar algo que precise de reinício ou que demore muito tempo.

---

*Esta fase reaproveita a `NitroProgressBar` criada na Fase 12 (Parte C) e segue o mesmo padrão de "ação pontual sem reversão" estabelecido na Fase 10 (Limpeza de RAM).*
