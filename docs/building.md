# Building

## Plugin Jar
```bash
./gradlew clean build
```

The normal plugin jar is written to `build/libs/`.

Use `build/libs/MayorSystem-<version>.jar` for server installs and Spigot uploads.
Do not upload `-api`, `-sources`, `-javadoc`, or `-all` jars to Spigot; those are not the normal server plugin upload artifact.

## Addon API Package
MayorSystem provides the addon API on Maven Central so addon developers can use `mavenCentral()` with no repository credentials:

```text
io.github.louguerrier22:mayorsystem-api:<version>
```

Addon projects should use the API package as a `compileOnly` dependency. The full MayorSystem plugin jar is still required on the server at runtime.

When built locally, addon API artifacts are written to `build/api-libs/` so they are kept separate from the server plugin jar.

## Local Fat Jar
```bash
./gradlew shadowJar
```

The `-all` jar is for local/offline testing only. Use the normal jar for server installs and uploads.
