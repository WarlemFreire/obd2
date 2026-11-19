# OBD Etios

App Android que conversa com o carro por um adaptador ELM327 (OBD2) para mostrar os
parâmetros do motor e ajudar no diagnóstico de manutenção. Feito para um Etios Sedan 2018
automático flex, mas funciona em qualquer carro com OBD2.

**Só lê.** O app recusa, antes de chegar ao adaptador, qualquer comando que apague códigos
ou escreva na central (`Elm327.SERVICOS_LEITURA`).

## Pegar o APK

1. Aba **Actions** do repositório → workflow **APK** → execução mais recente.
2. Em **Artifacts**, baixe `obd-etios-AAAAMMDD-<commit>`.
3. No celular, instale o `.apk` liberando "instalar apps de fontes desconhecidas".

Para compilar sem esperar um push: Actions → APK → **Run workflow**.

## Primeiro uso no carro (Fase 1)

1. Plugue o adaptador na tomada OBD (embaixo do painel, lado do motorista) e ligue a ignição.
2. **Bluetooth comum:** pareie em Ajustes → Bluetooth do celular (PIN `1234` ou `0000`).
   **BLE:** não pareia no sistema; use "Buscar BLE" no app.
   **Wi-Fi:** conecte o Wi-Fi do celular à rede do adaptador.
3. Aba **Conectar** → escolha o adaptador. A primeira conexão pode levar 10 s.
4. Ligue o motor, deixe aquecer, e na aba **Descoberta** toque em **Executar descoberta**.
5. **Compartilhar** o relatório. Ele mostra o que o *seu* carro realmente informa e é a
   base das Fases 2 e 3.

O adaptador plugado o tempo todo pode descarregar a bateria do carro. Até medirmos
(Fase 2), tire o adaptador se o carro for ficar parado mais de 2–3 dias.

## O que já funciona (Fase 1)

- Conexão por Bluetooth Clássico, BLE e Wi-Fi, com detecção automática do protocolo.
- Painel ao vivo com todos os parâmetros padrão que o carro suportar.
- Diagnóstico: códigos confirmados, pendentes e permanentes com explicação em português
  (140 códigos com causas prováveis e o que verificar primeiro), freeze frame, monitores de
  prontidão e falhas de ignição por cilindro (Modo 06).
- Relatório de descoberta: adaptador (detecta clones), protocolo, PIDs suportados, VIN,
  leituras, diagnóstico, sondagens Toyota do câmbio e a transcrição completa da conversa.

Próximas fases em [`docs/DECISOES.md`](docs/DECISOES.md).

## Desenvolvimento

```
app/    Android (Kotlin + Jetpack Compose). Conexões, telas.
core/   Kotlin puro, sem Android: protocolo ELM327, PIDs, códigos, descoberta. Testável no PC.
ferramentas/  Gerador da referência python-OBD usada nos testes.
```

Versões: AGP 9.5.1 (Kotlin embutido), Kotlin 2.4.21, Gradle 9.9.1, compileSdk 37, minSdk 26.

```sh
./gradlew :core:test            # testes do núcleo
./gradlew :app:assembleDebug    # APK (precisa de Android SDK; sem SDK o módulo app é ignorado)
```

### Teste contra um adaptador simulado

O [ELM327-emulator](https://github.com/Ircama/ELM327-emulator) simula o adaptador e um
Toyota (CAN 11 bits, 500 kbps):

```sh
pip install "git+https://github.com/Ircama/ELM327-emulator@dd3b1d2b19368875830ea8c86cea070040c26caf"
python -m elm -n 35000 -s car -d
OBD_EMULADOR=127.0.0.1:35000 ./gradlew :core:test   # gera core/build/relatorio-emulador.txt
```

O CI faz isso a cada push.

### Como as fórmulas são verificadas

`PidsReferenciaTest` compara cada decodificador com a [python-OBD](https://github.com/brendan-w/python-OBD)
em ~2.000 vetores de bytes aleatórios (`ferramentas/ref_python_obd.py`). Divergências
conhecidas e justificadas pela norma SAE J1979 estão documentadas no próprio teste.
