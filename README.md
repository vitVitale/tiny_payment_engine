# tiny_payment_engine

SEPA Instant Credit Transfer engine. Specs are in [specs/](specs/README.md), architecture in [architecture/](architecture/README.md).

## Build

Prerequisite: JDK 25 on the `PATH` or in `JAVA_HOME`. Maven comes from the wrapper (pinned to 3.9.16).

```bash
./mvnw verify
```

- Unit tests live in `src/test` and are named `*Test`; Surefire runs them.
- Component tests live in `src/integrationTest` and are named `*IT`; Failsafe runs them in `verify`.
- The build fails on a test placed in the wrong source set for its name.

## Contract tests (Pact)

Consumer pact tests run with the build and write their pacts to `<module>/target/pacts`; `verify` collects them in
`target/pacts`. Providers verify them in a second step ([20 §10.3](specs/20-testing-strategy.md#103-verifying-pacts)):

```bash
./mvnw verify
```

```bash
./mvnw -Ppact-verify verify
```

That step also verifies the last release's pacts in `pacts/released/` (backward check). The forward check runs the last
release tag's providers against this commit's pacts (nightly and in the release job):

```bash
.github/scripts/pact-forward-check.sh
```

## Local stack

PostgreSQL, Kafka and the mock OAuth2 issuer of
[00 §13](specs/00-engineering-baseline.md#13-local-development) (needs Docker with Compose v2):

```bash
docker compose -f deploy/docker-compose.yml up -d --wait postgres kafka oauth2
```

- PostgreSQL on `localhost:5432`: one database per service, the login role has the database's name and the password
  `<database>-local` (e.g. `ledger` / `ledger-local`). A role can connect only to its own database. The data stays
  in the `postgres-data` volume; `deploy/postgres/init.sql` runs only on an empty one (`down -v` resets it).
- Kafka on `localhost:9092` (containers: `kafka:19092`), with the topics of `deploy/kafka/topics.yml` and no
  auto-creation.
- Mock OAuth2 issuer `http://localhost:8099/default`. A customer token with `sub`, `client_id`, `scope` and
  `accounts` (one IBAN, or `[]` without the parameter):

```bash
curl -s -X POST http://localhost:8099/default/token -d grant_type=client_credentials -d client_id=local-channel -d client_secret=local -d "scope=payments:write payments:read" -d sub=customer-1 -d accounts=DE89370400440532013000
```

With the `observability` profile (off by default) the stack also runs Alloy, Mimir, Loki, Tempo and Grafana of
[26 §13](specs/26-observability.md#13-environments) and a sample workload that produces a trace, a log line and a
latency histogram:

```bash
./mvnw -pl tests/observability-sample -am package -DskipTests
```

```bash
docker compose -f deploy/docker-compose.yml --profile observability up -d --wait
```

- Grafana on `http://localhost:3000` (no login locally), with Mimir, Loki and Tempo provisioned and correlated.
- OTLP on `localhost:4317` (gRPC) / `4318` (HTTP): a service started on the host with
  `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` sends its traces there. Only containers labelled
  `tpe.io/scrape=true` are scraped and have their logs collected.
- `curl -X POST localhost:8095/sample/payments` handles a synthetic payment. The pipeline check (OBS-AC-01 … -04):
  `./mvnw -pl tests/system-tests -am verify -Dtpe.observability-stack=true`.

Start a service against it with its `local` profile:

```bash
./mvnw -pl services/ledger-service spring-boot:run -Dspring-boot.run.profiles=local
```

The service skeletons have no database, Kafka or security dependencies yet, so for now the profile only sets their
port; the connection settings take effect as those dependencies arrive. The credentials above are committed on
purpose: they work only against this stack ([00 §12.2](specs/00-engineering-baseline.md#122-data-handling)).

Smoke check of the running stack (databases and roles, topics against
[02 §2](specs/02-messaging-contracts.md#2-topics), token claims); CI runs it as well:

```bash
./mvnw -pl tests/system-tests -am verify -Dtpe.local-stack=true
```

## Traceability check

Every acceptance criterion (`<SVC>-AC-nn`), `E2E-*` and `CH-*` id in `specs/*.md` (short forms such as
`ISO-AC-01, -02` and ranges such as `ORC-AC-21 … -26` included) needs a test carrying it
([20 §1](specs/20-testing-strategy.md#1-test-levels), Traceability row). Every integration `P-nn` / `M-nn` of the
[20 §10.1](specs/20-testing-strategy.md#101-integration-inventory) table needs a consumer pact test (in a file with
`@PactTestFor`) and a provider verification (in a file with `@Provider`) carrying it.

A test carries an id in the name of a test method (`@Test`, `@ParameterizedTest`, `@TestTemplate`, jqwik `@Property`,
…) or of a class in a file with test methods (`MSG_AC_03_…`, `E2E_O_12_…`, `PACT_P04_…`), or in a `@Tag` with a
literal value on either (`@Tag("MSG-AC-03")`, `@Tag("P-04")`). Ids in comments, strings, helper methods and fields
do not count. Run it from the repository root (JDK only, no build; `origin/main` must be fetched):

```bash
java tools/traceability-check/src/main/java/com/tpe/traceabilitycheck/TraceabilityCheck.java
```

Ids whose work is not delivered yet are listed in
[`tools/traceability-check/pending-ids.txt`](tools/traceability-check/pending-ids.txt). The list only shrinks: remove
an id in the change that adds its test; an id may be added only together with its first appearance in the specs.
"The specs on main" means main at the merge base with `HEAD`, so a branch behind main is not failed by later work
on main. Pass `--base=<ref>` to use another ref than `origin/main` (e.g. `--base=main` in a clone without that
remote branch).
