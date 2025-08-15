/**
 *  Hubitat - MolSmart - 12+4 Input Board (v2.3 - Reconnect button + failure counter + keepalive '00')
 *  
 *  Copyright 2025 VH/TRATO
 *  License: Apache 2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *        
 *  Version 1.0 - 10/4/2025 - Beta 1.0
 *  Version 1.1 - 13/8/2025 -
 *  Version 1.2 - 15/8/2025

 *   
 */

import hubitat.helper.HexUtils

metadata {
    definition(
        name: "MolSmart - 12+4 Input Board",
        namespace: "TRATO",
        author: "VH",
        singleThreaded: true
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Polling"
        capability "PushableButton"
        capability "HoldableButton"

        command "discoverBoard"
        command "reconnect"
        command "reconnectNow"
        command "deleteAllChildren"
        command "createChildDevices"
        command "ping"

        attribute "channels", "number"
        attribute "connectionStatus", "string"
        attribute "boardStatus", "string"
        attribute "lastUpdate", "string"
        attribute "lastPingMs", "number"
        attribute "reconnectFailures", "number"
        attribute "lastPushed", "number"
        attribute "lastHeld", "number"
        attribute "lastmessage", "string"
        attribute "numberOfButtons", "number"
    }

    preferences {
        section("Network Settings") {
            input name: "ipAddress", type: "text", title: "IP Address", required: true
            input name: "tcpPort", type: "number", title: "TCP Port", defaultValue: 502, required: true
            input name: "commandDelay", type: "number", title: "Delay between commands (ms)", defaultValue: 100, range: "50..500"
            input name: "checkInterval", type: "number", title: "Connection check interval (seconds)", defaultValue: 90, range: "30..3600"
            input name: "autoRefresh", type: "number", title: "Auto-refresh interval (seconds, 0=disabled)", defaultValue: 60
        }
        section("Keepalive") {
            input name: "keepaliveEnabled", type: "bool", title: "Enable keepalive/heartbeat (command '00')", defaultValue: true
            input name: "keepaliveInterval", type: "number", title: "Keepalive interval (seconds)", defaultValue: 45, range: "15..3600"
        }
        section("Reconnect Backoff") {
            input name: "backoffBaseSeconds", type: "number", title: "Backoff base seconds", defaultValue: 5, range: "1..120"
            input name: "backoffMaxSeconds", type: "number", title: "Backoff max seconds (cap)", defaultValue: 300, range: "30..3600"
        }
        section("Digital Inputs") {
            input name: "inputsNormalOpen", type: "bool", title: "Contacts are Normally Open (NO)", defaultValue: true,
                  description: "ON = NO (default Open/Closed on press). OFF = NC (default Closed/Open on press)."
            input name: "buttonDebounceMs", type: "number", title: "Button debounce (ms)", defaultValue: 120, range: "0..2000"
            input name: "holdThresholdMs", type: "number", title: "Hold threshold (ms)", defaultValue: 2000, range: "500..5000"
        }
        section("Debugging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
            input name: "enableNotifications", type: "bool", title: "Enable connection notifications", defaultValue: false
        }
    }
}

/* ========================= Lifecycle ========================= */

def installed() {
    log.info "MolSmart 12+4 Input driver installed"
    sendEvent(name: "numberOfButtons", value: 12)
    initialize()
}

def updated() {
    log.info "MolSmart 12+4 Input driver updated"
    state.logDisableScheduled = false
    try {
        String ns = defaultNormalState()
        (1..12).each { ch ->
            def child = getChildDevice("${state.digitalNetIds}${ch < 10 ? "0$ch" : ch}")
            if (child) child.sendEvent(name: "normalState", value: ns)
        }
    } catch (ignored) {}
    initialize()
}

def initialize() {
    log.info "Initializing MolSmart 12+4 Input"
    try { unschedule() } catch (ignored) {}
    try { interfaces.rawSocket.close() } catch (ignored) {}

    // Defensive state inits
    if (state.lastMessageReceivedAt == null) state.lastMessageReceivedAt = 0L
    if (state.retryCount == null) state.retryCount = 0
    if (state.reconnectFailures == null) state.reconnectFailures = 0
    if (state.boardstatus == null) state.boardstatus = "offline"
    if (state.btnLastMs == null) state.btnLastMs = [:]
    if (state.btnLastHeldMs == null) state.btnLastHeldMs = [:]
    if (state.pendingPush == null) state.pendingPush = [:]
    if (state.prevInputBits == null) state.prevInputBits = ("1" * 12)  // released baseline (stored reversed; see handleDigitalInputs)
    if (state.lastAnalogStates == null) state.lastAnalogStates = [:]
    if (state.digitalNetIds == null) state.digitalNetIds = "${device?.id}-DI-"
    if (state.analogNetIds == null) state.analogNetIds = "${device?.id}-AI-"
    state.pingSentAt = 0L

    sendEvent(name: "reconnectFailures", value: state.reconnectFailures as int)
    sendEvent(name: "numberOfButtons", value: 12)

    // Connect
    try {
        Integer port = (settings?.tcpPort ?: 502) as Integer
        String host = (settings?.ipAddress ?: "").trim()
        if (!host) {
            log.error "IP Address not configured."
            setBoardStatus("offline")
            return
        }
        log.debug "Connecting to ${host}:${port}"
        interfaces.rawSocket.connect(host, port)
        setBoardStatus("online")
        state.lastMessageReceivedAt = now()
        state.retryCount = 0
    } catch (e) {
        log.error "Connection failed: ${e.message}"
        setBoardStatus("offline")
        incrementFailureCounter()
        scheduleReconnectBackoff()
        return
    }

    Integer chk = (settings?.checkInterval ?: 90) as Integer
    runIn(chk, "connectionCheck")

    if (settings?.keepaliveEnabled) {
        Integer ka = (settings?.keepaliveInterval ?: Math.max(30, (int)(chk/2))) as Integer
        runIn(Math.max(15, ka), "sendKeepAlive")
    }
    Integer r = (settings?.autoRefresh ?: 60) as Integer
    if (r > 0) runIn(r, "refresh")

    runIn(2, "createChildDevices")
    runIn(1, "discoverBoard")
}

/* ========================= Helpers / State ========================= */

private int MAX_RETRY() { return (settings?.backoffMaxSeconds ?: 300) as int }
private String defaultNormalState() { return (settings?.inputsNormalOpen == false) ? "Closed" : "Open" }

private void scheduleReconnectBackoff() {
    int c = (state.retryCount ?: 0) as int
    int baseCfg = (settings?.backoffBaseSeconds ?: 5) as int
    int maxCap = MAX_RETRY()
    int base = Math.min(maxCap, (int)Math.pow(2D, Math.min(c, 8)) * baseCfg)
    int jitter = (int)(Math.random() * 3)
    int delay = Math.max(baseCfg, Math.min(maxCap, base + jitter))
    state.retryCount = c + 1
    log.warn "Scheduling reconnect attempt #${state.retryCount} in ${delay}s"
    runIn(delay, "initialize")
}

private void incrementFailureCounter() {
    state.reconnectFailures = ((state.reconnectFailures ?: 0) as int) + 1
    sendEvent(name: "reconnectFailures", value: state.reconnectFailures as int)
}

def connectionCheck() {
    long nowTs = now()
    long delta = nowTs - (state.lastMessageReceivedAt ?: 0L)
    Integer chk = (settings?.checkInterval ?: 90) as Integer
    if (delta > (chk * 1000L)) {
        log.warn "No messages for ${(int)(delta / 1000)}s. Reconnecting..."
        setBoardStatus("offline")
        incrementFailureCounter()
        scheduleReconnectBackoff()
    } else {
        if (state.boardstatus != "online") setBoardStatus("online")
        runIn(chk, "connectionCheck")
    }
}

def setBoardStatus(String status) {
    if (state.boardstatus != status) {
        state.boardstatus = status
        sendEvent(name: "boardStatus", value: status)
        sendEvent(name: "connectionStatus", value: status)
        if (settings?.enableNotifications) {
            String msg = "MolSmart Input Board is now ${status}."
            log.info msg
            try { sendNotificationEvent(msg) } catch (ignored) {}
        }
    }
    if (status == "online") state.retryCount = 0
}

/* ========================= Children ========================= */

def discoverBoard() {
    log.info "Discovering board configuration..."
    state.digitalChannels = 12
    state.analogChannels = 4
    sendEvent(name: "channels", value: 16)
}

def createChildDevices() {
    log.info "Creating child devices (12 DI, 4 AI)"
    String thisId = device.id
    state.digitalNetIds = "${thisId}-DI-"
    state.analogNetIds  = "${thisId}-AI-"

    (1..12).each { channel ->
        def childDni = "${state.digitalNetIds}${channel < 10 ? "0$channel" : channel}"
        def child = getChildDevice(childDni)
        if (!child) {
            try {
                child = addChildDevice("hubitat", "Generic Component Contact Sensor", childDni,
                    [name: "${device.displayName} DI-${channel}", isComponent: true])
                log.info "Created DI child ${childDni}"
            } catch (Exception e) {
                log.error "Error creating DI child ${childDni}: ${e.message}"
            }
        }
        if (child) {
            if (!child.currentValue("contact")) child.sendEvent(name: "contact", value: "unknown")
            child.sendEvent(name: "normalState", value: defaultNormalState())
            if (!child.currentValue("holdStatus")) child.sendEvent(name: "holdStatus", value: "inactive")
            if (!child.currentValue("combinedStatus")) child.sendEvent(name: "combinedStatus", value: "unknown")
        }
    }

    (1..4).each { channel ->
        def childDni = "${state.analogNetIds}${channel < 10 ? "0$channel" : channel}"
        def child = getChildDevice(childDni)
        if (!child) {
            try {
                child = addChildDevice("hubitat", "Generic Component Voltage Sensor", childDni,
                    [name: "${device.displayName} AI-${channel}", isComponent: true])
                log.info "Created AI child ${childDni}"
            } catch (Exception e) {
                log.error "Error creating AI child ${childDni}: ${e.message}"
            }
        }
        if (child) {
            if (child.currentValue("voltage") == null) child.sendEvent(name: "voltage", value: 0, unit: "V")
            child.sendEvent(name: "sensorType", value: settings?."analogInput${channel}Type" ?: "4-20mA")
            child.sendEvent(name: "minValue",   value: settings?."analogInput${channel}Min" ?: 0)
            child.sendEvent(name: "maxValue",   value: settings?."analogInput${channel}Max" ?: 0)
            child.sendEvent(name: "unit",       value: settings?."analogInput${channel}Unit" ?: "")
        }
    }
}

/* ========================= Parse ========================= */

def parse(String message) {
    // Defensive inits in case a frame arrives before initialize() completes
    if (state.btnLastMs == null) state.btnLastMs = [:]
    if (state.btnLastHeldMs == null) state.btnLastHeldMs = [:]
    if (state.pendingPush == null) state.pendingPush = [:]
    if (state.prevInputBits == null) state.prevInputBits = ("1" * 12)
    if (state.digitalNetIds == null) state.digitalNetIds = "${device?.id ?: 'D'}-DI-"
    if (state.analogNetIds == null) state.analogNetIds = "${device?.id ?: 'D'}-AI-"

    state.lastMessageReceivedAt = now()
    if (!message) return

    String messageString
    try {
        def messageBytes = HexUtils.hexStringToByteArray(message)
        messageString = new String(messageBytes as byte[])
    } catch (Exception ex) {
        messageString = message
    }
    state.lastmessage = messageString

    // RTT for ping
    if ((state.pingSentAt ?: 0L) > 0L) {
        long rtt = now() - (state.pingSentAt as long)
        sendEvent(name: "lastPingMs", value: (int) rtt)
        if (settings?.txtEnable) log.info "Ping RTT: ${rtt} ms"
        state.pingSentAt = 0L
    }

    if (logEnable && messageString) {
        log.debug "RX: ${messageString.inspect()}"
        if (!state.logDisableScheduled) { runIn(180, "disableDebugLogging"); state.logDisableScheduled = true }
    }

    def messages = messageString.split(/\r|\n/).findAll { it && it.trim() }
    messages.each { msg ->
        try {
            String trimmed = msg.trim()
            if (trimmed) processResponse(trimmed)
        } catch (Exception e) {
            log.error "Error processing message '${msg}': ${e.message}"
        }
    }
}

private def processResponse(String response) {
    // Example: "0.473V0.946mA0.946mA0.946mA:111111111110:4:12::000000000001"
    def parts = response.split(":")
    if (parts.size() < 2) { log.error "Invalid response: ${response}"; return }

    boolean anyChanges = false
    String ts = new Date().format("yyyy-MM-dd HH:mm:ss")

    // ----- Analog snapshot (parts[0]) -----
    def analogValues = parseAnalogValues(parts[0])
    (1..4).each { channel ->
        def value = analogValues[channel - 1]
        if (value != null) {
            def childDni = "${state.analogNetIds}${channel < 10 ? "0$channel" : channel}"
            def child = getChildDevice(childDni)
            if (child) {
                def sensorType = settings?."analogInput${channel}Type" ?: "4-20mA"
                def unit = sensorType == "4-20mA" ? "mA" : "V"
                def scaledValue = scaleAnalogValue(channel, value)
                child.sendEvent(name: "voltage", value: scaledValue, unit: unit)
                child.sendEvent(name: "value", value: scaledValue)
                child.sendEvent(name: "sensorValue", value: String.format("%.3f", value))
                if (settings?.txtEnable) log.info "Analog ${channel}: ${String.format('%.3f', value)}${unit}"
                anyChanges = true
            }
        }
    }

    // ----- Digital bits & H mask -----
    String dState = parts.size() > 1 ? parts[1] : null
    String dMask  = parts.size() > 5 ? parts[5] : null
    if (dState && dState.length() >= 12) {
        if (dMask == null || dMask.length() < dState.length()) dMask = ("0" * dState.length())
        handleDigitalInputs(dState, dMask, ts)
        anyChanges = true
    }

    if (anyChanges) sendEvent(name: "lastUpdate", value: ts)
}

/* ========================= Digital inputs (press/hold + order fix) ========================= */

private void handleDigitalInputs(String dState, String dMask, String ts){
    // Reverse strings so Board Input 1 maps to index 0 in our loop → Hubitat "1"
    String revState = dState.reverse()
    String revMask  = dMask?.reverse()

    int chan = Math.min(12, revState.length())
    String prev = (state.prevInputBits ?: ("1" * chan))
    if (prev.length() != chan) prev = ("1" * chan)

    boolean normalOpen = (settings?.inputsNormalOpen != false) // default true
    int debounce = ((settings?.buttonDebounceMs ?: 120) as int)
    int holdMs   = ((settings?.holdThresholdMs ?: 2000) as int)
    long nowMs = now()

    if (state.btnLastMs == null) state.btnLastMs = [:]
    if (state.btnLastHeldMs == null) state.btnLastHeldMs = [:]
    if (state.pendingPush == null) state.pendingPush = [:]
    if (state.digitalNetIds == null) state.digitalNetIds = "${device?.id ?: 'D'}-DI-"

    for (int i=0; i<chan; i++){
        char before = (i < prev.length()) ? prev.charAt(i) : '1'
        char after  = revState.charAt(i)
        char m      = (i < (revMask?.length() ?: 0)) ? revMask.charAt(i) : '0'

        // '1' idle, '0' active, 'H' hold
        boolean nowPressed = (after == '0' || after == 'H')
        boolean wasPressed = (before == '0' || before == 'H')
        boolean pushEdge   = (!wasPressed && nowPressed)
        boolean holdSeen   = (after == 'H') || (m == 'H')

        // Update contact child
        String contactState = nowPressed ? (normalOpen ? "closed" : "open") : (normalOpen ? "open" : "closed")
        def childDni = "${state.digitalNetIds}${(i+1) < 10 ? "0${i+1}" : (i+1)}"
        def child = getChildDevice(childDni)
        if (child){
            if (child.currentValue("contact") != contactState){ child.sendEvent(name: "contact", value: contactState) }
            String holdStatus = holdSeen ? "active" : "inactive"
            if ((child.currentValue("holdStatus") ?: "inactive") != holdStatus){ child.sendEvent(name: "holdStatus", value: holdStatus) }
            String combined = (contactState == "closed") ? (holdStatus=="active"?"held-closed":"closed") : (holdStatus=="active"?"held-open":"open")
            child.sendEvent(name: "combinedStatus", value: combined)
            child.sendEvent(name: "lastChange", value: holdSeen ? "${ts} (Hold)" : (pushEdge ? "${ts} (Press)" : ts))
        }

        // BUTTON events with HOLD-precedence and delayed PUSH
        int idx = i+1
        if (holdSeen){
            state.pendingPush.remove("${idx}")
            long lastHeld = (state.btnLastHeldMs["${idx}"] ?: 0L) as Long
            if (debounce <= 0 || (nowMs - lastHeld) >= debounce){
                sendEvent(name: "held", value: idx, isStateChange: true, type: "physical",
                          descriptionText: "Input ${idx} held")
                sendEvent(name: "lastHeld", value: idx)
                state.btnLastHeldMs["${idx}"] = nowMs
            } else if (logEnable) {
                log.debug "Hold ignored (debounce) on input ${idx}: ${nowMs-lastHeld}ms < ${debounce}ms"
            }
        } else if (pushEdge){
            long fireAt = nowMs + holdMs
            state.pendingPush["${idx}"] = fireAt
            runInMillis(Math.max(holdMs, 1), "emitPendingPush", [data: [idx: idx]])
            if (logEnable) log.debug "Scheduled pending push for input ${idx} at +${holdMs}ms"
        }
    }

    // Store reversed state as our previous baseline
    state.prevInputBits = revState
}

void emitPendingPush(Map data){
    Integer idx = (data?.idx ?: 0) as Integer
    if (!idx) return
    long due = (state.pendingPush?."${idx}" ?: 0L) as long
    if (due == 0L) return // canceled by hold

    long nowMs = now()
    if (nowMs >= due){
        int debounce = ((settings?.buttonDebounceMs ?: 120) as int)
        long last = (state.btnLastMs?."${idx}" ?: 0L) as Long
        if (debounce <= 0 || (nowMs - last) >= debounce){
            sendEvent(name: "pushed", value: idx, isStateChange: true, type: "physical",
                      descriptionText: "Input ${idx} pushed")
            sendEvent(name: "lastPushed", value: idx)
            if (state.btnLastMs == null) state.btnLastMs = [:]
            state.btnLastMs["${idx}"] = nowMs
        } else if (logEnable) {
            log.debug "Push ignored (debounce) on input ${idx}: ${nowMs-last}ms < ${debounce}ms"
        }
        state.pendingPush.remove("${idx}")
    }
}

/* ========================= Analog helpers ========================= */

private def parseAnalogValues(String analogPart) {
    def values = []
    def matcher = (analogPart =~ /(\d+\.\d+)(mA|V)/)
    (1..4).each { i ->
        if (matcher.find()) {
            def value = matcher.group(1).toDouble()
            def configuredType = settings?."analogInput${i}Type"
            def actualUnit = matcher.group(2)
            if (configuredType == "4-20mA" && actualUnit == "V")      { value = (value / 10 * 16) + 4 }
            else if (configuredType == "0-10V" && actualUnit == "mA") { value = ((value - 4) / 16) * 10 }
            values << value
        } else { values << null }
    }
    return values
}

private def scaleAnalogValue(channel, rawValue) {
    def minValue = settings?."analogInput${channel}Min"?.toDouble()
    def maxValue = settings?."analogInput${channel}Max"?.toDouble()
    if (minValue == null || maxValue == null) return rawValue
    def sensorType = settings?."analogInput${channel}Type"
    def inputRange = (sensorType == "4-20mA") ? [4, 20] : [0, 10]
    def scaledValue = ((rawValue - inputRange[0]) / (inputRange[1] - inputRange[0])) * (maxValue - minValue) + minValue
    return scaledValue
}

/* ========================= Commands / Keepalive ========================= */

def refresh() {
    sendCommand("00")
    Integer r = (settings?.autoRefresh ?: 60) as Integer
    if (r > 0) runIn(r, "refresh")
}

def sendKeepAlive() {
    if (state.boardstatus == "online" && (settings?.keepaliveEnabled ?: true)) { sendCommand("00") }
    Integer ka = (settings?.keepaliveInterval ?: 45) as Integer
    runIn(Math.max(15, ka), "sendKeepAlive")
}

def ping() {
    state.pingSentAt = now()
    if (settings?.txtEnable) log.info "Sending ping '00'"
    sendCommand("00")
}

def reconnect() {
    log.warn "Manual reconnect requested."
    try { interfaces.rawSocket.close() } catch (ignored) {}
    pauseExecution(500)
    initialize()
}

def reconnectNow() {
    log.warn "Reconnect Now pressed."
    reconnect()
}

def deleteAllChildren() {
    getChildDevices().each { child ->
        try { deleteChildDevice(child.deviceNetworkId) } catch (e) { log.error "Delete child error: ${e.message}" }
    }
}

def socketStatus(status) {
    log.warn "socketStatus: ${status}"
    if (!status?.toString()?.equalsIgnoreCase("open")) {
        setBoardStatus("offline")
        incrementFailureCounter()
        scheduleReconnectBackoff()
    }
}

def uninstalled() {
    try { interfaces.rawSocket.close() } catch (ignored) {}
    deleteAllChildren()
}

private sendCommand(String command) {
    try {
        if (settings?.logEnable) log.debug "TX: ${command}"
        interfaces.rawSocket.sendMessage("${command}\n")
        pauseExecution((settings?.commandDelay ?: 100) as Integer)
    } catch (Exception e) {
        log.error "Command failed: ${e.message}"
        setBoardStatus("offline")
        incrementFailureCounter()
        scheduleReconnectBackoff()
    }
}

def disableDebugLogging() {
    log.warn "Disabling debug logging after 3 minutes"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    state.logDisableScheduled = false
}
