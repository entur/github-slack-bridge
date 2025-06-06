package no.entur.github_slack_bridge.github

import kotlinx.serialization.json.Json
import no.entur.github_slack_bridge.slack.SlackClient
import no.entur.github_slack_bridge.slack.SlackMessage
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

open class GitHubWebhookHandler(private val slackClient: SlackClient, protected val webhookSecret: String) {
    private val logger = LoggerFactory.getLogger(GitHubWebhookHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun handleWebhook(eventType: String?, payload: String, signature: String? = null) {
        logger.info("Received GitHub webhook event: $eventType")

        if (signature == null) {
            logger.warn("No signature provided, rejecting webhook")
            return
        }

        if (!isValidSignature(payload, signature)) {
            logger.warn("Invalid webhook signature, rejecting webhook")
            return
        }

        logger.info("Webhook signature validation passed")

        when (eventType) {
            "push" -> handlePushEvent(payload)
            "pull_request" -> handlePullRequestEvent(payload)
            else -> logger.info("Ignoring unsupported event type: $eventType")
        }
    }

    protected fun isValidSignature(payload: String, signature: String): Boolean {
        try {
            if (!signature.startsWith("sha256=")) {
                logger.warn("Invalid signature format: $signature")
                return false
            }

            val providedSignature = signature.substring(7)
            val secretKeySpec = SecretKeySpec(webhookSecret.toByteArray(), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKeySpec)
            val calculatedDigest = mac.doFinal(payload.toByteArray())
            val calculatedSignature = calculatedDigest.joinToString("") {
                String.format("%02x", it)
            }

            return MessageDigest.isEqual(
                providedSignature.toByteArray(),
                calculatedSignature.toByteArray()
            )
        } catch (e: Exception) {
            logger.error("Error validating webhook signature", e)
            return false
        }
    }

    private suspend fun handlePushEvent(payload: String) {
        try {
            val pushEvent = json.decodeFromString<GitHubPushEvent>(payload)
            val branchName = pushEvent.ref.removePrefix("refs/heads/")
            val repoName = pushEvent.repository.fullName

            if (pushEvent.commits.isEmpty()) {
                logger.info("Skipping push event with no commits")
                return
            }

            val commitCount = pushEvent.commits.size
            val authorName = pushEvent.commits.firstOrNull()?.author?.name ?: pushEvent.sender.login
            val formattedCommits = formatCommitMessages(pushEvent.commits)
            val pluralSuffix = if (commitCount > 1) "s" else ""

            val message = SlackMessage(
                text = "*${authorName}* pushed ${commitCount} commit${pluralSuffix} to " +
                        "<${pushEvent.repository.htmlUrl}/tree/${branchName}|${repoName}:${branchName}>\n$formattedCommits"
            )

            slackClient.sendMessage(message)
        } catch (e: Exception) {
            logger.error("Error processing push event", e)
            throw e
        }
    }

    private fun formatCommitMessages(commits: List<GitHubCommit>): String {
        return commits.joinToString("\n") { commit ->
            val shortId = commit.id.take(7)
            val shortMessage = commit.message.lines().first()
            "• <${commit.url}|$shortId>: $shortMessage"
        }
    }

    private suspend fun handlePullRequestEvent(payload: String) {
        try {
            val prEvent = json.decodeFromString<GitHubPullRequestEvent>(payload)
            val pullRequest = prEvent.pullRequest
            val action = prEvent.action
            val repository = prEvent.repository
            val supportedActions = listOf("opened", "closed", "reopened")

            if (action !in supportedActions) {
                logger.info("Ignoring PR action: $action")
                return
            }

            val isMerged = pullRequest.merged == true
            val emoji = getPullRequestEmoji(action, isMerged)
            val actionText = getPullRequestActionText(action, isMerged)

            val message = SlackMessage(
                text = "$emoji Pull Request ${actionText}: <${pullRequest.htmlUrl}|#${pullRequest.number} ${pullRequest.title}> " +
                        "by *${pullRequest.user.login}* in <${repository.htmlUrl}|${repository.fullName}>"
            )

            slackClient.sendMessage(message)
        } catch (e: Exception) {
            logger.error("Error processing pull request event", e)
            throw e
        }
    }

    private fun getPullRequestEmoji(action: String, isMerged: Boolean): String {
        return when {
            action == "opened" || action == "reopened" -> ":pr-open:"
            isMerged -> ":merged:"
            action == "closed" -> ":pr-closed:"
            else -> ":information_source:"
        }
    }

    private fun getPullRequestActionText(action: String, isMerged: Boolean): String {
        return when {
            action == "opened" -> "opened"
            action == "reopened" -> "reopened"
            isMerged -> "merged"
            action == "closed" -> "closed"
            else -> action
        }
    }
}
