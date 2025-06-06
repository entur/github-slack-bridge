package no.entur.github_slack_bridge

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import no.entur.github_slack_bridge.github.GitHubWebhookHandler
import no.entur.github_slack_bridge.slack.SlackClient

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val slackWebhookUrl = System.getenv("SLACK_WEBHOOK_URL")
        ?: throw IllegalStateException("SLACK_WEBHOOK_URL environment variable is required")
    val githubWebhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET")

    val slackClient = SlackClient(slackWebhookUrl)
    val githubWebhookHandler = GitHubWebhookHandler(slackClient, githubWebhookSecret)

    embeddedServer(Netty, port = port) {
        configureServer(githubWebhookHandler)
    }.start(wait = true)
}

fun Application.configureServer(githubWebhookHandler: GitHubWebhookHandler) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/") {
            val indexHtml = this::class.java.classLoader.getResource("index.html")?.readText()
                ?: "<html><body><h1>GitHub Slack Bridge</h1><p>Error loading index page.</p></body></html>"
            call.respondText(indexHtml, ContentType.Text.Html)
        }

        get("/actuator/health/liveness") {
            call.respondText("""{"status":"UP"}""", contentType = ContentType.Application.Json)
        }

        get("/actuator/health/readiness") {
            call.respondText("""{"status":"UP"}""", contentType = ContentType.Application.Json)
        }

        post("/webhook/{channel}") {
            val channel = call.parameters["channel"]
            val payload = call.receiveText()
            val eventType = call.request.header("X-GitHub-Event")
            val signature = call.request.header("X-Hub-Signature-256")

            try {
                githubWebhookHandler.handleWebhook(eventType, payload, signature, channel)
                call.respond(HttpStatusCode.OK, "Webhook processed successfully")
            } catch (e: Exception) {
                call.application.log.error("Error processing webhook", e)
                call.respond(HttpStatusCode.InternalServerError, "Error processing webhook: ${e.message}")
            }
        }
    }
}
