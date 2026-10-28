# Golden samples

One sample per message type and version: `<Type>.v<n>.json`, the JSON body only (type and version travel in
headers, [02 §1](../../../../../../specs/02-messaging-contracts.md#1-conventions)). `GoldenSampleTest` checks every
message in its list:
(a) the current record serialises to its current sample,
(b) every sample up to the current version still deserialises, and
(c) a sample with extra unknown fields deserialises.

## Evolution checklist ([01 §4](../../../../../../specs/01-shared-libraries.md#4-contracts))

Before changing a message record:

- [ ] Only **add** fields, and mark each new field `@OptionalField`. Consumers on the previous version still read the
      new samples, and new consumers still read old ones.
- [ ] Never remove, rename or change the type of a field.
- [ ] Never change the meaning of an enum constant.
- [ ] Before adding an enum constant, check that every consumer handles unknown constants: they read them as
      `UNKNOWN`, and send the message to the DLT only if they cannot proceed.
- [ ] Bump `@Contract(version = …)` and add `<Type>.v<n+1>.json`. Keep every older sample: they are the
      backward-compatibility tests.
- [ ] A breaking change is a **new message type** (`…V2`) with its own `@Contract` and samples. Publish it in parallel
      with the old type until every consumer has migrated.
- [ ] A new message type: add it to `Message`'s `permits`, add a `v1` sample, and add it to
      `GoldenSampleTest.messages()`.
