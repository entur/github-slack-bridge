package no.entur.github_slack_bridge.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubPushEvent(
    val ref: String,
    @SerialName("head_commit") val headCommit: HeadCommit? = null,
    val commits: List<GitHubCommit> = emptyList(),
    val repository: Repository,
    val sender: Sender
) {
    @Serializable
    data class HeadCommit(
        val id: String,
        val message: String,
        val timestamp: String,
        val url: String,
        val author: Author,
        val committer: Committer,
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val modified: List<String> = emptyList()
    )

    @Serializable
    data class Repository(
        val id: Int = 0,
        val name: String = "",
        @SerialName("full_name") val fullName: String,
        val description: String? = null,
        val url: String = "",
        @SerialName("html_url") val htmlUrl: String,
        val owner: Owner = Owner(login = "", id = 0)
    )

    @Serializable
    data class Owner(
        val name: String? = null,
        val email: String? = null,
        val login: String,
        val id: Int,
        @SerialName("avatar_url") val avatarUrl: String? = null
    )

    @Serializable
    data class Sender(
        val login: String,
        val id: Int = 0,
        @SerialName("avatar_url") val avatarUrl: String? = null
    )
}

@Serializable
data class GitHubCommit(
    val id: String,
    val message: String,
    val timestamp: String = "",
    val url: String,
    val author: Author,
    val committer: Committer = Committer(name = "", email = ""),
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val modified: List<String> = emptyList()
)

@Serializable
data class Author(
    val name: String,
    val email: String = "",
    val username: String? = null
)

@Serializable
data class Committer(
    val name: String,
    val email: String,
    val username: String? = null
)

@Serializable
data class GitHubPullRequestEvent(
    val action: String,
    @SerialName("pull_request") val pullRequest: PullRequest,
    val repository: GitHubPushEvent.Repository,
    val sender: GitHubPushEvent.Sender
) {
    @Serializable
    data class PullRequest(
        val url: String,
        @SerialName("html_url") val htmlUrl: String,
        val id: Int,
        val number: Int,
        val title: String,
        val state: String,
        val user: User,
        val body: String? = null,
        val merged: Boolean? = null,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
        @SerialName("closed_at") val closedAt: String? = null,
        @SerialName("merged_at") val mergedAt: String? = null
    )

    @Serializable
    data class User(
        val login: String,
        val id: Int,
        @SerialName("avatar_url") val avatarUrl: String? = null
    )
}

@Serializable
data class GitHubWorkflowRunEvent(
    val action: String,
    @SerialName("workflow_run") val workflowRun: WorkflowRun,
    val repository: GitHubPushEvent.Repository,
    val sender: GitHubPushEvent.Sender
) {
    @Serializable
    data class WorkflowRun(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String?,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
        @SerialName("workflow_id") val workflowId: Long,
        @SerialName("head_branch") val headBranch: String,
        @SerialName("head_sha") val headSha: String,
        @SerialName("check_suite_id") val checkSuiteId: Long,
        @SerialName("actor") val actor: Actor,
        @SerialName("run_number") val runNumber: Int
    )

    @Serializable
    data class Actor(
        val login: String,
        val id: Long,
        @SerialName("avatar_url") val avatarUrl: String? = null
    )
}
