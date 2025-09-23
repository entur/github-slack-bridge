# GitHub Slack Bridge

This service forwards GitHub webhook events to Slack channels.

### Single-repository setup

1. Go to your GitHub repository and click on **Settings**.
2. Select **Webhooks** from the left sidebar.
3. Click the **Add webhook** button.
4. In the **Payload URL** field, enter: `https://your-deployment-url/webhook/your-slack-channel`
    - Replace `your-deployment-url` with the base URL where this service is deployed (for EnTur, this is `github-slack-bridge.dev.entur.org`).
    - Replace `your-slack-channel` with the name of the Slack channel you want to send notifications to.
5. Set the **Content type** to `application/json`.
6. In the **Secret** field, enter your webhook secret. **This field is mandatory** and must match the `GITHUB_WEBHOOK_SECRET` environment variable set in the service
   (for EnTur, this is available in LastPass).
7. Under **Which events would you like to trigger this webhook?**:
    - Select **Let me select individual events**.
    - Check the following events: `Push`, `Pull requests`, and `Workflow runs`.
8. Make sure **Active** is checked.
9. Click **Add webhook** to save your configuration.

After setting up the webhook, GitHub will send a ping event to verify the connection. You should see this event in your service logs.

### Batch setup

If you want to add the webhook to multiple repositories, you can use the `gh_webook_tool.sh` script to automate the process.
For EnTur, the secret is available in LastPass. By default it *does not* make any changes. Add `--no-dry-run` to actually make changes:

```bash
./gh_webhook_tool.sh --secret 'webhook secret' --channel 'your-slack-channel' topic some-topic # For RoR we use 'ror'
./gh_webhook_tool.sh --secret 'webhook secret' --channel 'your-slack-channel' team some-team # For RoR we use 'team-ruter-reiseplanlegger'
```

## Development

### Server API Endpoint

**POST** `/webhook/{channel}`

Configure your GitHub repository webhooks to send events to this endpoint. The `{channel}` parameter in the URL specifies which Slack channel should receive the notifications.

### Service Configuration

This service requires the following environment variables (for EnTur, these are available in LastPass):

- `SLACK_WEBHOOK_URL` - **Required**. The Slack incoming webhook URL that messages will be sent to.
  - Example: `https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXX`
- `GITHUB_WEBHOOK_SECRET` - **Required**. The secret used to validate GitHub webhook payloads.

### Supported GitHub Events

- Push events (to main/master branches)
- Pull request events (opened, closed, reopened)
- Workflow run events (failed builds and fixed builds)

### Requirements

- Java 21 or higher
- Kotlin 2.2.0

### Running the Application

#### Using Gradle

```bash
./gradlew run
```

#### Using Docker

```bash
docker build -t github-slack-bridge .
docker run -p 8080:8080 \
  -e SLACK_WEBHOOK_URL=your_slack_webhook_url \
  -e GITHUB_WEBHOOK_SECRET=your_github_secret \
  github-slack-bridge
```

#### Using the JAR

```bash
./gradlew shadowJar
java -jar build/libs/github-slack-bridge-all.jar
```

### Running Tests

```bash
./gradlew test
```

### Building

```bash
./gradlew build
```

## License

This project is maintained by EnTur, using European Union Public License 1.2

