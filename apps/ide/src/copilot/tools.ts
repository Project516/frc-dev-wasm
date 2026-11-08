import type { IExtensionManifest } from '@codingame/monaco-vscode-api/extensions'
import type * as vscode from 'vscode'
import { DEFAULT_YEAR } from '@frc-dev-wasm/runtime'
import type { AgentTerminal } from '../frc/terminal'
import { projectSeason } from './season'
import { lookupApi, lookupDocs, SOURCES } from './wpilibDocs'

// Marks the tools the Copilot participants offer to the model
export const TOOL_TAG = 'frc-dev-wasm.copilot'
// What Ask mode may use: it looks at the project but does not change it
export const READ_ONLY_TOOLS = new Set(['read_file', 'list_dir', 'file_search', 'grep_search', 'get_errors', 'wpilib_api', 'wpilib_docs'])

const EXCLUDE = '**/{.git,.gradle,build,bin,node_modules}/**'
const MAX_LINES = 2000
// The member index is 2 MB, so a slow link needs a while
const FETCH_TIMEOUT = 30_000
const MAX_OUTPUT = 20_000
const MAX_MATCHES = 100
// Files that change how the project builds, which tools it runs or what leaves the machine
const GUARDED = /^(\.vscode|\.github|vendordeps)\/|^(build|settings)\.gradle(\.kts)?$|^gradle\.properties$/i
const GIT_DIR = /^\.git(\/|$)/i

const path = (description: string) => ({ type: 'string' as const, description })

type Contribution = NonNullable<NonNullable<IExtensionManifest['contributes']>['languageModelTools']>[number]

// Named as the Copilot extension names its tools, which models know
const DEFINITIONS: Contribution[] = [
  {
    name: 'read_file',
    displayName: 'Read File',
    modelDescription: 'Read the contents of a file. Give a line range for a long file. Lines are 1-based and the end line is included.',
    inputSchema: {
      type: 'object',
      properties: { filePath: path('Path of the file, relative to the project folder'), startLine: { type: 'number' }, endLine: { type: 'number' } },
      required: ['filePath']
    }
  },
  {
    name: 'list_dir',
    displayName: 'List Directory',
    modelDescription: 'List the contents of a directory. Names ending in / are directories.',
    inputSchema: { type: 'object', properties: { path: path('Directory, relative to the project folder. Empty for the project folder.') }, required: ['path'] }
  },
  {
    name: 'file_search',
    displayName: 'Find Files',
    modelDescription: 'Find files by glob pattern, such as **/*.java or src/main/**/Robot*.java. Returns their paths.',
    inputSchema: { type: 'object', properties: { query: { type: 'string', description: 'Glob pattern' } }, required: ['query'] }
  },
  {
    name: 'grep_search',
    displayName: 'Search Text',
    modelDescription: 'Search the text of the project files. Returns matching lines with their file and line number.',
    inputSchema: {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Text or regular expression to find' },
        isRegexp: { type: 'boolean' },
        includePattern: { type: 'string', description: 'Glob pattern of the files to search' }
      },
      required: ['query']
    }
  },
  {
    name: 'create_file',
    displayName: 'Create File',
    modelDescription: 'Create a new file with the given content. Missing directories are created. Fails if the file exists; edit it with replace_string_in_file instead.',
    inputSchema: { type: 'object', properties: { filePath: path('Path of the new file'), content: { type: 'string' } }, required: ['filePath', 'content'] }
  },
  {
    name: 'replace_string_in_file',
    displayName: 'Edit File',
    modelDescription:
      'Edit a file by replacing one exact piece of text with new text. oldString must appear exactly once: include a few lines around the change so it does. Read the file first.',
    inputSchema: {
      type: 'object',
      properties: { filePath: path('Path of the file to edit'), oldString: { type: 'string' }, newString: { type: 'string' } },
      required: ['filePath', 'oldString', 'newString']
    }
  },
  {
    name: 'delete_file',
    displayName: 'Delete File',
    modelDescription: 'Delete a file, or a directory with everything in it.',
    inputSchema: { type: 'object', properties: { filePath: path('Path to delete') }, required: ['filePath'] }
  },
  {
    name: 'run_in_terminal',
    displayName: 'Run in Terminal',
    modelDescription:
      'Run a bash command line in the FRC Dev shell, in the project folder, and return its output and exit code. The shell has git, gh, common Unix tools and ./gradlew, which supports build, clean and tasks. Commands run until they finish: do not start ./gradlew simulateJava or run, they run until stopped.',
    inputSchema: {
      type: 'object',
      properties: { command: { type: 'string' }, explanation: { type: 'string', description: 'One sentence on what the command does' } },
      required: ['command', 'explanation']
    }
  },
  {
    name: 'get_errors',
    displayName: 'Get Problems',
    modelDescription: 'List the errors and warnings in the Problems view, such as compile errors from the last build. Optionally only for the given files.',
    inputSchema: { type: 'object', properties: { filePaths: { type: 'array', items: { type: 'string' } } } }
  },
  {
    name: 'wpilib_api',
    displayName: 'WPILib API',
    modelDescription:
      "Look up a WPILib Java class in the javadoc of the open project's season and return its page as text: signature, constructors, methods and descriptions. Use it before you use a WPILib class you are not sure of, especially in a 2027 project, where packages moved from edu.wpi.first to org.wpilib. Give a class (TimedRobot, org.wpilib.util.Alert) or a member (TimedRobot.addPeriodic, PIDController#calculate). If the class is not found it suggests close names.",
    inputSchema: { type: 'object', properties: { name: { type: 'string', description: 'Class name, or Class.member' } }, required: ['name'] }
  },
  {
    name: 'wpilib_docs',
    displayName: 'WPILib Docs',
    modelDescription:
      "Search the WPILib documentation (docs.wpilib.org) of the open project's season, or read one page of it as text. Use it for how-to questions, concepts and setup that the javadoc does not answer, such as command-based programming, kinematics, vision or the robot simulation. Give query to get matching pages, then page (a path from the results or its address) to read one.",
    inputSchema: {
      type: 'object',
      properties: { query: { type: 'string', description: 'Words to search for' }, page: { type: 'string', description: 'Page to read: a path such as docs/software/commandbased/commands-v2/command-scheduler, or its address' } }
    }
  }
]

export const TOOLS = DEFINITIONS.map((tool) => ({ ...tool, tags: [TOOL_TAG], canBeReferencedInPrompt: true, toolReferenceName: tool.name }))

// Longer than any run of backticks in the text, so the text cannot close the block
export const fence = (text: string) => '`'.repeat(Math.max(3, ...(text.match(/`+/g) ?? []).map((run) => run.length + 1)))

function tail(output: string): string {
  return output.length > MAX_OUTPUT ? `...\n${output.slice(-MAX_OUTPUT)}` : output
}

export function registerTools(api: typeof vscode, terminal: AgentTerminal): void {
  const decoder = new TextDecoder()
  const result = (value: string) => new api.LanguageModelToolResult([new api.LanguageModelTextPart(value)])

  const root = () => {
    const folder = api.workspace.workspaceFolders?.[0]?.uri
    if (!folder) throw new Error('No project folder is open.')
    return folder
  }
  const resolve = (input: string | undefined) => {
    const base = root()
    const relative = input === '.' ? '' : (input ?? '').replace(/^(\.\/)+/, '')
    const uri = relative.startsWith('/') ? api.Uri.joinPath(base.with({ path: '/' }), relative) : api.Uri.joinPath(base, relative)
    if (uri.path !== base.path && !uri.path.startsWith(`${base.path}/`)) throw new Error(`${input} is outside the project folder.`)
    return uri
  }
  const inProject = (uri: vscode.Uri) => uri.path.slice(root().path.length + 1)
  const writable = (uri: vscode.Uri) => {
    if (GIT_DIR.test(inProject(uri))) throw new Error(`${show(uri)} is part of the git repository and cannot be changed.`)
    return uri
  }
  const confirmWrite = (verb: string) => ({ filePath }: { filePath: string }) => {
    let guarded = false
    try {
      guarded = GUARDED.test(inProject(resolve(filePath)))
    } catch {
      // run reports a path outside the project
    }
    return guarded ? { title: `${verb} project file?`, message: `Copilot wants to ${verb.toLowerCase()} \`${filePath}\`, which sets up how the project builds or runs.` } : undefined
  }
  const show = (uri: vscode.Uri) => api.workspace.asRelativePath(uri, false)
  const exists = (uri: vscode.Uri) => api.workspace.fs.stat(uri).then(
    () => true,
    () => false
  )
  const register = <T>(name: string, tool: { message: (input: T) => string; confirm?: (input: T) => { title: string; message: string } | undefined; run: (input: T, signal: AbortSignal) => Promise<string> }) =>
    api.lm.registerTool<T>(name, {
      prepareInvocation({ input }) {
        let message: string
        try {
          message = tool.message(input)
        } catch {
          message = name
        }
        const confirm = tool.confirm?.(input)
        return {
          invocationMessage: message,
          confirmationMessages: confirm && { title: confirm.title, message: new api.MarkdownString(confirm.message) }
        }
      },
      async invoke({ input }, token) {
        // Chat's own schema check needs the JSON language extension, which is not built in
        for (const key of (DEFINITIONS.find((d) => d.name === name)?.inputSchema?.required ?? []) as string[]) {
          if ((input as Record<string, unknown>)[key] === undefined) throw new Error(`${name} needs ${key}.`)
        }
        const controller = new AbortController()
        token.onCancellationRequested(() => controller.abort(new DOMException('Cancelled', 'AbortError')))
        return result(await tool.run(input, controller.signal))
      }
    })

  register<{ filePath: string; startLine?: number; endLine?: number }>('read_file', {
    message: ({ filePath }) => `Reading ${filePath}`,
    async run({ filePath, startLine, endLine }) {
      const document = await api.workspace.openTextDocument(resolve(filePath))
      const first = Math.max(1, startLine ?? 1)
      const last = Math.min(document.lineCount, endLine ?? first + MAX_LINES - 1, first + MAX_LINES - 1)
      const lines: string[] = []
      for (let i = first; i <= last; i++) lines.push(document.lineAt(i - 1).text)
      const more = last < document.lineCount ? `\n(lines ${first}-${last} of ${document.lineCount})` : ''
      return lines.join('\n') + more
    }
  })

  register<{ path: string }>('list_dir', {
    message: ({ path }) => `Listing ${path || 'the project folder'}`,
    async run({ path }) {
      const entries = await api.workspace.fs.readDirectory(resolve(path))
      return entries.map(([name, type]) => (type & api.FileType.Directory ? `${name}/` : name)).sort().join('\n') || '(empty)'
    }
  })

  register<{ query: string }>('file_search', {
    message: ({ query }) => `Finding ${query}`,
    async run({ query }) {
      // A bare name matches at any depth
      const glob = query.includes('/') ? query : `**/${query}`
      const files = await api.workspace.findFiles(new api.RelativePattern(root(), glob), EXCLUDE, 200)
      return files.map(show).sort().join('\n') || 'No files found.'
    }
  })

  register<{ query: string; isRegexp?: boolean; includePattern?: string }>('grep_search', {
    message: ({ query }) => `Searching for ${query}`,
    async run({ query, isRegexp, includePattern }, signal) {
      const pattern = new RegExp(isRegexp ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i')
      const files = await api.workspace.findFiles(new api.RelativePattern(root(), includePattern || '**/*'), EXCLUDE, 2000)
      const matches: string[] = []
      for (const file of files) {
        if (signal.aborted || matches.length >= MAX_MATCHES) break
        const bytes = await api.workspace.fs.readFile(file).then(
          (b) => b,
          () => undefined
        )
        if (!bytes || bytes.length > 512_000 || bytes.subarray(0, 8000).includes(0)) continue
        decoder
          .decode(bytes)
          .split('\n')
          .forEach((line, i) => {
            if (matches.length < MAX_MATCHES && pattern.test(line)) matches.push(`${show(file)}:${i + 1}: ${line.trim().slice(0, 200)}`)
          })
      }
      return matches.join('\n') || 'No matches.'
    }
  })

  register<{ filePath: string; content: string }>('create_file', {
    message: ({ filePath }) => `Creating ${filePath}`,
    confirm: confirmWrite('Create'),
    async run({ filePath, content }) {
      const uri = writable(resolve(filePath))
      if (await exists(uri)) throw new Error(`${filePath} exists. Edit it with replace_string_in_file.`)
      await api.workspace.fs.createDirectory(api.Uri.joinPath(uri, '..'))
      await api.workspace.fs.writeFile(uri, new TextEncoder().encode(content))
      return `Created ${show(uri)}.`
    }
  })

  register<{ filePath: string; oldString: string; newString: string }>('replace_string_in_file', {
    message: ({ filePath }) => `Editing ${filePath}`,
    confirm: confirmWrite('Edit'),
    async run({ filePath, oldString, newString }) {
      const uri = writable(resolve(filePath))
      const document = await api.workspace.openTextDocument(uri)
      const content = document.getText()
      const at = content.indexOf(oldString)
      if (!oldString || at < 0) throw new Error(`oldString was not found in ${filePath}. Read the file and copy the text exactly.`)
      if (content.indexOf(oldString, at + 1) >= 0) throw new Error(`oldString appears more than once in ${filePath}. Include more lines around the change.`)
      const change = new api.WorkspaceEdit()
      change.replace(uri, new api.Range(document.positionAt(at), document.positionAt(at + oldString.length)), newString)
      if (!(await api.workspace.applyEdit(change))) throw new Error(`Could not edit ${filePath}.`)
      await document.save()
      return `Edited ${show(uri)}.`
    }
  })

  register<{ filePath: string }>('delete_file', {
    message: ({ filePath }) => `Deleting ${filePath}`,
    confirm: ({ filePath }) => ({ title: 'Delete file?', message: `Copilot wants to delete \`${filePath}\`.` }),
    async run({ filePath }) {
      const uri = resolve(filePath)
      if (uri.path === root().path) throw new Error('The project folder itself cannot be deleted.')
      await api.workspace.fs.delete(uri, { recursive: true, useTrash: false })
      return `Deleted ${show(uri)}.`
    }
  })

  register<{ command: string; explanation: string }>('run_in_terminal', {
    message: ({ command }) => `Running \`${command}\``,
    confirm: ({ command, explanation }) => ({ title: 'Run command in terminal?', message: `${explanation ?? ''}\n\n${fence(command)}sh\n${command}\n${fence(command)}` }),
    async run({ command }, signal) {
      const { code, output } = await terminal.run(command, signal)
      return `Exit code: ${code}\n${tail(output)}`
    }
  })

  const get = async (url: string, signal?: AbortSignal) => {
    const timeout = AbortSignal.timeout(FETCH_TIMEOUT)
    const response = await fetch(url, { signal: signal ? AbortSignal.any([signal, timeout]) : timeout }).catch((error: unknown) => {
      throw new Error(`Could not reach ${new URL(url).host} (${error instanceof Error ? error.message : String(error)}). The page may be offline.`)
    })
    if (!response.ok) throw new Error(`${url} answered ${response.status}.`)
    return response.text()
  }
  // The open project's season, else the site's, and 2026 for one the docs do not cover
  const source = async () => SOURCES[(await projectSeason(api)) ?? DEFAULT_YEAR] ?? SOURCES['2026']

  register<{ name: string }>('wpilib_api', {
    message: ({ name }) => `Looking up ${name}`,
    async run({ name }, signal) {
      return lookupApi((url) => get(url, signal), await source(), name)
    }
  })

  register<{ query?: string; page?: string }>('wpilib_docs', {
    message: ({ query, page }) => (page ? `Reading ${page}` : `Searching the docs for ${query}`),
    async run(input, signal) {
      return lookupDocs((url) => get(url, signal), await source(), input)
    }
  })

  register<{ filePaths?: string[] }>('get_errors', {
    message: () => 'Checking problems',
    async run({ filePaths }) {
      const wanted = filePaths?.map((p) => resolve(p).toString())
      const lines: string[] = []
      for (const [uri, diagnostics] of api.languages.getDiagnostics()) {
        if (wanted ? !wanted.includes(uri.toString()) : !uri.path.startsWith(root().path)) continue
        for (const d of diagnostics) {
          if (d.severity > api.DiagnosticSeverity.Warning) continue
          const kind = d.severity === api.DiagnosticSeverity.Error ? 'error' : 'warning'
          lines.push(`${show(uri)}:${d.range.start.line + 1}:${d.range.start.character + 1}: ${kind}: ${d.message}`)
        }
      }
      return lines.slice(0, MAX_MATCHES).join('\n') || 'No problems.'
    }
  })
}
