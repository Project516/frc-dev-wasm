import { useStore } from './context.ts';
import { DevicePanels } from './devices.tsx';
import { Field, Mechanisms } from './field.tsx';
import { Joysticks, SystemJoysticks } from './joystickPanels.tsx';
import { NetworkTables } from './networkTables.tsx';
import { Fms, RobotState, Timing } from './panels.tsx';

const STATUS_LABELS = { stopped: 'Stopped', starting: 'Starting', running: 'Running' } as const;

export function App() {
  const { status } = useStore();
  const current = status.value;
  return (
    <div class="app">
      <header class="topbar">
        <span class={`status ${current}`} role="status">
          <span class="dot" aria-hidden="true" />
          Robot program: {STATUS_LABELS[current]}
        </span>
      </header>
      {current === 'stopped' ? (
        <section class="notice">
          <h2>No robot program is running</h2>
          <p>
            Run <strong>WPILib: Simulate Robot Code</strong> from the Command Palette (F1). Devices, NetworkTables and
            field views appear here once it starts. Driver station and joystick settings apply to the next run.
          </p>
        </section>
      ) : null}
      <main class="grid">
        <RobotState />
        <Timing />
        <Fms />
        <SystemJoysticks />
        <Joysticks />
        <DevicePanels />
        <NetworkTables />
        <Field />
        <Mechanisms />
      </main>
    </div>
  );
}
