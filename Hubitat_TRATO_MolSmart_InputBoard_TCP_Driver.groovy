/**
 * MolSmart - 12+4 Input Board
 *
 * Fork TecnoSimples do driver "MolSmart - 12+4 Input Board" de VH.
 * Copyright 2025 VH (original)
 * Copyright 2026 TecnoSimples Tecnologia LTDA (modificações)
 * contato@tecnosimples.com.br | (14) 99760-6885
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 *
 * Versão do pacote: 3.0.0
 *
 * Versões TecnoSimples:
 *   TS-1.0.0  26/10/2026  Fork do VH v2.3: botões, reconexão, vínculo pela série e migração no lugar
 *   TS-1.0.1  27/10/2026  Refresh dos filhos (componentRefresh); publicação no HPM
 *   TS-3.0.0  29/10/2026  Versão única nos dois canais (pacote TecnoSimples e repositório do VH); sem mudança de comportamento
 */
 
import groovy.transform.Field
metadata {
definition (
name: "MolSmart - 12+4 Input Board",
namespace: "TRATO",
author: "TecnoSimples Tecnologia LTDA (fork do driver de VH/TRATO)",
importUrl: "https://raw.githubusercontent.com/hhorigian/hubitat_MolSmart_InputBoard/main/Hubitat_TRATO_MolSmart_InputBoard_TCP_Driver.groovy",
singleThreaded: true
) {
capability "Initialize"
capability "Refresh"
capability "PushableButton"
capability "HoldableButton"
command "vincularPlaca"
attribute "driverVersion", "string"
attribute "online", "string"
attribute "lastRx", "number"
attribute "vinculoPlaca", "string" 
attribute "ultimaTrocaIp", "string" 
attribute "lastPushed", "number"
attribute "lastHeld", "number"
attribute "numberOfButtons", "number"
attribute "boardStatus", "string" 
attribute "connectionStatus", "string" 
}
preferences {
input(name: "secAjuda", type: "hidden", title: HELP_HTML)
input(name: "secConexao", type: "hidden", title: "<hr><b>Conexão</b>")
input(name: "ipAddress", type: "text", title: "IP da placa", required: true,
description: 'Endereço da placa de entradas MolSmart na rede. O dispositivo fica preso à placa pelo número de série: se o IP dela mudar, o driver a reencontra sozinho e atualiza este campo. A reserva de IP no roteador continua recomendada. Para trocar a placa por outra: mude o IP aqui e salve; se a placa nova ficou no mesmo IP, use o botão Vincular Placa.')
input(name: "tcpPort", type: "number", title: "Porta TCP da placa", defaultValue: 502, range: "1..65535",
description: 'Padrão 502. Só mude se a placa foi configurada com outra porta.')
input(name: "hbInterval", type: "number", title: "Intervalo de verificação da placa (s)", defaultValue: 15, range: "5..120",
description: 'A cada X segundos o hub pede o estado da placa: mantém a conexão viva e corrige as entradas. Padrão 15.')
input(name: "idleTimeout", type: "number", title: "Considerar a placa desconectada após (s)", defaultValue: 60, range: "10..600",
description: 'Se a placa ficar esse tempo sem mandar leitura válida, o driver reconecta. Nunca é menor que 3× o intervalo de verificação. Padrão 60.')
input(name: "reconnectMin", type: "number", title: "Reconexão: primeira espera (s)", defaultValue: 5, range: "1..120",
description: 'Depois de uma queda, o driver tenta de novo após este tempo e dobra a espera a cada falha, até o máximo abaixo. Padrão 5.')
input(name: "reconnectMax", type: "number", title: "Reconexão: espera máxima (s)", defaultValue: 60, range: "5..600",
description: 'Limite da espera entre tentativas. Padrão 60.')
input(name: "secVinculo", type: "hidden", title: "<hr><b>Vínculo com a placa</b>")
input(name: "autoFindBoard", type: "bool", title: "Vincular à placa pelo número de série e reencontrá-la se o IP mudar", defaultValue: true,
description: 'Ligado (padrão): o driver só conecta depois de a placa confirmar o número de série, e, se ela mudar de IP, procura na rede e atualiza o IP sozinho. Desligado: volta a confiar só no IP — inclusive conectando em outra placa que assuma esse IP.')
input(name: "findAfterFails", type: "number", title: "Procurar a placa na rede depois de quantas tentativas sem resposta", defaultValue: 3, range: "1..20",
description: 'Padrão 3. Vale quando a placa não responde no IP configurado.')
input(name: "findEveryMin", type: "number", title: "Intervalo mínimo entre buscas (min)", defaultValue: 30, range: "5..1440",
description: 'Padrão 30. Se a busca não achar a placa, a próxima só acontece depois deste tempo.')
input(name: "secEntradas", type: "hidden", title: "<hr><b>Entradas digitais</b>")
input(name: "inputsNormalOpen", type: "bool", title: "Sensor em repouso aparece como ABERTO", defaultValue: true,
description: 'Ligado (padrão): entrada acionada = fechado; em repouso = aberto. Desligado: inverte. Ao trocar de outro driver, confira isto ANTES de salvar: se não bater com a instalação, os sensores aparecem invertidos e disparam regras.')
input(name: "buttonDebounceMs", type: "number", title: "Anti-repique dos botões (ms)", defaultValue: 120, range: "0..2000",
description: 'Ignora um segundo apertado (ou segurado) no mesmo botão antes deste tempo. Padrão 120. 0 = desligado. Não afeta o sensor de contato.')
input(name: "holdTimeMs", type: "number", title: "Tempo para segurado (ms)", defaultValue: 0, range: "0..10000",
description: '0 = a placa decide (~0,6 s), padrão. Com um valor (100 a 10000), o driver decide: segurado depois deste tempo com a entrada acionada. Valores de 1 a 99 valem 100.')
input(name: "secAnalog", type: "hidden", title: "<hr><b>Entradas analógicas</b>")
input(name: "aiDeadband", type: "decimal", title: "Tolerância para publicar uma leitura nova", defaultValue: 0.05, range: "0..10",
description: 'Só publica quando a leitura muda pelo menos isto (na unidade da placa, V ou mA). Padrão 0,05. 0 = publica qualquer mudança.')
for (int n = 1; n <= 4; n++) {
input(name: "ai${n}Min", type: "decimal", title: "AI-${n}: valor no início da faixa (opcional)", required: false,
description: 'Com mínimo e máximo preenchidos, o atributo value sai em escala: 0 V ou 4 mA = mínimo; 10 V ou 20 mA = máximo.')
input(name: "ai${n}Max", type: "decimal", title: "AI-${n}: valor no fim da faixa (opcional)", required: false)
input(name: "ai${n}Unit", type: "text", title: "AI-${n}: unidade do valor em escala (opcional)", required: false)
}
input(name: "secLogs", type: "hidden", title: "<hr><b>Logs</b>")
input(name: "logEnable", type: "bool", title: "Registrar detalhes técnicos no log (diagnóstico)", defaultValue: false,
description: 'Desligado (padrão). Desliga sozinho 30 minutos depois de salvar. Avisos e erros aparecem sempre.')
input(name: "txtEnable", type: "bool", title: "Registrar no log cada botão e cada leitura analógica publicada", defaultValue: true)
}
}
@Field static final String HELP_HTML = '''<details><summary><b>Ajuda: entradas, botões e vínculo com a placa ▸</b></summary>
<p><b>Entradas digitais (DI-1 a DI-12)</b>: cada uma vira um sensor de contato filho e um botão deste dispositivo.</p><ul>
<li><b>pushed N</b>: toque curto, ao soltar.</li>
<li><b>held N</b>: segurado, uma vez por toque; esse toque não gera pushed. Com "Tempo para segurado" em 0, quem decide é a placa (~0,6 s).</li>
<li>No filho, <b>holdStatus</b> fica active e <b>combinedStatus</b> fica held-closed (ou held-open) enquanto segurado, e voltam ao soltar.</li></ul>
<p><b>Aberto/fechado</b>: com "Sensor em repouso aparece como ABERTO" ligado (padrão), entrada acionada = closed. Ao trocar de outro driver, confira esta opção antes de salvar: se ela não bater com a instalação, os sensores aparecem invertidos e disparam regras.</p>
<p><b>Entradas analógicas (AI-1 a AI-4)</b>: <i>voltage</i> = leitura da placa, em V ou mA conforme o jumper interno; <i>value</i> = leitura em escala, se mínimo e máximo estiverem preenchidos. Só publica quando a leitura muda além da tolerância.</p>
<p><b>Vínculo com a placa</b>: o dispositivo fica preso ao número de série da placa. Se o IP dela mudar, o driver procura na sub-rede e atualiza o IP sozinho (<i>ultimaTrocaIp</i>). Para trocar de placa: mude o IP e salve; se a placa nova ficou no mesmo IP, use <b>Vincular Placa</b>.</p>
<p><b>Atributos</b>: <i>online</i> (true/false; <i>boardStatus</i> e <i>connectionStatus</i> repetem como online/offline), <i>lastRx</i> (hora da última leitura válida), <i>vinculoPlaca</i>, <i>driverVersion</i>.</p></details>'''

@Field static java.util.Random _rng = new java.util.Random()
@Field static final String DRIVER_VERSION = "TS-3.0.0-VH"
@Field static final String TCP_TERMINATOR = "LF" 
@Field static final boolean RX_HEX = true 
@Field static final int N_DI = 12
@Field static final int N_AI = 4
@Field static final int GUARD_EXTRA_S = 45 
@Field static final int FIND_WINDOW = 6 
@Field static final long SCAN_LEASE_MS = 180000L 
@Field static final long SEEN_FRESH_MS = 300000L 
@Field static final String VINC_OK = "ok"
@Field static final String VINC_OFF = "desligado"
@Field static final String VINC_NOSN = "sem série"
@Field static final String VINC_NOCONF = "sem confirmação — a placa não respondeu o número de série"
@Field static final String VINC_WAIT = "aguardando a placa do IP novo responder"

@Field static final java.util.concurrent.ConcurrentHashMap SCAN_LEASE = new java.util.concurrent.ConcurrentHashMap() 
@Field static final java.util.concurrent.ConcurrentHashMap SEEN = new java.util.concurrent.ConcurrentHashMap() 
@Field static final java.util.concurrent.ConcurrentHashMap SCAN_CNT = new java.util.concurrent.ConcurrentHashMap() 
@Field static final int RX_LEFTOVER_MAX = 1024
@Field static final String AI_RE = /^(-?\d+(?:\.\d+)?(?:mA|V)){4}$/
@Field static final String BITS_RE = /^[01H]{12}$/

@Field static final List VH_STATE = ["pendingPush", "btnLastMs", "btnLastHeldMs", "prevInputBits", "retryCount",
"reconnectFailures", "boardstatus", "lastmessage", "lastAnalogStates", "analogChannels", "analogNetIds",
"digitalChannels", "digitalNetIds", "lastMessageReceivedAt", "logDisableScheduled", "pingSentAt"]
 
private void logDbg(msg){ if (settings?.logEnable) log.debug("${device.displayName ?: device.name}: ${msg}") }
private void logInf(msg){ log.info ("${device.displayName ?: device.name}: ${msg}") }
private void logWar(msg){ log.warn ("${device.displayName ?: device.name}: ${msg}") }
private void logErr(msg){ log.error("${device.displayName ?: device.name}: ${msg}") }
private boolean txtOn(){ settings?.txtEnable != false } 
private void markRxNow(){ state.lastRx = now(); sendEvent(name: "lastRx", value: state.lastRx) }
private int clampInt(v,l,h){ Math.max(l as int, Math.min(h as int, (v ?: 0) as int)) }


private int prefInt(String name, int dflt, int lo, int hi){
def v = settings?."${name}"
if (v == null || (v instanceof CharSequence && !v.toString().trim())) return dflt
try { return clampInt((v instanceof Number) ? v : new BigDecimal(v.toString().trim()), lo, hi) }
catch (e) { logWar("Preferência ${name} inválida (${v}); usando ${dflt}"); return dflt }
}

private BigDecimal prefDecOrNull(String name){
def v = settings?."${name}"
if (v == null || (v instanceof CharSequence && !v.toString().trim())) return null
try { return new BigDecimal(v.toString().trim().replace(',', '.')) }
catch (e) { logWar("Preferência ${name} inválida (${v}); ignorada"); return null }
}
private BigDecimal prefDec(String name, BigDecimal dflt, BigDecimal lo, BigDecimal hi){
BigDecimal v = prefDecOrNull(name)
return (v == null) ? dflt : v.max(lo).min(hi)
}


private def childOrNull(String dni){
try { return getChildDevice(dni) }
catch (e) { logWar("filho ${dni} indisponível neste frame (${e})"); return null }
}
private String resolveIP(){ String ip = settings?.ipAddress; return ip?.trim() ?: null }
private Integer resolvePort(){ prefInt("tcpPort", 502, 1, 65535) }

private void publishOnline(boolean on){
sendEvent(name: "online", value: on ? "true" : "false")
String s = on ? "online" : "offline"
if (device.currentValue("boardStatus") != s) sendEvent(name: "boardStatus", value: s)
if (device.currentValue("connectionStatus") != s) sendEvent(name: "connectionStatus", value: s)
}
 


private boolean needsMigration(){
if (state.drv == DRIVER_VERSION) return false
runIn(1, "initialize", [overwrite: true]) 
return true
}

def sendKeepAlive(){ needsMigration() }
def emitPendingPush(data = null){ needsMigration() }
def disableDebugLogging(){ needsMigration() }
def createChildDevices(){ needsMigration() }
def discoverBoard(){ needsMigration() }
 
def installed(){
sendEvent(name: "driverVersion", value: DRIVER_VERSION)
device.updateSetting("logEnable", [value: "false", type: "bool"])
initialize()
}
def updated(){
unschedule(); disconnectSocket()
initialize()
}
def uninstalled(){ unschedule(); state.remove("verify"); cancelScan(); disconnectSocket() } 
def initialize(){
logInf("Initialize (${DRIVER_VERSION})")
unschedule() 
VH_STATE.each { state.remove(it) }
state.syncRetry = 0
state.reconnecting = false 
state.rxFormatSeen = false
state.remove("verify"); cancelScan()
state.remove("snTryAt")
state.remove("aiPub") 
initRxStats()
sendEvent(name: "driverVersion", value: DRIVER_VERSION)
sendEvent(name: "numberOfButtons", value: N_DI)
try { syncChildren() } catch (e) { scheduleSyncRetry(e) } 
String ip = resolveIP()
if (!ip){
logWar("IP da placa não configurado: preencha em Preferences e salve.")
disconnectSocket()
} else if (applyBoardCheck(readSnSync(ip))){
connectSocket()
} else {
disconnectSocket() 
scheduleReconnect("placa não confirmada")
}
if (settings?.logEnable) runIn(1800, "logsOff", [overwrite: true])
state.drv = DRIVER_VERSION 
}
def logsOff(){
if (needsMigration()) return
log.info "Desabilitando logs de debug automaticamente (30 min)."
device.updateSetting("logEnable", [value: "false", type: "bool"])
}
 
private String diDni(int n){ "${device.id}-DI-${n.toString().padLeft(2, '0')}".toString() } 
private String aiDni(int n){ "${device.id}-AI-${n.toString().padLeft(2, '0')}".toString() }
private String diLegacy(int n){ "${device.deviceNetworkId}-DI${n}".toString() } 
private String aiLegacy(int n){ "${device.deviceNetworkId}-AI${n}".toString() }
private void syncChildren(){
for (int n = 1; n <= N_DI; n++) syncChild("DI", n, diDni(n), diLegacy(n), "Generic Component Contact Sensor")
for (int n = 1; n <= N_AI; n++) syncChild("AI", n, aiDni(n), aiLegacy(n), "Generic Component Voltage Sensor")
}

private void syncChild(String tipo, int n, String atual, String legado, String drv){
def cur = getChildDevice(atual)
def old = getChildDevice(legado)
if (cur){
if (old) logWar("filho antigo ${legado} (id ${old.id}) ficou de uma versão anterior; apague à mão se não for usado")
return
}
if (old){
try {
old.setDeviceNetworkId(atual)
logInf("filho ${legado} (id ${old.id}) adotado como ${atual}")
} catch (e) {
logWar("não consegui renomear o filho ${legado} para ${atual} (${e}); ${tipo}-${n} fica sem filho até o próximo Initialize")
}
return
}
addChildDevice("hubitat", drv, atual, [name: "${device.displayName} ${tipo}-${n}".toString(), isComponent: true])
logInf("filho ${atual} criado")
}


private void scheduleSyncRetry(e){
int n = ((state.syncRetry ?: 0) as int) + 1
state.syncRetry = n
if (n > 3){ logWar("syncChildren falhou (${e}) — 3 tentativas esgotadas; use Initialize"); return }
int delay = 10 + _rng.nextInt(21)
logWar("syncChildren falhou (${e}) — nova tentativa ${n}/3 em ${delay}s")
runIn(delay, "retrySyncChildren", [overwrite: true])
}
def retrySyncChildren(){
if (needsMigration()) return
try {
syncChildren()
logInf("syncChildren concluído na tentativa ${state.syncRetry}")
state.syncRetry = 0
} catch (e) { scheduleSyncRetry(e) }
}
 
private boolean autoFindOn(){ settings?.autoFindBoard != false } 
private String expectedSn(){ String s = (state.NumeroSerie ?: "").toString().trim(); return s ?: null }
private boolean linkActive(){ autoFindOn() && expectedSn() != null }

private void ensureBoundIp(){ if (expectedSn() != null && !state.boundIp){ String ip = resolveIP(); if (ip) state.boundIp = ip } }
private void publishVinculo(String v){ if (state.vinc != v){ state.vinc = v; sendEvent(name: "vinculoPlaca", value: v) } }

private String boardCheck(boolean ligado, String esperada, boolean pendente, String lida){
if (!ligado) return "SKIP"
if (esperada == null) return (lida != null) ? "MATCH" : "SKIP" 
if (pendente) return (lida != null) ? "MATCH" : "UNKNOWN" 
if (lida == null) return "UNKNOWN"
return (lida == esperada) ? "MATCH" : "MISMATCH"
}

private boolean applyBoardCheck(String lida){
ensureBoundIp()
String ip = resolveIP()
String esp = expectedSn()
boolean pend = (esp != null) && state.boundIp && (state.boundIp != ip) 
switch (boardCheck(autoFindOn(), esp, pend, lida)){
case "SKIP":
publishVinculo(autoFindOn() ? VINC_NOSN : VINC_OFF)
return true
case "MATCH":
if (esp == null || pend){
if (esp != lida) logInf("Placa vinculada: série ${lida}" + (esp ? " (antes ${esp})" : ""))
state.NumeroSerie = lida
}
state.boundIp = ip
state.idMiss = 0
cancelScan()
publishVinculo(VINC_OK)
return true
case "MISMATCH":
String dif = "placa diferente neste IP (série ${lida}; esperada ${esp}) — use Vincular Placa para vincular a esta".toString()
if (state.vinc != dif) logWar("A placa em ${ip} tem a série ${lida}; este dispositivo está vinculado à série ${esp}. Não conecto. Procurando a placa certa; para vincular a esta, use Vincular Placa.") 
publishVinculo(dif)
maybeStartScan(true)
return false
default: 
state.idMiss = ((state.idMiss ?: 0) as int) + 1
if (pend){ publishVinculo(VINC_WAIT); return false } 
if (!(state.vinc ?: "").startsWith("placa diferente")) publishVinculo(vinculoBusca() ?: VINC_NOCONF)
maybeStartScan(false)
return false
}
}
 
private String vinculoBusca(){
String esp = expectedSn()
if (state.scan) return "procurando a placa (série ${esp})…".toString()
if (state.scanMiss) return "procurando a placa (série ${esp}) — última busca ${state.scanMiss}, não encontrada".toString()
return null
}
private void releaseLease(){
String me = device.id.toString()
Map l = SCAN_LEASE.get("l") as Map
if (l != null && l.dev == me) SCAN_LEASE.remove("l", l)
}
private void cancelScan(){
if (state.scan){
boolean mini = ((state.scan as Map)?.mini == true)
releaseLease(); SCAN_CNT.remove(device.id.toString()); state.remove("scan"); unschedule("scanTick")
if (!mini) state.remove("lastScanAt") 
}
state.remove("scanMiss")
}
private void scanEnd(boolean achou){
boolean mini = ((state.scan as Map)?.mini == true)
releaseLease(); SCAN_CNT.remove(device.id.toString()); state.remove("scan"); unschedule("scanTick")
if (!achou && mini){ String e = expectedSn(); if (e) SEEN.remove(e); return } 
if (!achou){
state.scanMiss = new Date().format("HH:mm", location.timeZone)
logWar("Busca terminada: a placa (série ${expectedSn()}) não foi encontrada na sub-rede. Nova busca em ${prefInt('findEveryMin', 30, 5, 1440)} min.")
if (!(state.vinc ?: "").startsWith("placa diferente")) publishVinculo(vinculoBusca())
}
}

private void maybeStartScan(boolean agora){
if (state.scan) return
if (!agora && ((state.idMiss ?: 0) as int) < prefInt("findAfterFails", 3, 1, 20)) return
String esp = expectedSn(); String ip = resolveIP()
if (!esp || !ip) return


Map seen = SEEN.get(esp) as Map
if (seen != null && (now() - (seen.at as Long)) < SEEN_FRESH_MS && (seen.ip as String) != ip){ probeCandidate(seen.ip as String); return }
long every = prefInt("findEveryMin", 30, 5, 1440) * 60000L
if (state.lastScanAt && (now() - (state.lastScanAt as Long)) < every) return
if (!(ip ==~ /\d{1,3}(\.\d{1,3}){3}/)){ logWar("Busca da placa: o IP configurado (${ip}) não é um IPv4 — sem busca."); return }
String me = device.id.toString()

Map lease = SCAN_LEASE.get("l") as Map
Map novo = [dev: me, at: now()]
boolean got
if (lease == null) got = (SCAN_LEASE.putIfAbsent("l", novo) == null)
else if ((now() - (lease.at as Long)) >= SCAN_LEASE_MS || lease.dev == me) got = SCAN_LEASE.replace("l", lease, novo)
else got = false
if (!got) return 
int id = ((state.scanSeq ?: 0) as int) + 1
state.scanSeq = id
state.lastScanAt = now()
state.remove("scanMiss")
state.scan = [id: id, base: ip.tokenize(".").take(3).join("."), next: 1]
java.util.concurrent.ConcurrentHashMap c = new java.util.concurrent.ConcurrentHashMap()
c.putAll([id: id, sent: 0, done: 0, last: now()])
SCAN_CNT.put(me, c)
logWar("Procurando a placa (série ${esp}) na sub-rede ${state.scan.base}.x…")
if (!(state.vinc ?: "").startsWith("placa diferente")) publishVinculo(vinculoBusca())
runIn(_rng.nextInt(20) + 1, "scanTick")
}



private void probeCandidate(String cand){
int id = ((state.scanSeq ?: 0) as int) + 1
state.scanSeq = id
state.scan = [id: id, base: cand.tokenize(".").take(3).join("."), next: 255, mini: true]
java.util.concurrent.ConcurrentHashMap c = new java.util.concurrent.ConcurrentHashMap()
c.putAll([id: id, sent: 1, done: 0, last: now()])
SCAN_CNT.put(device.id.toString(), c)
try { asynchttpGet("scanCallback", [uri: "http://${cand}/get/sn.cgi".toString(), timeout: 2], [id: id, ip: cand]) }
catch (e) { c.put("done", 1) }
runIn(1, "scanTick")
}
def scanTick(){
if (needsMigration()) return
Map sc = state.scan as Map
if (!sc) return
Map c = SCAN_CNT.get(device.id.toString()) as Map
if (c == null || (c.id as Integer) != (sc.id as Integer)){ scanEnd(false); return } 
if ((now() - (c.last as Long)) > 30000L){ scanEnd(false); return } 
int n = sc.next as int
int infl = (c.sent as int) - (c.done as int)
while (infl < FIND_WINDOW && n <= 254){
String alvo = "${sc.base}.${n}".toString()
try {
asynchttpGet("scanCallback", [uri: "http://${alvo}/get/sn.cgi".toString(), timeout: 2], [id: sc.id, ip: alvo])
c.merge("sent", 1) { a, b -> a + b }; c.put("last", now()); infl++; n++
} catch (e) {
logDbg("busca: envio a ${alvo} falhou (${e}); repete no próximo segundo")
break
}
}
sc.next = n
state.scan = sc

if (n <= 254 || (infl > 0 && (now() - (c.last as Long)) < 10000L)) runIn(1, "scanTick")
else scanEnd(false)
}
def scanCallback(resp, Map data){
if (needsMigration()) return
String lida = snFromResponse(resp)
if (lida) SEEN.put(lida, [ip: data?.ip, at: now()]) 
Map c = SCAN_CNT.get(device.id.toString()) as Map
if (c != null && (c.id as Integer) == (data?.id as Integer)) c.merge("done", 1) { a, b -> a + b }
Map sc = state.scan as Map
if (!sc || (sc.id as Integer) != (data?.id as Integer)) return 
if (!lida || lida != expectedSn()) return
if ((data.ip as String) == resolveIP()){ scanEnd(true); return } 
boardFound(data.ip as String)
}

private void boardFound(String ipNovo){
String ipAntigo = resolveIP()
int port = resolvePort() as int
scanEnd(true)
disconnectSocket() 
state.idMiss = 0
state.remove("verify"); unschedule("verifyTimeout")
device.updateSetting("ipAddress", [value: ipNovo, type: "text"])
state.boundIp = ipNovo
state.conIp = ipNovo
String quando = new Date().format("dd/MM/yyyy HH:mm", location.timeZone)
sendEvent(name: "ultimaTrocaIp", value: "${ipAntigo} → ${ipNovo} em ${quando}".toString())
logWar("Placa (série ${expectedSn()}) encontrada em ${ipNovo}; antes ${ipAntigo}. IP atualizado — confira a reserva de DHCP.")
unschedule("doReconnect")
state.reconnecting = true 
runIn(1, "doReconnect")
runIn(1 + GUARD_EXTRA_S, "reconnectGuard", [overwrite:true]) 
}

def vincularPlaca(){
if (needsMigration()) return
String ip = resolveIP()
if (!ip){ logWar("Vincular Placa: IP não configurado."); return }
String lida = readSnSync(ip)
if (!lida){ logWar("Vincular Placa: a placa em ${ip} não respondeu o número de série — nada mudou."); return }
String antes = expectedSn()
state.NumeroSerie = lida
state.boundIp = ip
logInf("Vincular Placa: dispositivo vinculado à série ${lida} em ${ip}" + ((antes && antes != lida) ? " (antes ${antes})" : ""))
initialize()
}
 
private String readSnSync(String ip){
String lida = null
try {
httpGet([uri: "http://${ip}/get/sn.cgi".toString(), timeout: 5]) { resp ->
def d = resp.data
lida = (d instanceof Map) ? (d.sn != null ? d.sn.toString().trim() : null) : extractSnFlexible(d?.toString())
}
} catch (e) { logDbg("sn.cgi em ${ip} falhou: ${e}") }
return lida ?: null
}

private void trySnAdopt(){
if (!autoFindOn() || expectedSn() != null || state.socketOnline != true) return
if (now() - ((state.snTryAt ?: 0L) as Long) < 60000L) return
state.snTryAt = now()
String ip = resolveIP()
try { asynchttpGet("snAdoptCallback", [uri: "http://${ip}/get/sn.cgi".toString(), timeout: 5], [ip: ip, conAt: state.connectedAt]) }
catch (e) { logDbg("leitura da série não enviada: ${e}") } 
}
def snAdoptCallback(resp, Map data){
if (needsMigration()) return
if (state.socketOnline != true || expectedSn() != null || !autoFindOn()
|| (data?.ip as String) != resolveIP() || (data?.conAt as Long) != (state.connectedAt as Long)) return
String sn = snFromResponse(resp); if (!sn) return
state.NumeroSerie = sn; state.boundIp = data.ip
logInf("Placa vinculada: série ${sn}")
publishVinculo(VINC_OK) 
}

private String snFromResponse(resp){
try {
if ((resp?.status as Integer) != 200) return null
def d = resp.getData()
if (d instanceof Map) return (d.sn != null) ? d.sn.toString().trim() : null
return extractSnFlexible(d?.toString())
} catch (e) { return null }
}
private static String extractSnFlexible(String s){
if (!s) return null
String t = s.trim()

try {
def js = new groovy.json.JsonSlurper().parseText(t)
def v = js?.sn
if (v != null) return v.toString().trim()
} catch(ex) {}

def m = (t =~ /(?i)\b"sn"\s*:\s*"?([\w\-\.\:]+)"?/)
if (m.find()) return m.group(1)
m = (t =~ /(?i)\bsn\s*[=:]\s*"?([\w\-\.\:]+)"?/)
if (m.find()) return m.group(1)


if (t.startsWith("{") && t.endsWith("}") && t.contains("=")) {
String body = t.substring(1, t.length()-1)
for (String pair : body.split(/\s*,\s*/)) {
String[] kv = pair.split(/\s*[=:]\s*/, 2)
if (kv.size() == 2 && kv[0].trim().equalsIgnoreCase("sn")) {
return kv[1].trim().replaceAll(/^"(.*)"$/, "\$1")
}
}
}
return null
}
 
private void initRxStats(){
if (state.rxValid == null) state.rxValid = 0
if (state.rxInvalid == null) state.rxInvalid = 0
if (state.rxDropped == null) state.rxDropped = 0
if (state.rxStatsSince == null) state.rxStatsSince = new Date().format("yyyy-MM-dd HH:mm:ss")
}
private void bumpRx(String k){
if (state.rxStatsSince == null) initRxStats()
state[k] = ((state[k] ?: 0) as long) + 1
}


private long watchdogBase(){
long c = (state.connectedAt ?: 0L) as Long
long rx = (state.lastRx ?: c) as Long
long any = (state.lastAnyRx ?: c) as Long
long b = (state.rxFormatSeen == true) ? rx : Math.max(rx, any)
return Math.max(c, b)
}
 
private void connectSocket(){
String ip = resolveIP(); Integer port = resolvePort()
if (!ip){
logErr("IP não configurado nas Preferences.")
publishOnline(false); state.socketOnline = false
unschedule("heartbeat"); unschedule("connectionCheck")
return
}
unschedule("holdTimer"); state.remove("inBase") 
try { interfaces.rawSocket.close() } catch(ignored){} 
try{
interfaces.rawSocket.connect(ip, port as int)
state.socketOnline = true
state.rxBuf = '' 
publishOnline(true)
if (state.conIp != null && (state.conIp != ip || ((state.conPort ?: 0) as int) != (port as int))){
logInf("Placa nova em ${ip}:${port} (antes ${state.conIp}:${state.conPort})")
state.rxFormatSeen = false
}
state.conIp = ip; state.conPort = port
state.connectedAt = now()
state.rxCapWarned = false
logInf("Socket conectado a ${ip}:${port}")
state.reconnectAttempt = 0

unschedule("doReconnect"); unschedule("reconnectGuard"); state.reconnecting = false
scheduleHeartbeat(); scheduleWatchdog(); runIn(1, "heartbeat", [overwrite:true])
} catch(e){
logErr("Falha ao conectar em ${ip}:${port}: ${e}")
scheduleReconnect("connect error")
}
}

private void markSocketOffline(){ state.socketOnline = false; unschedule("heartbeat"); unschedule("holdTimer"); state.remove("inBase"); publishOnline(false) }
private void disconnectSocket(){ markSocketOffline(); try { interfaces.rawSocket.close() } catch(ignored){} }
private void scheduleHeartbeat(){ int s = prefInt("hbInterval", 15, 5, 120); runIn(s, "heartbeat", [overwrite:true]) }
def heartbeat(){
if (needsMigration()) return
txRaw("00", "HB"); scheduleHeartbeat(); trySnAdopt()
}
private void scheduleWatchdog(){ runIn(5, "connectionCheck", [overwrite:true]) }
def connectionCheck(){
if (needsMigration()) return
int hb = prefInt("hbInterval", 15, 5, 120)
int effIdle = Math.max(prefInt("idleTimeout", 60, 10, 600), hb*3)

if (state.connectedAt == null){ state.connectedAt = now(); scheduleWatchdog(); return }
long idle = now() - watchdogBase()
if (idle >= effIdle*1000L){
String oque = (state.rxFormatSeen == true) ? "leitura válida" : "dado nenhum"
logErr("Sem ${oque} da placa há ${String.format('%.1f', idle/1000.0)}s (limite=${effIdle}s). Reconectando.")
scheduleReconnect("idle timeout"); return
}
scheduleWatchdog()
}
private void scheduleReconnect(String reason, boolean closeSocket = true){
if (state.reconnecting == true) return
state.reconnecting = true
int attempt = ((state.reconnectAttempt ?: 0) as int) + 1
state.reconnectAttempt = attempt
int minS = prefInt("reconnectMin", 5, 1, 120)
int maxS = prefInt("reconnectMax", 60, 5, 600)
int delay = Math.min(maxS, (int)Math.pow(2D, Math.min(6,attempt-1)) * minS) + _rng.nextInt(Math.max(1, minS))
logWar("Reconectar (#${attempt}) em ~${delay}s (${reason})")
runIn(delay, "doReconnect", [overwrite:true])
runIn(delay + GUARD_EXTRA_S, "reconnectGuard", [overwrite:true]) 
if (closeSocket) disconnectSocket() else markSocketOffline() 
}



def reconnectGuard(){
if (needsMigration()) return
if (state.socketOnline == true || !resolveIP()) return
Map v = state.verify as Map
if (v != null && (now() - ((v.at ?: 0L) as Long)) < 30000L){ 
runIn(GUARD_EXTRA_S, "reconnectGuard", [overwrite:true]); return 
}
logWar("Reconexão parada: a tentativa agendada não aconteceu. Retomando sozinho.")
state.reconnecting = false
scheduleReconnect("reconexão parada")
}
def doReconnect(){
if (needsMigration()) return
state.reconnecting = false; verifyThenConnect()
}

private void verifyThenConnect(){
ensureBoundIp() 
String ip = resolveIP()
if (!ip || !linkActive()){ connectSocket(); return } 
Map v = state.verify as Map
if (v != null && (now() - ((v.at ?: 0L) as Long)) < 30000L) return 
int gen = ((state.verifyGen ?: 0) as int) + 1
state.verifyGen = gen
state.verify = [gen: gen, ip: ip, at: now()]
runIn(15, "verifyTimeout", [overwrite: true, data: [gen: gen]]) 
try {
asynchttpGet("verifyCallback", [uri: "http://${ip}/get/sn.cgi".toString(), timeout: 5], [gen: gen, ip: ip])
} catch (e) {
state.remove("verify"); unschedule("verifyTimeout")
logWar("Conferência da série não pôde ser enviada: ${e}")
scheduleReconnect("conferência não pôde ser enviada")
}
}
def verifyCallback(resp, Map data){
if (needsMigration()) return
Map v = state.verify as Map

if (v == null || (v.gen as Integer) != (data?.gen as Integer) || (data?.ip as String) != resolveIP()) return
state.remove("verify"); unschedule("verifyTimeout")
if (applyBoardCheck(snFromResponse(resp))) connectSocket()
else scheduleReconnect("placa não confirmada")
}

def verifyTimeout(Map data){
if (needsMigration()) return
Map v = state.verify as Map
if (v == null || (v.gen as Integer) != (data?.gen as Integer)) return
state.remove("verify")
scheduleReconnect("conferência sem resposta")
}
def socketStatus(String message){
if (needsMigration()) return
logWar("socketStatus: ${message}")
if ((message ?: "").toLowerCase().contains("broken pipe")) bumpRx("brokenPipe") 
if (!(message?.toLowerCase()?.contains("normal") ?: false)) scheduleReconnect("socketStatus", false)
}
private boolean txRaw(String msg, String reason){
String tx = withTerminator(msg)
try{
interfaces.rawSocket.sendMessage(tx)
logDbg("TX(${reason}): '${escapePrint(tx)}'")
return true
} catch(e){
logWar("Falha ao enviar '${msg}': ${e}")
if ("${e}".toLowerCase().contains("broken pipe")) bumpRx("brokenPipe")
scheduleReconnect("send fail")
return false
}
}
private String withTerminator(String s){
switch (TCP_TERMINATOR){
case "LF": return s + "\n"
case "CR": return s + "\r"
case "CRLF": return s + "\r\n"
default: return s
}
}
private static String escapePrint(String s){ (s ?: "").replace("\r", "\\r").replace("\n", "\\n") }
 
def parse(String payload){
if (needsMigration()) return
if (!payload) return
state.lastAnyRx = now() 
logDbg("RX cru: ${payload}")
String txt = payload
if (RX_HEX){
if (!(payload ==~ /(?i)^([0-9a-f]{2})+$/)){ bumpRx("rxInvalid"); logDbg("pedaço fora do hex descartado"); return }
txt = new String(hubitat.helper.HexUtils.hexStringToByteArray(payload), "ISO-8859-1")
}
String rxBuf = ((state.rxBuf ?: '') as String) + txt.replaceAll(/[\r\n]/, '\n')
int sep
while ((sep = rxBuf.indexOf('\n')) >= 0){
String tok = rxBuf.substring(0, sep).trim()
rxBuf = rxBuf.substring(sep + 1)
if (tok) handleFrame(tok)
}

if (rxBuf.length() > RX_LEFTOVER_MAX){
bumpRx("rxDropped")
if (state.rxCapWarned != true){
logWar("Sobra do buffer de recepção passou de ${RX_LEFTOVER_MAX} caracteres sem formar frame — descartada")
state.rxCapWarned = true
}
rxBuf = ''
}
state.rxBuf = rxBuf
}

private Map parseFrame(String tok){
String[] p = tok.split(':', -1)
if (p.length != 5 && p.length != 6) return null
if (p[4] != '') return null
if (p.length == 6 && !(p[5] ==~ BITS_RE)) return null
if (!(p[0] ==~ AI_RE) || !(p[1] ==~ BITS_RE)) return null
if (p[2] != '4' || p[3] != '12') return null
return [ai: p[0], bits: p[1].reverse(), mask: (p.length == 6) ? p[5].reverse() : null]
}
private void handleFrame(String tok){
Map f = parseFrame(tok)
if (f == null){ bumpRx("rxInvalid"); logDbg("frame inválido descartado: ${tok}"); return }
bumpRx("rxValid"); markRxNow()
if (state.rxFormatSeen != true) state.rxFormatSeen = true
state.lastFrame = tok
boolean base = (state.inBase == null)
Map edge = handleButtons(f.bits as String, f.mask as String, base)
if (base) state.inBase = now()
Map m = (state.btn ?: [:]) as Map
for (int n = 1; n <= N_DI; n++){
String k = n.toString()
updateContactChild(n, m[k] as Map, edge[k] as String)
}
handleAnalogs(f.ai as String)
}
 

private Map handleButtons(String bits, String mask, boolean base){
Map edge = [:]
Map m = (state.btn ?: [:]) as Map
int deb = prefInt("buttonDebounceMs", 120, 0, 2000)
int hold = prefInt("holdTimeMs", 0, 0, 10000); if (hold > 0 && hold < 100) hold = 100
long t = now()
for (int n = 1; n <= N_DI; n++){
String k = n.toString()
Map c = (m[k] ?: [p: false, h: false, o: false, s: 0, lp: 0L, lh: 0L]) as Map
String b = bits.substring(n - 1, n); String mk = mask ? mask.substring(n - 1, n) : '0'
boolean pr = (b == '0' || b == 'H' || mk == 'H')
boolean hSeen = (b == 'H' || mk == 'H')
if (!base){
if (pr && !c.p){ 
c.s = ((c.s ?: 0) as int) + 1; c.h = false; c.o = false; edge[k] = "Press"
if (hold > 0) runInMillis(hold, "holdTimer", [overwrite: false, data: [n: n, seq: c.s]])
}
if (pr && hold == 0 && hSeen && !c.h && !c.o){ c.h = emitHeld(n, c, deb, t); edge[k] = "Hold" } 
if (!pr && c.p){ edge[k] = "Release"; if (!c.h && !c.o) emitPushed(n, c, deb, t) } 
} else {
c.o = pr; c.h = false 
}
if (!pr){ c.h = false; c.o = false }
c.p = pr
m[k] = c
}
state.btn = m
return edge
}
def holdTimer(Map d){
if (needsMigration() || state.socketOnline != true || state.inBase == null) return
String k = (d?.n as Integer)?.toString(); if (!k) return
Map m = (state.btn ?: [:]) as Map; Map c = m[k] as Map
if (c == null || !c.p || c.h || c.o || (c.s as Integer) != (d.seq as Integer)) return
c.h = emitHeld(d.n as Integer, c, prefInt("buttonDebounceMs", 120, 0, 2000), now())
m[k] = c; state.btn = m
updateContactChild(d.n as Integer, c, "Hold") 
}
private void emitPushed(int n, Map c, int deb, long t){
long lp = (c.lp ?: 0L) as long
if (deb > 0 && t - lp < deb){ logDbg("pushed ${n} ignorado (anti-repique: ${t - lp} ms)"); return }
c.lp = t
sendEvent(name: "pushed", value: n, isStateChange: true, type: "physical", descriptionText: "Botão ${n} apertado")
sendEvent(name: "lastPushed", value: n)
if (txtOn()) logInf("botão ${n} apertado")
}


private boolean emitHeld(int n, Map c, int deb, long t){
long lh = (c.lh ?: 0L) as long
if (deb > 0 && t - lh < deb){ logDbg("held ${n} ignorado (anti-repique: ${t - lh} ms)"); return true }
c.lh = t
sendEvent(name: "held", value: n, isStateChange: true, type: "physical", descriptionText: "Botão ${n} segurado")
sendEvent(name: "lastHeld", value: n)
if (txtOn()) logInf("botão ${n} segurado")
return true
}


 
private void updateContactChild(int n, Map c, String borda){
def cd = childOrNull(diDni(n)); if (!cd) return
boolean pr = (c?.p == true)
boolean no = (settings?.inputsNormalOpen != false) 
String contato = (pr == no) ? "closed" : "open"
if (cd.currentValue("contact") != contato) cd.sendEvent(name: "contact", value: contato)

String hs = (c?.h == true && pr) ? "active" : "inactive"
String cb = (hs == "active") ? "held-${contato}".toString() : contato
Map pub = (state.inPub ?: [:]) as Map
String id = cd.id.toString()
Map last = (pub[id] ?: [:]) as Map
if (last.hs != hs) cd.sendEvent(name: "holdStatus", value: hs)
if (last.cb != cb) cd.sendEvent(name: "combinedStatus", value: cb)
if (last.hs != hs || last.cb != cb){ pub[id] = [hs: hs, cb: cb]; state.inPub = pub }
if (borda) cd.sendEvent(name: "lastChange", value: "${new Date().format('dd/MM/yyyy HH:mm:ss', location.timeZone)} (${borda})".toString())
}
 
private void handleAnalogs(String part){
def mt = (part =~ /(-?\d+(?:\.\d+)?)(mA|V)/)
Map pub = (state.aiPub ?: [:]) as Map
BigDecimal db = prefDec("aiDeadband", 0.05G, 0G, 10G)
boolean mudou = false
int n = 0
while (mt.find() && n < N_AI){
n++
BigDecimal v = new BigDecimal(mt.group(1)); String u = mt.group(2)
String k = n.toString()
Map last = pub[k] as Map
if (last != null && last.u == u){
BigDecimal lv = new BigDecimal(last.v as String)
if (db.signum() == 0 ? v.compareTo(lv) == 0 : (v - lv).abs().compareTo(db) < 0) continue
}
publishAnalog(n, v, u)
pub[k] = [v: v.toPlainString(), u: u] 
mudou = true
}
if (mudou) state.aiPub = pub
}
private void publishAnalog(int n, BigDecimal raw, String u){
def cd = childOrNull(aiDni(n)); if (!cd) return
BigDecimal mn = prefDecOrNull("ai${n}Min"), mx = prefDecOrNull("ai${n}Max")
BigDecimal val = raw
if (mn != null && mx != null && mn.compareTo(mx) != 0){
BigDecimal lo = (u == "V") ? 0G : 4G, hi = (u == "V") ? 10G : 20G
val = (mn + (raw - lo) / (hi - lo) * (mx - mn)).setScale(3, java.math.RoundingMode.HALF_UP)
}


String vu = ((settings?."ai${n}Unit" ?: "") as String).trim() ?: u
cd.sendEvent(name: "voltage", value: raw, unit: u)
cd.sendEvent(name: "value", value: val, unit: vu)
cd.sendEvent(name: "sensorValue", value: raw.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString())
if (txtOn()) logInf("AI-${n}: ${raw} ${u}" + ((val.is(raw)) ? "" : " → ${val} ${vu}"))
}
 
def refresh(){
if (needsMigration()) return
logInf("Refresh -> 00"); txRaw("00", "refresh")
}


def componentRefresh(cd){
if (needsMigration()) return
def mt = (cd?.deviceNetworkId =~ /-AI-(\d+)$/)
if (mt.find()){
Map pub = (state.aiPub ?: [:]) as Map
String k = (mt.group(1) as Integer).toString()
if (pub.remove(k) != null) state.aiPub = pub
}
refresh()
}
def push(button){
if (needsMigration()) return
simulateButton("pushed", "lastPushed", "Push", button)
}
def hold(button){
if (needsMigration()) return
simulateButton("held", "lastHeld", "Hold", button)
}

private void simulateButton(String evt, String lastAttr, String cmd, button){
Integer n = null
try {
BigDecimal b = new BigDecimal(button.toString().trim())
if (b.stripTrailingZeros().scale() <= 0) n = b.intValueExact()
} catch (e) { }
if (n == null || n < 1 || n > N_DI){ logWar("${cmd}: botão inválido (${button}) — use um número de 1 a ${N_DI}"); return }
sendEvent(name: evt, value: n, isStateChange: true, type: "digital",
descriptionText: "Botão ${n} ${evt == 'pushed' ? 'apertado' : 'segurado'} (simulado pelo comando ${cmd}; a placa não foi acionada)")
sendEvent(name: lastAttr, value: n)
}
