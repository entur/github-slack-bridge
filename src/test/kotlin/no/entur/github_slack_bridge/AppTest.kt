package no.entur.github_slack_bridge

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import no.entur.github_slack_bridge.github.BuildStats
import no.entur.github_slack_bridge.github.BuildStatus
import no.entur.github_slack_bridge.github.FailedBuild
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

        client.post("/webhook/general") {
            header("X-GitHub-Event", "push")
            contentType(ContentType.Application.Json)
            setBody("""{"ref":"refs/heads/main","commits":[]}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("Webhook processed successfully", bodyAsText())
            assertTrue(mockWebhookHandler.handledEvents.contains("push"))
            assertEquals("general", mockWebhookHandler.lastChannel)
        }
    }

    @Test
    fun `test builds endpoint returns empty status`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val mockWebhookHandler = MockGitHubWebhookHandler(mockSlackClient)

        application {
            configureServer(mockWebhookHandler)
        }

        client.get("/builds").apply {
            assertEquals(HttpStatusCode.OK, status)
            val body = bodyAsText()
            assertTrue(body.contains("totalFailedBuilds"), "Expected totalFailedBuilds field")
            assertTrue(body.contains("failedBuilds"), "Expected failedBuilds field")
            assertTrue(body.contains("trackingDurationDays"), "Expected trackingDurationDays field")
            // Empty array can have different whitespace formatting
            assertTrue(body.contains("[]") || body.contains("[ ]"), "Expected empty failedBuilds array. Body: $body")
        }
    }

    @Test
    fun `test builds endpoint returns failed builds`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val mockWebhookHandler = MockGitHubWebhookHandler(
            mockSlackClient,
            mockBuildStatus = BuildStatus(
                failedBuilds = listOf(
                    FailedBuild(
                        workflowId = "123456",
                        branch = "main",
                        failedAt = "2026-01-19T10:00:00Z",
                        failedFor = "2h 30m"
                    ),
                    FailedBuild(
                        workflowId = "789012",
                        branch = "prod",
                        failedAt = "2026-01-18T15:00:00Z",
                        failedFor = "1d 5h"
                    )
                ),
                stats = BuildStats(
                    totalFailedBuilds = 2,
                    failedByBranch = mapOf("main" to 1, "prod" to 1),
                    trackingDurationDays = 7
                )
            )
        )

        application {
            configureServer(mockWebhookHandler)
        }

        client.get("/builds").apply {
            assertEquals(HttpStatusCode.OK, status)
            val body = bodyAsText()
            assertTrue(body.contains("\"totalFailedBuilds\"") && body.contains("2"), "Expected totalFailedBuilds: 2")
            assertTrue(body.contains("123456"), "Expected workflowId 123456")
            assertTrue(body.contains("789012"), "Expected workflowId 789012")
            assertTrue(body.contains("\"main\""), "Expected branch main")
            assertTrue(body.contains("\"prod\""), "Expected branch prod")
            assertTrue(body.contains("2h 30m"), "Expected failedFor 2h 30m")
            assertTrue(body.contains("1d 5h"), "Expected failedFor 1d 5h")
        }
    }

    @Test
    fun `test index page contains build status section`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val mockWebhookHandler = MockGitHubWebhookHandler(mockSlackClient)

        application {
            configureServer(mockWebhookHandler)
        }

        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            val body = bodyAsText()
            assertTrue(body.contains("Build Status"))
            assertTrue(body.contains("id=\"build-content\""))
            assertTrue(body.contains("fetch('/builds')"))
            assertTrue(body.contains("loadBuildStatus"))
            assertTrue(body.contains("Failed Builds"))
        }
    }

    @Test
    fun `test github webhook endpoint handles errors`() = testApplication {
        val mockSlackClient = MockSlackClient()
        val errorWebhookHandler = ErrorGitHubWebhookHandler()

        application {
            configureServer(errorWebhookHandler)
        }

        client.post("/webhook/random-channel") {
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

    private class MockGitHubWebhookHandler(
        slackClient: SlackClient,
        var mockBuildStatus: BuildStatus = BuildStatus(
            failedBuilds = emptyList(),
            stats = BuildStats(totalFailedBuilds = 0, failedByBranch = emptyMap(), trackingDurationDays = 7)
        )
    ) : GitHubWebhookHandler(slackClient, "dummy-secret") {
        val handledEvents = mutableListOf<String>()
        var lastChannel: String? = null

        override suspend fun handleWebhook(eventType: String?, payload: String, signature: String?, channel: String?) {
            eventType?.let { handledEvents.add(eventType) }
            lastChannel = channel
        }

        override fun getBuildStatus(): BuildStatus = mockBuildStatus
    }

    private class ErrorGitHubWebhookHandler : GitHubWebhookHandler(MockSlackClient(), "dummy-secret") {
        override suspend fun handleWebhook(eventType: String?, payload: String, signature: String?, channel: String?) {
            throw RuntimeException("Expected test error, please ignore this")
        }
    }
}
