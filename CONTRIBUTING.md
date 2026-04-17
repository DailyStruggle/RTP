# Contributing to RTP

Thank you for your interest in contributing to the RTP plugin! To ensure a smooth workflow and safe changes, please adhere to the following guidelines.

## Contributor License Agreement

By submitting a pull request or otherwise contributing code, documentation, or other
material to this repository, you agree to the following terms (derived from the
[Apache Individual Contributor License Agreement v2.0](https://www.apache.org/licenses/icla.pdf)):

1. **Grant of Copyright License.** You grant the copyright holder a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to
   reproduce, prepare derivative works of, publicly display, publicly perform,
   sublicense, and distribute your contributions, including in commercially distributed
   binary releases.

2. **Grant of Patent License.** You grant the copyright holder a perpetual, worldwide,
   non-exclusive, no-charge, royalty-free, irrevocable patent license to make, have
   made, use, offer to sell, sell, import, and otherwise transfer the software, where
   such license applies only to patent claims licensable by you that are necessarily
   infringed by your contribution.

3. **Right to submit.** You represent that you are legally entitled to grant the above
   licenses. If your employer has rights to intellectual property you create, you
   represent that you have received permission to make this contribution on behalf of
   that employer.

4. **Original work.** You represent that your contribution is your original creation.
   If your contribution includes material that is not your original creation, you must
   identify it and provide its complete details (license, origin) in the pull request
   description.

5. **No warranty.** You are not expected to provide support for your contributions,
   and you provide them "as is", without warranty of any kind.

No transfer of copyright ownership is required. You retain all rights to your
contribution outside of this project.

## Prerequisites

* **Java 21**: This project targets Java 21. Ensure your JDK is installed and configured correctly.
* **Gradle**: The project is built using the Gradle wrapper (`gradlew`), so you do not need to install Gradle manually.

## Build Instructions

This codebase was put together in Intellij IDEA. You can open the base directory as a project, trust the sources, and build as-is with Gradle wrapper (`./gradlew build`).

More detailed instructions can be found here - https://github.com/DailyStruggle/RTP/wiki/Compiling-and-Editing

1. **Clone the repository**:
   ```sh
   git clone https://github.com/DailyStruggle/RTP.git
   cd RTP
   ```
2. **Build the project**:
   Use the provided Gradle wrapper to compile and build the plugin.
   ```sh
   ./gradlew build
   ```

## Development Guidelines

### Code Formatting
This project utilizes **Spotless** to enforce a consistent coding style.
* Before pushing your changes, run the following command to format your code:
  ```sh
  ./gradlew spotlessApply
  ```
* Continuous Integration (CI) may fail if the code is not formatted properly.

### Running Tests
We use JUnit for testing and Jacoco for coverage reports.
* To run the test suite:
  ```sh
  ./gradlew test
  ```
* Please ensure all tests pass locally before submitting a Pull Request.
* When adding new features or fixing bugs, please write accompanying unit tests in the appropriate module's `src/test` directory.

### Making Safe Changes
1. **Target the correct module**: If you are fixing a platform-specific issue (e.g., asynchronous chunk loading on Paper), apply changes in the respective adapter module (like `rtp-paper`). If it's a general logic issue, apply it to `rtp-core`.
2. **Use the API**: If your change could be implemented via `rtp-api` without modifying core code, consider writing an addon instead.
3. **Keep performance in mind**: RTP pre-calculates many teleports asynchronously to avoid server lag. Ensure that any new geometry or validation checks are thread-safe and performant.

## Adding or Changing Requirements

RTP uses a structured requirements workflow enforced by CI. If you add or modify a requirement, all four steps below must be completed **in the same commit** or the pipeline will fail.

1. **Add the requirement** to the correct `docs/REQUIREMENTS.md` file (or the relevant submodule `REQUIREMENTS.md`) with the next sequential ID in its category (e.g., `REQ-CORE-F-008`). Format:
   ```
   - **REQ-CORE-F-008 — Short Title:** The system must ...
   ```
2. **Add a row** to `docs/dev/TRACEABILITY.md` in the matching section:

   | Req ID | Description | Design Ref | Implementing Class(es) | Test(s) |
   |---|---|---|---|---|
   | REQ-CORE-F-008 | Short title | docs/DESIGN.md §X | `ClassName.java` | `TestClass#method` |

   Use `— (pending)` for columns that don't exist yet.

3. **Update `docs/DESIGN.md`** if the requirement introduces a new architectural decision.
4. **Write or update a test.** For architectural rules, add a rule to `RTPArchitectureTest.java`. For behavioral requirements, add a unit or integration test. For platform-specific behavior that cannot be automated, note `— (manual)` in the Test column.

The CI `Traceability Check` stage runs `check_traceability.sh` before the build and will fail with a list of untraced IDs if step 2 is skipped.

For the full ID scheme and category reference, see [REQUIREMENTS.md](docs/dev/REQUIREMENTS.md).
For term definitions used in requirements, see [GLOSSARY.md](docs/dev/GLOSSARY.md).
For actor and stakeholder context, see [STAKEHOLDERS.md](docs/dev/STAKEHOLDERS.md).

## Pull Requests

A PR template is provided at `.github/PULL_REQUEST_TEMPLATE.md` and will be pre-filled automatically when you open a PR on GitHub.

### Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Bug fix | `fix/<short-description>` | `fix/chunk-leak-on-reload` |
| New feature | `feature/<short-description>` | `feature/rectangle-shape` |
| Documentation | `docs/<short-description>` | `docs/adr-paperlib-removal` |
| Release prep | `release/<version>` | `release/3.0.0` |

### Definition of Ready to Merge

A PR is ready to merge when **all** of the following are true:
1. All CI checks pass (build, Spotless, traceability check, tests).
2. `CHANGELOG.md` has an entry under `[Unreleased]`.
3. Any new or modified REQ-IDs have a row in `docs/dev/TRACEABILITY.md`.
4. If the change breaks `rtp-api` compatibility, `docs/MIGRATION.md` is updated.

### Semantic Versioning Policy

RTP follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html). The version number is `MAJOR.MINOR.PATCH`:

| Change type | Version bump | Example |
|-------------|-------------|---------|
| Breaking change to `rtp-api` public interface | **MAJOR** | `3.x.x` → `4.0.0` |
| New feature, new config key, new command | **MINOR** | `3.0.x` → `3.1.0` |
| Bug fix, performance improvement, documentation | **PATCH** | `3.0.0` → `3.0.1` |

> **Addon developers:** A MAJOR bump means your addon must be recompiled and may require source changes. MINOR and PATCH bumps are always backward-compatible with existing addons compiled against the same MAJOR version.
