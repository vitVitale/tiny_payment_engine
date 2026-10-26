#!/bin/bash
# Entrypoint of the local kafka service: starts the broker, runs kafka-init.sh, then marks the container ready, so
# `up -d kafka` alone yields the topics (the healthcheck waits for the marker).
set -euo pipefail

ready=/tmp/kafka-init.done
rm -f "$ready"

/__cacert_entrypoint.sh /etc/kafka/docker/run &
broker=$!
trap 'kill -TERM "$broker"; wait "$broker"' TERM INT

if ! bash /opt/tpe/kafka-init.sh /opt/tpe/topics.yml localhost:9092; then
  echo "kafka-init failed; stopping the broker" >&2
  kill -TERM "$broker"
  wait "$broker" || true
  exit 1
fi
touch "$ready"
wait "$broker"
