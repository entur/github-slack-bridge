package no.entur.github_slack_bridge

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import no.entur.github_slack_bridge.github.GitHubWebhookHandler
import no.entur.github_slack_bridge.slack.SlackClient
import no.entur.github_slack_bridge.slack.SlackMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppTest {

    @Test
    fun `test health endpoint returns ok`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val mockWebhookHandler = MockGitHubWebhookHandler(mockSlackClient)

        application {
            configureServer(mockWebhookHandler)
        }

        client.get("/actuator/health/liveness").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("""{"status":"UP"}""", bodyAsText())
        }
    }

    @Test
    fun `test github webhook endpoint processes valid webhook`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val mockWebhookHandler = MockGitHubWebhookHandler(mockSlackClient)

        application {
            configureServer(mockWebhookHandler)
        }

        client.post("/webhook/github") {
            header("X-GitHub-Event", "push")
            contentType(ContentType.Application.Json)
            setBody("""{"ref":"refs/heads/main","commits":[]}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("Webhook processed successfully", bodyAsText())
            assertTrue(mockWebhookHandler.handledEvents.contains("push"))
        }
    }

    @Test
    fun `test github webhook endpoint handles errors`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val errorWebhookHandler = ErrorGitHubWebhookHandler()

        application {
            configureServer(errorWebhookHandler)
        }

        client.post("/webhook/github") {
            header("X-GitHub-Event", "push")
            contentType(ContentType.Application.Json)
            setBody("""{"ref":"refs/heads/main"}""")
        }.apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
            assertTrue(bodyAsText().contains("Error processing webhook"))
        }
    }

    private class MockSlackClient : SlackClient("https://dummy-url") {
        val sentMessages = mutableListOf<SlackMessage>()

        override suspend fun sendMessage(message: SlackMessage) {
            sentMessages.add(message)
        }

        override fun createHttpClient() = throw UnsupportedOperationException("Not used in tests")
    }

    private class MockGitHubWebhookHandler(slackClient: SlackClient) : GitHubWebhookHandler(slackClient, "dummy-secret") {
        val handledEvents = mutableListOf<String>()

        override suspend fun handleWebhook(eventType: String?, payload: String, signature: String?) {
            eventType?.let { handledEvents.add(eventType) }
        }
    }

    private class ErrorGitHubWebhookHandler : GitHubWebhookHandler(MockSlackClient(), "dummy-secret") {
        override suspend fun handleWebhook(eventType: String?, payload: String, signature: String?) {
            throw RuntimeException("Expected test error, please ignore this")
        }
    }
}
