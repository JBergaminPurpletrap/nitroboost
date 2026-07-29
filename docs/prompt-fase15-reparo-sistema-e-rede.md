# 🛠️ Prompt — Fase 15: Reparo do Sistema e Limpeza de Rede

Copie e cole no chat do Claude Code, dentro da pasta do projeto (recomendado depois que a Fase 12 - Barras de Progresso - já estiver pronta, já que este recurso reaproveita a `NitroProgressBar`):

---

Antes de escrever qualquer código, leia por completo o arquivo `NITRO-BOOST-fase15-reparo-sistema-e-rede.md`, na raiz do projeto.

## Parte 1 — Estrutura e Navegação

1. Adicionar "Reparo do Sistema" como novo item na barra lateral esquerda (`Main.java`).
2. Criar `ui/SystemRepairView.java` com duas seções: reparo de arquivos do sistema (Parte 2 abaixo) e diagnóstico/limpeza de rede (Parte 3 abaixo).

## Parte 2 — Reparo de Arquivos do Sistema (SFC + DISM)

1. Criar `SystemFileRepairTool.java` (novo pacote `repair/`), executando via `ProcessBuilder`, **nesta ordem**: `DISM /Online /Cleanup-Image /RestoreHealth` e depois `sfc /scannow`. Leia a saída de cada comando em tempo real, linha a linha, em uma thread separada (não bloquear a UI).
2. Implemente extração de percentual de progresso via expressão regular na saída de ambos os comandos (o SFC imprime "X% concluída", o DISM imprime uma barra de texto com percentual) e alimente o componente `NitroProgressBar` (já existente desde a Fase 12) em tempo real.
3. Implemente um painel de log em tempo real na `SystemRepairView`, estilo terminal — fundo preto, texto verde, fonte monoespaçada — mostrando a saída bruta dos comandos conforme rodam.
4. Implemente a detecção melhor-esforço do resultado final do SFC, classificando em 3 estados (nenhum problema / corrigido com sucesso / não foi possível corrigir tudo), tentando reconhecer palavras-chave em português e inglês (o idioma da saída varia conforme o idioma do Windows). **Sempre exiba o log bruto completo independente da detecção funcionar ou não.**
5. Antes de iniciar a execução, mostre um modal de confirmação com os avisos: processo pode levar vários minutos, não fechar o app enquanto roda, DISM precisa de internet.
6. Desabilite os outros botões de ação/scan do app enquanto o reparo estiver em andamento.
7. Registre a execução no histórico com tipo `system_repair`, **sem backup/reversão associada** — mesma exceção já estabelecida na Fase 10 para a limpeza de RAM (documente isso no Javadoc, como fez naquela fase).
8. Teste isoladamente via console antes de conectar à UI.

## Parte 3 — Limpeza e Reset de Componentes de Rede

1. Criar `NetworkRepairTool.java` (mesmo pacote `repair/`) com os métodos: `flushDns()`, `resetWinsock()`, `resetTcpIp()`, `renewIp()` (release + renew em sequência), `clearArpCache()`.
2. Implemente o botão principal "Limpar Cache de DNS" (o pedido original) com indicador de progresso indeterminado, já que é uma ação rápida.
3. Implemente a seção "Diagnóstico de Rede Avançado" com os demais métodos, **avisando claramente na UI quais exigem reinício do computador** antes de executar (Winsock e TCP/IP reset exigem; os outros não).
4. Registre cada execução no histórico com tipo `network_repair`, mesma lógica sem reversão.

## Regras que continuam valendo (reforço)

- Esta fase reaproveita o `NitroProgressBar` criado na Fase 12 — não crie um componente de progresso novo, reutilize o existente.
- Nenhuma dessas ações precisa de backup/lock — são diagnósticos/reparos pontuais, não configurações permanentes, seguindo a mesma exceção já documentada na Fase 10.
- O painel de log deve sempre mostrar a saída bruta completa dos comandos, mesmo quando a detecção de resultado (sucesso/falha) não conseguir interpretar automaticamente — isso garante que a informação real nunca fica escondida atrás de uma interpretação que pode falhar.

Comece pela Parte 1, depois a Parte 2, e me avise antes de seguir para a Parte 3.
