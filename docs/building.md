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

## Local Fat Jar
```bash
./gradlew shadowJar
```

The `-all` jar is for local/offline testing only. Use the normal jar for server installs and uploads.
