package no.entur.github_slack_bridge.slack

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class SlackMessage(
    val text: String,
    val username: String? = "bottie",
    val icon_emoji: String? = ":rocket:",
    val icon_url: String? = null,
    val channel: String? = null
)

open class SlackClient(private val webhookUrl: String) {
    private val logger = LoggerFactory.getLogger(SlackClient::class.java)
    private val client by lazy { createHttpClient() }

    protected open fun createHttpClient(): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }

    open suspend fun sendMessage(message: SlackMessage) {
        try {
            logger.info("Sending message to Slack: ${message.text}")

            val response = client.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(message)
            }

            if (response.status.isSuccess()) {
                logger.info("Successfully sent message to Slack")
            } else {
                logger.error("Failed to send message to Slack: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Error sending message to Slack", e)
            throw e
        }
    }
}
