package no.entur.github_slack_bridge.github

import kotlinx.serialization.json.Json
import no.entur.github_slack_bridge.slack.SlackClient
import no.entur.github_slack_bridge.slack.SlackMessage
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

open class GitHubWebhookHandler(private val slackClient: SlackClient, protected val webhookSecret: String) {
    private val logger = LoggerFactory.getLogger(GitHubWebhookHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Consider persisting this:
    private val recentlyFailedBuilds = ConcurrentHashMap<String, FailedBuildInfo>()
    private val failureTrackingDuration = Duration.ofDays(7)

    open suspend fun handleWebhook(
        eventType: String?,
        payload: String,
        signature: String? = null,
        channel: String? = null
    ) {
        logger.info("Received GitHub webhook event: $eventType for channel: $channel")

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
            "push" -> handlePushEvent(payload, channel)
            "pull_request" -> handlePullRequestEvent(payload, channel)
            "workflow_run" -> handleWorkflowRunEvent(payload, channel)
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

    private suspend fun handlePushEvent(payload: String, channel: String?) {
        try {
            val pushEvent = json.decodeFromString<GitHubPushEvent>(payload)
            val branchName = pushEvent.ref.removePrefix("refs/heads/")
            val repoName = pushEvent.repository.fullName

            if (!supported_branches.contains(branchName)) {
                logger.info("Skipping push event for branch: $branchName (not $supported_branches")
                return
            }

            if (pushEvent.commits.isEmpty()) {
                logger.info("Skipping push event with no commits")
                return
            }

            val commitCount = pushEvent.commits.size
            val authorLogin = pushEvent.sender.login
            val lastCommitMsg = formatCommitMessages(pushEvent.commits)
            val compareUrl = pushEvent.compare
            val sha = pushEvent.commits.last().id.take(7)
            val pluralSuffix = if (commitCount > 1) "s" else ""

            val message = SlackMessage(
                text = ":rocket: pushed $commitCount commit$pluralSuffix to <${pushEvent.repository.htmlUrl}/tree/${branchName}|${repoName}> `$branchName` (<${compareUrl}|$sha>) $lastCommitMsg",
                channel = channel,
                username = authorLogin,
            )

            slackClient.sendMessage(message)
        } catch (e: Exception) {
            logger.error("Error processing push event", e)
            throw e
        }
    }

    private fun formatCommitMessages(commits: List<GitHubCommit>): String {
        var message = commits.last().message.lines().first()
        if (message.length > 40) {
            message = message.replace("\n", " ").take(40) + "…"
        }
        return message
    }

    private suspend fun handlePullRequestEvent(payload: String, channel: String?) {
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
                text = "$emoji Pull Request ${actionText}: <${pullRequest.htmlUrl}|#${pullRequest.number} ${pullRequest.title}> in <${repository.htmlUrl}|${repository.fullName}>",
                channel = channel,
                username = pullRequest.user.login,
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
            isMerged -> ":pr-merged:"
            action == "closed" -> ":pr-closed:"
            else -> ":info:"
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

    private suspend fun handleWorkflowRunEvent(payload: String, channel: String?) {
        try {
            val workflowRunEvent = json.decodeFromString<GitHubWorkflowRunEvent>(payload)
            val workflowRun = workflowRunEvent.workflowRun
            val repository = workflowRunEvent.repository

            val branchName = workflowRun.headBranch

            if (!supported_branches.contains(branchName)) {
                logger.info("Skipping workflow run event for branch: $branchName (not in $supported_branches)")
                return
            }

            val workflowKey = "${workflowRun.workflowId}:${branchName}"

            if (workflowRun.status == "completed") {
                val actorName = workflowRun.actor.login
                val shortSha = workflowRun.headSha.take(7)
                val repoName = repository.fullName
                val workflowName = workflowRun.name.split("[\n\r]".toRegex()).first()

                if (workflowRun.conclusion == "failure") {
                    val buildCreatedAt = Instant.parse(workflowRun.createdAt)
                    val buildAge = Duration.between(buildCreatedAt, Instant.now())

                    if (buildAge > Duration.ofDays(28)) {
                        logger.info("Ignoring failure for old build (${buildAge.toDays()} days old): ${workflowRun.name} #${workflowRun.runNumber}")
                    } else {
                        val again = if (recentlyFailedBuilds.contains(workflowKey)) " again" else ""
                        recentlyFailedBuilds[workflowKey] = FailedBuildInfo(
                            workflowName = workflowName,
                            htmlUrl = workflowRun.htmlUrl,
                            repoFullName = repoName,
                            failedAt = Instant.now()
                        )

                        val message = SlackMessage(
                            text = ":x: build failed$again: <${workflowRun.htmlUrl}|$workflowName #${workflowRun.runNumber}> " +
                                    "in <${repository.htmlUrl}|$repoName> " +
                                    "on `$branchName` (<${repository.htmlUrl}/commit/${workflowRun.headSha}|${shortSha}>)",
                            channel = channel,
                            username = actorName,
                        )

                        slackClient.sendMessage(message)
                        logger.info("Sent notification for failed build: ${workflowRun.name} #${workflowRun.runNumber}")
                    }
                } else if (workflowRun.conclusion == "success") {
                    val lastFailureInfo = recentlyFailedBuilds.remove(workflowKey)

                    if (lastFailureInfo != null) {
                        val timeSinceFailure = Duration.between(lastFailureInfo.failedAt, Instant.now())

                        if (timeSinceFailure <= failureTrackingDuration) {
                            val message = SlackMessage(
                                text = ":white_check_mark: build fixed: <${workflowRun.htmlUrl}|$workflowName #${workflowRun.runNumber}> " +
                                        "in <${repository.htmlUrl}|$repoName> " +
                                        "on `$branchName` (<${repository.htmlUrl}/commit/${workflowRun.headSha}|${shortSha}>)",
                                channel = channel,
                                username = actorName,
                            )

                            slackClient.sendMessage(message)
                            logger.info("Sent notification for fixed build: ${workflowRun.name} #${workflowRun.runNumber}")
                        }
                    } else {
                        logger.info("Ignoring successful workflow run that wasn't previously failing: ${workflowRun.name} #${workflowRun.runNumber}")
                    }
                }
            } else {
                logger.info("Ignoring workflow run with status: ${workflowRun.status}")
            }

            cleanupOldFailedBuilds()
        } catch (e: Exception) {
            logger.error("Error processing workflow_run event", e)
            throw e
        }
    }

    private fun cleanupOldFailedBuilds() {
        val now = Instant.now()
        val keysToRemove = recentlyFailedBuilds.entries
            .filter { (_, info) ->
                Duration.between(info.failedAt, now) > failureTrackingDuration
            }
            .map { it.key }

        keysToRemove.forEach { key ->
            recentlyFailedBuilds.remove(key)
        }
    }

    open fun getBuildStatus(): BuildStatus {
        cleanupOldFailedBuilds()
        val now = Instant.now()

        val failedBuilds = recentlyFailedBuilds.map { (key, info) ->
            val (workflowId, branch) = key.split(":", limit = 2)
            val duration = Duration.between(info.failedAt, now)
            FailedBuild(
                workflowId = workflowId,
                branch = branch,
                failedAt = info.failedAt.toString(),
                failedFor = formatDuration(duration),
                name = info.workflowName,
                htmlUrl = info.htmlUrl,
                repoFullName = info.repoFullName
            )
        }.sortedByDescending { it.failedAt }

        return BuildStatus(
            failedBuilds = failedBuilds,
            stats = BuildStats(
                totalFailedBuilds = failedBuilds.size,
                failedByBranch = failedBuilds.groupingBy { it.branch }.eachCount(),
                trackingDurationDays = failureTrackingDuration.toDays()
            )
        )
    }

    private fun formatDuration(duration: Duration): String {
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60

        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    companion object {
        val supported_branches = listOf("master", "main", "prod")
    }
}

@Serializable
data class BuildStatus(
    val failedBuilds: List<FailedBuild>,
    val stats: BuildStats
)

@Serializable
data class FailedBuild(
    val workflowId: String,
    val branch: String,
    val failedAt: String,
    val failedFor: String,
    val name: String,
    val htmlUrl: String,
    val repoFullName: String
)

data class FailedBuildInfo(
    val workflowName: String,
    val htmlUrl: String,
    val repoFullName: String,
    val failedAt: Instant
)

@Serializable
data class BuildStats(
    val totalFailedBuilds: Int,
    val failedByBranch: Map<String, Int>,
    val trackingDurationDays: Long
)
