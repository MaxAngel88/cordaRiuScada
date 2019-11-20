package com.riuscada.server

import com.riuscada.flow.MeasureFlow.Issuer
import com.riuscada.flow.MeasureFlow.Updater
import com.riuscada.flow.CommandFlow.IssuerCommand
import com.riuscada.flow.CommandFlow.UpdaterCommand
import com.riuscada.server.pojo.*
import com.riuscada.state.CommandState
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
import javax.servlet.http.HttpServletRequest

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
    fun getLastMeasures() : ResponseEntity<List<StateAndRef<MeasureState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<MeasureState>().states)
    }

    /**
     * Displays last MeasureStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastMeasureByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasureByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

        val foundHostnameMeasures = proxy.vaultQueryBy<MeasureState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameMeasures)
    }

    /**
     * Displays last MeasureStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getLastMeasureByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasureByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

        val foundMacAddressMeasures = proxy.vaultQueryBy<MeasureState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.macAddress == macAddress }

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
            hostname : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive CONSUMED and UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

        val foundHostnameMeasures = proxy.vaultQueryBy<MeasureState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.hostname == hostname }


        return ResponseEntity.ok(foundHostnameMeasures)
    }

    /**
     * Displays History MeasureStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getHistoryMeasureStateByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryMeasureStateByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive CONSUMED and UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

        val foundHostnameMeasures = proxy.vaultQueryBy<MeasureState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.macAddress == macAddress }


        return ResponseEntity.ok(foundHostnameMeasures)
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

        return try {
            val updateMeasure = proxy.startTrackedFlow(::Updater, measureLinearId, hostname, macAddress, Instant.parse(time), xmlData).returnValue.getOrThrow()
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
    fun getLastCommand() : ResponseEntity<List<StateAndRef<CommandState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<CommandState>().states)
    }

    /**
     * Displays last CommandStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastCommandByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastCommandByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<CommandState>>> {

        // setting the criteria for retrive UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

        val foundHostnameCommands = proxy.vaultQueryBy<CommandState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameCommands)
    }

    /**
     * Displays last CommandStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getLastCommandByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastCommandByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<CommandState>>> {

        // setting the criteria for retrive UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

        val foundMacAddressCommands = proxy.vaultQueryBy<CommandState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.macAddress == macAddress }

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
            hostname : String ) : ResponseEntity<List<StateAndRef<CommandState>>> {

        // setting the criteria for retrive CONSUMED and UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

        val foundHostnameCommands = proxy.vaultQueryBy<CommandState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.hostname == hostname }


        return ResponseEntity.ok(foundHostnameCommands)
    }

    /**
     * Displays History CommandStates that exist in the node's vault for selected macAddress.
     */
    @GetMapping(value = [ "getHistoryCommandStateByMacAddress/{macAddress}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryCommandStateByMacAddress(
            @PathVariable("macAddress")
            macAddress : String ) : ResponseEntity<List<StateAndRef<CommandState>>> {

        // setting the criteria for retrive CONSUMED and UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)

        val foundHostnameCommands = proxy.vaultQueryBy<CommandState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.macAddress == macAddress }


        return ResponseEntity.ok(foundHostnameCommands)
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

}