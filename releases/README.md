# Installing

## aotvloader-1.0.0.jar  (install this one)

Put it in `mods/` and leave it there. On every launch it fetches the current
pathfinder build and starts it, so the pathfinder jar never has to be replaced
by hand again.

The pathfinder is downloaded to `<instance>/minecraft/aotv/` rather than
`mods/`. That is deliberate: Fabric opens every jar in `mods/` at startup and
holds it open, and an open jar cannot be replaced on Windows, so a mod that
updates itself in place can only ever take effect on the *next* launch. Keeping
it somewhere Fabric never looks means the update applies to the run that
downloaded it.

**Delete `aotvpathfinder-*.jar` from `mods/` when you install the loader.**
If both are present Fabric starts the installed copy itself, and the loader
stands down rather than registering everything twice. It will say so in the log.

## aotvpathfinder-1.0.0.jar  (only if you don't want the loader)

The mod on its own. Put it in `mods/` and update it manually.

# Settings

`<instance>/minecraft/aotv/loader.properties` is written on first run:

    jar_url=https://raw.githubusercontent.com/.../releases/aotvpathfinder-1.0.0.jar

Point it at another branch or a fork to track that instead.

# Behaviour when GitHub is unreachable

The download is skipped and the copy already in `aotv/` is used, so the mod
still starts offline. Only a first run with no connection leaves nothing to
load. A failed or non-jar response never overwrites a working copy.
