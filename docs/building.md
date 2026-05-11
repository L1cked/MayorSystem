# Building

## Plugin Jar
```bash
./gradlew clean build
```

The normal plugin jar is written to `build/libs/`.

## Addon API Jar
```bash
./gradlew apiJar
```

This produces `MayorSystem-<version>-api.jar`, which addon projects should use as a `compileOnly` dependency.

## Publish Addon API Package
MayorSystem can publish the API jar as a Maven package for addon developers:

```text
ca.l1cked:mayorsystem-api:<version>
```

Local publish test:

```bash
./gradlew publishMayorSystemApiPublicationToMavenLocal
```

Publish to GitHub Packages:

```bash
./gradlew clean apiJar publishMayorSystemApiPublicationToGitHubPackagesRepository
```

For local publishing, set credentials outside the repository in your user Gradle properties file:

```properties
gpr.user=YourGitHubUsername
gpr.key=YourGitHubToken
```

The included `publish-api-package` GitHub Actions workflow publishes the API package when a GitHub release is published, or when the workflow is run manually. The workflow uses `GITHUB_TOKEN` and publishes only the API artifact, not the full plugin jar.

## Local Fat Jar
```bash
./gradlew shadowJar
```

The `-all` jar is for local/offline testing only. Use the normal jar for server installs and uploads.
