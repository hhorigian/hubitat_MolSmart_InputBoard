# hubitat_MolSmart_InputBoard
Driver for MolSmart 12+4 Input Board .


- O Input board pode ser usado com 12 sensores de entrada, por exemplo:
<img width="870" alt="image" src="https://github.com/user-attachments/assets/b8dbf77c-c5e2-4d1a-af85-33a021da7a85" />

## TecnoSimples — atualização / update

### Português

Este driver é mantido pela **TecnoSimples** a partir do código original de Vartan Horigian (VH / TRATO), sob a licença Apache 2.0. O nome, o namespace `TRATO` e os IDs do pacote são os mesmos de sempre: quem instalou por aqui recebe a versão nova pelo **HPM → Update**, sem trocar o Type de nenhum device.

**Como atualizar**
1. Atualize o próprio HPM antes (Hubitat Package Manager 1.9.16 ou mais novo).
2. HPM → **Update** → marque este pacote → aguarde a página dizer "complete" antes de sair dela.
3. Recomendado: abra cada placa e clique **Save Preferences** uma vez. Sem isso, o driver migra sozinho no primeiro dado da placa.

**Como saber de onde veio o driver:** o atributo `driverVersion` do device mostra `TS-3.0.0-VH` quando o código foi baixado deste repositório (`TS-3.0.0`, sem `-VH`, é o mesmo código pelo repositório da TecnoSimples).

**Atenção**
- Um toque passa a gerar `pushed` **ou** `held`, nunca os dois. Regras que contavam com os dois no mesmo toque precisam de ajuste.
- Antes de atualizar, anote o valor de "Contacts are Normally Open (NO)". No 3.0.0 a opção se chama "Sensor em repouso aparece como ABERTO"; o valor é mantido, mas confira depois.
- Saem os comandos `deleteAllChildren`, `reconnect` e `reconnectNow` e o atributo `reconnectFailures`. Nos filhos analógicos, `voltage` segue com a leitura crua e `value` traz a leitura em escala, se configurada.

### English

This driver is maintained by **TecnoSimples**, based on the original code by Vartan Horigian (VH / TRATO), under the Apache 2.0 license. The name, the `TRATO` namespace and the package IDs are unchanged: if you installed from here, you get the new version through **HPM → Update**, without changing any device's Type.

**How to update**
1. Update HPM itself first (Hubitat Package Manager 1.9.16 or newer).
2. HPM → **Update** → tick this package → wait for the page to say "complete" before leaving it.
3. Recommended: open each board and click **Save Preferences** once. Without it, the driver migrates on its own with the board's first data.

**Where did the driver come from:** the device's `driverVersion` attribute shows `TS-3.0.0-VH` when the code was downloaded from this repository (`TS-3.0.0`, without `-VH`, is the same code from the TecnoSimples repository).

**Heads-up**
- One press now gives `pushed` **or** `held`, never both. Rules that relied on both in the same press need adjusting.
- Before updating, note the value of "Contacts are Normally Open (NO)". In 3.0.0 the option is called "Sensor em repouso aparece como ABERTO" (sensor at rest shows as OPEN); the value is kept, but check it afterwards.
- The commands `deleteAllChildren`, `reconnect` and `reconnectNow` and the `reconnectFailures` attribute are gone. On analog children, `voltage` keeps the raw reading and `value` carries the scaled reading, if configured.
