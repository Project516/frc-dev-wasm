// Plays the workbench: offers the SimHost on window and declares theme variables on .monaco-workbench.
import { MockHost } from './mock-host.ts';

const DARK = `
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
  --vscode-font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
  --vscode-editor-font-family: 'Cascadia Code', Menlo, monospace;
  --vscode-font-size: 13px;
  --vscode-editor-background: #1f1f1f;
  --vscode-foreground: #cccccc;
  --vscode-descriptionForeground: #9d9d9d;
  --vscode-panel-border: #2b2b2b;
  --vscode-sideBarSectionHeader-background: #181818;
  --vscode-sideBarSectionHeader-foreground: #cccccc;
  --vscode-input-background: #313131;
  --vscode-input-foreground: #cccccc;
  --vscode-input-border: #3c3c3c;
  --vscode-focusBorder: #0078d4;
  --vscode-button-background: #0078d4;
  --vscode-button-foreground: #ffffff;
  --vscode-button-secondaryBackground: #313131;
  --vscode-button-secondaryForeground: #cccccc;
  --vscode-button-secondaryHoverBackground: #3c3c3c;
  --vscode-list-hoverBackground: #2a2d2e;
  --vscode-list-activeSelectionBackground: #04395e;
  --vscode-list-activeSelectionForeground: #ffffff;
  --vscode-testing-iconPassed: #73c991;
  --vscode-editorWarning-foreground: #cca700;
  --vscode-errorForeground: #f85149;
  --vscode-badge-background: #616161;
  --vscode-badge-foreground: #ffffff;
  --vscode-charts-red: #f14c4c;
  --vscode-charts-blue: #3794ff;
  --vscode-charts-green: #89d185;
  --vscode-charts-orange: #d18616;
  --vscode-charts-purple: #b180d7;
  --vscode-charts-yellow: #cca700;
`;

const LIGHT = `
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
  --vscode-font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
  --vscode-editor-font-family: 'Cascadia Code', Menlo, monospace;
  --vscode-font-size: 13px;
  --vscode-editor-background: #ffffff;
  --vscode-foreground: #3b3b3b;
  --vscode-descriptionForeground: #717171;
  --vscode-panel-border: #e5e5e5;
  --vscode-sideBarSectionHeader-background: #f3f3f3;
  --vscode-sideBarSectionHeader-foreground: #3b3b3b;
  --vscode-input-background: #ffffff;
  --vscode-input-foreground: #3b3b3b;
  --vscode-input-border: #cecece;
  --vscode-focusBorder: #005fb8;
  --vscode-button-background: #005fb8;
  --vscode-button-foreground: #ffffff;
  --vscode-button-secondaryBackground: #e5e5e5;
  --vscode-button-secondaryForeground: #3b3b3b;
  --vscode-button-secondaryHoverBackground: #cccccc;
  --vscode-list-hoverBackground: #f2f2f2;
  --vscode-list-activeSelectionBackground: #e4e6f1;
  --vscode-list-activeSelectionForeground: #000000;
  --vscode-testing-iconPassed: #388a34;
  --vscode-editorWarning-foreground: #bf8803;
  --vscode-errorForeground: #f85149;
  --vscode-badge-background: #cccccc;
  --vscode-badge-foreground: #3b3b3b;
  --vscode-charts-red: #e51400;
  --vscode-charts-blue: #1a85ff;
  --vscode-charts-green: #388a34;
  --vscode-charts-orange: #d18616;
  --vscode-charts-purple: #652d90;
  --vscode-charts-yellow: #bf8803;
`;

const host = new MockHost();
(window as unknown as Record<string, unknown>).frcDevWasmSim = host;
(window as unknown as Record<string, unknown>).mock = host;

const style = document.getElementById('theme') as HTMLStyleElement;
function setTheme(dark: boolean): void {
  style.textContent = `.monaco-workbench { ${dark ? DARK : LIGHT} }`;
  document.body.classList.toggle('vs-dark', dark);
}
setTheme(true);

document.getElementById('start')!.addEventListener('click', () => host.start());
document.getElementById('stop')!.addEventListener('click', () => host.stop());
document.getElementById('dark')!.addEventListener('click', () => setTheme(true));
document.getElementById('light')!.addEventListener('click', () => setTheme(false));
