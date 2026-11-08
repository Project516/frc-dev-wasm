#!/usr/bin/env bash
# Runs one group of the browser and Java suites, each to the end, and fails if any failed.
set -uo pipefail
ide=(smoke cache git github-link gh terminal copilot robotbuilder tools smartdashboard maven)
failed=()
run() {
  echo "::group::$*"
  if "$@"; then echo "::endgroup::"; else echo "::endgroup::"; echo "::error::failed: $*"; failed+=("$*"); fi
}
case "${1:?group: ide, java or teams}" in
  ide)
    for s in "${ide[@]}"; do run pnpm --filter @frc-dev-wasm/ide test:$s; done
    ;;
  java)
    run pnpm --filter @frc-dev-wasm/java test
    run pnpm --filter @frc-dev-wasm/runtime test
    run env WPILIB_YEAR=2027 pnpm --filter @frc-dev-wasm/runtime test
    run pnpm --filter @frc-dev-wasm/java-web test
    run pnpm --filter @frc-dev-wasm/ide test:2027
    run pnpm --filter @frc-dev-wasm/ide test:junit
    run pnpm --filter @frc-dev-wasm/sim-gui test
    run pnpm --filter @frc-dev-wasm/wpigui-tools test
    run pnpm --filter @frc-dev-wasm/git-wasm test
    ;;
  teams)
    run pnpm --filter @frc-dev-wasm/ide test:teams2026
    run pnpm --filter @frc-dev-wasm/ide test:teams2027
    run pnpm --filter @frc-dev-wasm/ide test:spectrum
    run pnpm --filter @frc-dev-wasm/ide test:junit-team
    ;;
  *) echo "unknown group $1" >&2; exit 2 ;;
esac
[ ${#failed[@]} -eq 0 ] || { printf 'failed: %s\n' "${failed[@]}"; exit 1; }
