# GitHub Slack Bridge

Forwards GitHub webhook events to Slack channels.

For Entur, the service runs at `https://github-slack-bridge.dev.entur.org`, and the webhook secret is in 1Password.

## Supported events

- **Push** to the default branch
- **Pull request** opened or merged
- **Workflow run** failed or fixed, on the default branch (forks are ignored)

Failed builds are tracked in memory, so a redeploy drops pending "build fixed" notifications.

## Adding a webhook

### With the script

`batch_add_webhook.sh` adds the webhook to a single repository, or to every repository with a topic or administered by a team:

```bash
./batch_add_webhook.sh --secret 'webhook secret' --channel 'your-slack-channel' repo some-repo
./batch_add_webhook.sh --secret 'webhook secret' --channel 'your-slack-channel' topic some-topic # For RoR we use 'ror'
./batch_add_webhook.sh --secret 'webhook secret' --channel 'your-slack-channel' team some-team   # For RoR we use 'team-ruter-reiseplanlegger'
```

It runs as a dry run by default. Add `--no-dry-run` to make changes. See `--help` for all options.

### Manually

In the repository, go to **Settings > Webhooks > Add webhook** and set:

| Field        | Value                                                     |
|--------------|-----------------------------------------------------------|
| Payload URL  | `https://<service-url>/webhook/<slack-channel>`           |
| Content type | `application/json`                                        |
| Secret       | Must match `GITHUB_WEBHOOK_SECRET` in the service         |
| Events       | **Let me select individual events**: `Push`, `Pull requests`, `Workflow runs` |
| Active       | Checked                                                   |

GitHub sends a ping event to verify the connection, which shows up in the service logs.

## Development

Requires Java 21 or higher.

The service exposes one endpoint, `POST /webhook/{channel}`, and needs two environment variables:

- `SLACK_WEBHOOK_URL`: the Slack incoming webhook URL, e.g. `https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXX`
- `GITHUB_WEBHOOK_SECRET`: the secret used to validate GitHub webhook payloads

```bash
./gradlew run        # run locally
./gradlew test       # run tests
./gradlew build      # build
./gradlew shadowJar  # build build/libs/github-slack-bridge-all.jar
```

With Docker (build the JAR first):

```bash
docker build -t github-slack-bridge .
docker run -p 8080:8080 \
  -e SLACK_WEBHOOK_URL=your_slack_webhook_url \
  -e GITHUB_WEBHOOK_SECRET=your_github_secret \
  github-slack-bridge
```

## Deployment

A push to `main` dispatches a deploy in the internal repo
[github-slack-bridge-deployment-config](https://github.com/entur/github-slack-bridge-deployment-config), which
holds the helm chart and the deploy workflow.

## License

Maintained by Entur, licensed under the European Union Public License 1.2.
