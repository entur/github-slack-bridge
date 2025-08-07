#!/usr/bin/env bash

set -uo pipefail

ORG=entur
DRY_RUN=true
WEBHOOK_URL=https://github-slack-bridge.dev.entur.org/webhook

usage() {
  cat <<EOF

Usage: $0 [OPTIONS] COMMAND [ARG]

Commands:
  team TEAM_NAME      Add webhooks to all repositories administered by TEAM_NAME
  topic TOPIC_NAME    Add webhooks to all repositories with TOPIC_NAME

Options:
  --org ORG           GitHub organization (default: $ORG)
  --no-dry-run        Disable dry run mode (default: dry run is enabled)
  --secret SECRET     Webhook secret
  --url URL           Webhook base URL (default: $WEBHOOK_URL)
  --channel CHANNEL   Slack channel name
  -h, --help          Show this help message

Examples:
  $0 --secret "my secret" --channel "the-slack-channel" team team-ruter-reiseplanlegger
  $0 --no-dry-run --secret "my secret" --channel "the-slack-channel" topic ror
EOF
}

require_argument() {
  local option="$1"
  if [[ $# -lt 3 ]]; then
    echo "Error: $option requires a value" >&2
    usage
    exit 1
  fi
}

require_variable() {
  local var_value="$1"
  local error_message="$2"

  if [[ -z "${var_value:-}" ]]; then
    echo "Error: $error_message" >&2
    usage
    exit 1
  fi
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --org)
      require_argument "$1" $#
      ORG="$2"
      shift 2
      ;;
    --no-dry-run)
      DRY_RUN=false
      shift
      ;;
    --secret)
      require_argument "$1" $#
      SECRET="$2"
      shift 2
      ;;
    --url)
      require_argument "$1" $#
      WEBHOOK_URL="$2"
      shift 2
      ;;
    --channel)
      require_argument "$1" $#
      CHANNEL="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    team)
      require_argument "$1" $#
      COMMAND="team"
      TEAM_NAME="$2"
      shift 2
      ;;
    topic)
      require_argument "$1" $#
      COMMAND="topic"
      TOPIC_NAME="$2"
      shift 2
      ;;
    *)
      echo "Error: Unknown option or command: $1" >&2
      usage
      exit 1
      ;;
  esac
done

require_variable "${COMMAND:-}" "No command specified"
require_variable "${SECRET:-}" "--secret is required"
require_variable "${CHANNEL:-}" "--channel is required"

FULL_WEBHOOK_URL="$WEBHOOK_URL/$CHANNEL"

team_repos() {
  local team=$1

  gh api "orgs/$ORG/teams/$team/repos" --paginate --jq '.[] | select(.archived == false) | .name'
}

repo_admins() {
  local repo=$1

  gh api "/repos/$ORG/$repo/teams" --jq '.[] | select(.permission == "admin") | .slug' 2>/dev/null
}

has_repo_admin() {
  local repo=$1
  local team=$2

  if [[ "$(repo_admins "$repo")" == *"$team"* ]]; then
    return 0
  else
    return 1
  fi
}

has_webhook() {
  local repo=$1
  local has_hook=$(gh api "/repos/$ORG/$repo/hooks" --jq "any(.[]; .config.url==\"$FULL_WEBHOOK_URL\")" 2>/dev/null)
  if [[ "$has_hook" == "true" ]]; then
    return 0
  else
    return 1
  fi
}

add_webhook() {
  local repo=$1

  local json_payload=$(cat <<EOF
{
  "name": "web",
  "active": true,
  "events": ["push", "pull_request", "workflow_run"],
  "config": {
    "url": "$FULL_WEBHOOK_URL",
    "content_type": "json",
    "secret": "$SECRET",
    "insecure_ssl": "0"
  }
}
EOF
)

  if [[ "$DRY_RUN" == "true" ]]; then
    echo "$ORG/$repo: would have created webhook [dry run] ℹ️"
  else
    local output
    local exit_code

    gh api --method POST \
            -H "Accept: application/vnd.github.v3+json" \
            "/repos/$ORG/$repo/hooks" \
            --input <(echo "$json_payload") >/dev/null 2>&1 || exit_code=$?

    if [[ "${exit_code:-0}" -ne 0 ]]; then
      echo "$ORG/$repo: failed to create webhook, probably missing permissions ⛔️" >&2
      return 1
    else
      echo "$ORG/$repo: webhook created ✅"
    fi
  fi
}

topic_repos() {
  local topic=$1

  gh repo list "$ORG" --limit 1000 --json name,repositoryTopics,isArchived \
    -q ".[] | select(.repositoryTopics != null and (.repositoryTopics[].name == \"$topic\") and (.isArchived == false)) | .name"
}

add_webhook_by_topic() {
  local topic=$1

  echo "Fetching repositories with topic '$topic' in org '$ORG'..."

  topic_repos "$topic" | while read -r repo; do
    if has_webhook "$repo"; then
      echo "$ORG/$repo already has webhook, skipping."
    else
      add_webhook "$repo"
    fi
  done
}

add_webhook_by_team() {
  local team=$1

  echo "Fetching repositories with team '$team' in org '$ORG'..."

  team_repos "$team" | while read -r repo; do
    if has_repo_admin "$repo" "$team"; then
      if has_webhook "$repo"; then
        echo "$ORG/$repo: already has webhook, skipping."
      else
        add_webhook "$repo"
      fi
    else
      echo "$ORG/$repo: missing admin permission ⛔️"
    fi
  done
}

# Execute the command
case $COMMAND in
  team)
    add_webhook_by_team "$TEAM_NAME"
    ;;
  topic)
    add_webhook_by_topic "$TOPIC_NAME"
    ;;
esac
