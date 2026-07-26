# 🔧 NITRO BOOST — Fase 11: Verificação de BIOS e Drivers da Placa-Mãe

> Esta fase adiciona detecção da versão atual de BIOS/drivers da placa-mãe e uma verificação (em dois níveis de confiabilidade) se há atualização disponível no site do fabricante.

⚠️ **Natureza desta fase, para gerenciar expectativas:** o **Nível 1** (detecção local + link direto) é 100% confiável e deve ser tratado como o recurso principal. O **Nível 2** (leitura automática da versão mais recente no site do fabricante) é **melhor esforço** — pode parar de funcionar a qualquer momento se o fabricante mudar o site, e isso é esperado, não é bug a "consertar às pressas". O app deve sempre degradar graciosamente para o Nível 1 quando o Nível 2 falhar.

---

## 1. Nível 1 — Detecção Local + Link Direto (sempre funciona)

### 1.1 O que detectar

| Dado | Fonte | Mecanismo |
|---|---|---|
| Fabricante da placa-mãe (ex: ASUSTeK, Micro-Star, Gigabyte) | WMI `Win32_BaseBoard.Manufacturer` | OSHI (`Baseboard.getManufacturer()`) — já suportado, sem dependência nova |
| Modelo exato da placa-mãe (ex: "ROG STRIX B650-A GAMING WIFI") | WMI `Win32_BaseBoard.Product` | OSHI (`Baseboard.getModel()`) |
| Versão atual da BIOS instalada | WMI `Win32_BIOS.SMBIOSBIOSVersion` | OSHI (`Firmware.getVersion()`) |
| Data de release da BIOS atual | WMI `Win32_BIOS.ReleaseDate` | OSHI (`Firmware.getReleaseDate()`) |
| Versões de drivers instalados (chipset, LAN, áudio, GPU) | WMI `Win32_PnPSignedDriver` | Consulta via `wmic` ou PowerShell `Get-WmiObject Win32_PnPSignedDriver` (mesma técnica de `ProcessBuilder` já usada nos outros scanners) |

### 1.2 Geração do link direto de suporte

Para cada fabricante reconhecido, mantar um mapeamento fixo (`VendorLinkStrategy`) com 3 camadas de fallback, da mais específica para a mais garantida:

1. **Link profundo conhecido** (ex: página de suporte por modelo da ASUS/MSI/Gigabyte/ASRock) — mais útil, mas é o que mais quebra com o tempo.
2. **Busca interna do próprio site do fabricante** (ex: `https://www.asus.com/support/search/?keyword=<modelo>`) — mais estável que o link profundo, porque a maioria dos fabricantes mantém uma URL de busca simples por muito tempo.
3. **Busca externa (garantida):** montar uma URL de busca (ex: Google/Bing) com a query `site:<dominio-do-fabricante> <modelo> BIOS driver`, e abrir no navegador padrão. **Esta camada nunca falha tecnicamente** (é só montar uma URL), mesmo que as duas anteriores quebrem.

### 1.3 Fabricantes a cobrir no lançamento

- ASUS / ROG (`asus.com`)
- MSI (`msi.com`)
- Gigabyte / Aorus (`gigabyte.com`)
- ASRock (`asrock.com`)
- (Genérico/desconhecido: cai direto na camada 3 — busca externa)

---

## 2. Nível 2 — Verificação Automática (melhor esforço, pode quebrar)

### 2.1 Como funciona

1. Só é executado **quando o usuário clicar em "Verificar atualização online"** — nunca automaticamente durante um scan normal (evita tráfego desnecessário ao site do fabricante e respeita o fato de que a checagem pode demorar/falhar).
2. Faz uma requisição HTTP (via `HttpClient` nativo do Java, sem dependência nova) à página de suporte do fabricante (camada 1 ou 2 da estratégia de link).
3. Tenta extrair a versão mais recente de BIOS listada na página, usando um parser **específico por fabricante** (`VendorPageParser`, uma implementação por marca).
4. Compara a versão extraída com a versão atual instalada (comparação simples de string/número — **documentar que formatos variam entre fabricantes**, então a comparação deve ser exibida ao usuário para ele mesmo confirmar visualmente, e não tratada como 100% automática/definitiva).
5. Se qualquer etapa falhar (site fora do ar, estrutura mudou, timeout, modelo não reconhecido pelo parser), **capturar o erro silenciosamente para o usuário** (sem travar a tela) e mostrar só o link do Nível 1, com uma mensagem simples: "Não foi possível verificar automaticamente agora — use o link abaixo para checar manualmente."

### 2.2 Cache e respeito ao site do fabricante

- Resultado da verificação online deve ser **armazenado em cache local** (SQLite, com timestamp) por pelo menos 24h — nunca bater no site do fabricante a cada clique repetido em pouco tempo.
- Respeitar `robots.txt` do domínio antes de fazer a requisição (checagem simples, requisição adicional cacheada também).
- Como o projeto é pensado para uso por múltiplas pessoas no futuro (não só você), isso é ainda mais importante: evita que muitos usuários do app gerem tráfego repetitivo desnecessário no site do fabricante.

### 2.3 Estrutura técnica

- **`HardwareIdentityScanner.java`** (novo, `core/`): implementa a seção 1.1 (leitura local).
- **`VendorLinkStrategy.java`** (novo, `updates/` — pacote novo) + implementações por fabricante (`AsusLinkStrategy`, `MsiLinkStrategy`, `GigabyteLinkStrategy`, `AsRockLinkStrategy`, `GenericLinkStrategy` como fallback).
- **`VendorPageParser.java`** (interface, `updates/`) + implementações por fabricante para o Nível 2 — cada uma isolada, para que a quebra de uma não afete as outras.
- **`UpdateCheckCache.java`** (novo, `updates/`): gerencia o cache SQLite de 24h mencionado acima.
- **`ui/HardwareUpdateView.java`** (novo): tela mostrando fabricante/modelo/versão de BIOS atual, versões de drivers detectados, botão "Verificar atualização online" (Nível 2, sob demanda), e botão "Abrir página de suporte" (Nível 1, sempre disponível).

---

## 3. Checklist de Implementação (Fase 11)

### Nível 1 (prioridade, sempre entregar isso primeiro)
- [x] Criar `HardwareIdentityScanner.java` lendo fabricante, modelo, versão de BIOS, data de release via OSHI
- [x] Adicionar leitura de drivers instalados (chipset, LAN, áudio, GPU) via WMI/PowerShell
- [x] Criar `VendorLinkStrategy` + implementações para ASUS, MSI, Gigabyte, ASRock, e fallback genérico (camadas 1/2/3 da seção 1.2)
- [x] Criar `ui/HardwareUpdateView.java` exibindo os dados detectados + botão "Abrir página de suporte"
- [x] Testar com a placa-mãe real do usuário: confirmar que o link gerado realmente leva à página certa (ou pelo menos à busca certa)
- [x] Atualizar `PROGRESS.md`

### Nível 2 (melhor esforço, depois do Nível 1 validado)
- [x] Criar `VendorPageParser` (interface) + implementação para pelo menos o fabricante da placa-mãe do usuário primeiro (validar o conceito antes de expandir para os outros 3) — implementado para **ASUS** (`AsusPageParser`), não para Dell (a máquina de teste real é um notebook, fora do escopo desta fase — ver decisão documentada em `PROGRESS.md`)
- [x] Criar `UpdateCheckCache.java` (cache SQLite de 24h)
- [x] Adicionar botão "Verificar atualização online" na `HardwareUpdateView`, chamando o parser sob demanda
- [x] Implementar tratamento de falha gracioso (nunca travar a tela; sempre cair de volta pro Nível 1 com mensagem clara)
- [x] Testar o cenário de falha de propósito (ex: mudar a URL para uma inválida) para confirmar que o fallback funciona
- [ ] Expandir os parsers para os outros fabricantes, se fizer sentido (não obrigatório no lançamento — pode ficar só com o fabricante do usuário por enquanto) — MSI/Gigabyte/ASRock ainda não têm parser de Nível 2 (ver `PROGRESS.md`)
- [x] Documentar em `PROGRESS.md` quais fabricantes têm parser funcional e quais ainda não (transparência sobre o estado real do recurso)

**Critério de conclusão:** o usuário consegue ver a versão atual da sua BIOS/drivers e, com um clique, chegar à página certa do fabricante — com ou sem a informação automática de "qual é a versão mais nova", que é tratada como bônus e não como garantia.

---

## 4. Observação Importante sobre Manutenção

Diferente de quase todas as outras fases (que são baseadas em chaves de registro/comandos do Windows, relativamente estáveis), o **Nível 2 desta fase depende de sites de terceiros que Anthropic/você não controla**. É esperado que, ao longo do tempo, algum parser pare de funcionar quando o fabricante atualizar o site. Isso deve ser tratado como manutenção normal, não como falha de projeto — por isso o Nível 1 (sempre funcional) é o que garante o valor real do recurso a longo prazo.

---

*Este documento complementa as fases anteriores. Nenhuma ação desta fase modifica BIOS/drivers automaticamente — o app só informa e direciona; a atualização em si continua sendo manual, feita pelo próprio usuário no site do fabricante (mesma lógica de "detecção + tutorial" já usada para o XMP na Fase 5).*
