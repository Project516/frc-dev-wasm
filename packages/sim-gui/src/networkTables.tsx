import type { NtTopic, NtValue } from '@frc-dev-wasm/runtime';
import { useState } from 'preact/hooks';
import { useStore } from './context.ts';
import { buildTree, formatValue, isEditableType, parseValue, type TreeNode } from './nt.ts';
import { persisted } from './storage.ts';
import { Empty, NumInput, Panel, TextInput } from './ui.tsx';

const expanded = persisted<Record<string, boolean>>('nt.expanded', {});
const options = persisted<{ metadata: boolean }>('nt.options', { metadata: false });

interface Row {
  node: TreeNode;
  level: number;
  open: boolean;
}

function matches(node: TreeNode, filter: string): boolean {
  return node.path.toLowerCase().includes(filter) || node.children.some((c) => matches(c, filter));
}

function flatten(node: TreeNode, level: number, filter: string, rows: Row[]): void {
  for (const child of node.children) {
    if (filter && !matches(child, filter)) continue;
    const open = filter ? true : (expanded.value[child.path] ?? false);
    rows.push({ node: child, level, open });
    if (open) flatten(child, level + 1, filter, rows);
  }
}

// `value` is a prop because components with hook state skip re-renders when their props are unchanged.
function ValueCell({ topic, value }: { topic: NtTopic; value: NtValue | undefined }) {
  const { nt } = useStore();
  const [invalid, setInvalid] = useState(false);
  const { type, name } = topic;
  if (!isEditableType(type)) return <span class="value">{formatValue(value)}</span>;
  if (type === 'boolean') {
    return (
      <label class="check">
        <input type="checkbox" aria-label={name} checked={value === true} onChange={(e) => nt.publish(topic, e.currentTarget.checked)} />
        {String(value === true)}
      </label>
    );
  }
  if (type === 'double' || type === 'float' || type === 'int') {
    return <NumInput label={name} value={typeof value === 'number' ? value : 0} onCommit={(n) => nt.publish(topic, type === 'int' ? Math.round(n) : n)} />;
  }
  return (
    <TextInput
      label={name}
      value={formatValue(value)}
      invalid={invalid}
      onCommit={(text) => {
        const parsed = parseValue(type, text);
        setInvalid(parsed === undefined);
        if (parsed !== undefined) nt.publish(topic, parsed);
      }}
    />
  );
}

function focusSibling(row: HTMLElement, direction: 'next' | 'previous'): void {
  const sibling = direction === 'next' ? row.nextElementSibling : row.previousElementSibling;
  (sibling as HTMLElement | null)?.focus();
}

export function NetworkTables() {
  const { nt, status } = useStore();
  // Reading the signals subscribes this view to topic changes.
  nt.values.value;
  nt.structure.value;
  const [filter, setFilter] = useState('');
  const [focused, setFocused] = useState<string>();
  const showMeta = options.value.metadata;
  if (nt.topics.size === 0 && status.value === 'stopped') return null;

  const topics = [...nt.topics.values()].filter((t) => showMeta || !t.name.split('/').some((p) => p.startsWith('.')));
  const rows: Row[] = [];
  flatten(buildTree(topics), 0, filter.trim().toLowerCase(), rows);

  const toggle = (path: string, open: boolean) => (expanded.value = { ...expanded.value, [path]: open });

  return (
    <Panel id="networktables" title="NetworkTables" wide badge={`${topics.length} topics`}>
      {nt.topics.size === 0 ? (
        <Empty>No topics yet. They appear while a robot program runs.</Empty>
      ) : (
        <>
          <div class="toolbar">
            <input
              type="search"
              class="text"
              aria-label="Filter topics"
              placeholder="Filter"
              value={filter}
              onInput={(e) => setFilter(e.currentTarget.value)}
            />
            <label class="check">
              <input type="checkbox" checked={showMeta} onChange={(e) => (options.value = { metadata: e.currentTarget.checked })} />
              Metadata
            </label>
          </div>
          <div class="nt-head" aria-hidden="true">
            <span>Name</span>
            <span>Type</span>
            <span>Value</span>
          </div>
          <div class="nt-tree" role="tree" aria-label="NetworkTables topics">
            {rows.map(({ node, level, open }, i) => {
              const hasChildren = node.children.length > 0;
              return (
                <div
                  class="nt-row"
                  role="treeitem"
                  aria-level={level + 1}
                  aria-expanded={hasChildren ? open : undefined}
                  tabIndex={(focused ?? rows[0]?.node.path) === node.path ? 0 : -1}
                  style={{ '--level': level }}
                  onFocus={(e) => e.target === e.currentTarget && setFocused(node.path)}
                  onClick={(e) => {
                    if (hasChildren && (e.target as HTMLElement).closest('.nt-value') === null) toggle(node.path, !open);
                  }}
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return;
                    const row = e.currentTarget;
                    if (e.key === 'ArrowDown') focusSibling(row, 'next');
                    else if (e.key === 'ArrowUp') focusSibling(row, 'previous');
                    else if (e.key === 'ArrowRight' && hasChildren && !open) toggle(node.path, true);
                    else if (e.key === 'ArrowLeft') {
                      if (hasChildren && open) toggle(node.path, false);
                      else for (let j = i - 1; j >= 0; j--) if (rows[j].level < level) return void (row.parentElement?.children[j] as HTMLElement).focus();
                    } else if ((e.key === 'Enter' || e.key === ' ') && hasChildren) toggle(node.path, !open);
                    else return;
                    e.preventDefault();
                  }}
                >
                  <span class="nt-name" title={node.path}>
                    <span class={`twisty${hasChildren ? (open ? ' open' : '') : ' none'}`} aria-hidden="true" />
                    {node.name}
                  </span>
                  <span class="nt-type">{node.topic?.type ?? ''}</span>
                  <span class="nt-value">{node.topic ? <ValueCell topic={node.topic} value={node.topic.value} /> : null}</span>
                </div>
              );
            })}
          </div>
        </>
      )}
    </Panel>
  );
}
