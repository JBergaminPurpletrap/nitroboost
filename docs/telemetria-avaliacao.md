# Avaliação — Telemetria Anônima Opcional (Fase 7)

Documento de avaliação, não de implementação. Item correspondente do checklist da Fase 7:
"Avaliar necessidade de telemetria anônima opcional, caso o projeto vire produto distribuído".
**Nenhuma coleta de dados de usuários foi implementada nesta fase** — a decisão de construir isso
de fato é do usuário/dono do projeto, feita separadamente e de forma explícita, por ser uma
funcionalidade sensível de privacidade.

## O que seria coletado (se implementado)

Hipótese mínima razoável para um app deste tipo (otimização de sistema): contagem de execuções,
quais das 7 categorias de scan são mais usadas, quais ações (kill/disable/uninstall) são mais
aplicadas, taxa de sucesso/erro por tipo de ação, versão do Windows/app. **Nunca**: nomes de
processos/serviços específicos do usuário, conteúdo de arquivos, identificadores pessoais (nome de
máquina, usuário, IP, hardware ID único).

## Por que alguém pediria isso

- Priorizar quais dos 66 itens da base de conhecimento realmente importam manter atualizados.
- Detectar cedo se uma ação está falhando com frequência incomum em versões específicas do Windows
  (ex: um comando `powercfg`/`schtasks` que mudou de comportamento numa build nova).
- Entender adoção geral (quantas pessoas de fato usam o app) caso vire produto distribuído.

## Riscos de privacidade

- **Este é um app que mexe em processos, serviços, registro e apps instalados do usuário** — o
  risco de reidentificação por telemetria "anônima" mal desenhada é maior que em um app comum,
  porque combinações de "quais itens de bloatware você tem instalado" já são quase uma impressão
  digital da máquina.
- Falso senso de segurança: "anônimo" é fácil de prometer e difícil de garantir de verdade (IP de
  origem da requisição HTTP já é um identificador, mesmo sem nenhum campo explícito de ID).
- Expectativa do usuário: é um projeto pessoal/privado hoje (README já deixa isso claro); qualquer
  telemetria, mesmo opt-in, muda esse contrato implícito de confiança se não for comunicada de
  forma muito clara antes de ligar por padrão.
- Custo de manutenção contínuo: uma vez que existe um endpoint recebendo dados de usuários reais,
  existe uma obrigação de manter isso seguro, teria que ter política de retenção/exclusão,
  possivelmente LGPD dependendo de quão "anônimo" o dado realmente é.

## Esforço de implementação (se fosse construir)

- Backend para receber os eventos: não existe hoje nenhuma infraestrutura de servidor no projeto
  (o "servidor" mais próximo que existe é o próprio GitHub, usado só para hospedar arquivos
  estáticos como o JSON da base remota — não aceita `POST` de eventos). Precisaria de um serviço
  novo (mesmo que serverless/gratuito), o que é uma dependência de infraestrutura nova que hoje o
  projeto não tem e que contraria a filosofia atual (YAGNI, zero infra além do próprio SO/GitHub).
- Cliente: opt-in explícito na UI (tela de configurações que ainda nem existe), um identificador
  anônimo rotativo (não persistente e não vinculável entre sessões, para reduzir risco de
  reidentificação), fila local com retry, e novamente `HttpClient` com POST assíncrono.
- Não é grande em linhas de código, mas é uma superfície nova de responsabilidade contínua
  (servidor no ar, política de dados, consentimento) desproporcional ao que o projeto precisa hoje.

## Alternativas

1. **Nenhuma telemetria** (padrão atual, mantido).
2. **Métricas 100% locais, nunca enviadas**: já existe uma base para isso sem nenhum código novo —
   a tabela `actions_history` do SQLite local já registra toda ação feita (tipo, sucesso/falha,
   timestamp). O usuário (ou uma futura tela de "estatísticas" só dele) pode consultar isso
   localmente sem que nenhum dado saia da máquina. Cobre boa parte do valor ("quais ações eu mais
   uso") sem nenhum dos riscos de privacidade/infra acima.
3. **Telemetria opt-in real, só se o projeto virar produto distribuído de verdade**: adiar a
   decisão até existir um motivo concreto (usuários reais além do dono, necessidade real de dado
   agregado) — nesse momento, desenhar com consentimento explícito na primeira execução,
   documentação pública clara do que é coletado, e preferencialmente um provedor de terceiros já
   auditado para esse fim, em vez de infraestrutura própria.

## Recomendação

**Não implementar telemetria agora.** A opção 2 (métricas locais via `actions_history`, já
existente) entrega a maior parte do valor prático sem nenhum risco de privacidade ou custo de
infraestrutura novo. Revisitar a opção 3 apenas se e quando o projeto de fato ganhar uma base de
usuários fora do desenvolvedor — nesse ponto, tratar como uma decisão de produto separada, com
opt-in explícito e documentação pública, não como uma extensão silenciosa desta fase.
