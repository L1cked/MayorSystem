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

Addon projects should use the published API package as a `compileOnly` dependency. The full MayorSystem plugin jar is still required on the server at runtime.

## Local Fat Jar
```bash
./gradlew shadowJar
```

The `-all` jar is for local/offline testing only. Use the normal jar for server installs and uploads.
