#!/bin/bash
# Creates the topics of deploy/kafka/topics.yml (specs/00-engineering-baseline.md §13) and aligns their retention and
# cleanup policy. Idempotent; fails on a partition count that differs from the file (02 §2 forbids changing it).
# Usage: kafka-init.sh <topics.yml> <bootstrap-server>
set -euo pipefail

file=$1
bootstrap=$2
bin=/opt/kafka/bin

until "$bin/kafka-broker-api-versions.sh" --bootstrap-server "$bootstrap" >/dev/null 2>&1; do
  sleep 1
done

# one line per topic: name partitions retention-ms cleanup-policy
topics=$(awk '
  { sub(/#.*/, ""); gsub(/^[ \t-]+|[ \t\r]+$/, "") }
  /^name:/            { if (name != "") print name, partitions, retention, cleanup; name = $2 }
  /^partitions:/      { partitions = $2 }
  /^retention-ms:/    { retention = $2 }
  /^cleanup-policy:/  { cleanup = $2 }
  END                 { if (name != "") print name, partitions, retention, cleanup }
' "$file")

while read -r name partitions retention cleanup; do
  if [[ -z "$partitions" || -z "$retention" || -z "$cleanup" ]]; then
    echo "kafka-init: incomplete entry for topic $name in $file" >&2
    exit 1
  fi
  "$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$name" \
    --partitions "$partitions" --replication-factor 1 \
    --config "retention.ms=$retention" --config "cleanup.policy=$cleanup" >/dev/null
  "$bin/kafka-configs.sh" --bootstrap-server "$bootstrap" --alter --entity-type topics --entity-name "$name" \
    --add-config "retention.ms=$retention,cleanup.policy=$cleanup" >/dev/null
  actual=$("$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --describe --topic "$name" \
    | sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p')
  if [[ "$actual" != "$partitions" ]]; then
    echo "kafka-init: topic $name has $actual partitions, $file declares $partitions" >&2
    exit 1
  fi
  echo "kafka-init: $name (partitions $partitions, retention.ms $retention, cleanup.policy $cleanup)"
done <<< "$topics"
