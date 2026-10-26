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
