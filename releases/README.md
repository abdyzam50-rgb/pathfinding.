# Installing

## aotvloader-1.1.0.jar  (install this one)

Put it in `mods/` and leave it there. It keeps `mods/aotvpathfinder.jar`
current on its own, so the jar never has to be replaced by hand again.

**You do not need to download aotvpathfinder yourself.** The loader fetches it.

Updates take effect on the launch after they are found:

    launch 1   finds a new build, downloads it
    launch 2   installs it at startup, and runs it

That delay is unavoidable rather than a shortcut. Fabric holds every jar in
`mods/` open for the whole session, and an open jar cannot be replaced on
Windows, so the file fetched during one run is swapped in at the start of the
next — before anything has opened it.

Keeping the jar in `mods/` is what makes it a real mod to Fabric: it shows up in
the mod list, crash reports attribute it properly, and it would still work if
the pathfinder ever gained a mixin.

## aotvpathfinder-1.0.0.jar  (only if you don't want the loader)

The mod on its own. Put it in `mods/` and update it manually. If you install the
loader later it will take over this file.

# Settings

`<instance>/minecraft/aotv/loader.properties` is written on first run:

    jar_url=https://raw.githubusercontent.com/.../releases/aotvpathfinder-1.0.0.jar

Point it at another branch or a fork to track that instead.

# When GitHub is unreachable

The check is skipped and whatever is installed keeps running, so launching
offline is fine. Only a first run with no connection leaves nothing to install.
A failed or non-jar response is never staged, so a bad response cannot replace a
working jar with an error page.
