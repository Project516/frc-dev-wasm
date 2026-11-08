// Mirrors the workbench theme into this document so the page looks native.
// The workbench declares --vscode-* variables in stylesheet rules scoped to
// .monaco-workbench, so they are read from the rules, not computed styles.

function collectVariables(parent: Document): Map<string, string> {
  const vars = new Map<string, string>();
  const sheets = [...parent.styleSheets, ...parent.adoptedStyleSheets];
  const visit = (rules: CSSRuleList) => {
    for (const rule of rules) {
      // Rules come from the parent window, so instanceof checks against this window's classes fail.
      if ('selectorText' in rule) {
        const style = rule as CSSStyleRule;
        if (!/\.monaco-workbench|:root|^html|^body/.test(style.selectorText)) continue;
        for (const name of style.style) {
          if (name.startsWith('--vscode-')) vars.set(name, style.style.getPropertyValue(name).trim());
        }
      } else if ('cssRules' in rule) {
        visit((rule as CSSGroupingRule).cssRules);
      }
    }
  };
  for (const sheet of sheets) {
    try {
      visit(sheet.cssRules);
    } catch {
      // cross-origin sheet
    }
  }
  return vars;
}

function isDark(parent: Document): boolean {
  const workbench = parent.querySelector('.monaco-workbench') ?? parent.body;
  return workbench.classList.contains('vs-dark') || workbench.classList.contains('hc-black');
}

function apply(parent: Document, doc: Document, applied: Set<string>): void {
  const root = doc.documentElement;
  const vars = collectVariables(parent);
  for (const name of applied) if (!vars.has(name)) root.style.removeProperty(name);
  applied.clear();
  for (const [name, value] of vars) {
    root.style.setProperty(name, value);
    applied.add(name);
  }
  root.style.colorScheme = isDark(parent) ? 'dark' : 'light';
}

/** Copies the parent's theme variables onto `doc` and keeps them current. Returns a disposer. */
export function syncTheme(parent: Document, doc: Document): () => void {
  const applied = new Set<string>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const schedule = () => {
    clearTimeout(timer);
    timer = setTimeout(() => apply(parent, doc, applied), 50);
  };
  apply(parent, doc, applied);
  const observer = new MutationObserver(schedule);
  observer.observe(parent.head, { childList: true, subtree: true, characterData: true });
  observer.observe(parent.documentElement, { attributes: true, attributeFilter: ['class', 'style'] });
  const workbench = parent.querySelector('.monaco-workbench');
  if (workbench) observer.observe(workbench, { attributes: true, attributeFilter: ['class', 'style'] });
  return () => {
    observer.disconnect();
    clearTimeout(timer);
  };
}
