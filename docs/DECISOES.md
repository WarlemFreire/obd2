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

## Pendências que dependem do carro real

- Decodificar as sondagens Toyota (serviço 21 em 7E0/7E1) a partir do relatório:
  ATF, marcha, lock-up.
- Confirmar se o carro informa teor de etanol (PID 52), marcha (A4) e odômetro (A6).
- PID 54 (pressão do EVAP, faixa larga): a fórmula diverge entre fontes. Resolver com a
  leitura real em repouso (≈ 0 Pa).
