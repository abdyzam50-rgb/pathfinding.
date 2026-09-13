# Installing

## aotvloader-1.4.0.jar  (install this one)

Put it in `mods/` and leave it there. It keeps `mods/aotvpathfinder.jar`
current on its own, so the jar never has to be replaced by hand again.

**You do not need to download aotvpathfinder yourself.** The loader fetches it.

Updates take effect on the launch after they are found:

    launch 1   downloads the new build, installs it, then closes
    launch 2   runs it

The close is deliberate. Fabric has already chosen which mods it is loading by
the time the loader runs, so continuing would play the previous build for a
whole session while the new one sat unused on disk -- looking like it had
updated when it had not. Nothing is lost by stopping: no window has opened and
no world has loaded. The launcher reports it as an ordinary close, not a crash.

The delay is unavoidable. Fabric's startup goes: discover mods, load them, then
run preLaunch code. The loader runs at preLaunch, which is the earliest a mod
can act and the last moment the jar is still replaceable — but discovery has
already happened by then, so a jar written now is picked up on the following
launch.

Both halves have to happen in that one preLaunch, though. Downloading later in
the launch and installing at the next one costs an extra launch for nothing,
because the jar then lands after discovery has already been and gone.

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
