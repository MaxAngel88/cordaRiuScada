package com.riuscada.server

import com.riuscada.flow.CommandFlow.IssuerCommand
import com.riuscada.flow.CommandFlow.UpdaterCommand
import com.riuscada.flow.FlowComputerFlow.FlowComputerIssuer
import com.riuscada.flow.FlowComputerFlow.FlowComputerUpdater
import com.riuscada.flow.ForcedMeasureFlow.ForcedIssuer
import com.riuscada.flow.ForcedMeasureFlow.ForcedUpdater
import com.riuscada.flow.MeasureFlow.Issuer
import com.riuscada.flow.MeasureFlow.Updater
import com.riuscada.schema.CommandSchemaV1
import com.riuscada.schema.FlowComputerSchemaV1
import com.riuscada.schema.ForcedMeasureSchemaV1
import com.riuscada.schema.MeasureSchemaV1
import com.riuscada.server.pojo.*
import com.riuscada.state.CommandState
import com.riuscada.state.FlowComputerState
import com.riuscada.state.ForcedMeasureState
import com.riuscada.state.MeasureState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.*
import net.corda.core.utilities.getOrThrow
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

val SERVICE_NAMES = listOf("Notary", "Network Map Service")

/**
 * Define your API endpoints here.
 */
@CrossOrigin
@RestController
@RequestMapping("/riuscada.com/api/") // The paths for HTTP requests are relative to this base path.
class Controller(rpc: NodeRPCConnection) {

    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    private val myLegalName = rpc.proxy.nodeInfo().legalIdentities.first().name
    private val proxy = rpc.proxy

    /**
     * Returns the node's name.
     */
    @GetMapping(value = [ "me" ], produces = [ APPLICATION_JSON_VALUE ])
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the network map service. These names can be used to look up identities using
     * the identity service.
     */
    @GetMapping(value = [ "peers" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getPeers(): Map<String, List<CordaX500Name>> {
        val nodeInfo = proxy.networkMapSnapshot()
        return mapOf("peers" to nodeInfo
                .map { it.legalIdentities.first().name }
                //filter out myself, notary and eventual network map started by driver
                .filter { it.organisation !in (SERVICE_NAMES + myLegalName.organisation) })
    }

    /**
     *
     * MEASURE API -----------------------------------------------------------------------------------------------
     *
     */

    /**
     * Displays all MeasureStates that exist in the node's vault.
     */
    @GetMapping(value = [ "getLastMeasures" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasures() : ResponseEntity<ResponseCounterPojo> {
        var foundLastMeasureStates = proxy.vaultQueryBy<MeasureState>(
                paging = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000)).states
        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of registered rius", size = foundLastMeasureStates.size, data = foundLastMeasureStates))
    }

    /**
     * Displays last MeasureStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastMeasureByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasureByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {MeasureSchemaV1.PersistentMeasure::hostname.equal(hostname)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(MeasureState::class.java))

        val foundHostnameMeasures = proxy.vaultQueryBy<MeasureState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameMeasures)
    }

    /**
     * Displays last MeasureStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getLastMeasureByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasureByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {MeasureSchemaV1.PersistentMeasure::macAddress.equal(macAddress)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(MeasureState::class.java))

        val foundMacAddressMeasures = proxy.vaultQueryBy<MeasureState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.ok(foundMacAddressMeasures)
    }

    /**
     * Initiates a flow to agree an Measure between two nodes.
     *
     * Once the flow finishes it will have written the Measure to ledger. Both NodeA, NodeB are able to
     * see it when calling /spring/riuscada.com/api/ on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PostMapping(value = [ "issue-measure" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/json" ])
    fun issueMeasure(
            @RequestBody
            issueMeasurePojo : IssueMeasurePojo): ResponseEntity<ResponsePojo> {
        val hostname = issueMeasurePojo.hostname
        val macAddress = issueMeasurePojo.macAddress
        val time = issueMeasurePojo.time
        val xmlData = issueMeasurePojo.xmlData

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(time.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "time cannot be empty", data = null))
        }

        if(xmlData.isNotEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlData must be empty on issue measure", data = null))
        }
        return try {
            val measure = proxy.startTrackedFlow(::Issuer, hostname, macAddress, Instant.parse(time), xmlData).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${measure.linearId.id} committed to ledger.\n", data = measure))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     * Displays History MeasureStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getHistoryMeasureStateByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryMeasureStateByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {MeasureSchemaV1.PersistentMeasure::hostname.equal(hostname)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(MeasureState::class.java))


        val foundHostnameMeasures = proxy.vaultQueryBy<MeasureState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }


        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of measure state for $hostname", size = foundHostnameMeasures.size, data = foundHostnameMeasures))
    }

    /**
     * Displays History MeasureStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getHistoryMeasureStateByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryMeasureStateByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {MeasureSchemaV1.PersistentMeasure::macAddress.equal(macAddress)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(MeasureState::class.java))

        val foundMacaddressMeasures = proxy.vaultQueryBy<MeasureState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of measure state for $macAddress", size = foundMacaddressMeasures.size, data = foundMacaddressMeasures))
    }

    /***
     *
     * Update Measure
     *
     */
    @PostMapping(value = [ "update-measure" ], consumes = [APPLICATION_JSON_VALUE], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/json" ])
    fun updateMeasure(
            @RequestBody
            updateMeasurePojo: UpdateMeasurePojo): ResponseEntity<ResponsePojo> {

        val measureLinearId = updateMeasurePojo.measureLinearId
        val hostname = updateMeasurePojo.hostname
        val macAddress = updateMeasurePojo.macAddress
        val time = updateMeasurePojo.time
        val xmlData = updateMeasurePojo.xmlData
        val uuid = updateMeasurePojo.uuid

        if(measureLinearId.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "measureLinearId cannot be empty", data = null))
        }

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(time.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "time cannot be empty", data = null))
        }

        if(xmlData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlData cannot be empty", data = null))
        }

        if(uuid.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "uuid cannot be empty", data = null))
        }

        return try {
            val updateMeasure = proxy.startTrackedFlow(::Updater, measureLinearId, hostname, macAddress, Instant.parse(time), xmlData, uuid).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Measure with id: $measureLinearId update correctly. " + "New MeasureState with id: ${updateMeasure.linearId.id}  created.. ledger updated.\n", data = updateMeasure))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     *
     * COMMAND API -----------------------------------------------------------------------------------------------
     *
     */

    /**
     * Displays all CommandStates that exist in the node's vault.
     */
    @GetMapping(value = [ "getLastCommands" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastCommand() : ResponseEntity<ResponseCounterPojo> {
        var foundCommandStates = proxy.vaultQueryBy<CommandState>(
                paging = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000)).states
        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of riu with command state", size = foundCommandStates.size, data = foundCommandStates))
    }

    /**
     * Displays last CommandStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastCommandByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastCommandByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<CommandState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {CommandSchemaV1.PersistentCommand::hostname.equal(hostname)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(CommandState::class.java))

        val foundHostnameCommands = proxy.vaultQueryBy<CommandState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameCommands)
    }

    /**
     * Displays last CommandStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getLastCommandByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastCommandByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<CommandState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {CommandSchemaV1.PersistentCommand::macAddress.equal(macAddress)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(CommandState::class.java))

        val foundMacAddressCommands = proxy.vaultQueryBy<CommandState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.ok(foundMacAddressCommands)
    }

    /**
     * Initiates a flow to agree an Command between two nodes.
     *
     * Once the flow finishes it will have written the Command to ledger. Both NodeA, NodeB are able to
     * see it when calling /spring/riuscada.com/api/ on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PostMapping(value = [ "issue-command" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/json" ])
    fun issueCommand(
            @RequestBody
            issueCommandPojo : IssueCommandPojo): ResponseEntity<ResponsePojo> {
        val hostname = issueCommandPojo.hostname
        val macAddress = issueCommandPojo.macAddress
        val time = issueCommandPojo.time
        val xmlCommandData = issueCommandPojo.xmlCommandData
        val status = issueCommandPojo.status

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(time.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "time cannot be empty", data = null))
        }

        if(xmlCommandData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlCommandData cannot be empty on issue command", data = null))
        }

        if(status.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "status cannot be empty", data = null))
        }

        return try {
            val command = proxy.startTrackedFlow(::IssuerCommand, hostname, macAddress, Instant.parse(time), xmlCommandData, status).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${command.linearId.id} committed to ledger.\n", data = command))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     * Displays History CommandStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getHistoryCommandStateByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryCommandStateByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {CommandSchemaV1.PersistentCommand::hostname.equal(hostname)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(CommandState::class.java))

        val foundHostnameCommands = proxy.vaultQueryBy<CommandState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of command state for $hostname", size = foundHostnameCommands.size, data = foundHostnameCommands))
    }

    /**
     * Displays History CommandStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getHistoryCommandStateByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryCommandStateByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {CommandSchemaV1.PersistentCommand::macAddress.equal(macAddress)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(CommandState::class.java))

        val foundMacaddressCommands = proxy.vaultQueryBy<CommandState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of command state for $macAddress", size = foundMacaddressCommands.size, data = foundMacaddressCommands))
    }

    /***
     *
     * Update Command
     *
     */
    @PostMapping(value = [ "update-command" ], consumes = [APPLICATION_JSON_VALUE], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/json" ])
    fun updateCommand(
            @RequestBody
            updateCommandPojo: UpdateCommandPojo): ResponseEntity<ResponsePojo> {

        val commandLinearId = updateCommandPojo.commandLinearId
        val hostname = updateCommandPojo.hostname
        val macAddress = updateCommandPojo.macAddress
        val time = updateCommandPojo.time
        val xmlCommandData = updateCommandPojo.xmlCommandData
        val status = updateCommandPojo.status

        if(commandLinearId.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "commandLinearId cannot be empty", data = null))
        }

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(time.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "time cannot be empty", data = null))
        }

        if(xmlCommandData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlCommandData cannot be empty", data = null))
        }

        if(status.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "status cannot be empty", data = null))
        }

        return try {
            val updateCommand = proxy.startTrackedFlow(::UpdaterCommand, commandLinearId, hostname, macAddress, Instant.parse(time), xmlCommandData, status).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Command with id: $commandLinearId update correctly. " + "New CommandState with id: ${updateCommand.linearId.id}  created.. ledger updated.\n", data = updateCommand))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     *
     * FORCED MEASURE API -----------------------------------------------------------------------------------------------
     *
     */

    /**
     * Displays all ForcedMeasureStates that exist in the node's vault.
     */
    @GetMapping(value = [ "getLastForcedMeasures" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastForcedMeasures() : ResponseEntity<ResponseCounterPojo> {
        var foundForcedMeasures = proxy.vaultQueryBy<ForcedMeasureState>(paging = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000)).states
        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of riu with forced measure state", size = foundForcedMeasures.size, data = foundForcedMeasures))
    }

    /**
     * Displays last ForcedMeasureStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastForcedMeasureByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastForcedMeasureByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<ForcedMeasureState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {ForcedMeasureSchemaV1.PersistentForcedMeasure::hostname.equal(hostname)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(ForcedMeasureState::class.java))

        val foundHostnameForcedMeasures = proxy.vaultQueryBy<ForcedMeasureState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameForcedMeasures)
    }

    /**
     * Displays last ForcedMeasureStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getLastForcedMeasureByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastForcedMeasureByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<ForcedMeasureState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {ForcedMeasureSchemaV1.PersistentForcedMeasure::macAddress.equal(macAddress)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(ForcedMeasureState::class.java))

        val foundMacAddressForcedMeasures = proxy.vaultQueryBy<ForcedMeasureState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.ok(foundMacAddressForcedMeasures)
    }

    /**
     * Initiates a flow to agree an ForcedMeasure between two nodes.
     *
     * Once the flow finishes it will have written the Measure to ledger. Both NodeA, NodeB are able to
     * see it when calling /spring/riuscada.com/api/ on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PostMapping(value = [ "issue-forced-measure" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/json" ])
    fun issueForcedMeasure(
            @RequestBody
            issueForcedMeasurePojo : IssueFocedMeasurePojo): ResponseEntity<ResponsePojo> {
        val hostname = issueForcedMeasurePojo.hostname
        val macAddress = issueForcedMeasurePojo.macAddress
        val xmlData = issueForcedMeasurePojo.xmlData
        val startTime = issueForcedMeasurePojo.startTime
        val endTime = issueForcedMeasurePojo.endTime

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }


        if(xmlData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlData cannot be empty on issue forced measure", data = null))
        }

        if(startTime.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "startTime cannot be empty", data = null))
        }

        if(endTime.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "endTime cannot be empty", data = null))
        }

        return try {
            val forcedMeasure = proxy.startTrackedFlow(::ForcedIssuer, hostname, macAddress, xmlData, Instant.parse(startTime), Instant.parse(endTime)).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${forcedMeasure.linearId.id} committed to ledger.\n", data = forcedMeasure))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     * Displays History ForcedMeasureStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getHistoryForcedMeasureStateByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryForcedMeasureStateByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {ForcedMeasureSchemaV1.PersistentForcedMeasure::hostname.equal(hostname)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(ForcedMeasureState::class.java))

        val foundHostnameForcedMeasures = proxy.vaultQueryBy<ForcedMeasureState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of forced measure state for $hostname", size = foundHostnameForcedMeasures.size, data = foundHostnameForcedMeasures))
    }

    /**
     * Displays History ForcedMeasureStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getHistoryForcedMeasureStateByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryForcedMeasureStateByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {ForcedMeasureSchemaV1.PersistentForcedMeasure::macAddress.equal(macAddress)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(ForcedMeasureState::class.java))

        val foundMacaddressForcedMeasures = proxy.vaultQueryBy<ForcedMeasureState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of forced measure state for $macAddress", size = foundMacaddressForcedMeasures.size, data = foundMacaddressForcedMeasures))
    }

    /***
     *
     * Update ForcedMeasure
     *
     */
    @PostMapping(value = [ "update-forced-measure" ], consumes = [APPLICATION_JSON_VALUE], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/json" ])
    fun updateForcedMeasure(
            @RequestBody
            updateForcedMeasurePojo: UpdateForcedMeasurePojo): ResponseEntity<ResponsePojo> {

        val forcedMeasureLinearId = updateForcedMeasurePojo.forcedMeasureLinearId
        val hostname = updateForcedMeasurePojo.hostname
        val macAddress = updateForcedMeasurePojo.macAddress
        val xmlData = updateForcedMeasurePojo.xmlData
        val startTime = updateForcedMeasurePojo.startTime
        val endTime = updateForcedMeasurePojo.endTime

        if(forcedMeasureLinearId.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "forcedMeasureLinearId cannot be empty", data = null))
        }

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(xmlData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlData cannot be empty", data = null))
        }

        if(startTime.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "startTime cannot be empty", data = null))
        }

        if(endTime.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "endTime cannot be empty", data = null))
        }

        return try {
            val updateForcedMeasure = proxy.startTrackedFlow(::ForcedUpdater, forcedMeasureLinearId, hostname, macAddress, xmlData, Instant.parse(startTime), Instant.parse(endTime)).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Measure with id: $forcedMeasureLinearId update correctly. " + "New ForcedMeasureState with id: ${updateForcedMeasure.linearId.id}  created.. ledger updated.\n", data = updateForcedMeasure))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     *
     * FLOW COMPUTER API -----------------------------------------------------------------------------------------------
     *
     */

    /**
     * Displays all FlowComputerStates that exist in the node's vault.
     */
    @GetMapping(value = [ "getLastFlowComputer" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastFlowComputer() : ResponseEntity<ResponseCounterPojo> {
        var foundFlowComputerState = proxy.vaultQueryBy<FlowComputerState>(paging = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000)).states
        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of riu with flow computer state", size = foundFlowComputerState.size, data = foundFlowComputerState))
    }

    /**
     * Displays last FlowComputerStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastFlowComputerByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastFlowComputerByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<FlowComputerState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {FlowComputerSchemaV1.PersistentFlowComputer::hostname.equal(hostname)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(FlowComputerState::class.java))

        val foundHostnameFlowComputer = proxy.vaultQueryBy<FlowComputerState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameFlowComputer)
    }

    /**
     * Displays last FlowComputerStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getLastFlowComputerByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastFlowComputerByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<FlowComputerState>>> {

        // setting the criteria for retrive UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {FlowComputerSchemaV1.PersistentFlowComputer::macAddress.equal(macAddress)}, status = Vault.StateStatus.UNCONSUMED, contractStateTypes = setOf(FlowComputerState::class.java))

        val foundMacAddressFlowComputer = proxy.vaultQueryBy<FlowComputerState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.ok(foundMacAddressFlowComputer)
    }

    /**
     * Initiates a flow to agree an FlowComputer between two nodes.
     *
     * Once the flow finishes it will have written the Measure to ledger. Both NodeA, NodeB are able to
     * see it when calling /spring/riuscada.com/api/ on their respective nodes.
     *
     * This end-point takes a Party name parameter as part of the path. If the serving node can't find the other party
     * in its network map cache, it will return an HTTP bad request.
     *
     * The flow is invoked asynchronously. It returns a future when the flow's call() method returns.
     */
    @PostMapping(value = [ "issue-flow-computer" ], produces = [ APPLICATION_JSON_VALUE ], headers = [ "Content-Type=application/json" ])
    fun issueFlowComputer(
            @RequestBody
            issueFlowComputerPojo : IssueFlowComputerPojo): ResponseEntity<ResponsePojo> {
        val hostname = issueFlowComputerPojo.hostname
        val macAddress = issueFlowComputerPojo.macAddress
        val binaryData = issueFlowComputerPojo.binaryData

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()){
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(binaryData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "binaryData cannot be empty on issue flow computer", data = null))
        }

        return try {
            val flowComputer = proxy.startTrackedFlow(::FlowComputerIssuer, hostname, macAddress, binaryData).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${flowComputer.linearId.id} committed to ledger.\n", data = flowComputer))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    /**
     * Displays History FlowComputerStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getHistoryFlowComputerStateByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryFlowComputerStateByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for hostname
        var hostnameCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {FlowComputerSchemaV1.PersistentFlowComputer::hostname.equal(hostname)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(FlowComputerState::class.java))

        val foundHostnameFlowComputer = proxy.vaultQueryBy<FlowComputerState>(
                hostnameCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.hostname == hostname }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of flow computer state for $hostname", size = foundHostnameFlowComputer.size, data = foundHostnameFlowComputer))
    }

    /**
     * Displays History FlowComputerStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getHistoryFlowComputerStateByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryFlowComputerStateByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<ResponseCounterPojo> {

        // setting the criteria for retrive CONSUMED - UNCONSUMED state AND filter it for macAddress
        var macAddressCriteria : QueryCriteria = QueryCriteria.VaultCustomQueryCriteria(expression = builder {FlowComputerSchemaV1.PersistentFlowComputer::macAddress.equal(macAddress)}, status = Vault.StateStatus.ALL, contractStateTypes = setOf(FlowComputerState::class.java))

        val foundMacaddressFlowComputer = proxy.vaultQueryBy<FlowComputerState>(
                macAddressCriteria,
                PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = 1000),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states
                //.filter { it.state.data.macAddress == macAddress }

        return ResponseEntity.status(HttpStatus.OK).body(ResponseCounterPojo(outcome = "SUCCESS", message = "Number of flow computer state for $macAddress", size = foundMacaddressFlowComputer.size, data = foundMacaddressFlowComputer))
    }

    /***
     *
     * Update FlowComputer
     *
     */
    @PostMapping(value = [ "update-flow-computer" ], consumes = [APPLICATION_JSON_VALUE], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/json" ])
    fun updateFlowComputer(
            @RequestBody
            updateFlowComputerPojo: UpdateFlowComputerPojo): ResponseEntity<ResponsePojo> {

        val flowComputerLinearId = updateFlowComputerPojo.flowComputerLinearId
        val hostname = updateFlowComputerPojo.hostname
        val macAddress = updateFlowComputerPojo.macAddress
        val binaryData = updateFlowComputerPojo.binaryData

        if(flowComputerLinearId.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "flowComputerLinearId cannot be empty", data = null))
        }

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(macAddress.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "macAddress cannot be empty", data = null))
        }

        if(binaryData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "binaryData cannot be empty", data = null))
        }

        return try {
            val updateFlowComputer = proxy.startTrackedFlow(::FlowComputerUpdater, flowComputerLinearId, hostname, macAddress, binaryData).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "FlowComputer with id: $flowComputerLinearId update correctly. " + "New FlowComputerState with id: ${updateFlowComputer.linearId.id}  created.. ledger updated.\n", data = updateFlowComputer))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

}