// Runs git commands in WebAssembly instances that share one file system, as processes share a disk.
// A child command (stash runs several) is a new instance created synchronously while its parent waits.
//
// Files of the repository are mounted lazily: git reads their bytes from `read(buffer, position)` and
// only a file it writes is copied into memory. After the command, changes() lists what to write back.

// Creates an instance synchronously. The factory fills in `opts` (FS, callMain, _main) before it
// returns. Its promise rejects only later, so a failure is found by what is missing.
function instantiate(createGit, wasmModule, opts) {
  Object.assign(opts, { wasmModule, noInitialRun: true })
  createGit(opts).catch(() => {})
  if (!opts.FS || !opts.callMain || !opts._main) throw new Error(`git did not start: ${opts.frcStartError ?? 'its WebAssembly instance could not be created'}`)
}

// git submodule (clone --recursive runs it) is a shell script around submodule--helper, which runs
// here directly: the subcommand first, then the options the script takes before it
const SUBMODULE_COMMANDS = new Set(['add', 'foreach', 'init', 'deinit', 'update', 'set-branch', 'set-url', 'status', 'summary', 'sync', 'absorbgitdirs'])
function submodule(args) {
  let i = 0
  while (['-q', '--quiet', '--cached'].includes(args[i])) i++
  const command = SUBMODULE_COMMANDS.has(args[i]) ? args[i] : 'status'
  return ['submodule--helper', command, ...args.slice(0, i), ...args.slice(command === args[i] ? i + 1 : i)]
}

export class GitHost {
  constructor(createGit, wasmModule) {
    this.createGit = createGit
    this.wasmModule = wasmModule
  }

  // Creates an instance. `fs` is the file system of the instance to share it with.
  instance({ env, cwd, input, fs, stdout, stderr, tty }) {
    let at = 0
    const opts = {
      ...this.moduleOptions,
      frcTty: tty,
      frcEnv: env,
      stdout: (byte) => byte !== null && stdout(byte & 255),
      stderr: (byte) => byte !== null && stderr(byte & 255),
      stdin: typeof input === 'function' ? input : () => (input && at < input.length ? input[at++] : null),
      frcSpawn: (args, childEnv, dir, childInput) => this.child(opts, args, childEnv, dir, childInput),
      // A git command whose standard streams are functions, for a remote helper's RPC
      frcRun: (args, io) => this.main(this.instance({ env: opts.frcEnv, cwd: opts.FS.cwd(), fs: opts.FS, input: io.stdin, stdout: io.stdout, stderr: io.stderr }), args, opts.FS)
    }
    instantiate(this.createGit, this.wasmModule, opts)
    if (fs) {
      opts.FS.root = fs.root
      opts.FS.nameTable = fs.nameTable
      opts.FS.mounts = fs.mounts
      opts.FS.nextInode = fs.nextInode
      opts.FS.currentPath = '/'
    }
    if (cwd) opts.FS.chdir(cwd)
    return opts
  }

  // Runs `args` in an instance and returns the exit status
  main(opts, args, parentFs) {
    let status
    try {
      status = opts.callMain([...args]) ?? 0
    } catch (e) {
      if (e?.status === undefined) throw e
      status = e.status
    }
    if (parentFs) parentFs.nextInode = opts.FS.nextInode
    return status
  }

  child(parent, args, envList, dir, input) {
    const env = {}
    for (const entry of envList) {
      const eq = entry.indexOf('=')
      if (eq > 0) env[entry.slice(0, eq)] = entry.slice(eq + 1)
    }
    const out = []
    const err = []
    let rest = args[0] === 'git' ? args.slice(1) : [args[0].replace(/^git-/, ''), ...args.slice(1)]
    if (rest[0] === 'submodule') {
      rest = submodule(rest.slice(1))
      env.GIT_PROTOCOL_FROM_USER = '0'
    }
    // `input` is bytes, or a file of the shared file system and where reading starts
    const file = input instanceof Uint8Array ? undefined : input
    const opts = this.instance({ env, cwd: !dir ? parent.FS.cwd() : dir.startsWith('/') ? dir : `${parent.FS.cwd()}/${dir}`, input: file ? undefined : input, fs: parent.FS, stdout: (b) => out.push(b), stderr: (b) => err.push(b) })
    if (file) {
      opts.FS.close(opts.FS.getStream(0))
      opts.FS.open(file.node, 0).position = file.position
    }
    const status = this.main(opts, rest, parent.FS)
    return { status, stdout: new Uint8Array(out), stderr: new Uint8Array(err) }
  }

  // Mounts each of `mounts` ({ root, dirs, files }: paths relative to root, files with size, mtime
  // and read), then runs git in `cwd`. Returns the exit status and what changed in each mount.
  // `moduleOptions` go to every instance, such as frcCorsProxy and frcAuth for remotes. With `tty`,
  // the command's output is a terminal of `columns` columns, as in a desktop terminal.
  run({ args, env, cwd, mounts, input, stdout, stderr, moduleOptions, tty = false, columns }) {
    this.moduleOptions = { ...moduleOptions, frcShared: {}, frcColumns: columns }
    const world = this.instance({ env, stdout, stderr, input, tty })
    const { FS, MEMFS } = world
    const lazy = mounts.map((m) => mountLazily(FS, MEMFS, m.root, m.dirs, m.files))
    FS.mkdirTree(cwd)
    FS.chdir(cwd)
    const status = this.main(world, args)
    return { status, changes: lazy.map((l) => l.changes()) }
  }
}

function mountLazily(FS, MEMFS, root, dirs, files) {
  FS.mkdirTree(root)
  for (const dir of dirs) FS.mkdirTree(`${root}/${dir}`)
  const originalDirs = new Set(dirs)
  const originalFiles = new Set()
  const file = MEMFS.ops_table.file

  const materialize = (node, keep) => {
    if (!node.lazy) return
    const data = new Uint8Array(keep ? node.lazy.size : 0)
    if (keep) node.lazy.read(data, 0)
    node.lazy = null
    node.contents = data
    node.usedBytes = data.length
    node.node_ops = file.node
    node.stream_ops = file.stream
  }
  const nodeOps = {
    getattr(node) {
      const attr = file.node.getattr(node)
      if (node.lazy) {
        attr.size = node.lazy.size
        attr.blocks = Math.ceil(attr.size / 4096)
      }
      return attr
    },
    setattr(node, attr) {
      if (attr.size !== undefined) materialize(node, attr.size !== 0)
      file.node.setattr(node, attr)
    }
  }
  const streamOps = {
    llseek(stream, offset, whence) {
      if (whence !== 2 || !stream.node.lazy) return file.stream.llseek(stream, offset, whence)
      const position = offset + stream.node.lazy.size
      if (position < 0) throw new FS.ErrnoError(28)
      return position
    },
    read(stream, buffer, offset, length, position) {
      const node = stream.node
      if (!node.lazy) return file.stream.read(stream, buffer, offset, length, position)
      if (position >= node.lazy.size) return 0
      const size = Math.min(node.lazy.size - position, length)
      node.lazy.read(buffer.subarray(offset, offset + size), position)
      return size
    },
    write(stream, ...rest) {
      materialize(stream.node, true)
      return file.stream.write(stream, ...rest)
    },
    mmap(stream, ...rest) {
      materialize(stream.node, true)
      return file.stream.mmap(stream, ...rest)
    },
    msync: file.stream.msync
  }

  for (const f of files) {
    const path = `${root}/${f.path}`
    FS.writeFile(path, new Uint8Array(0))
    const node = FS.lookupPath(path).node
    node.lazy = { size: f.size, read: f.read }
    node.origin = f.path
    node.atime = node.mtime = node.ctime = f.mtime
    node.node_ops = nodeOps
    node.stream_ops = streamOps
    originalFiles.add(f.path)
  }

  return {
    // removed: paths to delete, files or directories. dirs: directories to create. writes: files to
    // write, with their bytes or the original path to copy them from. Apply them in that order, after
    // reading what `from` names.
    changes() {
      const writes = []
      const created = []
      const finalDirs = new Set()
      const finalFiles = new Set()
      const walk = (dir, prefix) => {
        for (const [name, node] of Object.entries(dir.contents)) {
          const path = prefix ? `${prefix}/${name}` : name
          if (FS.isDir(node.mode)) {
            finalDirs.add(path)
            if (!originalDirs.has(path)) created.push(path)
            walk(node, path)
          } else if (FS.isFile(node.mode)) {
            finalFiles.add(path)
            if (node.lazy && node.origin === path) continue
            writes.push(node.lazy ? { path, from: node.origin } : { path, data: node.contents.subarray(0, node.usedBytes) })
          }
        }
      }
      walk(FS.lookupPath(root).node, '')
      const removed = [...[...originalDirs].filter((p) => !finalDirs.has(p)), ...[...originalFiles].filter((p) => !finalFiles.has(p))]
      // Deleting a directory takes its contents too
      const top = removed.filter((path) => !removed.some((other) => path.startsWith(`${other}/`)))
      return { removed: top, dirs: created, writes }
    }
  }
}
