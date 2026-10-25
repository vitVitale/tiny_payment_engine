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
