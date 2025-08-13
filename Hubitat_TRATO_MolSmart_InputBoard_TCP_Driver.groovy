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
 *  		- Immediate auto-reconnect on socketStatus("closed") with exponential backoff + jitter
 *   		- Keepalive/heartbeat scheduler (uses GET_STATES) to detect silent drops faster
 *  		- Reset retryCount when going online
 * 			- Safer parse() with per-message try/catch and corrupted-frame handling
 *   		- Stronger manual reconnect() (forces close before re-init)
 *   		- Minor scheduling guards and null-safe settings handling
 *   		- Preferences to configure Keepalive (enable/disable, command text, interval)
 *   		- Preferences to configure reconnect backoff (base seconds, max seconds)
 *   		- New command "ping" to manually test round-trip; exposes attribute "lastPingMs"
 *   		- New button/command: "reconnectNow" (visible in UI) to force reconnect
 *   		- Attribute "reconnectFailures" tracks number of reconnection failures/attempts
 *   		- Keepalive and Ping now use fixed command "00" (as requested)
 *   		
 */
metadata {
    definition (
        name: "MolSmart - 12+4 Input Board",
        namespace: "VH",
        author: "VH",
        importUrl: "https://raw.githubusercontent.com/your-repo/main/12ChannelInputBoardWithAnalog.groovy",
        singleThreaded: true
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Polling"
        
        command "discoverBoard"
        command "reconnect"
        command "reconnectNow"   // UI button
        command "deleteAllChildren"
        command "createChildDevices"
        command "ping"

        attribute "channels", "number"
        attribute "connectionStatus", "string"
        attribute "boardStatus", "string"
        attribute "lastUpdate", "string"
        attribute "lastPingMs", "number"
        attribute "reconnectFailures", "number"
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
        section("Debugging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
            input name: "enableNotifications", type: "bool", title: "Enable connection notifications", defaultValue: false
        }
    }
}

/** ===== Internals ===== */
def MAX_RETRY_DELAY() { return (settings?.backoffMaxSeconds ?: 300) as int }

def installed() {
    log.info "12-Channel Input Board Driver installed"
    initialize()
}

def updated() {
    log.info "12-Channel Input Board Driver updated"
    state.logDisableScheduled = false
    initialize()
}

def initialize() {
    log.info "Initializing 12-Channel Input Board"
    try { unschedule() } catch (ignored) {}
    try { interfaces.rawSocket.close() } catch (ignored) {}
    
    // Initialize state variables
    if (state.lastMessageReceivedAt == null) state.lastMessageReceivedAt = 0L
    if (state.retryCount == null) state.retryCount = 0
    if (state.reconnectFailures == null) state.reconnectFailures = 0
    if (state.boardstatus == null) state.boardstatus = "offline"
    if (state.inputHoldStatus == null) state.inputHoldStatus = [:]
    if (state.lastInputStates == null) state.lastInputStates = [:]
    if (state.lastAnalogStates == null) state.lastAnalogStates = [:]
    state.pingSentAt = 0L
    sendEvent(name: "reconnectFailures", value: state.reconnectFailures as int)
    
    // Establish TCP connection
    try {
        Integer port = (settings?.tcpPort ?: 502) as Integer
        String host = (settings?.ipAddress ?: "").trim()
        if (!host) {
            log.error "IP Address not configured. Please set preferences."
            setBoardStatus("offline")
            return
        }
        log.debug "Attempting connection to ${host}:${port}"
        interfaces.rawSocket.connect(host, port)
        setBoardStatus("online")
        state.lastMessageReceivedAt = now()
        state.retryCount = 0  // reset on success
    } catch (e) {
        log.error "Connection failed: ${e.message}"
        setBoardStatus("offline")
        incrementFailureCounter()
        scheduleReconnectBackoff()
        return
    }
    
    // Schedule regular checks
    Integer chk = (settings?.checkInterval ?: 90) as Integer
    runIn(chk, "connectionCheck")
    
    // Keepalive (fixed "00")
    if (settings?.keepaliveEnabled) {
        Integer ka = (settings?.keepaliveInterval ?: Math.max(30, (int)(chk / 2))) as Integer
        runIn(Math.max(15, ka), "sendKeepAlive")
    }
    
    // Auto-refresh
    Integer r = (settings?.autoRefresh ?: 60) as Integer
    if (r > 0) runIn(r, "refresh")
    
    // Create child devices and initial discovery
    runIn(2, "createChildDevices")
    runIn(1, "discoverBoard")
}

private void scheduleReconnectBackoff() {
    int c = (state.retryCount ?: 0) as int
    int baseCfg = (settings?.backoffBaseSeconds ?: 5) as int
    int maxCap = MAX_RETRY_DELAY() as int
    // exponential backoff capped, with small jitter (0-3s)
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
    long timeSinceLastMessage = nowTs - (state.lastMessageReceivedAt ?: 0L)

    Integer chk = (settings?.checkInterval ?: 90) as Integer
    if (timeSinceLastMessage > (chk * 1000L)) {
        log.warn "No messages received for ${(int)(timeSinceLastMessage / 1000)} seconds. Reconnecting..."
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
            String msg = "12-Channel Input Board is now ${status}."
            log.info msg
            try { sendNotificationEvent(msg) } catch (ignored) {}
        }
    }
    if (status == "online") {
        state.retryCount = 0
    }
}

def discoverBoard() {
    log.info "Discovering board configuration"
    state.digitalChannels = 12
    state.analogChannels = 4
    sendEvent(name: "channels", value: 16) // 12 digital + 4 analog
}

def createChildDevices() {
    log.info "Creating child devices"
    String thisId = device.id
    state.digitalNetIds = "${thisId}-DI-"
    state.analogNetIds = "${thisId}-AI-"
    
    // Create digital input children
    (1..12).each { channel ->
        def childDni = "${state.digitalNetIds}${channel < 10 ? "0$channel" : channel}"
        def child = getChildDevice(childDni)
        
        if (!child) {
            try {
                child = addChildDevice("hubitat", "Generic Component Contact Sensor", childDni, 
                                    [name: "${device.displayName} DI-${channel}", 
                                     isComponent: true])
                log.info "Created digital input child device ${childDni}"
                child.sendEvent(name: "contact", value: "unknown")
                child.sendEvent(name: "normalState", value: settings?."digitalInput${channel}NormalState" ?: "Open")
                child.sendEvent(name: "holdStatus", value: "inactive")
                child.sendEvent(name: "combinedStatus", value: "unknown")
            } catch (Exception e) {
                log.error "Error creating child device ${childDni}: ${e.message}"
            }
        }
    }
    
    // Create analog input children
    (1..4).each { channel ->
        def childDni = "${state.analogNetIds}${channel < 10 ? "0$channel" : channel}"
        def child = getChildDevice(childDni)
        
        if (!child) {
            try {
                child = addChildDevice("hubitat", "Generic Component Voltage Sensor", childDni,
                                     [name: "${device.displayName} AI-${channel}",
                                      isComponent: true])
                log.info "Created analog input child device ${childDni}"
                child.sendEvent(name: "voltage", value: 0, unit: "V")
                child.sendEvent(name: "sensorType", value: settings?."analogInput${channel}Type" ?: "4-20mA")
                child.sendEvent(name: "minValue", value: settings?."analogInput${channel}Min" ?: 0)
                child.sendEvent(name: "maxValue", value: settings?."analogInput${channel}Max" ?: 0)
                child.sendEvent(name: "unit", value: settings?."analogInput${channel}Unit" ?: "")
            } catch (Exception e) {
                log.error "Error creating child device ${childDni}: ${e.message}"
            }
        }
    }
}

def parse(String message) {
    state.lastMessageReceivedAt = now()
    if (!message) return
    
    String messageString
    try {
        // Hubitat delivers hex string; decode safely
        def messageBytes = hubitat.helper.HexUtils.hexStringToByteArray(message)
        messageString = new String(messageBytes as byte[])
    } catch (Exception ex) {
        log.error "Parse error: cannot decode incoming frame: ${ex.message}"
        return
    }
    state.lastmessage = messageString
    
    // If we had sent a ping, measure round-trip on first subsequent frame
    if ((state.pingSentAt ?: 0L) > 0L) {
        long rtt = now() - (state.pingSentAt as long)
        sendEvent(name: "lastPingMs", value: (int)rtt)
        if (settings?.txtEnable) log.info "Ping RTT: ${rtt} ms"
        state.pingSentAt = 0L
    }
    
    if (logEnable) {
        log.debug "Received raw message: ${messageString.inspect()}"
        if (!state.logDisableScheduled) {
            runIn(180, "disableDebugLogging")
            state.logDisableScheduled = true
        }
    }
    
    // Split by CR; discard empties and noisy whitespace
    def messages = messageString.split(/\r/).findAll { it && it.trim() }
    
    messages.each { msg ->
        try {
            def trimmedMsg = msg.trim()
            if (logEnable) log.debug "Processing individual message: ${trimmedMsg}"
            if (trimmedMsg) processResponse(trimmedMsg)
        } catch (Exception e) {
            log.error "Error processing message '${msg}': ${e.message}"
        }
    }
}

private def processResponse(String response) {
    def parts = response.split(":")
    if (parts.size() < 6) {
        log.error "Invalid response format: ${response}"
        return
    }
    
    if (hasDigitalChanges(parts[1]) || hasAnalogChanges(parts[0])) {
        def ts = new Date().format("yyyy-MM-dd HH:mm:ss")
        boolean anyChanges = false
        
        // Process analog inputs
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
                    
                    if (settings?.txtEnable) log.info "Analog Input ${channel} value: ${String.format("%.3f", value)}${unit}"
                    anyChanges = true
                }
            }
        }
        
        // Process digital inputs with enhanced HOLD status tracking
        if (parts[1]?.length() == 12 && parts[5]?.length() >= 12) {
            (1..12).each { channel ->
                def stateChar = parts[1].charAt(channel - 1)
                def changeChar = parts[5].charAt(channel - 1)
                def normalState = settings?."digitalInput${channel}NormalState" ?: "Open"
                def currentState = (stateChar == '1') ? (normalState == "Open" ? "open" : "closed") : (normalState == "Open" ? "closed" : "open")
                
                def childDni = "${state.digitalNetIds}${channel < 10 ? "0$channel" : channel}"
                def child = getChildDevice(childDni)
                
                if (child) {
                    def previousState = child.currentValue("contact")
                    def currentHoldStatus = changeChar == 'H' ? "active" : "inactive"
                    def previousHoldStatus = child.currentValue("holdStatus") ?: "inactive"
                    
                    if (previousState != currentState) {
                        anyChanges = true
                        child.sendEvent(name: "contact", value: currentState)
                        if (settings?.txtEnable) log.info "Digital Input ${channel} is now ${currentState}"
                    }
                    
                    if (currentHoldStatus != previousHoldStatus) {
                        anyChanges = true
                        child.sendEvent(name: "holdStatus", value: currentHoldStatus)
                        if (settings?.txtEnable) log.info "Digital Input ${channel} HOLD status: ${currentHoldStatus}"
                        
                        def combinedStatus = currentState == "closed" ? 
                            (currentHoldStatus == "active" ? "held-closed" : "closed") : 
                            (currentHoldStatus == "active" ? "held-open" : "open")
                        child.sendEvent(name: "combinedStatus", value: combinedStatus)
                        
                        def actionType = currentHoldStatus == "active" ? "Hold" : "Release"
                        child.sendEvent(name: "lastChange", value: "${ts} (${actionType})")
                    }
                }
            }
        }
        
        if (anyChanges) {
            sendEvent(name: "lastUpdate", value: ts)
            state.lastInputStates.current = parts[1]
            state.lastAnalogStates.current = parts[0]
        }
    }
}

private Boolean hasDigitalChanges(digitalStatus) {
    if (!digitalStatus) return false
    return state.lastInputStates.current != digitalStatus
}

private Boolean hasAnalogChanges(analogStatus) {
    if (!analogStatus) return false
    return state.lastAnalogStates.current != analogStatus
}

private def parseAnalogValues(String analogPart) {
    def values = []
    def matcher = (analogPart =~ /(\d+\.\d+)(mA|V)/)
    
    (1..4).each { i ->
        if (matcher.find()) {
            def value = matcher.group(1).toDouble()
            def configuredType = settings?."analogInput${i}Type"
            def actualUnit = matcher.group(2)
            
            if (configuredType == "4-20mA" && actualUnit == "V") {
                value = (value / 10 * 16) + 4
            } else if (configuredType == "0-10V" && actualUnit == "mA") {
                value = ((value - 4) / 16) * 10
            }
            values << value
        } else {
            values << null
        }
    }
    return values
}

private def scaleAnalogValue(channel, rawValue) {
    def minValue = settings?."analogInput${channel}Min"?.toDouble()
    def maxValue = settings?."analogInput${channel}Max"?.toDouble()
    
    if (minValue == null || maxValue == null) {
        return rawValue
    }
    
    def sensorType = settings?."analogInput${channel}Type"
    def inputRange = (sensorType == "4-20mA") ? [4, 20] : [0, 10]
    
    def scaledValue = ((rawValue - inputRange[0]) / (inputRange[1] - inputRange[0])) * (maxValue - minValue) + minValue
    return scaledValue
}

def refresh() {
    sendCommand("00")   // also use "00" for refresh to normalize behavior
    Integer r = (settings?.autoRefresh ?: 60) as Integer
    if (r > 0) runIn(r, "refresh")
}

def sendKeepAlive() {
    if (state.boardstatus == "online" && (settings?.keepaliveEnabled ?: true)) {
        sendCommand("00")
    }
    Integer ka = (settings?.keepaliveInterval ?: 45) as Integer
    runIn(Math.max(15, ka), "sendKeepAlive")
}

def ping() {
    state.pingSentAt = now()
    if (settings?.txtEnable) log.info "Sending ping with command '00'"
    sendCommand("00")
}

def reconnect() {
    log.warn "Manual reconnect requested."
    try { interfaces.rawSocket.close() } catch (ignored) {}
    pauseExecution(500)
    initialize()
}

def reconnectNow() {
    // UI button that simply calls reconnect(), but kept separate for clarity
    log.warn "Reconnect Now button pressed."
    reconnect()
}

def deleteAllChildren() {
    getChildDevices().each { child ->
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (e) {
            log.error "Error deleting child: ${e.message}"
        }
    }
}

def socketStatus(status) {
    // Hubitat may send: "closed", "error: x", etc.
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

private sendCommand(command) {
    try {
        if (settings?.logEnable) log.debug "Sending command: ${command}"
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
    log.warn "Automatically disabling debug logging after 3 minutes"
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    state.logDisableScheduled = false
}
