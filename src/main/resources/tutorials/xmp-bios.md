# Como Habilitar o XMP (ou EXPO) na BIOS

## O que é isso

XMP (Intel) e EXPO (AMD) são perfis salvos dentro do próprio pente de
memória RAM. Sem ativar esse perfil, a maioria das placas-mãe roda a RAM
numa velocidade padrão bem mais baixa do que ela é capaz - mesmo que a
caixa do produto anuncie uma velocidade maior. Ativar o XMP/EXPO faz a RAM
rodar na velocidade que você realmente pagou por ela, o que pode melhorar
o desempenho em jogos e outras tarefas que dependem de memória rápida.

Isso é uma configuração feita na BIOS/UEFI da placa-mãe (a tela azul ou
cinza que aparece antes do Windows abrir) - o NITRO BOOST consegue avisar
que há indício de XMP desativado, mas não consegue ativá-lo sozinho por
software, porque é uma configuração de hardware de baixo nível.

## Passo a passo geral

Os nomes e menus variam de fabricante para fabricante, mas o caminho é
sempre parecido:

1. Reinicie o computador.
2. Durante a tela inicial (antes do Windows carregar), pressione
   repetidamente a tecla para entrar na BIOS/UEFI. Geralmente é `Delete`
   (ou `Del`) ou `F2` - a própria tela costuma mostrar qual tecla usar.
3. Procure por um modo "Avançado" ou "Advanced Mode" - em muitas placas a
   BIOS abre num modo simplificado primeiro, e a opção de XMP fica escondida
   no modo avançado.
4. Procure por uma seção com nome parecido com "Ai Tweaker", "Overclocking",
   "Memory" ou "DOCP/EXPO" (em placas AMD) e localize a opção **XMP** (ou
   **EXPO**, ou **DOCP** em algumas placas ASUS com AMD).
5. Ative o perfil (normalmente é só mudar de "Disabled" para "Profile 1" ou
   "Enabled"). Se houver mais de um perfil, o "Profile 1" costuma ser a
   opção mais segura e testada pelo fabricante da memória.
6. Salve as alterações e saia (geralmente a tecla `F10`, ou uma opção "Save
   & Exit" no menu).
7. O computador vai reiniciar. Isso é normal - às vezes o PC reinicia mais
   de uma vez sozinho enquanto testa a nova velocidade da memória.

## Alguma coisa deu errado?

Se o computador não ligar direito depois de ativar o XMP/EXPO (tela preta,
reinicializações repetidas), não se preocupe: quase toda placa-mãe detecta
esse tipo de falha automaticamente e volta para as configurações seguras
sozinha em alguns segundos. Se isso não acontecer, procure por uma opção
chamada "Clear CMOS" (às vezes um botão físico na placa-mãe ou no
gabinete, ou uma jumper) para restaurar a BIOS ao padrão de fábrica.

## Links oficiais por fabricante

Cada fabricante tem sua própria central de suporte com o passo a passo
específico para o modelo exato da sua placa-mãe. Vale a pena procurar pelo
modelo da sua placa nesses sites:

- **ASUS**: https://www.asus.com/support/
- **Gigabyte**: https://www.gigabyte.com/Support
- **MSI**: https://www.msi.com/support
- **ASRock**: https://www.asrock.com/support/index.asp

Dica: pesquise "como ativar XMP" (ou "EXPO") seguido do modelo exato da sua
placa-mãe (ex: "como ativar XMP ASUS TUF B550") diretamente no site de
suporte do fabricante ou no Google - os manuais costumam ter capturas de
tela do menu exato da BIOS do seu modelo.
