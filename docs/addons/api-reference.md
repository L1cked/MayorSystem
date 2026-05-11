# Addon API Reference

## Service
Load `MayorSystemApi` through Bukkit `ServicesManager`.

```kotlin
val api = server.servicesManager.load(MayorSystemApi::class.java)
```

## MayorSystemApi
```kotlin
fun snapshot(): MayorSystemSnapshot
fun currentMayor(): MayorSnapshot?
fun currentTerm(): TermSnapshot?
fun electionTerm(): TermSnapshot?
fun activePerkIds(): Set<String>
fun allPerkIds(): Set<String>
fun registerPerkSource(owner: Plugin, source: MayorPerkSource): MayorAddonRegistration
```

## Snapshots
Snapshots are immutable DTOs under `mayorSystem.api.snapshot`.

They expose:
- ready state;
- current term;
- election term;
- current mayor;
- active perks;
- display reward state;
- candidates, votes, fake vote adjustments, selected perks, and candidate bio.

Snapshots do not expose mutable config, storage rows, Bukkit players, or internal services.

## Threading
Call API methods from the server thread unless MayorSystem documents a method as async-safe. Addons should do their own expensive work asynchronously and return to the server thread before touching Bukkit state.

## Internal Classes
Do not import from:
- `mayorSystem.platform`
- `mayorSystem.service`
- `mayorSystem.data`
- `mayorSystem.config`
- `mayorSystem.ui`
- `mayorSystem.system`
- `mayorSystem.elections`
- `mayorSystem.perks`

Only `mayorSystem.api`, `mayorSystem.api.snapshot`, and `mayorSystem.api.events` are supported for addons.
