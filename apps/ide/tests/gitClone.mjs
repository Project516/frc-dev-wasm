// Records each git command the Git extension runs in globalThis.gitRuns: when it started and ended, and the end
// of its error output (git's progress). VS Code's Git output lists a command only when it ends.
export const traceGit = (page) =>
  page.evaluate(() => {
    const git = globalThis.frcDevWasmGit
    if (!git || globalThis.gitRuns) return
    const run = git.run
    globalThis.gitRuns = []
    git.run = (request) => {
      const entry = { args: request.args.join(' '), started: Math.round(performance.now()), stderr: '' }
      globalThis.gitRuns.push(entry)
      const decoder = new TextDecoder()
      const stderr = request.stderr
      request.stderr = (bytes) => {
        entry.stderr = (entry.stderr + decoder.decode(bytes, { stream: true })).slice(-400)
        stderr(bytes)
      }
      return run(request).then(
        (code) => Object.assign(entry, { code, ended: Math.round(performance.now()) }).code,
        (error) => {
          Object.assign(entry, { error: `${error}`, ended: Math.round(performance.now()) })
          throw error
        }
      )
    }
  })

// A focus change can close Git: Clone's URL or folder pick, and the Git extension then drops the clone without a
// message. Without the trace, or when a finished clone reloads the page into its folder, it counts as started.
export const cloneStarted = (page, timeout = 60_000) =>
  page
    .waitForFunction(() => !globalThis.gitRuns || globalThis.gitRuns.some((run) => run.args.startsWith('clone ')), undefined, { timeout })
    .then(
      () => true,
      (error) => {
        if (error.name?.includes('Timeout')) return false
        if (/context was destroyed|navigat/i.test(error.message)) return true
        throw error
      }
    )
