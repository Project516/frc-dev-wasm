// Also run by scripts/fetch-deps.mjs under Node, so no imports and no parameter properties.

export interface PomDependency {
  group: string;
  artifact: string;
  /** Empty when the pom does not say. */
  version: string;
}

// Repeated, so a comment that the removal of another one completes is removed too
const uncommented = (xml: string) => {
  let previous;
  do {
    previous = xml;
    xml = xml.replace(/<!--[\s\S]*?-->/g, '');
  } while (xml !== previous);
  return xml;
};
const field = (xml: string, tag: string) => new RegExp(`<${tag}>([^<]*)</${tag}>`).exec(xml)?.[1].trim();
// What belongs to other poms or to the build, and not to this pom's own coordinates and dependencies
const OTHER = /<(parent|build|profiles|reporting)>[\s\S]*?<\/\1>/g;
const MANAGEMENT = /<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g;

/** The parent that the pom names, if any. */
export function pomParent(pom: string): { group: string; artifact: string; version: string } | undefined {
  const parent = /<parent>([\s\S]*?)<\/parent>/.exec(uncommented(pom))?.[1];
  if (!parent) return undefined;
  return { group: field(parent, 'groupId') ?? '', artifact: field(parent, 'artifactId') ?? '', version: field(parent, 'version') ?? '' };
}

function propertiesOf(pom: string): Record<string, string> {
  pom = uncommented(pom);
  const props: Record<string, string> = {};
  for (const block of pom.matchAll(/<properties>([\s\S]*?)<\/properties>/g)) {
    for (const p of block[1].matchAll(/<([\w.-]+)>([^<]*)<\/\1>/g)) props[p[1]] = p[2].trim();
  }
  // ${project.version} and friends: the pom's own coordinates, or its parent's when it leaves them out
  const parent = pomParent(pom);
  const own = pom.replace(OTHER, '').replace(MANAGEMENT, '').replace(/<dependencies>[\s\S]*?<\/dependencies>|<properties>[\s\S]*?<\/properties>/g, '');
  const version = field(own, 'version') ?? parent?.version;
  const group = field(own, 'groupId') ?? parent?.group;
  for (const prefix of ['project', 'pom']) {
    if (version) props[`${prefix}.version`] = version;
    if (group) props[`${prefix}.groupId`] = group;
  }
  if (version) props.version = version;
  if (parent?.version) props['project.parent.version'] = parent.version;
  return props;
}

interface PomEntry {
  group: string;
  artifact: string;
  version: string;
  scope: string;
  optional: boolean;
  type: string;
  classifier: string;
}

// The dependency lists of a pom and its parents, with properties substituted
function pomReader(pom: string, parents: string[]) {
  const props: Record<string, string> = {};
  for (const p of [pom, ...parents].reverse()) Object.assign(props, propertiesOf(p));
  // A property can name another, as jackson.version.annotations names jackson.version
  const substitute = (text: string): string => {
    for (let depth = 0; depth < 10; depth++) {
      const next = text.replace(/\$\{([^}]+)\}/g, (all, k: string) => props[k] ?? all);
      if (next === text) break;
      text = next;
    }
    return text;
  };
  const entries = (xml: string): PomEntry[] =>
    [...xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map((m) => {
      const tag = (t: string) => substitute((new RegExp(`<${t}>([^<]*)</${t}>`).exec(m[1])?.[1] ?? '').trim());
      return {
        group: tag('groupId'),
        artifact: tag('artifactId'),
        version: tag('version'),
        scope: tag('scope') || 'compile',
        optional: tag('optional') === 'true',
        type: tag('type') || 'jar',
        classifier: tag('classifier'),
      };
    });
  const management = (xml: string) => [...xml.matchAll(/<dependencyManagement>([\s\S]*?)<\/dependencyManagement>/g)].flatMap((block) => entries(block[1]));
  // The versions of the dependencyManagement of the pom and its parents, the pom's own taking precedence
  const managed = new Map<string, string>();
  for (const p of [pom, ...parents].reverse()) {
    for (const d of management(p)) if (d.scope !== 'import' && d.version) managed.set(`${d.group}:${d.artifact}`, d.version);
  }
  const imports = management(pom).filter((d) => d.scope === 'import' && d.type === 'pom');
  return { entries, managed, imports };
}

/** The versions a BOM pom manages, by `group:artifact`. `parents` are as for `pomDependencies`. */
export function pomManagedVersions(pom: string, parents: string[] = []): Map<string, string> {
  return pomReader(uncommented(pom), parents.map(uncommented)).managed;
}

/** BOMs imported by dependencyManagement, with the nearest definition of each BOM taking precedence. */
export function pomImports(pom: string, parents: string[] = []): PomDependency[] {
  const seen = new Set<string>();
  const chain = [pom, ...parents].map(uncommented);
  return chain.flatMap((p, i) => pomReader(p, chain.slice(i + 1)).imports)
    .filter((d) => {
      const name = `${d.group}:${d.artifact}`;
      if (seen.has(name)) return false;
      seen.add(name);
      return true;
    })
    .map(({ group, artifact, version }) => ({ group, artifact, version }));
}

/**
 * The compile and runtime dependencies of a pom that are plain jars and not optional. `parents` are the
 * poms of its parent, then that one's parent and so on, which supply properties and managed versions.
 */
export function pomDependencies(pom: string, parents: string[] = []): PomDependency[] {
  pom = uncommented(pom);
  parents = parents.map(uncommented);
  const { entries, managed } = pomReader(pom, parents);
  const deps = /<dependencies>([\s\S]*?)<\/dependencies>/.exec(pom.replace(OTHER, '').replace(MANAGEMENT, ''));
  if (!deps) return [];
  return entries(deps[1])
    .filter((d) => !d.optional && ['compile', 'runtime'].includes(d.scope) && d.type === 'jar' && !d.classifier)
    .map((d) => ({ group: d.group, artifact: d.artifact, version: d.version || managed.get(`${d.group}:${d.artifact}`) || '' }));
}
