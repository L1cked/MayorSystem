# How To Make A MayorSystem Addon

## Gradle
For normal addon development, depend on the MayorSystem API package and keep it `compileOnly`.

<!-- Only bump this API coordinate after manually running the publish-maven-central-api GitHub Actions workflow for that version. -->

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:<your-paper-version>")
    compileOnly("io.github.louguerrier22:mayorsystem-api:1.1.5")
}
```

Choose the Paper API version that matches the Minecraft versions your addon supports. `compileOnly` means the API classes are available while compiling the addon, but they are not bundled into the addon jar. The installed MayorSystem plugin provides the runtime API on the server.

## plugin.yml
Use `depend` if your addon requires MayorSystem:

```yml
depend:
  - MayorSystem
```

Use `softdepend` if the addon can run without MayorSystem:

```yml
softdepend:
  - MayorSystem
```

## Loading The API
```kotlin
import mayorSystem.api.MayorAddonRegistration
import mayorSystem.api.MayorSystemApi
import org.bukkit.plugin.java.JavaPlugin

class ExampleMayorAddon : JavaPlugin() {
    private var registration: MayorAddonRegistration? = null

    override fun onEnable() {
        val api = server.servicesManager.load(MayorSystemApi::class.java)
        if (api == null) {
            logger.warning("MayorSystem API is not available.")
            return
        }

        logger.info("Current mayor: ${api.currentMayor()?.lastKnownName ?: "none"}")
        registration = api.registerPerkSource(this, ExamplePerkSource())
    }

    override fun onDisable() {
        registration?.close()
        registration = null
    }
}
```

## Listening To Events
```kotlin
import mayorSystem.api.events.MayorPerksAppliedEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class MayorAddonListener : Listener {
    @EventHandler
    fun onMayorPerksApplied(event: MayorPerksAppliedEvent) {
        // React to the active perk ids for this term.
    }
}
```

## Adding Perks
See [External perk sources](external-perks.md).
