# Contributing to RTP

Thank you for your interest in contributing to the RTP plugin! To ensure a smooth workflow and safe changes, please adhere to the following guidelines.

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

## Pull Requests
* Provide a clear and descriptive title for your PR.
* Explain the problem being solved and how your changes address it.
* Mention any related issues.
