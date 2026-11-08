import type { IEditorOverrideServices } from '@codingame/monaco-vscode-api'
import { SyncDescriptor } from '@codingame/monaco-vscode-api/vscode/vs/platform/instantiation/common/descriptors'
import { ISearchService } from '@codingame/monaco-vscode-api/vscode/vs/workbench/services/search/common/search.service'
import getSearchServiceOverride from '@codingame/monaco-vscode-search-service-override'
import { match, type IExpression } from '@codingame/monaco-vscode-api/vscode/vs/base/common/glob'
import type { URI } from '@codingame/monaco-vscode-api/vscode/vs/base/common/uri'

const INDEX_TIMEOUT_MS = 60_000
const POLL_MS = 25

interface WorkspaceProvider {
  isInitialized?: boolean
  textSearch(...args: unknown[]): Promise<unknown>
  fileSearch(...args: unknown[]): Promise<unknown>
}

interface SearchQuery {
  folderQueries?: { folder: URI; includePattern?: IExpression }[]
  includePattern?: IExpression
  maxResults?: number
}

interface SearchResult {
  results?: { resource: URI }[]
  limitHit?: boolean
}

// Whether a file is under one of the query's folders and matches its include patterns, which are
// relative to the folder. findFiles sends its pattern this way.
function included(query: SearchQuery, resource: URI): boolean {
  const folders = query.folderQueries ?? []
  if (folders.length === 0) return !query.includePattern || match(query.includePattern, resource.path)
  return folders.some(({ folder, includePattern }) => {
    const root = folder.path.replace(/\/?$/, '/')
    if (!resource.path.startsWith(root)) return false
    const relative = resource.path.slice(root.length)
    return [includePattern, query.includePattern].every((pattern) => !pattern || match(pattern, relative))
  })
}

type SearchServiceClass = new (...args: unknown[]) => { registerSearchResultProvider(...args: unknown[]): unknown }

// The workspace provider indexes every file in the background after the search service is created
// and throws "Search provider not initialized" until that finishes. Searches wait for the index.
// It also leaves out the query's folders and include patterns, so its results are filtered by them.
function waitForIndex(provider: WorkspaceProvider) {
  const indexed = async () => {
    for (const start = Date.now(); !provider.isInitialized && Date.now() - start < INDEX_TIMEOUT_MS; ) {
      await new Promise((resolve) => setTimeout(resolve, POLL_MS))
    }
  }
  for (const method of ['textSearch', 'fileSearch'] as const) {
    const original = provider[method]
    provider[method] = async (...args) => {
      await indexed()
      // The limit applies to what is left after filtering
      const [query, ...rest] = args as [SearchQuery, ...unknown[]]
      const result = (await original.call(provider, { ...query, maxResults: undefined }, ...rest)) as SearchResult
      if (result?.results) {
        const files = result.results.filter((file) => included(query, file.resource))
        result.limitHit = query.maxResults !== undefined && files.length > query.maxResults
        result.results = result.limitHit ? files.slice(0, query.maxResults) : files
      }
      return result
    }
  }
}

export function getSearchServices(): IEditorOverrideServices {
  const services = getSearchServiceOverride()
  const key = ISearchService.toString()
  const descriptor = services[key] as SyncDescriptor<unknown>
  class SearchService extends (descriptor.ctor as SearchServiceClass) {
    override registerSearchResultProvider(...args: unknown[]) {
      const provider = args[2] as WorkspaceProvider
      if ('isInitialized' in provider) waitForIndex(provider)
      return super.registerSearchResultProvider(...args)
    }
  }
  return {
    ...services,
    [key]: new SyncDescriptor(SearchService, descriptor.staticArguments, descriptor.supportsDelayedInstantiation)
  }
}
