package com.korrit.kotlin.ktor.features.logging

import io.ktor.application.ApplicationCall
import io.ktor.http.content.OutgoingContent
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.split
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ObservedContent(private val channel: ByteReadChannel) : OutgoingContent.ReadChannelContent() {
    override fun readFrom(): ByteReadChannel = channel
}

internal suspend fun PipelineContext<Any, ApplicationCall>.observe(): Pair<ByteReadChannel, OutgoingContent> {
    return when (val body = subject) {
        is OutgoingContent.ByteArrayContent -> {
            val observer = ByteChannel().apply {
                writeFully(body.bytes())
                close(null)
            }

            observer to body
        }
        is OutgoingContent.ReadChannelContent -> {
            val (observer, observed) = body.readFrom().split(this)

            observer to ObservedContent(observed)
        }
        is OutgoingContent.WriteChannelContent -> {
            val (observer, observed) = body.toReadChannel(this).split(this)

            observer to ObservedContent(observed)
        }
        else -> {
            val emptyObserver = ByteChannel().apply {
                close(null)
            }

            emptyObserver to body as OutgoingContent
        }
    }
}

private fun OutgoingContent.WriteChannelContent.toReadChannel(scope: CoroutineScope): ByteReadChannel {
    val channel = ByteChannel()
    scope.launch(Dispatchers.Unconfined) {
        writeTo(channel)
        channel.close(null)
    }
    return channel
}
