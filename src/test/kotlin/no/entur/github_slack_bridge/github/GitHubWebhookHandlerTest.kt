package no.entur.github_slack_bridge.github

import kotlinx.coroutines.runBlocking
import no.entur.github_slack_bridge.slack.SlackClient
import no.entur.github_slack_bridge.slack.SlackMessage
import java.time.Instant
import java.time.temporal.ChronoUnit
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
        val pushEventPayload = createPushPayload(branch = "main")

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()

        assertTrue(message.text.contains(":rocket: pushed 1 commit"))
        assertTrue(message.text.contains("user/test-repo"))
        assertTrue(message.text.contains("Fix bug in authentication"))
        assertTrue(message.text.contains("<https://github.com/user/test-repo/compare/oldsha...newsha|1234567>"))

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
    fun `test handling pull request merged event`() = runBlocking {
        val prEventPayload = """
        {
          "action": "closed",
          "pull_request": {
            "id": 123456789,
            "number": 42,
            "title": "Add new feature",
            "html_url": "https://github.com/user/test-repo/pull/42",
            "url": "https://api.github.com/repos/user/test-repo/pulls/42",
            "state": "closed",
            "merged": true,
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

        assertTrue(message.text.contains(":pr-merged: Pull Request merged:"))
        assertTrue(message.text.contains("#42 Add new feature"))
    }

    @Test
    fun `test ignoring closed (not merged) pull request event`() = runBlocking {
        val prEventPayload = """
        {
          "action": "closed",
          "pull_request": {
            "id": 123456789,
            "number": 42,
            "title": "Add new feature",
            "html_url": "https://github.com/user/test-repo/pull/42",
            "url": "https://api.github.com/repos/user/test-repo/pulls/42",
            "state": "closed",
            "merged": false,
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

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test ignoring reopened pull request event`() = runBlocking {
        val prEventPayload = """
        {
          "action": "reopened",
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

        assertEquals(0, mockSlackClient.sentMessages.size)
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
        val pushEventPayload = createPushPayload(branch = "feature/new-feature")

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature", "dev-team")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test push event on master branch is processed`() = runBlocking {
        val pushEventPayload = createPushPayload(branch = "master", defaultBranch = "master")

        val signature = generateSignature(pushEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", pushEventPayload, "sha256=$signature", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()
        assertTrue(message.text.contains(":rocket: pushed 1 commit"))
        assertTrue(message.text.contains("user/test-repo"))
        assertTrue(message.text.contains("Fix bug in authentication"))
        assertTrue(message.text.contains("<https://github.com/user/test-repo/compare/oldsha...newsha|1234567>"))
    }

    @Test
    fun `test handling workflow run failure event`() = runBlocking {
        val workflowRunEventPayload = createWorkflowRunPayload(conclusion = "failure")

        val signature = generateSignature(workflowRunEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", workflowRunEventPayload, "sha256=$signature", "builds-channel")

        assertEquals(1, mockSlackClient.sentMessages.size)
        val message = mockSlackClient.sentMessages.first()

        assertTrue(message.text.contains(":x: build failed"))
        assertTrue(message.text.contains("CI Build #42"))
        assertTrue(message.text.contains("user/test-repo"))
        assertTrue(message.text.contains("abcdef1"))
        assertEquals("testuser", message.username)
        assertEquals("builds-channel", message.channel)
    }

    @Test
    fun `test ignoring successful workflow run event`() = runBlocking {
        val workflowRunEventPayload = createWorkflowRunPayload(conclusion = "success")

        val signature = generateSignature(workflowRunEventPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", workflowRunEventPayload, "sha256=$signature", "builds-channel")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test successful workflow run after failure sends notification`() = runBlocking {
        val failedWorkflowRunPayload = createWorkflowRunPayload(conclusion = "failure")
        val successWorkflowRunPayload = createWorkflowRunPayload(
            conclusion = "success",
            id = 987654322,
            runNumber = 43,
            headSha = "bcdef1234567890abcdef1234567890abcdef123"
        )

        val failureSignature = generateSignature(failedWorkflowRunPayload, testSecret)
        val successSignature = generateSignature(successWorkflowRunPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", failedWorkflowRunPayload, "sha256=$failureSignature", "builds-channel")

        webhookHandler.handleWebhook("workflow_run", successWorkflowRunPayload, "sha256=$successSignature", "builds-channel")

        assertEquals(2, mockSlackClient.sentMessages.size)

        val failureMessage = mockSlackClient.sentMessages[0]
        assertTrue(failureMessage.text.contains(":x: build failed"))
        assertTrue(failureMessage.text.contains("CI Build #42"))
        assertTrue(failureMessage.text.contains("user/test-repo"))
        assertTrue(failureMessage.text.contains("abcdef1"))
        assertEquals("testuser", failureMessage.username)

        val successMessage = mockSlackClient.sentMessages[1]
        assertTrue(successMessage.text.contains(":white_check_mark: build fixed"))
        assertTrue(successMessage.text.contains("CI Build #43"))
        assertTrue(successMessage.text.contains("user/test-repo"))
        assertTrue(successMessage.text.contains("bcdef1"))
        assertEquals("testuser", successMessage.username)
        assertEquals("builds-channel", successMessage.channel)
    }

    @Test
    fun `test successful workflow run without previous failure doesn't send notification`() = runBlocking {
        val successWorkflowRunPayload = createWorkflowRunPayload(conclusion = "success")

        val signature = generateSignature(successWorkflowRunPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        // Send only a successful build notification with no previous failure
        webhookHandler.handleWebhook("workflow_run", successWorkflowRunPayload, "sha256=$signature", "builds-channel")

        // Verify that no messages were sent for successful workflow runs without previous failures
        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test getBuildStatus returns empty when no failures`() = runBlocking {
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        val buildStatus = webhookHandler.getBuildStatus()

        assertEquals(0, buildStatus.failedBuilds.size)
        assertEquals(0, buildStatus.stats.totalFailedBuilds)
        assertTrue(buildStatus.stats.failedByBranch.isEmpty())
        assertEquals(7, buildStatus.stats.trackingDurationDays)
    }

    @Test
    fun `test getBuildStatus returns failed builds after failure`() = runBlocking {
        val workflowRunPayload = createWorkflowRunPayload(conclusion = "failure")
        val signature = generateSignature(workflowRunPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", workflowRunPayload, "sha256=$signature", "builds-channel")

        val buildStatus = webhookHandler.getBuildStatus()

        assertEquals(1, buildStatus.failedBuilds.size)
        assertEquals(1, buildStatus.stats.totalFailedBuilds)
        assertEquals("123456", buildStatus.failedBuilds[0].workflowId)
        assertEquals("main", buildStatus.failedBuilds[0].branch)
        assertEquals(mapOf("main" to 1), buildStatus.stats.failedByBranch)
    }

    @Test
    fun `test getBuildStatus removes fixed builds`() = runBlocking {
        val failedPayload = createWorkflowRunPayload(conclusion = "failure")
        val successPayload = createWorkflowRunPayload(
            conclusion = "success",
            id = 987654322,
            runNumber = 43,
            headSha = "bcdef1234567890abcdef1234567890abcdef123"
        )

        val failureSignature = generateSignature(failedPayload, testSecret)
        val successSignature = generateSignature(successPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", failedPayload, "sha256=$failureSignature", "builds-channel")

        var buildStatus = webhookHandler.getBuildStatus()
        assertEquals(1, buildStatus.failedBuilds.size)

        webhookHandler.handleWebhook("workflow_run", successPayload, "sha256=$successSignature", "builds-channel")

        buildStatus = webhookHandler.getBuildStatus()
        assertEquals(0, buildStatus.failedBuilds.size)
        assertEquals(0, buildStatus.stats.totalFailedBuilds)
    }

    @Test
    fun `test getBuildStatus tracks multiple workflows separately`() = runBlocking {
        val workflow1Payload = createWorkflowRunPayload(conclusion = "failure", workflowId = 111111)
        val workflow2Payload = createWorkflowRunPayload(
            conclusion = "failure",
            workflowId = 222222,
            id = 987654322,
            runNumber = 43
        )
        val workflow3Payload = createWorkflowRunPayload(
            conclusion = "failure",
            workflowId = 333333,
            id = 987654323,
            runNumber = 44
        )

        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook(
            "workflow_run",
            workflow1Payload,
            "sha256=${generateSignature(workflow1Payload, testSecret)}",
            "builds-channel"
        )
        webhookHandler.handleWebhook(
            "workflow_run",
            workflow2Payload,
            "sha256=${generateSignature(workflow2Payload, testSecret)}",
            "builds-channel"
        )
        webhookHandler.handleWebhook(
            "workflow_run",
            workflow3Payload,
            "sha256=${generateSignature(workflow3Payload, testSecret)}",
            "builds-channel"
        )

        val buildStatus = webhookHandler.getBuildStatus()

        assertEquals(3, buildStatus.failedBuilds.size)
        assertEquals(3, buildStatus.stats.totalFailedBuilds)
        assertEquals(mapOf("main" to 3), buildStatus.stats.failedByBranch)

        val workflowIds = buildStatus.failedBuilds.map { it.workflowId }.toSet()
        assertTrue(workflowIds.contains("111111"))
        assertTrue(workflowIds.contains("222222"))
        assertTrue(workflowIds.contains("333333"))
    }

    @Test
    fun `test old build failures are ignored`() = runBlocking {
        val oldBuildPayload = createWorkflowRunPayload(
            conclusion = "failure",
            createdAt = Instant.now().minus(30, ChronoUnit.DAYS)
        )

        val signature = generateSignature(oldBuildPayload, testSecret)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", oldBuildPayload, "sha256=$signature", "builds-channel")

        // Verify that no messages were sent for old build failures
        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test push event on non-standard default branch is processed`() = runBlocking {
        val payload = createPushPayload(branch = "rutebanken", defaultBranch = "rutebanken")
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, "sha256=${generateSignature(payload, testSecret)}", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
        assertTrue(mockSlackClient.sentMessages.first().text.contains("`rutebanken`"))
    }

    @Test
    fun `test push event on main is ignored when default branch is something else`() = runBlocking {
        val payload = createPushPayload(branch = "main", defaultBranch = "rutebanken")
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", payload, "sha256=${generateSignature(payload, testSecret)}", "dev-team")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test workflow run from a fork is ignored even on the default branch`() = runBlocking {
        // Fork owners routinely open PRs from a branch named like our default branch
        val payload = createWorkflowRunPayload(
            conclusion = "failure",
            headBranch = "main",
            defaultBranch = "main",
            headRepository = "contributor/test-repo"
        )
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", payload, "sha256=${generateSignature(payload, testSecret)}", "builds")

        assertEquals(0, mockSlackClient.sentMessages.size)
        assertEquals(0, webhookHandler.getBuildStatus().stats.totalFailedBuilds)
    }

    @Test
    fun `test workflow run from the repository itself is processed`() = runBlocking {
        val payload = createWorkflowRunPayload(
            conclusion = "failure",
            headBranch = "main",
            defaultBranch = "main",
            headRepository = "user/test-repo"
        )
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", payload, "sha256=${generateSignature(payload, testSecret)}", "builds")

        assertEquals(1, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test fork failure does not clear a real failure on the default branch`() = runBlocking {
        val ourFailure = createWorkflowRunPayload(conclusion = "failure", headRepository = "user/test-repo")
        val forkSuccess = createWorkflowRunPayload(
            conclusion = "success",
            id = 987654350,
            runNumber = 50,
            headRepository = "contributor/test-repo"
        )

        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", ourFailure, "sha256=${generateSignature(ourFailure, testSecret)}", "builds")
        webhookHandler.handleWebhook("workflow_run", forkSuccess, "sha256=${generateSignature(forkSuccess, testSecret)}", "builds")

        // The fork's green run must not post "build fixed" nor drop our tracked failure
        assertEquals(1, mockSlackClient.sentMessages.size)
        assertTrue(mockSlackClient.sentMessages.first().text.contains(":x: build failed:"))
        assertEquals(1, webhookHandler.getBuildStatus().stats.totalFailedBuilds)
    }

    @Test
    fun `test repeated failure of the same workflow reports failed again`() = runBlocking {
        val firstFailure = createWorkflowRunPayload(conclusion = "failure")
        val secondFailure = createWorkflowRunPayload(conclusion = "failure", id = 987654399, runNumber = 43)

        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook(
            "workflow_run",
            firstFailure,
            "sha256=${generateSignature(firstFailure, testSecret)}",
            "builds-channel"
        )
        webhookHandler.handleWebhook(
            "workflow_run",
            secondFailure,
            "sha256=${generateSignature(secondFailure, testSecret)}",
            "builds-channel"
        )

        assertEquals(2, mockSlackClient.sentMessages.size)
        assertTrue(mockSlackClient.sentMessages[0].text.contains(":x: build failed:"))
        assertTrue(mockSlackClient.sentMessages[1].text.contains(":x: build failed again:"))
    }

    @Test
    fun `test pull request with id beyond Int range is handled`() = runBlocking {
        // Real GitHub pull request ids exceed 2^31, which used to fail deserialization
        val prEventPayload = """
        {
          "action": "opened",
          "pull_request": {
            "id": 4458676007,
            "number": 464,
            "title": "Add new feature",
            "html_url": "https://github.com/user/test-repo/pull/464",
            "url": "https://api.github.com/repos/user/test-repo/pulls/464",
            "state": "open",
            "created_at": "2026-09-05T12:00:00Z",
            "updated_at": "2026-09-05T12:00:00Z",
            "user": {
              "login": "contributor",
              "id": 9876543210
            }
          },
          "repository": {
            "id": 8123456789,
            "name": "test-repo",
            "full_name": "user/test-repo",
            "html_url": "https://github.com/user/test-repo",
            "url": "https://api.github.com/repos/user/test-repo",
            "default_branch": "main",
            "owner": {
              "login": "user",
              "id": 9876543210
            }
          },
          "sender": {
            "login": "contributor",
            "id": 9876543210
          }
        }
        """.trimIndent()

        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook(
            "pull_request",
            prEventPayload,
            "sha256=${generateSignature(prEventPayload, testSecret)}",
            "pull-requests"
        )

        assertEquals(1, mockSlackClient.sentMessages.size)
        assertTrue(mockSlackClient.sentMessages.first().text.contains("#464 Add new feature"))
    }

    @Test
    fun `test push event on prod is ignored when default branch is main`() = runBlocking {
        val prodPayload = createPushPayload(branch = "prod", defaultBranch = "main")
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", prodPayload, "sha256=${generateSignature(prodPayload, testSecret)}", "dev-team")

        assertEquals(0, mockSlackClient.sentMessages.size)

        // Positive control: the same handler does deliver a push on the default branch
        val mainPayload = createPushPayload(branch = "main", defaultBranch = "main")
        webhookHandler.handleWebhook("push", mainPayload, "sha256=${generateSignature(mainPayload, testSecret)}", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test workflow run on prod is ignored when default branch is main`() = runBlocking {
        val payload = createWorkflowRunPayload(
            conclusion = "failure",
            headBranch = "prod",
            defaultBranch = "main"
        )
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", payload, "sha256=${generateSignature(payload, testSecret)}", "builds")

        assertEquals(0, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test push event falls back to main and master when default branch is absent`() = runBlocking {
        val masterPayload = createPushPayload(branch = "master", defaultBranch = null)
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("push", masterPayload, "sha256=${generateSignature(masterPayload, testSecret)}", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)

        val prodPayload = createPushPayload(branch = "prod", defaultBranch = null)
        webhookHandler.handleWebhook("push", prodPayload, "sha256=${generateSignature(prodPayload, testSecret)}", "dev-team")

        assertEquals(1, mockSlackClient.sentMessages.size)
    }

    @Test
    fun `test workflow run on non-standard default branch is processed`() = runBlocking {
        val payload = createWorkflowRunPayload(
            conclusion = "failure",
            headBranch = "rutebanken",
            defaultBranch = "rutebanken"
        )
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", payload, "sha256=${generateSignature(payload, testSecret)}", "builds")

        assertEquals(1, mockSlackClient.sentMessages.size)
        assertTrue(mockSlackClient.sentMessages.first().text.contains("`rutebanken`"))
    }

    @Test
    fun `test workflow run on main is ignored when default branch is something else`() = runBlocking {
        val payload = createWorkflowRunPayload(
            conclusion = "failure",
            headBranch = "main",
            defaultBranch = "rutebanken"
        )
        val mockSlackClient = MockSlackClient()
        val webhookHandler = GitHubWebhookHandler(mockSlackClient, testSecret)

        webhookHandler.handleWebhook("workflow_run", payload, "sha256=${generateSignature(payload, testSecret)}", "builds")

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

    private fun createWorkflowRunPayload(
        conclusion: String,
        id: Long = 987654321,
        runNumber: Int = 42,
        headSha: String = "abcdef1234567890abcdef1234567890abcdef12",
        createdAt: Instant = Instant.now(),
        workflowId: Long = 123456,
        headBranch: String = "main",
        defaultBranch: String? = "main",
        headRepository: String? = null
    ): String {
        val updatedAt = createdAt.plus(15, ChronoUnit.MINUTES)
        return """
        {
          "action": "completed",
          "workflow_run": {
            "id": $id,
            "name": "CI Build",
            "status": "completed",
            "conclusion": "$conclusion",
            "html_url": "https://github.com/user/test-repo/actions/runs/$id",
            "created_at": "$createdAt",
            "updated_at": "$updatedAt",
            "workflow_id": $workflowId,
            "head_branch": "$headBranch",
            "head_sha": "$headSha",
            "check_suite_id": 123456789,
            "actor": {
              "login": "testuser",
              "id": 12345,
              "avatar_url": "https://avatars.githubusercontent.com/u/12345?v=4"
            },
            "run_number": $runNumber
            ${headRepository?.let { ""","head_repository": { "full_name": "$it" }""" } ?: ""}
          },
          "repository": {
            "id": 123456789,
            "name": "test-repo",
            "full_name": "user/test-repo",
            "html_url": "https://github.com/user/test-repo",
            "url": "https://api.github.com/repos/user/test-repo",
            ${defaultBranchField(defaultBranch)}
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
    }

    // Absent when null, so tests can exercise the main/master fallback
    private fun defaultBranchField(defaultBranch: String?): String =
        defaultBranch?.let { """"default_branch": "$it",""" } ?: ""

    private fun createPushPayload(branch: String, defaultBranch: String? = "main"): String = """
        {
          "ref": "refs/heads/$branch",
          "repository": {
            "id": 123456789,
            "name": "test-repo",
            "full_name": "user/test-repo",
            "html_url": "https://github.com/user/test-repo",
            "url": "https://api.github.com/repos/user/test-repo",
            ${defaultBranchField(defaultBranch)}
            "owner": {
              "login": "user",
              "id": 12345
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
              }
            }
          ],
          "sender": {
            "login": "testuser",
            "id": 12345
          },
          "compare": "https://github.com/user/test-repo/compare/oldsha...newsha"
        }
        """.trimIndent()

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
            } catch (_: Exception) {
                webhookProcessed = false
            }
        }
    }
}
