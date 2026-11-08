import type { IExtensionContributions } from '@codingame/monaco-vscode-api/extensions'

const GIT = 'Git'
const here = 'scmProvider == frc-git'

const publicCommands = [
  ['git.clone', 'Clone'],
  ['git.init', 'Initialize Repository'],
  ['git.commit', 'Commit', '$(check)'],
  ['git.push', 'Push'],
  ['git.pull', 'Pull'],
  ['git.checkout', 'Checkout to...'],
  ['git.branch', 'Create Branch...'],
  ['git.refresh', 'Refresh', '$(refresh)'],
  ['git.viewHistory', 'View History'],
  ['git.signOutGitHub', 'Sign Out of GitHub']
] as const

const resourceCommands = [
  ['git.stage', 'Stage Changes', '$(add)'],
  ['git.stageAll', 'Stage All Changes', '$(add)'],
  ['git.unstage', 'Unstage Changes', '$(remove)'],
  ['git.unstageAll', 'Unstage All Changes', '$(remove)'],
  ['git.discard', 'Discard Changes', '$(discard)'],
  ['git.discardAll', 'Discard All Changes', '$(discard)']
] as const

export const gitCommandIds: string[] = [...publicCommands, ...resourceCommands].map(([id]) => id)

const inGroup = (id: 'index' | 'workingTree') => `${here} && scmResourceGroup == ${id}`

export const gitContributions: IExtensionContributions = {
  commands: [...publicCommands, ...resourceCommands].map(([command, title, icon]) => ({ command, title, category: GIT, ...(icon ? { icon } : {}) })),
  menus: {
    commandPalette: resourceCommands.map(([command]) => ({ command, when: 'false' })),
    'scm/title': [
      { command: 'git.commit', group: 'navigation@1', when: here },
      { command: 'git.refresh', group: 'navigation@2', when: here },
      { command: 'git.pull', group: '1_sync@1', when: here },
      { command: 'git.push', group: '1_sync@2', when: here },
      { command: 'git.checkout', group: '2_branch@1', when: here },
      { command: 'git.branch', group: '2_branch@2', when: here }
    ],
    'scm/resourceGroup/context': [
      { command: 'git.stageAll', group: 'inline@2', when: inGroup('workingTree') },
      { command: 'git.discardAll', group: 'inline@1', when: inGroup('workingTree') },
      { command: 'git.unstageAll', group: 'inline@1', when: inGroup('index') }
    ],
    'scm/resourceState/context': [
      { command: 'git.stage', group: 'inline@2', when: inGroup('workingTree') },
      { command: 'git.discard', group: 'inline@1', when: inGroup('workingTree') },
      { command: 'git.unstage', group: 'inline@1', when: inGroup('index') }
    ]
  },
  configuration: {
    title: GIT,
    properties: {
      'git.corsProxy': {
        type: 'string',
        default: '',
        scope: 'application',
        markdownDescription:
          'CORS proxy for git remotes, for example `https://cors.isomorphic-git.org`. For projects in the browser, git reaches every remote through a proxy, GitHub too, and uses the git route of `wpilib.vendorProxy` when this is empty. For folders opened from disk, GitHub is reached through its REST API, and other hosts need this. Requests, including any credentials, pass through the proxy, so use one you trust.'
      },
      'git.user.name': { type: 'string', default: '', description: 'Name for the author of commits' },
      'git.user.email': { type: 'string', default: '', description: 'Email address for the author of commits' }
    }
  },
  viewsWelcome: [
    {
      view: 'scm',
      contents:
        "The folder currently open doesn't have a git repository. You can initialize a repository which will enable source control features powered by git.\n[Initialize Repository](command:git.init?%5Btrue%5D)\nOr clone a repository from GitHub into the workspace.\n[Clone Repository](command:git.clone)",
      when: 'workspaceFolderCount != 0 && scm.providerCount == 0'
    },
    {
      view: 'scm',
      contents: 'In order to use git features, you can open a folder containing a git repository or clone from a URL.\n[Open Folder](command:wpilib.openFolder)\n[Clone Repository](command:git.clone)',
      when: 'workspaceFolderCount == 0 && scm.providerCount == 0'
    }
  ],
  colors: [
    { id: 'gitDecoration.modifiedResourceForeground', description: 'Color for modified resources', defaults: { dark: '#E2C08D', light: '#895503', highContrast: '#E2C08D' } },
    { id: 'gitDecoration.addedResourceForeground', description: 'Color for added resources', defaults: { dark: '#81b88b', light: '#587c0c', highContrast: '#81b88b' } },
    { id: 'gitDecoration.deletedResourceForeground', description: 'Color for deleted resources', defaults: { dark: '#c74e39', light: '#ad0707', highContrast: '#c74e39' } },
    { id: 'gitDecoration.untrackedResourceForeground', description: 'Color for untracked resources', defaults: { dark: '#73C991', light: '#007100', highContrast: '#73C991' } }
  ]
}
