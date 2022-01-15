package com.korrit.kotlin.ktor.features.logging

import com.koriit.kotlin.slf4j.logger
import com.koriit.kotlin.slf4j.mdc.correlation.withCorrelation
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
import io.ktor.http.charset
import io.ktor.request.RequestAlreadyConsumedException
import io.ktor.request.httpMethod
import io.ktor.request.httpVersion
import io.ktor.request.path
import io.ktor.request.receive
import io.ktor.routing.Route
import io.ktor.routing.Routing.Feature.RoutingCallStarted
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.lang.Exception

/**
 * Logging feature. Allows logging performance, requests and responses.
 */
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
        log.info(
            StringBuilder().apply {
                appendLine("Received request:")
                val requestURI = if (logFullUrl) call.request.origin.uri else call.request.path()
                appendLine(call.request.origin.run { "${method.value} $scheme://$host:$port$requestURI $version" })

                if (logHeaders) {
                    call.request.headers.forEach { header, values ->
                        appendLine("$header: ${values.firstOrNull()}")
                    }
                }

                if (logBody) {
                    try {
                        // empty line before body as in HTTP request
                        appendLine()
                        // have to receive ByteArray for DoubleReceive to work
                        append(String(call.receive<ByteArray>()))
                        // new line after body because in the log there might be additional info after "log message"
                        // and we don't want it to be mixed with logged body
                        appendLine()
                    } catch (e: RequestAlreadyConsumedException) {
                        log.error("Logging payloads requires DoubleReceive feature to be installed with receiveEntireContent=true", e)
                    }
                }
            }.toString()
        )
    }

    protected open suspend fun logResponse(pipeline: PipelineContext<Any, ApplicationCall>): Any {
        if (logBody) {
            return logResponseWithBody(pipeline)
        }

        // Since we are not logging response body we can log immediately and continue pipeline normally
        log.info(
            StringBuilder().apply {
                appendResponse(pipeline.call)
            }.toString()
        )

        return pipeline.subject
    }

    /**
     * To log response payload we need to duplicate response stream
     * which is why this function returns a new pipeline subject to proceed with.
     */
    protected open suspend fun logResponseWithBody(pipeline: PipelineContext<Any, ApplicationCall>): Any {
        @Suppress("TooGenericExceptionCaught") // intended
        try {
            // logging a response body is harder than logging a request body because
            // there is no public api for observing response body stream or something like DoubleReceive for requests
            val (observer, observed) = pipeline.observe()
            val charset = observed.contentType?.charset() ?: Charsets.UTF_8
            // launch a coroutine that will eventually log the response once it is fully written
            pipeline.launch(Dispatchers.Unconfined) {
                val responseBody = observer.readRemaining().readText(charset = charset)

                log.info(
                    StringBuilder().apply {
                        appendResponse(pipeline.call)
                        // empty line before body as in HTTP response
                        appendLine()
                        append(responseBody)
                        // new line after body because in the log there might be additional info after "log message"
                        // and we don't want it to be mixed with logged body
                        appendLine()
                    }.toString()
                )
            }
            return observed
        } catch (e: Exception) {
            log.warn(e.message, e)
            return pipeline.subject
        }
    }

    protected open fun StringBuilder.appendResponse(call: ApplicationCall) {
        appendLine("Sent response:")
        appendLine("${call.request.httpVersion} ${call.response.status()}")
        if (logHeaders) {
            call.response.headers.allValues().forEach { header, values ->
                appendLine("$header: ${values.firstOrNull()}")
            }
        }
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
                throw IllegalStateException("Logging request payloads requires DoubleReceive feature to be installed")
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
                    proceedWith(logResponse(this))
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
