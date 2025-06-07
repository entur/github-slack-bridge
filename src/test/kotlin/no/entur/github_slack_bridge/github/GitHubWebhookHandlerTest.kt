package no.entur.github_slack_bridge.github

import kotlinx.coroutines.runBlocking
import no.entur.github_slack_bridge.slack.SlackClient
import no.entur.github_slack_bridge.slack.SlackMessage
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
          },
          "compare": "https://github.com/user/test-repo/compare/oldsha...newsha"
        }
        """.trimIndent()

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()

        assertTrue(message.text.contains("pushed 1 commit"))
        assertTrue(message.text.contains("user/test-repo:main"))
        assertTrue(message.text.contains("Fix bug in authentication"))
        assertTrue(message.text.contains("[diff](https://github.com/user/test-repo/compare/oldsha...newsha)"))

        assertEquals("dev-team", message.channel)
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

        webhookHandler.handleWebhook("pull_request", prEventPayload, "sha256=$signature", "pull-requests")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()

        assertTrue(message.text.contains(":pr-open: Pull Request opened:"))
        assertTrue(message.text.contains("#42 Add new feature"))
        assertTrue(message.text.contains("in <https://github.com/user/test-repo|user/test-repo>"))

        assertEquals("pull-requests", message.channel)
    }

    @Test
    fun `test ignoring unsupported event type`() = runBlocking {
        val payload = "{}"
        val signature = generateSignature(payload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("issue_comment", payload, "sha256=$signature", "general")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test webhook with valid signature is processed`() = runBlocking {
        val payload = """{"ref":"refs/heads/main","repository":{"full_name":"test/repo","html_url":"https://github.com/test/repo"},"commits":[{"id":"abc1234","message":"Test commit","author":{"name":"Test User"},"url":"https://github.com/test/repo/commit/abc1234"}],"sender":{"login":"testuser"},"compare":"https://github.com/test/repo/compare/oldsha...newsha"}"""
        val signature = generateSignature(payload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = TestGitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, "sha256=$signature", "test-channel")

        assertTrue(webhookHandler.webhookProcessed)
        assertEquals("test-channel", webhookHandler.lastChannel)
    }

    @Test
    fun `test webhook with invalid signature is rejected`() = runBlocking {
        val payload = """{"ref":"refs/heads/main","repository":{"full_name":"test/repo","html_url":"https://github.com/test/repo"},"commits":[{"id":"abc1234","message":"Test commit","author":{"name":"Test User"},"url":"https://github.com/test/repo/commit/abc1234"}],"sender":{"login":"testuser"},"compare":"https://github.com/test/repo/compare/oldsha...newsha"}"""
        val invalidSignature = "invalid_signature"
        val mockSlackClient = MockSlackClient()
        val webhookHandler = TestGitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, "sha256=$invalidSignature", "general")

        assertFalse(webhookHandler.webhookProcessed)
        assertNull(webhookHandler.lastChannel)
    }

    @Test
    fun `test webhook with missing signature is rejected when secret is configured`() = runBlocking {
        val payload = """{"ref":"refs/heads/main","repository":{"full_name":"test/repo","html_url":"https://github.com/test/repo"},"commits":[{"id":"abc1234","message":"Test commit","author":{"name":"Test User"},"url":"https://github.com/test/repo/commit/abc1234"}],"sender":{"login":"testuser"},"compare":"https://github.com/test/repo/compare/oldsha...newsha"}"""
        val mockSlackClient = MockSlackClient()
        val webhookHandler = TestGitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, null, "random-channel")

        assertFalse(webhookHandler.webhookProcessed)
        assertNull(webhookHandler.lastChannel)
    }

    @Test
    fun `test push event on feature branch is ignored`() = runBlocking {
        val pushEventPayload = """
        {
          "ref": "refs/heads/feature/new-feature",
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
              "message": "Add feature code",
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
              "added": ["src/feature.ts"],
              "removed": [],
              "modified": []
            }
          ],
          "sender": {
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          },
          "compare": "https://github.com/user/test-repo/compare/oldsha...newsha"
        }
        """.trimIndent()

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature", "dev-team")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test push event on master branch is processed`() = runBlocking {
        val pushEventPayload = """
        {
          "ref": "refs/heads/master",
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
              "message": "Fix bug in production",
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
              "modified": ["src/production.ts"]
            }
          ],
          "sender": {
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          },
          "compare": "https://github.com/user/test-repo/compare/oldsha...newsha"
        }
        """.trimIndent()

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()
        assertTrue(message.text.contains("pushed 1 commit"))
        assertTrue(message.text.contains("user/test-repo:master"))
        assertTrue(message.text.contains("Fix bug in production"))
        assertTrue(message.text.contains("[diff](https://github.com/user/test-repo/compare/oldsha...newsha)"))
    }

    @Test
    fun `test handling workflow run failure event`() = runBlocking {
        val workflowRunEventPayload = """
        {
          "action": "completed",
          "workflow_run": {
            "id": 987654321,
            "name": "CI Build",
            "status": "completed",
            "conclusion": "failure",
            "html_url": "https://github.com/user/test-repo/actions/runs/987654321",
            "created_at": "2025-06-05T12:00:00Z",
            "updated_at": "2025-06-05T12:15:00Z",
            "workflow_id": 123456,
            "head_branch": "main",
            "head_sha": "abcdef1234567890abcdef1234567890abcdef12",
            "check_suite_id": 123456789,
            "actor": {
              "login": "testuser",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            },
            "run_number": 42
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
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          }
        }
        """.trimIndent()

        val signature = generateSignature(workflowRunEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", workflowRunEventPayload, "sha256=$signature", "builds-channel")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()

        assertTrue(message.text.contains(":x: Build failed"))
        assertTrue(message.text.contains("*CI Build* workflow run"))
        assertTrue(message.text.contains("#42"))
        assertTrue(message.text.contains("user/test-repo"))
        assertTrue(message.text.contains("main"))
        assertTrue(message.text.contains("abcdef1"))

        assertEquals("builds-channel", message.channel)
    }

    @Test
    fun `test ignoring successful workflow run event`() = runBlocking {
        val workflowRunEventPayload = """
        {
          "action": "completed",
          "workflow_run": {
            "id": 987654321,
            "name": "CI Build",
            "status": "completed",
            "conclusion": "success",
            "html_url": "https://github.com/user/test-repo/actions/runs/987654321",
            "created_at": "2025-06-05T12:00:00Z",
            "updated_at": "2025-06-05T12:15:00Z",
            "workflow_id": 123456,
            "head_branch": "main",
            "head_sha": "abcdef1234567890abcdef1234567890abcdef12",
            "check_suite_id": 123456789,
            "actor": {
              "login": "testuser",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            },
            "run_number": 42
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
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          }
        }
        """.trimIndent()

        val signature = generateSignature(workflowRunEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", workflowRunEventPayload, "sha256=$signature", "builds-channel")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test successful workflow run after failure sends notification`() = runBlocking {
        val failedWorkflowRunPayload = """
        {
          "action": "completed",
          "workflow_run": {
            "id": 987654321,
            "name": "CI Build",
            "status": "completed",
            "conclusion": "failure",
            "html_url": "https://github.com/user/test-repo/actions/runs/987654321",
            "created_at": "2025-06-05T12:00:00Z",
            "updated_at": "2025-06-05T12:15:00Z",
            "workflow_id": 123456,
            "head_branch": "main",
            "head_sha": "abcdef1234567890abcdef1234567890abcdef12",
            "check_suite_id": 123456789,
            "actor": {
              "login": "testuser",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            },
            "run_number": 42
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
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          }
        }
        """.trimIndent()

        val successWorkflowRunPayload = """
        {
          "action": "completed",
          "workflow_run": {
            "id": 987654322,
            "name": "CI Build",
            "status": "completed",
            "conclusion": "success",
            "html_url": "https://github.com/user/test-repo/actions/runs/987654322",
            "created_at": "2025-06-05T13:00:00Z",
            "updated_at": "2025-06-05T13:15:00Z",
            "workflow_id": 123456,
            "head_branch": "main",
            "head_sha": "bcdef1234567890abcdef1234567890abcdef123",
            "check_suite_id": 123456790,
            "actor": {
              "login": "testuser",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            },
            "run_number": 43
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
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          }
        }
        """.trimIndent()

        val failureSignature = generateSignature(failedWorkflowRunPayload, testSecret)
        val successSignature = generateSignature(successWorkflowRunPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        // First, send the failed build notification
        webhookHandler.handleWebhook("workflow_run", failedWorkflowRunPayload, "sha256=$failureSignature", "builds-channel")

        // Then send the successful build notification
        webhookHandler.handleWebhook("workflow_run", successWorkflowRunPayload, "sha256=$successSignature", "builds-channel")

        // We should get 2 messages: one for the failure and one for the success
        assertEquals(2, mockSlackClient.sentMessages.size)

        // Check the failure message
        val failureMessage = mockSlackClient.sentMessages[0]
        assertTrue(failureMessage.text.contains(":x: Build failed"))
        assertTrue(failureMessage.text.contains("*CI Build* workflow run"))

        // Check the success message
        val successMessage = mockSlackClient.sentMessages[1]
        assertTrue(successMessage.text.contains(":white_check_mark: Build fixed"))
        assertTrue(successMessage.text.contains("*CI Build* workflow run"))
        assertTrue(successMessage.text.contains("#43"))
        assertTrue(successMessage.text.contains("now passing"))

        assertEquals("builds-channel", successMessage.channel)
    }

    @Test
    fun `test successful workflow run without previous failure doesn't send notification`() = runBlocking {
        val successWorkflowRunPayload = """
        {
          "action": "completed",
          "workflow_run": {
            "id": 987654322,
            "name": "CI Build",
            "status": "completed",
            "conclusion": "success",
            "html_url": "https://github.com/user/test-repo/actions/runs/987654322",
            "created_at": "2025-06-05T13:00:00Z",
            "updated_at": "2025-06-05T13:15:00Z",
            "workflow_id": 123456,
            "head_branch": "main",
            "head_sha": "bcdef1234567890abcdef1234567890abcdef123",
            "check_suite_id": 123456790,
            "actor": {
              "login": "testuser",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            },
            "run_number": 43
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
            "login": "testuser",
            "id": 12345,
            "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
          }
        }
        """.trimIndent()

        val signature = generateSignature(successWorkflowRunPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        // Send only a successful build notification with no previous failure
        webhookHandler.handleWebhook("workflow_run", successWorkflowRunPayload, "sha256=$signature", "builds-channel")

        // Verify that no messages were sent for successful workflow runs without previous failures
        assertEquals(0, mockSlackClient.sentMessages.size)
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
        var lastChannel: String? = null

        override suspend fun handleWebhook(eventType: String?, payload: String, signature: String?, channel: String?) {
            webhookProcessed = false
            handledEventType = null
            lastChannel = null

            try {
                val messageCountBefore = mockClient.sentMessages.size
                super.handleWebhook(eventType, payload, signature, channel)

                // For both signature validation failures and unsupported event types,
                // super.handleWebhook will return without sending a message.
                // We consider a webhook "processed" only if it passes signature validation
                // AND is a supported event type, which will result in a message being sent.
                webhookProcessed = mockClient.sentMessages.size > messageCountBefore
                if (webhookProcessed) {
                    handledEventType = eventType
                    lastChannel = channel
                }
            } catch (e: Exception) {
                webhookProcessed = false
            }
        }
    }
}
