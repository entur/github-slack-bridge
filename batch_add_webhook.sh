#!/usr/bin/env bash

set -uo pipefail

ORG=entur
DRY_RUN=true
UPDATE_SECRET=false
WEBHOOK_URL=https://github-slack-bridge.dev.entur.org/webhook

usage() {
  cat <<EOF

Usage: $0 [OPTIONS] COMMAND [ARG]

Commands:
  team TEAM_NAME      Add webhooks to all repositories administered by TEAM_NAME
  topic TOPIC_NAME    Add webhooks to all repositories with TOPIC_NAME

Options:
  --org ORG             GitHub organization (default: $ORG)
  --no-dry-run          Disable dry run mode (default: dry run is enabled)
  --secret SECRET       Webhook secret
  --url URL             Webhook base URL (default: $WEBHOOK_URL)
  --channel CHANNEL     Slack channel name
  --update-secret       Update secret on existing webhooks instead of creating new ones
  -h, --help            Show this help message

Examples:
  $0 --secret "my secret" --channel "the-slack-channel" team team-ruter-reiseplanlegger
  $0 --no-dry-run --secret "my secret" --channel "the-slack-channel" topic ror
  $0 --update-secret --secret "new secret" --channel "the-slack-channel" topic ror
EOF
}

require_argument() {
  local option="$1"
  local remaining_args="$2"
  if [[ $remaining_args -lt 2 ]]; then
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
    --update-secret)
      UPDATE_SECRET=true
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

has_repo_admin() {
  local repo=$1
  local team=$2
  gh api "/repos/$ORG/$repo/teams" --paginate 2>/dev/null \
    | jq -e --arg t "$team" 'any(.[]; .slug == $t and .permission == "admin")' >/dev/null
}

# Prints the id of the bridge's webhook on $repo (empty if none).
# Returns 2 with an error if the API call fails or multiple matches exist.
webhook_id() {
  local repo=$1
  local output ids count
  if ! output=$(gh api "/repos/$ORG/$repo/hooks" --paginate 2>&1); then
    echo "$ORG/$repo: failed to list webhooks: $output ⛔️" >&2
    return 2
  fi
  ids=$(printf '%s' "$output" | jq -r --arg url "$FULL_WEBHOOK_URL" \
          '.[] | select(.name == "web" and .config.url == $url) | .id')
  count=$(printf '%s' "$ids" | grep -cE '^[0-9]+$' || true)
  if [[ $count -gt 1 ]]; then
    echo "$ORG/$repo: $count matching webhooks found, refusing to modify ⛔️" >&2
    return 2
  fi
  printf '%s' "$ids"
}

add_webhook() {
  local repo=$1
  local json_payload
  json_payload=$(jq -n \
    --arg url "$FULL_WEBHOOK_URL" \
    --arg secret "$SECRET" \
    '{
       name: "web",
       active: true,
       events: ["push", "pull_request", "workflow_run"],
       config: {
         url: $url,
         content_type: "json",
         secret: $secret,
         insecure_ssl: "0"
       }
     }')

  if [[ "$DRY_RUN" == "true" ]]; then
    echo "$ORG/$repo: would have created webhook [dry run] ℹ️"
    return
  fi

  local output
  if ! output=$(printf '%s' "$json_payload" \
                  | gh api --method POST \
                      -H "Accept: application/vnd.github.v3+json" \
                      "/repos/$ORG/$repo/hooks" \
                      --input - 2>&1); then
    echo "$ORG/$repo: failed to create webhook ⛔️ — $output" >&2
    return 1
  fi
  echo "$ORG/$repo: webhook created ✅"
}

update_webhook_secret() {
  local repo=$1
  local hook_id=$2
  local json_payload
  json_payload=$(jq -n \
    --arg url "$FULL_WEBHOOK_URL" \
    --arg secret "$SECRET" \
    '{
       config: {
         url: $url,
         content_type: "json",
         secret: $secret,
         insecure_ssl: "0"
       }
     }')

  if [[ "$DRY_RUN" == "true" ]]; then
    echo "$ORG/$repo: would have updated webhook secret [dry run] ℹ️"
    return
  fi

  local output
  if ! output=$(printf '%s' "$json_payload" \
                  | gh api --method PATCH \
                      -H "Accept: application/vnd.github.v3+json" \
                      "/repos/$ORG/$repo/hooks/$hook_id" \
                      --input - 2>&1); then
    echo "$ORG/$repo: failed to update webhook ⛔️ — $output" >&2
    return 1
  fi
  echo "$ORG/$repo: webhook secret updated ✅"
}

process_repo() {
  local repo=$1
  local hook_id
  if ! hook_id=$(webhook_id "$repo"); then
    return 1
  fi

  if [[ "$UPDATE_SECRET" == "true" ]]; then
    if [[ -z "$hook_id" ]]; then
      echo "$ORG/$repo: no matching webhook, skipping."
    else
      update_webhook_secret "$repo" "$hook_id"
    fi
  else
    if [[ -n "$hook_id" ]]; then
      echo "$ORG/$repo: already has webhook, skipping."
    else
      add_webhook "$repo"
    fi
  fi
}

topic_repos() {
  local topic=$1

  gh repo list "$ORG" --limit 1000 --json name,repositoryTopics,isArchived \
    | jq -r --arg topic "$topic" \
        '.[] | select(.repositoryTopics != null and (.repositoryTopics[].name == $topic) and (.isArchived == false)) | .name'
}

add_webhook_by_topic() {
  local topic=$1

  echo "Fetching repositories with topic '$topic' in org '$ORG'..."

  topic_repos "$topic" | while IFS= read -r repo; do
    process_repo "$repo"
  done
}

add_webhook_by_team() {
  local team=$1

  echo "Fetching repositories with team '$team' in org '$ORG'..."

  team_repos "$team" | while IFS= read -r repo; do
    if has_repo_admin "$repo" "$team"; then
      process_repo "$repo"
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
