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
    protected open val logRequests = config.logRequests
    protected open val logResponses = config.logResponses
    protected open val logFullUrl = config.logFullUrl
    protected open val logHeaders = config.logHeaders
    protected open val logBody = config.logBody

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
         * Whether to log request.
         *
         * WARN: request logs may contain sensitive data.
         */
        var logRequests = false

        /**
         * Whether to log responses.
         *
         * WARN: responses logs may contain sensitive data.
         */
        var logResponses = false

        /**
         * Whether to log full request/response urls.
         *
         * WARN: url queries may contain sensitive data.
         */
        var logFullUrl = false

        /**
         * Whether to log request/response headers.
         *
         * WARN: headers may contain sensitive data.
         */
        var logHeaders = false

        /**
         * Whether to log request/response payloads.
         *
         * WARN: payloads may contain sensitive data.
         */
        var logBody = false

        /**
         * Custom request filter. Logs only if any filter returns true.
         */
        fun filter(predicate: (ApplicationCall) -> Boolean) {
            filters.add(predicate)
        }

        /**
         * Filter requests by path prefixes. Logs only for given paths.
         */
        fun filterPath(vararg paths: String) = filter { call ->
            val requestPath = call.request.path().removePrefix(call.application.environment.rootPath)

            paths.any { requestPath == it || requestPath.startsWith(it) }
        }
    }

    protected open fun logPerformance(call: ApplicationCall) {
        val duration = System.currentTimeMillis() - call.attributes[startTimeKey]
        val status = call.response.status()?.value
        val method = call.request.httpMethod.value
        val requestURI = if (logFullUrl) call.request.origin.uri else call.request.path()
        val url = call.request.origin.run { "$scheme://$host:$port$requestURI" }

        log.info("{} ms - {} - {} {}", duration, status, method, url)
    }

    protected open suspend fun logRequest(call: ApplicationCall) {
        log.info(StringBuilder().apply {
            appendln("Received request:")
            val requestURI = if (logFullUrl) call.request.origin.uri else call.request.path()
            appendln(call.request.origin.run { "${method.value} $scheme://$host:$port$requestURI $version" })

            if (logHeaders) {
                call.request.headers.forEach { header, values ->
                    appendln("$header: ${values.firstOrNull()}")
                }
            }

            if (logBody) {
                try {
                    // new line before body as in HTTP request
                    appendln()
                    // have to receive ByteArray for DoubleReceive to work
                    // new line after body because in the log there might be additional info after "log message"
                    appendln(String(call.receive<ByteArray>()))
                } catch (e: RequestAlreadyConsumedException) {
                    log.error("Logging payloads requires DoubleReceive feature to be installed with receiveEntireContent=true", e)
                }
            }
        }.toString())
    }

    protected open fun logResponse(call: ApplicationCall, subject: Any) {
        log.info(StringBuilder().apply {
            appendln("Sent response:")
            appendln("${call.request.httpVersion} ${call.response.status()}")
            if (logHeaders) {
                call.response.headers.allValues().forEach { header, values ->
                    appendln("$header: ${values.firstOrNull()}")
                }
            }
            if (logBody && subject is OutgoingContent.ByteArrayContent) {
                // new line before body as in HTTP response
                appendln()
                // new line after body because in the log there might be additional info after "log message"
                appendln(String(subject.bytes()))
            }
            // do not log warning if  subject is not OutgoingContent.ByteArrayContent
            // as we could possibly spam warnings without any option to disable them
        }.toString())
    }

    protected open fun shouldLog(call: ApplicationCall): Boolean {
        return filters.isEmpty() || filters.any { it(call) }
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
        pipeline.intercept(startTimePhase) {
            call.attributes.put(startTimeKey, System.currentTimeMillis())
        }

        pipeline.intercept(CallId.phase) {
            withCorrelation(call.callId!!) {
                proceed()
                log.debug("Finished call")
            }
        }

        pipeline.sendPipeline.addPhase(responseLoggingPhase)
        pipeline.sendPipeline.intercept(responseLoggingPhase) {
            if (shouldLog(call)) {
                logPerformance(call)
            }
        }

        if (logRequests || logResponses) {
            if (logBody && pipeline.featureOrNull(DoubleReceive) == null) {
                throw IllegalStateException("Logging payloads requires DoubleReceive feature to be installed")
            }
            if (!logBody && !logHeaders && !logFullUrl) {
                log.warn("You have enabled logging of requests/responses but body, full url and headers logging is disabled and there is no information gain")
            }
        }

        if (logRequests) {
            pipeline.intercept(ApplicationCallPipeline.Monitoring) {
                if (shouldLog(call)) {
                    logRequest(call)
                }
            }
        }

        if (logResponses) {
            pipeline.sendPipeline.intercept(responseLoggingPhase) {
                if (shouldLog(call)) {
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
