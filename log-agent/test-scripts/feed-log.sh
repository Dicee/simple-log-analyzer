#!/usr/bin/env bash
# Feed lines from a source file to a target file one at a time with randomized jitter.
# Usage: feed-log.sh <source> <target> [min_ms] [max_ms]
#   min_ms  minimum delay between lines in milliseconds (default: 200)
#   max_ms  maximum delay between lines in milliseconds (default: 2000)

set -euo pipefail

usage() {
  echo "Usage: $0 <source> <target> [min_ms] [max_ms]" >&2
  exit 1
}

[ $# -lt 2 ] && usage

SOURCE="$1"
TARGET="$2"
MIN_MS="${3:-200}"
MAX_MS="${4:-2000}"

[ ! -f "$SOURCE" ] && { echo "Error: source file '$SOURCE' not found" >&2; exit 1; }
[ "$MIN_MS" -ge "$MAX_MS" ] && { echo "Error: min_ms must be less than max_ms" >&2; exit 1; }

RANGE=$(( MAX_MS - MIN_MS ))

while IFS= read -r line; do
  echo "$line" >> "$TARGET"
  delay_ms=$(( MIN_MS + RANDOM % RANGE ))
  sleep "$(echo "scale=3; $delay_ms / 1000" | bc)"
done < "$SOURCE"
