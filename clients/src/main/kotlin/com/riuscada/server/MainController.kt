package com.riuscada.server

import com.riuscada.flow.MeasureFlow.Issuer
import com.riuscada.flow.MeasureFlow.Updater
import com.riuscada.server.pojo.IssueMeasurePojo
import com.riuscada.server.pojo.ResponsePojo
import com.riuscada.server.pojo.UpdateMeasurePojo
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
    fun getLastMeasure() : ResponseEntity<List<StateAndRef<MeasureState>>> {
        return ResponseEntity.ok(proxy.vaultQueryBy<MeasureState>().states)
    }

    /**
     * Displays last MeasureStates that exist in the node's vault for selected hostname.
     */
    @GetMapping(value = [ "getLastMeasureByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getLastMeasureByHostname(
            @PathVariable("hostname")
            hostname : String ) : ResponseEntity<List<StateAndRef<MeasureState>>> {

        // setting the criteria for retrive CONSUMED and UNCONSUMED state from VAULT
        var criteria : QueryCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)

        val foundHostnameMeasures = proxy.vaultQueryBy<MeasureState>(
                criteria,
                PageSpecification(1, MAX_PAGE_SIZE),
                Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.RECORDED_TIME), Sort.Direction.DESC)))
        ).states.filter { it.state.data.hostname == hostname }

        return ResponseEntity.ok(foundHostnameMeasures)
    }

    /**
     * Initiates a flow to agree an Measure between two nodes.
     *
     * Once the flow finishes it will have written the Message to ledger. Both NodeA, NodeB are able to
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
        val time = issueMeasurePojo.time
        val xmlData = issueMeasurePojo.xmlData

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(time.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "time cannot be empty", data = null))
        }

        if(xmlData.isNotEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlData must be empty on issue measure", data = null))
        }
        return try {
            val measure = proxy.startTrackedFlow(::Issuer, hostname, Instant.parse(time), xmlData).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Transaction id ${measure.linearId.id} committed to ledger.\n", data = measure))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

    @GetMapping(value = [ "getHistoryByHostname/{hostname}" ], produces = [ APPLICATION_JSON_VALUE ])
    fun getHistoryByHostname(
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

    /***
     *
     * Update Measure
     *
     */
    @PostMapping(value = [ "update-measure" ], consumes = [APPLICATION_JSON_VALUE], produces = [ APPLICATION_JSON_VALUE], headers = [ "Content-Type=application/json" ])
    fun updateMeasure(
            @RequestBody
            updateMeasureJson: UpdateMeasurePojo): ResponseEntity<ResponsePojo> {

        val measureLinearId = updateMeasureJson.measureLinearId
        var hostname = updateMeasureJson.hostname
        val time = updateMeasureJson.time
        val xmlData = updateMeasureJson.xmlData

        if(measureLinearId.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "measureLinearId cannot be empty", data = null))
        }

        if(hostname.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "hostname cannot be empty", data = null))
        }

        if(time.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "time cannot be empty", data = null))
        }

        if(xmlData.isEmpty()) {
            return ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = "xmlData cannot be empty", data = null))
        }

        return try {
            val updateMeasure = proxy.startTrackedFlow(::Updater, measureLinearId, hostname, Instant.parse(time), xmlData).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body(ResponsePojo(outcome = "SUCCESS", message = "Measure with id: $measureLinearId update correctly. " + "New MeasureState with id: ${updateMeasure.linearId.id}  created.. ledger updated.\n", data = updateMeasure))
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().body(ResponsePojo(outcome = "ERROR", message = ex.message!!, data = null))
        }
    }

}