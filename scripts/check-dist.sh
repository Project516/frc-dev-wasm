#!/usr/bin/env bash
# Refuses an apps/ide/dist that lacks a separately built part, so it cannot be published.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
dist="$root/apps/ide/dist"
[ -f "$dist/index.html" ] || { echo "apps/ide/dist is missing; run pnpm build:all first" >&2; exit 1; }

# A dist from another commit, or from uncommitted changes, must not go live.
head=$(git -C "$root" describe --always --dirty --abbrev=40)
built=$(cat "$dist/.commit" 2>/dev/null || echo unknown)
[ "$built" = "$head" ] || { echo "apps/ide/dist was built from $built, not $head; run pnpm build:all" >&2; exit 1; }
case "$head" in *-dirty) echo "the working tree has uncommitted changes" >&2; exit 1;; esac

years=$(cd "$root" && node -e "import('./packages/runtime/src/years.ts').then((m) => console.log(m.WPILIB_YEARS.join(' ')))")
java_web_years=$(cd "$root" && node -e "import('./packages/runtime/src/years.ts').then((m) => console.log(m.WPILIB_YEARS.filter((y) => m.JAVA_WEB_YEARS.includes(y)).join(' ')))")
java_web_files=$([ -z "$java_web_years" ] || echo runtime/java-web/java-web.js runtime/java-web/java-web-worker.js runtime/java-web/boot.pack runtime/java-web/jdk/java.base.jar runtime/java-web/jdk/javac.pack)
season_files=$(for year in $years; do echo "runtime/$year/manifest.json runtime/$year/frcdevwasm-shim.jar runtime/$year/frcdevwasm-tools.jar runtime/$year/vscode-wpilib/dist/browser/extension.js runtime/$year/tools/elastic/index.html"; for tool in glass outlineviewer datalogtool sysid simgui; do echo "runtime/$year/tools/$tool/index.wasm"; done; done)
for file in $season_files $java_web_files runtime/terminal/coreutils.wasm runtime/git/git.wasm runtime/vscode-git/git/dist/browser/extension.js \
  runtime/tools/advantagescope/index.html runtime/tools/robotbuilder/RobotBuilder.jar runtime/tools/smartdashboard/SmartDashboard.jar; do
  [ -s "$dist/$file" ] || { echo "apps/ide/dist/$file is missing; run pnpm build:all" >&2; exit 1; }
done

node "$root/packages/wpigui-tools/source.mjs" "$dist/runtime"

echo "apps/ide/dist has every built part"
