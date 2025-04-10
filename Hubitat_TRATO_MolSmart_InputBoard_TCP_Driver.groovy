/**
 *  Hubitat - MolSmart - 12+4 Input Board
 *  
 *
 *  Copyright 2025 VH/TRATO
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *        
 *  Version 1.0 - 10/4/2025 - Beta 1.0
 *                 
 *                 
 *                 
 *                 
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
        command "deleteAllChildren"
        command "createChildDevices"

        attribute "channels", "number"
        attribute "connectionStatus", "string"
        attribute "boardStatus", "string"
        attribute "lastUpdate", "string"
    }

    preferences {
        section("Network Settings") {
            input name: "ipAddress", type: "text", title: "IP Address", required: true
            input name: "tcpPort", type: "number", title: "TCP Port", defaultValue: 502, required: true
            input name: "commandDelay", type: "number", title: "Delay between commands (ms)", defaultValue: 100, range: "50..500"
            input name: "checkInterval", type: "number", title: "Connection check interval (seconds)", defaultValue: 90, range: "30..3600"
            input name: "autoRefresh", type: "number", title: "Auto-refresh interval (seconds, 0=disabled)", defaultValue: 60
        }
        
        
        
        section("Debugging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
            input name: "enableNotifications", type: "bool", title: "Enable connection notifications", defaultValue: false
        }
    }
}

def MAX_RETRY_DELAY() { return 300 }

def installed() {
    log.info "12-Channel Input Board Driver installed"
    initialize()
}

def updated() {
    log.info "12-Channel Input Board Driver updated"
    initialize()
}

def initialize() {
    log.info "Initializing 12-Channel Input Board"
    unschedule()
    interfaces.rawSocket.close()
    
    // Initialize state variables
    if (state.lastMessageReceivedAt == null) state.lastMessageReceivedAt = 0
    if (state.retryCount == null) state.retryCount = 0
    if (state.boardstatus == null) state.boardstatus = "offline"
    if (state.inputHoldStatus == null) state.inputHoldStatus = [:] // Track HOLD status for each input
    
    // Establish TCP connection
    try {
        log.debug "Attempting connection to ${settings.ipAddress}:${settings.tcpPort}"
        interfaces.rawSocket.connect(settings.ipAddress, settings.tcpPort.toInteger())
        setBoardStatus("online")
        state.lastMessageReceivedAt = now()
    } catch (e) {
        log.error "Connection failed: ${e.message}"
        setBoardStatus("offline")
        def retryDelay = Math.min(MAX_RETRY_DELAY(), (state.retryCount ?: 0) * 60)
        state.retryCount = (state.retryCount ?: 0) + 1
        runIn(retryDelay, "initialize")
        return
    }
    
    // Schedule regular checks
    runIn(checkInterval, "connectionCheck")
    if (settings.autoRefresh && settings.autoRefresh > 0) {
        runIn(settings.autoRefresh, "refresh")
    }
    
    // Create child devices
    runIn(2, "createChildDevices")
    
    // Initial board discovery
    runIn(1, "discoverBoard")
}

def connectionCheck() {
    def now = now()
    def timeSinceLastMessage = now - (state.lastMessageReceivedAt ?: 0)

    if (timeSinceLastMessage > (checkInterval * 1000)) {
        log.warn "No messages received for ${timeSinceLastMessage / 1000} seconds. Reconnecting..."
        setBoardStatus("offline")
        initialize()
    } else {
        if (state.boardstatus != "online") {
            setBoardStatus("online")
        }
        runIn(checkInterval, "connectionCheck")
    }
}

def setBoardStatus(String status) {
    if (state.c != status) {
        state.boardstatus = status
        sendEvent(name: "boardStatus", value: status)
        sendEvent(name: "connectionStatus", value: status)
        
        if (settings.enableNotifications) {
            if (status == "online") {
                log.info "12-Channel Input Board is now online."
                sendNotificationEvent("12-Channel Input Board is now online.")
            } else if (status == "offline") {
                log.info "12-Channel Input Board is now offline."
                sendNotificationEvent("12-Channel Input Board is now offline.")
            }
        }
    }
}

def discoverBoard() {
    log.info "Discovering board configuration"
    // For this board, we know it has 12 digital and 4 analog inputs
    state.digitalChannels = 12
    state.analogChannels = 4
    sendEvent(name: "channels", value: 16) // 12 digital + 4 analog
}

def createChildDevices() {
    log.info "Creating child devices"
    
    // Create digital input children with HOLD status capability
    (1..12).each { channel ->
        def childDni = "${device.deviceNetworkId}-DI${channel}"
        def child = getChildDevice(childDni)
        
        if (!child) {
            try {
                child = addChildDevice("hubitat", "Generic Component Contact Sensor", childDni, 
                                    [name: "${device.displayName} DI${channel}", 
                                     label: settings."digitalInput${channel}Label" ?: "Digital Input ${channel}",
                                     isComponent: false])
                log.info "Created digital input child device ${childDni}"
                child.sendEvent(name: "contact", value: "unknown")
                child.sendEvent(name: "normalState", value: settings."digitalInput${channel}NormalState" ?: "Open")
                child.sendEvent(name: "holdStatus", value: "inactive") // Initialize HOLD status
            } catch (Exception e) {
                log.error "Error creating child device ${childDni}: ${e.message}"
            }
        }
    }
    
    // Create analog input children (unchanged)
    (1..4).each { channel ->
        def childDni = "${device.deviceNetworkId}-AI${channel}"
        def child = getChildDevice(childDni)
        
        if (!child) {
            try {
                child = addChildDevice("hubitat", "Generic Component Voltage Sensor", childDni,
                                     [name: "${device.displayName} AI${channel}",
                                      label: settings."analogInput${channel}Label" ?: "Analog Input ${channel}",
                                      isComponent: false])
                log.info "Created analog input child device ${childDni}"
                child.sendEvent(name: "voltage", value: 0, unit: "V")
                child.sendEvent(name: "sensorType", value: settings."analogInput${channel}Type" ?: "4-20mA")
                child.sendEvent(name: "minValue", value: settings."analogInput${channel}Min" ?: 0)
                child.sendEvent(name: "maxValue", value: settings."analogInput${channel}Max" ?: 0)
                child.sendEvent(name: "unit", value: settings."analogInput${channel}Unit" ?: "")
            } catch (Exception e) {
                log.error "Error creating child device ${childDni}: ${e.message}"
            }
        }
    }
}

def parse(message) {
    state.lastMessageReceivedAt = now()
    
    def newmsg = hubitat.helper.HexUtils.hexStringToByteArray(message)
    def newmsg2 = new String(newmsg)
    state.lastmessage = newmsg2
     
    if (logEnable) log.debug "Received message: ${newmsg2}"    
    
    processResponse(newmsg2)
}


private def processResponse(String response) {
    def parts = response.split(":")
    if (parts.size() < 6) {
        log.error "Invalid response format: ${response}"
        return
    }
    
    def now = new Date().format("yyyy-MM-dd HH:mm:ss")
    def anyChanges = false
    
    // Process analog inputs (unchanged)
    def analogValues = parseAnalogValues(parts[0])
    (1..4).each { channel ->
        def value = analogValues[channel - 1]
        if (value != null) {
            def childDni = "${device.deviceNetworkId}-AI${channel}"
            def child = getChildDevice(childDni)
            
            if (child) {
                def sensorType = settings."analogInput${channel}Type" ?: "4-20mA"
                def unit = sensorType == "4-20mA" ? "mA" : "V"
                def scaledValue = scaleAnalogValue(channel, value)
                
                child.sendEvent(name: "voltage", value: scaledValue, unit: unit)
                child.sendEvent(name: "value", value: scaledValue)
                child.sendEvent(name: "sensorValue", value: String.format("%.3f", value))
                
                if (settings.txtEnable) log.info "Analog Input ${channel} value: ${String.format("%.3f", value)}${unit}"
                anyChanges = true
            }
        }
    }
    
    // Process digital inputs with enhanced HOLD status tracking
    if (parts[1]?.length() == 12 && parts[5]?.length() >= 12) {
        (1..12).each { channel ->
            def stateChar = parts[1].charAt(channel - 1)
            def changeChar = parts[5].charAt(channel - 1)
            def normalState = settings."digitalInput${channel}NormalState" ?: "Open"
            def currentState = (stateChar == '1') ? (normalState == "Open" ? "open" : "closed") : (normalState == "Open" ? "closed" : "open")
            
            def childDni = "${device.deviceNetworkId}-DI${channel}"
            def child = getChildDevice(childDni)
            
            if (child) {
                def previousState = child.currentValue("contact")
                def currentHoldStatus = changeChar == 'H' ? "active" : "inactive"
                def previousHoldStatus = child.currentValue("holdStatus") ?: "inactive"
                
                // Update contact state if changed
                if (previousState != currentState) {
                    anyChanges = true
                    child.sendEvent(name: "contact", value: currentState)
                    if (settings.txtEnable) log.info "Digital Input ${channel} is now ${currentState}"
                }
                
                // Update HOLD status if changed
                if (currentHoldStatus != previousHoldStatus) {
                    anyChanges = true
                    child.sendEvent(name: "holdStatus", value: currentHoldStatus)
                    if (settings.txtEnable) log.info "Digital Input ${channel} HOLD status: ${currentHoldStatus}"
                    
                    // Update lastChange with timestamp and action type
                    def actionType = currentHoldStatus == "active" ? "Hold" : "Release"
                    child.sendEvent(name: "lastChange", value: "${now} (${actionType})")
                }
                
                // If in HOLD state, ensure we mark the status appropriately
                if (currentHoldStatus == "active") {
                    child.sendEvent(name: "contact", value: "held")
                }
            }
        }
    }
    
    if (anyChanges) {
        sendEvent(name: "lastUpdate", value: now)
    }
}


private def parseAnalogValues(String analogPart) {
    def values = []
    def matcher = (analogPart =~ /(\d+\.\d+)(mA|V)/)
    
    (1..4).each { i ->
        if (matcher.find()) {
            def value = matcher.group(1).toDouble()
            // If the unit doesn't match the configured type, convert the value
            def configuredType = settings."analogInput${i}Type"
            def actualUnit = matcher.group(2)
            
            if (configuredType == "4-20mA" && actualUnit == "V") {
                // Convert 0-10V to 4-20mA (assuming linear relationship)
                value = (value / 10 * 16) + 4
            } else if (configuredType == "0-10V" && actualUnit == "mA") {
                // Convert 4-20mA to 0-10V
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
    def minValue = settings."analogInput${channel}Min"?.toDouble()
    def maxValue = settings."analogInput${channel}Max"?.toDouble()
    
    if (minValue == null || maxValue == null) {
        return rawValue
    }
    
    def sensorType = settings."analogInput${channel}Type"
    def inputRange = (sensorType == "4-20mA") ? [4, 20] : [0, 10]
    
    // Scale the raw value to the configured min/max range
    def scaledValue = ((rawValue - inputRange[0]) / (inputRange[1] - inputRange[0])) * (maxValue - minValue) + minValue
    
    return scaledValue
}

def refresh() {
    sendCommand("GET_STATES")
    if (settings.autoRefresh && settings.autoRefresh > 0) {
        runIn(settings.autoRefresh, "refresh")
    }
}

def reconnect() {
    initialize()
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
    if (status == "closed") {
        setBoardStatus("offline")
    }
}

def uninstalled() {
    interfaces.rawSocket.close()
    deleteAllChildren()
}

private sendCommand(command) {
    try {
        if (settings.logEnable) log.debug "Sending command: ${command}"
        interfaces.rawSocket.sendMessage("${command}\n")
        pauseExecution(settings.commandDelay ?: 100)
    } catch (Exception e) {
        log.error "Command failed: ${e.message}"
        setBoardStatus("offline")
    }
}
