#!/usr/bin/env bash
# Runs one group of the browser and Java suites, each to the end, and fails if any failed.
set -uo pipefail
HAR_DIR=${HAR_DIR:-}
ide=(smoke cache git github-link gh terminal copilot robotbuilder tools smartdashboard maven)
failed=()
# A suite that fails runs once more: browser suites have timing failures on a loaded runner. A pass on the
# second try is still reported, so a suite that keeps needing it stands out.
run() {
  echo "::group::$*"
  if "$@"; then echo "::endgroup::"; return; fi
  echo "::endgroup::"
  echo "::group::$* (second try)"
  if "$@"; then echo "::endgroup::"; echo "::warning::passed on the second try: $*"; return; fi
  echo "::endgroup::"
  echo "::error::failed: $*"
  failed+=("$*")
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
    # With HAR_DIR the team projects' downloads are recorded once and replayed after that
    har=${HAR_DIR/#\~/$HOME}
    if [ -n "$har" ]; then mkdir -p "$har/teams" || exit 1; fi
    run env ${har:+TEAMS_HAR=$har/teams} pnpm --filter @frc-dev-wasm/ide test:teams2026
    run env ${har:+TEAMS_HAR=$har/teams} pnpm --filter @frc-dev-wasm/ide test:teams2027
    run env ${har:+SPECTRUM_HAR=$har/spectrum.zip} pnpm --filter @frc-dev-wasm/ide test:spectrum
    run env ${har:+JUNIT_HAR=$har/junit-team.zip} pnpm --filter @frc-dev-wasm/ide test:junit-team
    ;;
  *) echo "unknown group $1" >&2; exit 2 ;;
esac
[ ${#failed[@]} -eq 0 ] || { printf 'failed: %s\n' "${failed[@]}"; exit 1; }
