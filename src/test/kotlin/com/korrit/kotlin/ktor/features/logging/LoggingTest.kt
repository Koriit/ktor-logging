package com.korrit.kotlin.ktor.features.logging

import ch.qos.logback.classic.Level
import com.koriit.kotlin.slf4j.logger
import com.koriit.kotlin.slf4j.mdc.correlation.correlateThread
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallId
import io.ktor.features.DoubleReceive
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalStateException
import java.util.UUID

internal class LoggingTest {

    private val testLogger = spyk(logger {})

    init {
        correlateThread()
        testLogger as ch.qos.logback.classic.Logger
        testLogger.level = Level.DEBUG
    }

    @Test
    fun `Should log request, response and performance`() {
        val server = testServer {
            logRequests = true
            logResponses = true
        }

        server.start()
        server.handleRequest(Get, "/api")

        verify(exactly = 2) {
            testLogger.info(any())
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should log for filtered paths`() {
        val server = testServer {
            logRequests = true
            logResponses = true
            filterPath("/api")
        }

        server.start()
        server.handleRequest(Get, "/api")

        verify(exactly = 2) {
            testLogger.info(any())
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should log for filtered paths with root path`() {
        val server = testServer(rootPath = "/service") {
            logRequests = true
            logResponses = true
            filterPath("/api")
        }

        server.start()
        server.handleRequest(Get, "/service/api")

        verify(exactly = 2) {
            testLogger.info(any())
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should not log for filtered out paths`() {
        val server = testServer {
            logRequests = true
            logResponses = true
            filterPath("/other")
        }

        server.start()
        server.handleRequest(Get, "/api")

        verify(exactly = 0) {
            testLogger.info(any())
        }
        verify(exactly = 0) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should log request and performance`() {
        val server = testServer {
            logRequests = true
        }

        server.start()
        server.handleRequest(Get, "/api")

        verify(exactly = 1) {
            testLogger.info(any())
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should log response and performance`() {
        val server = testServer {
            logResponses = true
        }

        server.start()
        server.handleRequest(Get, "/api")

        verify(exactly = 1) {
            testLogger.info(any())
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should always log performance`() {
        val server = testServer {}

        server.start()
        server.handleRequest(Get, "/api")

        verify(exactly = 0) {
            testLogger.info(any())
        }
        verify(exactly = 1) {
            testLogger.info(any(), *anyVararg())
        }

        server.stop(0, 0)
    }

    @Test
    fun `Should log body, full url and headers`() {
        val server = testServer {
            logRequests = true
            logResponses = true
            logFullUrl = true
            logBody = true
            logHeaders = true
        }

        server.start()
        server.handleRequest(Post, "/api?queryParam=true") {
            addHeader("My-Header", "My-Value")
            setBody("SOME_BODY")
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

        val request = payloads[0]
        assertTrue(request.contains("POST"))
        assertTrue(request.contains("/api?queryParam=true"))
        assertTrue(request.contains("My-Header"))
        assertTrue(request.contains("My-Value"))
        assertTrue(request.contains("SOME_BODY"))

        val response = payloads[1]
        assertTrue(response.contains("200 OK"))
        assertTrue(request.contains("/api?queryParam=true"))
        assertTrue(response.contains("SOME_BODY"))

        server.stop(0, 0)
    }

    @Test
    fun `Should not log request and response payloads and headers`() {
        val server = testServer {
            logRequests = true
            logResponses = true
        }

        server.start()
        server.handleRequest(Post, "/api?queryParam=true") {
            addHeader("My-Header", "My-Value")
            setBody("SOME_BODY")
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

        val request = payloads[0]
        assertTrue(request.contains("POST"))
        assertTrue(request.contains("/api"))
        assertFalse(request.contains("queryParam=true"))
        assertFalse(request.contains("My-Header"))
        assertFalse(request.contains("My-Value"))
        assertFalse(request.contains("SOME_BODY"))

        val response = payloads[1]
        assertTrue(response.contains("200 OK"))
        assertTrue(request.contains("/api"))
        assertFalse(request.contains("queryParam=true"))
        assertFalse(response.contains("SOME_BODY"))

        server.stop(0, 0)
    }

    @Test
    fun `Should throw when no CallId feature`() {
        assertThrows<IllegalStateException> {
            testServer(installCallId = false).start()
        }
    }

    @Test
    fun `Should throw when logging body and no DoubleReceive feature`() {
        assertThrows<IllegalStateException> {
            testServer(installDoubleReceive = false) {
                logRequests = true
                logBody = true
            }.start()
        }
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

                    routing {
                        get("/api") {
                            call.respond("OK")
                        }
                        post("/api") {
                            call.respond(call.receiveText())
                        }
                    }
                }
            }
        )
    }
}
