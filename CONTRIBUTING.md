# Contributing to Eureka

## Building

The build requires **Java 21**. Source and target compatibility are set to `21`.

```bash
./gradlew build
```

## Code Quality

The build automatically runs the following quality checks:

- **JaCoCo** — code coverage reports (XML + HTML) generated after each test run, found in `<subproject>/build/reports/jacoco/`
- **SpotBugs** — static analysis for common bug patterns, reports in `<subproject>/build/reports/spotbugs/`
- **Checkstyle** — configuration at `codequality/checkstyle.xml` (available for local use)

SpotBugs is configured with `ignoreFailures = true` so it produces reports without blocking the build.

## Docker

A `Dockerfile` is provided at the repository root. It builds the `eureka-server` WAR and runs it via Jetty Runner.

```bash
docker build -t eureka-server .
docker run -p 8080:8080 eureka-server
```

The **Docker Build & Push** workflow (`.github/workflows/docker-publish.yml`) automatically builds and pushes images to GitHub Container Registry (`ghcr.io`) on:
- Pushes to `master` → tagged as `latest`
- Version tags (`v*.*.*`) → tagged with the version number

This workflow uses `GITHUB_TOKEN` automatically — no additional secrets are needed.

## CI/CD Workflows

### CI (`.github/workflows/nebula-ci.yml`)

Runs on every push and pull request:
- Builds and tests with Java 21
- Generates JaCoCo coverage reports (uploaded as artifacts)
- Generates SpotBugs static analysis reports (uploaded as artifacts)
- Runs dependency review on pull requests (reports critical vulnerabilities, non-blocking — requires [Dependency Graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-the-dependency-graph) to be enabled in repo settings)

### Snapshot Publish (`.github/workflows/nebula-snapshot.yml`)

Publishes snapshot artifacts on pushes to `master`.

### Release Publish (`.github/workflows/nebula-publish.yml`)

Publishes release artifacts when version tags are pushed.

## Releases

| Tag format | Release type |
|---|---|
| `v*.*.*` (e.g., `v1.2.3`) | Final release → Maven Central |
| `v*.*.*-rc.*` (e.g., `v1.2.3-rc.1`) | Release candidate → NetflixOSS |

To create a release:
```bash
git tag v1.2.3
git push origin v1.2.3
```

## Required GitHub Secrets

### `Publish` Environment

The following secrets must be configured in the **Publish** GitHub environment for the snapshot and release workflows:

| Secret | Purpose |
|---|---|
| `ORG_SIGNING_KEY` | GPG signing key for artifact signing |
| `ORG_SIGNING_PASSWORD` | Passphrase for the GPG signing key |
| `ORG_NETFLIXOSS_USERNAME` | NetflixOSS repository username |
| `ORG_NETFLIXOSS_PASSWORD` | NetflixOSS repository password |
| `ORG_SONATYPE_USERNAME` | Sonatype (Maven Central) username — used for final releases |
| `ORG_SONATYPE_PASSWORD` | Sonatype (Maven Central) password — used for final releases |

### Automatic Secrets

| Secret | Purpose |
|---|---|
| `GITHUB_TOKEN` | Used by CI and Docker publish workflows — provided automatically by GitHub Actions |
