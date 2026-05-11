# MayorSystem Addons

MayorSystem exposes one public API service for addons: `mayorSystem.api.MayorSystemApi`.

Addon jars should compile against `io.github.louguerrier22:mayorsystem-api:<version>`. At runtime, addons load the live API from Bukkit `ServicesManager`.

MayorSystem detects addon plugins at runtime. Addons that register perk sources can be mostly drop-in for server owners: install the addon, restart or reload, then MayorSystem seeds only missing perk config values and leaves existing server-owner edits untouched.

Supported addon operations:
- read immutable snapshots of active MayorSystem state;
- listen to MayorSystem events;
- register addon-provided perk sections;
- unregister cleanly on addon disable.

Do not depend on `MayorPlugin`, platform classes, services, stores, commands, menus, or config internals.

See [How to make an addon](how-to-make-addon.md).
