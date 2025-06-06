package no.entur.github_slack_bridge.github

import kotlinx.coroutines.runBlocking
import no.entur.github_slack_bridge.slack.SlackClient
import no.entur.github_slack_bridge.slack.SlackMessage
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubWebhookHandlerTest {

    private val testSecret = "test_webhook_secret"

    @Test
    fun `test handling push event`() = runBlocking {
        val pushEventPayload = """
        {
          "ref": "refs/heads/main",
          "repository": {
            "id": 123456789,
            "name": "test-repo",
            "full_name": "user/test-repo",
            "html_url": "https://github.com/user/test-repo",
            "url": "https://api.github.com/repos/user/test-repo",
            "owner": {
              "login": "user",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            }
          },
          "commits": [
            {
              "id": "1234567890abcdef1234567890abcdef12345678",
              "message": "Fix bug in authentication",
              "timestamp": "2025-06-05T12:00:00Z",
              "url": "https://github.com/user/test-repo/commit/1234567890abcdef1234567890abcdef12345678",
              "author": {
                "name": "Test User",
                "email": "test@example.com",
                "username": "testuser"
              },
              "committer": {
                "name": "Test User",
                "email": "test@example.com",
                "username": "testuser"
              },
              "added": [],
              "removed": [],
              "modified": ["src/auth.ts"]
            }
          ],
          "sender": {
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          }
        }
        """.trimIndent()

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first().text

        assertTrue(message.contains("*Test User*"))
        assertTrue(message.contains("pushed 1 commit"))
        assertTrue(message.contains("user/test-repo:main"))
        assertTrue(message.contains("1234567"))
        assertTrue(message.contains("Fix bug in authentication"))
    }

    @Test
    fun `test handling pull request opened event`() = runBlocking {
        val prEventPayload = """
        {
          "action": "opened",
          "pull_request": {
            "id": 123456789,
            "number": 42,
            "title": "Add new feature",
            "html_url": "https://github.com/user/test-repo/pull/42",
            "url": "https://api.github.com/repos/user/test-repo/pulls/42",
            "state": "open",
            "body": "This PR adds a new awesome feature",
            "created_at": "2025-06-05T12:00:00Z",
            "updated_at": "2025-06-05T12:00:00Z",
            "user": {
              "login": "contributor",
              "id": 54321,
              "avatar_url": "https://avatars.githubusercontent.com/u/54321?v=4"
            }
          },
          "repository": {
            "id": 123456789,
            "name": "test-repo",
            "full_name": "user/test-repo",
            "html_url": "https://github.com/user/test-repo",
            "url": "https://api.github.com/repos/user/test-repo",
            "owner": {
              "login": "user",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            }
          },
          "sender": {
            "login": "contributor",
            "id": 54321,
            "avatar_url": "https://avatars.githubusercontent.com/u/54321?v=4"
          }
        }
        """.trimIndent()

        val signature = generateSignature(prEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("pull_request", prEventPayload, "sha256=$signature")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first().text

        assertTrue(message.contains("Pull Request opened"))
        assertTrue(message.contains("#42 Add new feature"))
        assertTrue(message.contains("by *contributor*"))
        assertTrue(message.contains("user/test-repo"))
    }

    @Test
    fun `test ignoring unsupported event type`() = runBlocking {
        val payload = "{}"
        val signature = generateSignature(payload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("issue_comment", payload, "sha256=$signature")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test webhook with valid signature is processed`() = runBlocking {
        val payload = """{"ref":"refs/heads/main","repository":{"full_name":"test/repo","html_url":"https://github.com/test/repo"},"commits":[{"id":"abc1234","message":"Test commit","author":{"name":"Test User"},"url":"https://github.com/test/repo/commit/abc1234"}],"sender":{"login":"testuser"}}"""
        val signature = generateSignature(payload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = TestGitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, "sha256=$signature")

        assertTrue(webhookHandler.webhookProcessed)
    }

    @Test
    fun `test webhook with invalid signature is rejected`() = runBlocking {
        val payload = """{"ref":"refs/heads/main","repository":{"full_name":"test/repo","html_url":"https://github.com/test/repo"},"commits":[{"id":"abc1234","message":"Test commit","author":{"name":"Test User"},"url":"https://github.com/test/repo/commit/abc1234"}],"sender":{"login":"testuser"}}"""
        val invalidSignature = "invalid_signature"
        val mockSlackClient = MockSlackClient()
        val webhookHandler = TestGitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, "sha256=$invalidSignature")

        assertFalse(webhookHandler.webhookProcessed)
    }

    @Test
    fun `test webhook with missing signature is rejected when secret is configured`() = runBlocking {
        val payload = """{"ref":"refs/heads/main","repository":{"full_name":"test/repo","html_url":"https://github.com/test/repo"},"commits":[{"id":"abc1234","message":"Test commit","author":{"name":"Test User"},"url":"https://github.com/test/repo/commit/abc1234"}],"sender":{"login":"testuser"}}"""
        val mockSlackClient = MockSlackClient()
        val webhookHandler = TestGitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload)

        assertFalse(webhookHandler.webhookProcessed)
    }

    private fun generateSignature(payload: String, secret: String): String {
        val secretKeySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)
        val calculatedDigest = mac.doFinal(payload.toByteArray())

        return calculatedDigest.joinToString("") {
            String.format("%02x", it)
        }
    }

    private class MockSlackClient : SlackClient("https://dummy-url") {
        val sentMessages = mutableListOf<SlackMessage>()

        override suspend fun sendMessage(message: SlackMessage) {
            sentMessages.add(message)
        }

        override fun createHttpClient() = throw UnsupportedOperationException("Not used in tests")
    }

    private class TestGitHubWebhookHandler(
        private val mockClient: MockSlackClient,
        webhookSecret: String = "dummy-secret"
    ) : GitHubWebhookHandler(mockClient, webhookSecret) {
        var webhookProcessed = false
        var handledEventType: String? = null

        override suspend fun handleWebhook(eventType: String?, payload: String, signature: String?) {
            webhookProcessed = false
            handledEventType = null

            try {
                val messageCountBefore = mockClient.sentMessages.size
                super.handleWebhook(eventType, payload, signature)

                // For both signature validation failures and unsupported event types,
                // super.handleWebhook will return without sending a message.
                // We consider a webhook "processed" only if it passes signature validation
                // AND is a supported event type, which will result in a message being sent.
                webhookProcessed = mockClient.sentMessages.size > messageCountBefore
                if (webhookProcessed) {
                    handledEventType = eventType
                }
            } catch (e: Exception) {
                webhookProcessed = false
            }
        }
    }
}
