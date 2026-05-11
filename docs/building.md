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
MayorSystem publishes the addon API to Maven Central so addon developers can use `mavenCentral()` with no repository credentials:

```text
io.github.louguerrier22:mayorsystem-api:<version>
```

Local Maven publish test:

```bash
./gradlew publishMayorSystemApiPublicationToMavenLocal
```

Build the signed Maven Central Portal bundle:

```bash
./gradlew clean centralPortalBundle
```

This writes a Central Portal upload archive to `build/central-portal/`.

The included `publish-maven-central-api` GitHub Actions workflow builds that signed bundle and uploads it to the Central Portal. By default it uses `USER_MANAGED` publishing, so the deployment still needs to be reviewed and published from the Central Portal after validation.

Required GitHub Actions secrets:
- `SIGNING_KEY`: ASCII-armored private GPG key.
- `SIGNING_PASSWORD`: GPG key password.
- `CENTRAL_PORTAL_USERNAME`: Sonatype Central Portal user-token username.
- `CENTRAL_PORTAL_PASSWORD`: Sonatype Central Portal user-token password.

For local bundle builds, use Gradle properties or environment variables instead of committing secrets:

```properties
signingInMemoryKey=ASCII-armored-private-key
signingInMemoryKeyPassword=key-password
```

## Local Fat Jar
```bash
./gradlew shadowJar
```

The `-all` jar is for local/offline testing only. Use the normal jar for server installs and uploads.
