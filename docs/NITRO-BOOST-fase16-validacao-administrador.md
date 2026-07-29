# 🔑 NITRO BOOST — Fase 16: Validação Real com Administrador e Interface Gráfica

> Esta fase não adiciona nenhuma funcionalidade nova. É uma **segunda rodada de testes**, complementar à `TESTING.md` existente, cobrindo exatamente os itens que a rodada anterior não pôde validar por falta de dois pré-requisitos: **privilégio de Administrador** e **sessão gráfica interativa** (para abrir a UI JavaFX). Deve ser executada na sua máquina gamer real (com GPU dedicada), não em outra máquina de teste.

> **Nota (2026-07-29):** foi criada uma **ferramenta de automação** dentro do próprio app para agilizar
> esta rodada manual — botão "🧪 Autoteste (Fase 16)" na tela "Reparo do Sistema"
> (`validation/Fase16ValidationRunner.java` + `ui/SystemRepairView.java`). Ela roda automaticamente o
> máximo possível do roteiro abaixo (round-trips reais de aplicar/confirmar/reverter, leitura de
> RAM/taxa de tela via OSHI, etc.) e gera um relatório em Markdown. **Isso NÃO substitui a execução
> desta rodada manual** — os itens puramente visuais (seção 1, ícone do Copilot na seção 4, comparação
> final da seção 9) e o empacotamento (seção 10) continuam exigindo um humano de verdade, e o autoteste
> em si ainda **não rodou nenhuma vez** numa máquina elevada/com GPU real (ver `docs/PROGRESS.md`,
> entrada "Fase 16: ferramenta de Autoteste implementada"). Os checkboxes abaixo continuam refletindo o
> estado real da validação manual (não marcados), não o que a ferramenta é capaz de automatizar.

---

## 0. Pré-requisitos antes de começar

- [ ] Abrir o terminal (PowerShell ou `cmd`) **como Administrador** (clique direito → "Executar como administrador")
- [ ] Confirmar elevação: rodar `whoami /groups | findstr /i "S-1-5-32-544"` — deve aparecer `BUILTIN\Administrators` com atributo `Enabled`
- [ ] Rodar `.\mvnw.cmd clean javafx:run` a partir da raiz do projeto (ou usar o `.exe` empacotado + `run-as-admin.bat`, se já tiver sido gerado)

---

## 1. Checklist Visual (UI nunca aberta na rodada anterior)

- [ ] A janela abre corretamente com o tema Carbono & Verde Turbo (fundo preto/textura carbono, acentos verde neon, fonte HUD)
- [ ] O velocímetro e o gráfico de linha de CPU/RAM no `DashboardView` atualizam a cada ~2s sem travar a interface
- [ ] Clicar em "ESCANEAR SISTEMA" mostra a barra de progresso (`NitroProgressBar`) se movendo de forma suave e proporcional — não pulando de 0% para 100% de repente
- [ ] Todas as telas da barra lateral abrem sem erro: Painel de Controle, Resultados da Varredura, Diagnóstico do Sistema, Reparo do Sistema, BIOS/Drivers, Histórico, Tutoriais

---

## 2. Ações de Escrita — Caminho de Sucesso (só o caminho de falha foi testado até agora)

Escolher **um item seguro e reversível por categoria**, aplicar a ação, confirmar a mudança real via comando nativo do Windows, reverter pelo app, confirmar que voltou ao estado original:

| Categoria | Item sugerido para o teste | Como confirmar a mudança real |
|---|---|---|
| Serviços | `MapsBroker` (mesmo item já usado no teste sem admin) | `Get-Service MapsBroker` antes/depois |
| Registro (Telemetria/Consumidor) | Um item de anúncio/sugestão do Menu Iniciar | `reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager"` antes/depois |
| Startup | Um item não-crítico da sua lista real | `Get-CimInstance Win32_StartupCommand` antes/depois |
| Bloqueio (Lock) | Bloquear um item, confirmar que a ação é recusada mesmo com admin | Deve continuar recusando, independente de privilégio |

- [ ] Para cada linha acima: aplicar → confirmar mudança real → reverter pelo app → confirmar volta ao estado original
- [ ] Confirmar no Histórico que todas as 8 ações (4 aplicar + 4 reverter) aparecem corretamente

---

## 3. Limpeza de RAM (bloqueada na rodada anterior)

- [ ] Anotar RAM livre exibida pelo Gerenciador de Tarefas antes de clicar
- [ ] Clicar em "Limpar Cache de RAM Agora", confirmar que não trava a UI (indicador indeterminado aparece e desaparece)
- [ ] Comparar RAM livre antes/depois, tanto no app quanto no Gerenciador de Tarefas
- [ ] Confirmar registro no histórico (tipo `memory_cleanup`, sem opção de reverter — comportamento esperado)

---

## 4. IA — Copilot (bloqueada na rodada anterior)

- [ ] Confirmar se esta máquina tem o pacote Appx do Copilot separado (`Get-AppxPackage *Copilot*`) — se sim, testar tanto "Desativar" quanto "Desinstalar"; se não, testar só "Desativar" via política
- [ ] Depois de desativar, confirmar visualmente que o botão/ícone do Copilot some da barra de tarefas
- [ ] Reverter e confirmar que volta a aparecer (se a ação foi só desativar, não desinstalar)

---

## 5. Telemetria de GPU (não aplicável na máquina de teste anterior — sem GPU dedicada)

- [ ] Rodar a varredura completa e confirmar que os serviços de GPU aparecem corretamente conforme o fabricante da sua placa de vídeo (NVIDIA: `NvTelemetryContainer`, `NVDisplay.ContainerLocalSystem`; AMD: os equivalentes)
- [ ] **Não desativar o `NVDisplay.ContainerLocalSystem` neste teste** (ou qualquer equivalente AMD) sem antes ler a descrição com atenção — esse item específico tem impacto potencial em overlay/G-SYNC, conforme documentado na Fase 12
- [ ] Testar desativar/reverter o item de telemetria "seguro" (`NvTelemetryContainer` ou equivalente AMD), confirmando que o painel de controle da GPU continua funcionando normalmente depois

---

## 6. Reparo do Sistema — SFC/DISM (nunca executado de verdade, só testes de unidade)

⚠️ Este teste demora — o DISM sozinho pode levar vários minutos, e o SFC mais 10-20 minutos. Reserve um tempo adequado.

- [ ] Clicar em "Verificar e Reparar Sistema", confirmar que o painel de log em tempo real mostra a saída do DISM primeiro, depois do SFC
- [ ] Confirmar que a barra de progresso reflete o percentual real extraído da saída (não fica parada em 0% o tempo todo)
- [ ] Confirmar que outros botões de ação/scan ficam desabilitados durante a execução
- [ ] Ao final, confirmar qual dos 3 estados foi detectado (nenhum problema / corrigido / não foi possível corrigir tudo) e comparar com o log bruto exibido, pra ver se a detecção bateu com o que o comando realmente disse
- [ ] Registrar o resultado em `TESTING.md`

---

## 7. Reparo de Rede (nunca executado de verdade)

- [ ] Testar "Limpar Cache de DNS" — ação rápida, sem reinício, confirmar que roda e volta sem travar
- [ ] Testar "Renovar IP" — confirmar aviso de queda momentânea de conexão antes de confirmar
- [ ] **Não testar Winsock Reset / TCP-IP Reset nesta rodada**, a menos que você esteja disposto a reiniciar o PC logo em seguida — documentar como "não testado nesta rodada" se pular, não como falha

---

## 8. Modo de Jogo — Teste da Lógica Invertida (ponto de atenção específico da Fase 14)

Este é o único item de todo o projeto onde a sugestão de melhoria é **ativar**, não desativar — vale um teste dedicado para garantir que não foi implementado ao contrário por engano.

- [ ] Verificar o estado atual do Modo de Jogo no Windows (Configurações → Jogos → Modo de Jogo)
- [ ] Se estiver **desativado**: confirmar que o Diagnóstico do Sistema mostra esse item como 🟡 sugestão, com o botão de ação dizendo **"Ativar Modo de Jogo"** (não um texto genérico de desativar)
- [ ] Aplicar a ação, confirmar no Windows que o Modo de Jogo realmente ficou **ativado** (não desativado por engano)
- [ ] Reverter e confirmar que volta ao estado original

---

## 9. Taxa de Atualização da Tela

- [ ] Confirmar que a taxa atual detectada pelo app bate com o que aparece em Configurações → Sistema → Tela → Exibição avançada
- [ ] Confirmar que a taxa máxima detectada bate com a maior opção disponível nessa mesma tela
- [ ] Clicar em "Abrir Configurações de Tela" e confirmar que abre a tela certa do Windows (`ms-settings:display`)

---

## 10. Empacotamento (.exe)

- [ ] Rodar `scripts\jpackage-build.bat` e confirmar que gera `target\dist\NitroBoost\NitroBoost.exe` sem erro
- [ ] Rodar `run-as-admin.bat` e confirmar que pede elevação via UAC corretamente e abre o app já elevado
- [ ] Repetir pelo menos os testes das seções 1 e 2 usando o `.exe` empacotado (não só via `javafx:run`), para garantir que o empacotamento não quebrou nada

---

## Onde Documentar

- [ ] Criar uma nova seção em `TESTING.md` (ex: "Rodada 2 — Validação com Administrador", com data) cobrindo os itens desta fase, no mesmo formato de tabela já usado
- [ ] Qualquer item que falhar de verdade (não só "bloqueado por falta de admin", que já não se aplica mais nesta rodada) deve virar uma entrada nova em `BLOCKERS.md`
- [ ] Atualizar `PROGRESS.md` com o resumo desta rodada

**Critério de conclusão:** todos os itens que estavam marcados como "Bloqueado (ambiente)" na rodada anterior agora têm um resultado real (sucesso ou falha genuína, não mais "não testável"), e a interface gráfica foi confirmada funcionando visualmente de ponta a ponta pelo menos uma vez.
