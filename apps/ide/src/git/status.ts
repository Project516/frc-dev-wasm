export type Kind = 'M' | 'A' | 'D' | 'U'

export interface Change {
  path: string
  kind: Kind
}

// Rows are isomorphic-git statusMatrix rows: [path, HEAD, WORKDIR, STAGE]. HEAD and WORKDIR are 0 (absent),
// 1 (same as HEAD) or 2 (differs). STAGE is 0 (absent), 1 (same as HEAD), 2 (same as WORKDIR) or 3 (differs from both).
export function classify(rows: Array<[string, number, number, number]>): { staged: Change[]; changes: Change[] } {
  const staged: Change[] = []
  const changes: Change[] = []
  for (const [path, head, workdir, stage] of rows) {
    if (head === 1 && stage === 0) staged.push({ path, kind: 'D' })
    else if (head === 1 && stage >= 2) staged.push({ path, kind: 'M' })
    else if (head === 0 && stage >= 2) staged.push({ path, kind: 'A' })

    if (stage === 0) {
      if (workdir !== 0) changes.push({ path, kind: 'U' })
    } else if (stage === 3 || (stage === 1 && workdir !== 1)) {
      changes.push({ path, kind: workdir === 0 ? 'D' : 'M' })
    }
  }
  return { staged, changes }
}
