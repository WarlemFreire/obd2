# Decisões do projeto

Registradas na entrevista de definição (grill-me), novembro de 2026.

## Contexto

- Carro: Toyota Etios Sedan 2018, automático, flex. Provável motor 1.5 Dual VVT-i (2NR-FBE)
  com câmbio automático de 4 marchas: **confirmar com o relatório de descoberta**.
- Uso: carro de aplicativo → manutenção pelo **plano severo**.
- Adaptador: ELM327 de tipo desconhecido (Bluetooth Clássico, BLE ou Wi-Fi). O app suporta os três.
- Celular: Android, modelo ainda não informado (o relatório registra).

## Decisões

| Tema | Decisão |
|---|---|
| Plataforma | Só Android, Kotlin nativo + Jetpack Compose |
| Relação com o Copiloto | Independente, repositório próprio |
| Parâmetros | OBD2 padrão. Exceções experimentais Toyota: temperatura do fluido do câmbio (ATF), marcha e lock-up |
| Diagnóstico | (a) códigos explicados offline em pt-BR; (c) tendências; (d) agenda de manutenção |
| Apagar códigos | **Não.** O app só lê |
| Dados | Só no celular, sem nuvem. Exportação manual em CSV (Fase 3) |
| Gravação | Manual por padrão, com opção automática. Decidir com medição de bateria (Fase 2) |
| Odômetro | Digitado ao abastecer; estimado entre abastecimentos pela velocidade |
| IA | Fora da v1 |

## Fases

1. **Descoberta e leitura** (feito): conexão, relatório de descoberta, painel ao vivo,
   códigos com explicação, freeze frame, prontidão, falhas de ignição por cilindro.
2. **Histórico e alertas:** gravação de viagens; tendências (correção de mistura por combustível,
   tempo de aquecimento, tensão na partida e de carga, falhas por cilindro, ATF máxima, marcha
   lenta); alertas críticos (água > 105 °C, ATF > 110 °C, carga < 13,0 V, código novo);
   medição de consumo de bateria do celular e da queda de tensão do carro parado.
3. **Manutenção e consumo:** agenda pelo plano severo com histórico inicial; abastecimentos
   (litros, preço, combustível, odômetro); km/l e R$/km reais por combustível; exportação CSV.

## Confirmado no carro (relatório de descoberta, 19/11/2026)

- **Adaptador:** ELM327 "v1.5" clone, **BLE**. Responde `OK` a comandos que não existem nele
  (`STI`, `STDI`, `ATCS`): é clone. ~45 ms por pedido depois que o BLE negocia intervalo curto
  (~130 ms antes). Perde ~3% das respostas (NO DATA para PID suportado, multi-quadro incompleto):
  o app repete uma vez.
- **Carro:** CAN 11 bits 500 kbps, uma central (7E8), norma **OBD-Brasil fase 2**, VIN Toyota do Brasil.
- **Suporta:** 39 PIDs, incluindo **teor de etanol (52)**, sonda banda larga, temperatura de
  catalisador e posição da alavanca (65: P/N = 0, D = 1, conferido no carro; R não testado). Modo 06 com falhas de ignição **por cilindro** (A1–A5).
- **Não suporta:** marcha (A4), odômetro (A6), temperatura do óleo (5C), códigos permanentes (0A).
  O odômetro fica mesmo digitado, como decidido.
- **Câmbio:** nada responde em 7E1, nem OBD padrão nem serviço 21. A central do câmbio não está
  em 7E1; no Etios o controle do câmbio provavelmente fica dentro da central do motor.
- **Serviço 21 na central do motor (7E0):** `2101` devolve 28 bytes e `2181` devolve 8. No `2101`
  já se reconhecem: temperatura da água (0x79 = 81 °C), do ar (0x69 = 65 °C), rotação
  (0x0C57 / 4 ≈ 790 rpm), minutos e km desde que os códigos foram apagados (0x02D6 = 726,
  0x00C2 = 194). Os outros bytes são candidatos à temperatura do fluido do câmbio.
- Códigos apagados (ou bateria desligada) há 194 km / 4 aquecimentos: por isso o monitor do
  catalisador estava incompleto.

## Pendências que dependem do carro real

- Identificar a temperatura do fluido do câmbio no `2101` da central do motor: gravar o bloco
  junto com a água durante uma partida a frio (Fase 2). O byte que sobe mais devagar que a
  água e passa dela depois de rodar é o candidato. Sem isso, não afirmar nada sobre ATF.
- PID 54 (pressão do EVAP, faixa larga): a fórmula diverge entre fontes. Resolver com a
  leitura real em repouso (≈ 0 Pa).

## Caso "consumo alto" (19/11/2026)

Fatos medidos (gravações de 19/11, etanol 100%, filtro de cabine novo):
- Consumo calculado pelo app bate com a bomba: 4,1 km/l (app) x 4,3 km/l (bomba, 91 km).
- Ar-condicionado é o fator principal: cidade 4,1 km/l com ar x 6,6 km/l sem, na mesma
  velocidade média (17 km/h); parado 1,92 x 0,78 L/h (+1,14 L/h). Compressor nunca desliga.
- Motor e câmbio saudáveis: mistura ~+2–3%, falhas ~0, termostato ok (88–96 °C sem ar),
  conversor trava acima de 60 km/h (rpm/kmh ~24 em 4ª), 11,1 km/l a 70–80 km/h com ar
  (Inmetro estrada 10,4; cidade 8,4 km/l etanol, sem ar).
- Ar admitido ~70 °C parado x ~55 °C acima de 60 km/h: pouca circulação de ar no cofre parado.
- P0420 pendente (catalisador): sonda traseira alterna 0,07–0,82 V em cruzeiro estável.
  Provável causa: GNV com falhas e estouros. Não trocar catalisador antes de resolver o GNV.
- Tensão com motor ligado às vezes 12,4–12,9 V: testar sistema de carga.
- Blocos Toyota 2101/2181 (7E0) só repetem dados padrão (água, ar, rotação, tensão em mV,
  tempo, distância). Sinal do compressor e temperatura do câmbio não estão neles.

Suspeitos do ar fraco, em ordem: recirculação travada no ar externo, ventoinha (ronca),
condensador sujo/sem vedação, carga de gás (confirmar por peso ou sub-resfriamento),
compressor/válvula de expansão (manifold). Embreagem do compressor trocada por trinca antiga.

Métrica de antes/depois: teste parado, 2 min sem ar + 2 min com ar (hoje +1,14 L/h).

## Roteiro combinado

1. Etanol redondo: ar-condicionado (acima), sistema de carga, vazamento no escapamento.
2. GNV (kit Landi Renzo de 5ª geração; hoje falha e dá estouros): diagnosticar; no app,
   modo GNV (consumo por MAF não vale no GNV) e, se viável, leitura da central do kit.
3. Catalisador: só depois do GNV.


## Ideias para depois (não priorizadas)

- **Central auxiliar (ESP32 via BLE)** para o "carro que lê e registra tudo", gravando junto com o
  OBD no mesmo CSV. Começar pelo que não envolve o gás do ar: sinal da embreagem do compressor
  (optoacoplador), temperaturas (saída do painel, frente do condensador, cofre) com DS18B20,
  corrente da ventoinha (sensor Hall de alicate). Depois, se fizer sentido, pressão de alta do ar
  (transdutor na válvula de serviço). Motor e GNV não precisam: o OBD já cobre.

## GNV: o que se sabe (19/11/2026)

- Kit Landi Renzo de 5ª geração. Velas revisadas (boas a razoáveis), uma bobina nova e as outras boas,
  "limpeza" feita há menos de 7 dias (a confirmar de quê).
- Sintomas: estouro na caixa de ar quando o motor pede força (ultrapassagem, subida); marcha lenta
  irregular às vezes. Hipótese principal: mistura pobre em carga alta (vazão ou pressão do redutor,
  filtro, bicos de gás, mapa do kit). Marcha lenta irregular: possível bico de gás de um cilindro.
- App (0.2.13+): chave Líquido/GNV (coluna "combustivel"), botão de evento (coluna "evento"),
  consumo em m³ (AFR 17, 0,74 kg/m³; ±15%), falhas por cilindro a cada 10 linhas no GNV.
- Protocolo: gravação curta no GNV (10–15 min: lenta quente, cruzeiro, acelerações), marcando cada
  falha/estouro; comparar correção de mistura, lambda, avanço e falhas por cilindro com o etanol.

## Manutenção (Fase 3, iniciada 19/11/2026)

- Aba Manutenção: odômetro (informado + km medidos pelo app desde a última informação), plano com
  situação por item (vencido / perto de vencer / em dia / sem registro; vence pelo que chegar
  primeiro, km ou meses; "perto" = 10% do intervalo de km ou 30 dias), registro de serviço, histórico.
- Plano padrão: uso severo + GNV. Intervalos são sugestão editável; os marcados "conferir" não foram
  confirmados no manual Toyota nem com o instalador do kit.
- Um único CSV (tipo = plano | servico | odometro) para guardar, exportar e importar. Importar mescla:
  itens do plano com o mesmo id são substituídos; serviços e leituras novos são somados.
- Descoberta virou botão dentro de Diagnóstico.

## Correções após o prontuário do proprietário (19/11/2026)

- **Válvula termostática foi retirada** (após superaquecimento e troca do radiador) e não foi
  reinstalada. A conclusão anterior "termostato ok" estava errada: na estrada a água caiu para
  73–74 °C (corrida das 10:55). Reinstalar é prioridade junto com a ventoinha.
- **Furo no escapamento** confirmado por foto (base do cano de saída): pode influenciar a sonda
  traseira e o P0420, dependendo da posição em relação à sonda.
- **P1603/P1604/P1605 no Toyota** = histórico de motor morrendo / dificuldade de partida / marcha
  lenta irregular (não EEPROM nem código de imobilizador, leitura de scanner genérico). Acrescentados
  à base de códigos do app. Bateria fraca aparece como causa de P1604: reforça testar o sistema de carga.
- Histórico importado no app a partir do prontuário: serviços podem ter só data ou só km; o app não
  inventa o que falta.

## Prontuário feito por IA: como é tratado (19/11/2026)

- Histórico de serviços (o que, km, valor) é relato do proprietário: mantido.
- Afirmações técnicas e intervalos do prontuário são hipóteses. O plano foi refeito e cada item diz a
  fonte: "prática comum", "conferir no manual", "conferir com o instalador" ou "exigência legal".
- Retirados: "1 tanque de gasolina a cada 2 meses" (justificativa fraca) e "folga de válvulas a cada
  60.000 km" (trocado por teste de compressão a cada 30.000 km; folga só se a compressão cair).
- CSV aceita linhas `remover,<id>` para tirar itens de um plano importado antes; a tela permite remover itens.
