# 🔧 Prompt — Fase 11: Verificação de BIOS e Drivers da Placa-Mãe

Copie e cole no chat do Claude Code, dentro da pasta do projeto (depois de confirmar que a Fase 10 já foi concluída e validada):

---

Antes de escrever qualquer código, leia por completo o arquivo `NITRO-BOOST-fase11-bios-drivers.md`, na raiz do projeto.

## Parte 1 — Detecção Local + Link Direto (Nível 1, prioridade máxima)

1. Criar `HardwareIdentityScanner.java` (`core/`), lendo via OSHI: fabricante da placa-mãe, modelo exato, versão atual da BIOS e data de release.
2. Adicionar leitura de versões de drivers instalados (chipset, LAN, áudio, GPU) via WMI/PowerShell (`Get-WmiObject Win32_PnPSignedDriver` ou equivalente), seguindo o mesmo padrão defensivo dos scanners existentes.
3. Criar o pacote `updates/` com `VendorLinkStrategy.java` e as implementações `AsusLinkStrategy`, `MsiLinkStrategy`, `GigabyteLinkStrategy`, `AsRockLinkStrategy` e `GenericLinkStrategy` (fallback), seguindo as 3 camadas descritas na seção 1.2 do documento (link profundo → busca interna do fabricante → busca externa garantida).
4. Criar `ui/HardwareUpdateView.java` exibindo os dados detectados (fabricante, modelo, BIOS atual, drivers) e um botão "Abrir página de suporte", usando a estratégia de link correspondente ao fabricante detectado.
5. Testar com a placa-mãe real da minha máquina: confirme que o link gerado realmente chega perto da página certa (mesmo que caia na camada de busca, não precisa ser o link profundo perfeito de primeira).
6. Atualizar `PROGRESS.md`.

## Parte 2 — Verificação Automática Online (Nível 2, melhor esforço)

Só comece depois que a Parte 1 estiver validada.

1. Criar a interface `VendorPageParser.java` (`updates/`) e implemente **primeiro só para o fabricante da minha placa-mãe** (detectado na Parte 1) — não implemente os outros 3 fabricantes ainda, para validar o conceito antes de expandir.
2. Criar `UpdateCheckCache.java` — cache local em SQLite com validade de 24h, para nunca repetir a consulta ao site do fabricante em curto intervalo de tempo.
3. Adicionar o botão "Verificar atualização online" na `HardwareUpdateView`, chamando o parser **só quando clicado** (nunca automaticamente durante um scan geral).
4. Implemente tratamento de falha gracioso: qualquer erro (timeout, HTML mudou, modelo não reconhecido) deve cair de volta para o Nível 1 silenciosamente, mostrando a mensagem "Não foi possível verificar automaticamente agora — use o link abaixo para checar manualmente." — **nunca travar a tela ou lançar exceção visível ao usuário.**
5. Teste o cenário de falha de propósito (aponte para uma URL inválida temporariamente) para confirmar que o fallback funciona antes de seguir.
6. Documente em `PROGRESS.md` exatamente quais fabricantes têm parser funcional no momento — isso é esperado mudar com o tempo, então deixe claro que é o estado atual, não uma garantia permanente.

## Regras que continuam valendo (reforço)

- Este recurso **nunca modifica BIOS ou drivers automaticamente** — só informa e direciona para o site oficial. A atualização em si é sempre manual, feita por mim.
- Respeitar o `robots.txt` do domínio do fabricante antes de fazer qualquer requisição HTTP no Nível 2.
- O Nível 2 é conhecidamente frágil — se algum parser parar de funcionar no futuro por mudança no site do fabricante, isso é esperado, não é motivo para reescrever tudo às pressas; o Nível 1 continua garantindo o valor do recurso.

Comece pela Parte 1 e me avise quando estiver pronta para eu validar antes de seguir para a Parte 2.
