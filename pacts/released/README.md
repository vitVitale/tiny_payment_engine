# Released pacts

The pact files of each release, one folder per version (`<major>.<minor>.<patch>/`), committed by the release job
(`.github/workflows/release.yml`, specs/20-testing-strategy.md §10.3). Never edit them by hand: they are what the
consumers of that release expect.

- **Backward check** (every pull request, `-Ppact-verify`): the providers of this commit also verify the newest folder
  here, through `@PactVerification` on their verification tests.
- **Forward check** (nightly and before every release, `.github/scripts/pact-forward-check.sh`): the providers of the
  last release tag verify this commit's pacts.

Until the first release this folder holds no version, and both checks pass with "nothing to verify".
