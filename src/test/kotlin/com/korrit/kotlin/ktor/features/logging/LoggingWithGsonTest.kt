package com.korrit.kotlin.ktor.features.logging

import ch.qos.logback.classic.Level.DEBUG
import com.google.gson.Gson
import com.koriit.kotlin.slf4j.logger
import com.koriit.kotlin.slf4j.mdc.correlation.correlateThread
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallId
import io.ktor.features.ContentNegotiation
import io.ktor.features.DoubleReceive
import io.ktor.gson.gson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

internal class LoggingWithGsonTest {

    private val testLogger = spyk(logger {})
    private val gson = Gson()

    private data class SampleRequest(val field: String, val innerObject: SampleRequest? = null)

    init {
        correlateThread()
        testLogger as ch.qos.logback.classic.Logger
        testLogger.level = DEBUG
    }

    @Test
    fun `Should log body, full url and headers with Gson`() {
        val server = testServer {
            logRequests = true
            logResponses = true
            logFullUrl = true
            logBody = true
            logHeaders = true
        }

        server.start()

        val reqeust = SampleRequest(field = "value", innerObject = SampleRequest("value2"))

        val apiCall = server.handleRequest(Post, "/api?queryParam=true") {
            addHeader("My-Header", "My-Value")
            addHeader("Content-Type", "application/json")
            setBody(gson.toJson(reqeust))
        }

        val payloads = mutableListOf<String>()

        verify(exactly = 2) {
            testLogger.info(
                withArg {
                    payloads.add(it)
                }
            )
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        Assertions.assertEquals("""{"field":"value","innerObject":{"field":"value2"}}""", apiCall.response.content)

        val loggedRequest = payloads[0]
        assertTrue(loggedRequest.contains("POST"))
        assertTrue(loggedRequest.contains("/api?queryParam=true"))
        assertTrue(loggedRequest.contains("My-Header"))
        assertTrue(loggedRequest.contains("My-Value"))
        assertTrue(loggedRequest.contains("""{"field":"value","innerObject":{"field":"value2"}}"""))

        val loggedResponse = payloads[1]
        assertTrue(loggedResponse.contains("200 OK"))
        assertTrue(loggedResponse.contains("""{"field":"value","innerObject":{"field":"value2"}}"""))

        server.stop(0, 0)
    }

    private fun testServer(
        installCallId: Boolean = true,
        installDoubleReceive: Boolean = true,
        rootPath: String = "",
        configureLogging: Logging.Configuration.() -> Unit = {}
    ): TestApplicationEngine {
        return TestApplicationEngine(
            applicationEngineEnvironment {
                this.rootPath = rootPath
                module {
                    if (installCallId) {
                        install(CallId) {
                            header(HttpHeaders.XRequestId)
                            generate { UUID.randomUUID().toString() }
                            verify { it.isNotBlank() }
                        }
                    }
                    if (installDoubleReceive) {
                        install(DoubleReceive) {
                            receiveEntireContent = true
                        }
                    }

                    install(Logging) {
                        logger = testLogger
                        configureLogging()
                    }

                    install(ContentNegotiation) {
                        gson {}
                    }

                    routing {
                        post("/api") {
                            val body: SampleRequest = call.receive()
                            call.respond(body)
                        }
                    }
                }
            }
        )
    }
}
