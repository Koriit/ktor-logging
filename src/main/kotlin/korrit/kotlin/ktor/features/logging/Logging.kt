package korrit.kotlin.ktor.features.logging

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.ApplicationFeature
import io.ktor.application.ApplicationStopped
import io.ktor.application.call
import io.ktor.application.featureOrNull
import io.ktor.features.CallId
import io.ktor.features.DoubleReceive
import io.ktor.features.callId
import io.ktor.features.origin
import io.ktor.http.content.OutgoingContent
import io.ktor.request.RequestAlreadyConsumedException
import io.ktor.request.httpMethod
import io.ktor.request.httpVersion
import io.ktor.request.path
import io.ktor.request.receive
import io.ktor.routing.Route
import io.ktor.routing.Routing.Feature.RoutingCallStarted
import io.ktor.util.AttributeKey
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.pipeline.PipelinePhase
import koriit.kotlin.slf4j.logger
import koriit.kotlin.slf4j.mdc.correlation.withCorrelation
import org.slf4j.Logger

/**
 * Logging feature. Allows logging performance, requests and responses.
 */
@KtorExperimentalAPI
open class Logging(config: Configuration) {

    protected open val filters: List<(ApplicationCall) -> Boolean> = config.filters
    protected open val logPayloads: Boolean = config.logPayloads

    private val log: Logger = config.logger ?: logger {}

    /**
     * Logging feature config.
     */
    open class Configuration {
        internal val filters = mutableListOf<(ApplicationCall) -> Boolean>()

        /**
         * Custom logger object.
         */
        var logger: Logger? = null

        /**
         * Whether to log request/response payloads.
         *
         * WARN: payloads may contain sensitive data.
         */
        var logPayloads: Boolean = false

        /**
         * Custom request filter.
         */
        fun filter(predicate: (ApplicationCall) -> Boolean) {
            filters.add(predicate)
        }

        /**
         * Filter requests by path prefixes.
         */
        fun filterPath(vararg paths: String) = filter {
            it.request.path().run {
                paths.any { startsWith(it) }
            }
        }
    }

    protected open fun logPerformance(call: ApplicationCall) {
        val duration = System.currentTimeMillis() - call.attributes[startTimeKey]
        val status = call.response.status()?.value
        val method = call.request.httpMethod.value
        val url = call.request.origin.run { "$scheme://$host:$port$uri" }

        log.info("{} ms - {} - {} {}", duration, status, method, url)
    }

    protected open suspend fun logRequest(call: ApplicationCall) {
        try {
            val log = StringBuilder().apply {
                appendln("Received request:")
                appendln(call.request.origin.run { "${method.value} $scheme://$host:$port$uri $version" })
                call.request.headers.forEach { header, values ->
                    appendln("$header: ${values.firstOrNull()}")
                }
                appendln()
                // Have to receive ByteArray for DoubleReceive to work
                appendln(String(call.receive<ByteArray>()))
            }
            this.log.info(log.toString())
        } catch (e: RequestAlreadyConsumedException) {
            log.error("Logging payloads requires DoubleReceive feature to be installed with receiveEntireContent=true", e)
        }
    }

    protected open fun logResponse(call: ApplicationCall, subject: Any) {
        if (subject is OutgoingContent.ByteArrayContent) {
            val log = StringBuilder().apply {
                appendln("Sent response:")
                appendln("${call.request.httpVersion} ${call.response.status()}")
                call.response.headers.allValues().forEach { header, values ->
                    appendln("$header: ${values.firstOrNull()}")
                }
                appendln()
                appendln(String(subject.bytes()))
            }
            this.log.info(log.toString())

        } else {
            log.warn("Cannot log response of type: ${subject.javaClass.simpleName}")
        }
    }

    /**
     * Feature installation.
     */
    protected open fun install(pipeline: Application) {
        pipeline.featureOrNull(CallId) ?: throw IllegalStateException("Logging requires CallId feature to be installed")

        pipeline.environment.monitor.subscribe(RoutingCallStarted) {
            it.attributes.computeIfAbsent(routeKey) { it.route }
        }
        pipeline.environment.monitor.subscribe(ApplicationStopped) {
            log.info("Server stopped")
        }

        pipeline.insertPhaseBefore(CallId.phase, startTimePhase)
        pipeline.sendPipeline.addPhase(responseLoggingPhase)

        pipeline.intercept(startTimePhase) {
            call.attributes.put(startTimeKey, System.currentTimeMillis())
        }

        pipeline.intercept(CallId.phase) {
            withCorrelation(call.callId!!) {
                proceed()
                log.debug("Finished call")
            }
        }

        pipeline.sendPipeline.intercept(responseLoggingPhase) {
            if (filters.isEmpty() || filters.any { it(call) }) {
                logPerformance(call)
            }
        }

        if (logPayloads) {
            pipeline.featureOrNull(DoubleReceive) ?: throw IllegalStateException("Logging payloads requires DoubleReceive feature to be installed")

            pipeline.intercept(ApplicationCallPipeline.Monitoring) {
                if (filters.isEmpty() || filters.any { it(call) }) {
                    logRequest(call)
                }
            }

            pipeline.sendPipeline.intercept(responseLoggingPhase) {
                if (filters.isEmpty() || filters.any { it(call) }) {
                    logResponse(call, subject)
                }
            }
        }
    }

    /**
     * Feature installation.
     */
    companion object Feature : ApplicationFeature<Application, Configuration, Logging> {

        override val key = AttributeKey<Logging>("Logging Feature")

        /**
         * Attribute key mapping to request duration start timestamp.
         */
        val startTimeKey = AttributeKey<Long>("Start Time")

        /**
         * Attribute key mapping to matched [Route].
         */
        val routeKey = AttributeKey<Route>("Route")

        /**
         * Phase when request duration starts counting.
         */
        val startTimePhase = PipelinePhase("StartTime")

        /**
         * Phase when response is logged.
         */
        val responseLoggingPhase = PipelinePhase("ResponseLogging")

        override fun install(pipeline: Application, configure: Configuration.() -> Unit): Logging {
            val configuration = Configuration().apply(configure)

            return Logging(configuration).apply { install(pipeline) }
        }
    }
}
