package no.entur.github_slack_bridge.slack

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SlackClientTest {

    val dummyUrl = "https://hooks.slack.com/services/dummy/token"
    
    @Test
    fun `test successful message sending`() = runBlocking {
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.toString().contains("hooks.slack.com/services"),
                       "URL should contain the Slack webhook path")

            respond(
                content = "ok",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val slackClient = createSlackClientWithMockEngine(
            dummyUrl,
            mockEngine
        )

        slackClient.sendMessage(
            SlackMessage(
                text = "Test message",
                username = "Test Bot",
                icon_emoji = ":test:"
            )
        )

        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `test handling failed response`() = runBlocking {
        val mockEngine = MockEngine { request ->
            respond(
                content = "channel_not_found",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }

        val slackClient = createSlackClientWithMockEngine(
            dummyUrl,
            mockEngine
        )

        slackClient.sendMessage(
            SlackMessage(
                text = "Test message",
                username = "Test Bot",
                icon_emoji = ":test:"
            )
        )

        assertEquals(1, mockEngine.requestHistory.size)
    }

    @Test
    fun `test handling connection error`() = runBlocking {
        val mockEngine = MockEngine {
            throw IOException("Simulated network error")
        }

        val slackClient = createSlackClientWithMockEngine(
            dummyUrl,
            mockEngine
        )

        assertFailsWith<IOException> {
            slackClient.sendMessage(
                SlackMessage(
                    text = "Test message",
                    username = "Test Bot",
                    icon_emoji = ":test:"
                )
            )
        }
    }

    private fun createSlackClientWithMockEngine(webhookUrl: String, mockEngine: MockEngine): SlackClient {
        return object : SlackClient(webhookUrl) {
            override fun createHttpClient(): HttpClient {
                return HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        json(Json {
                            prettyPrint = true
                            isLenient = true
                            ignoreUnknownKeys = true
                        })
                    }
                }
            }
        }
    }
}
