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
  catalisador e estado N/D do câmbio (65). Modo 06 com falhas de ignição **por cilindro** (A1–A5).
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
- PID 65: confirmar qual valor do bit N/D é P/N e qual é D/R (olhar o painel trocando a alavanca).
- PID 54 (pressão do EVAP, faixa larga): a fórmula diverge entre fontes. Resolver com a
  leitura real em repouso (≈ 0 Pa).
