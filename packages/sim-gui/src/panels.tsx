import { useEffect, useRef, useState } from 'preact/hooks';
import { useStore } from './context.ts';
import { MODES, STATIONS, type OpModeOption, type Station } from './ds.ts';
import { Check, NumInput, Panel, TextInput } from './ui.tsx';

const STATION_LABELS: Record<Station, string> = {
  red1: 'Red 1',
  red2: 'Red 2',
  red3: 'Red 3',
  blue1: 'Blue 1',
  blue2: 'Blue 2',
  blue3: 'Blue 3',
};

function groups(options: OpModeOption[]): [string, OpModeOption[]][] {
  const byGroup = new Map<string, OpModeOption[]>();
  for (const o of [...options].sort((a, b) => a.name.localeCompare(b.name))) byGroup.set(o.group, [...(byGroup.get(o.group) ?? []), o]);
  return [...byGroup];
}

export function RobotState() {
  const store = useStore();
  const { ds } = store;
  const current = ds.mode.value;
  const options = ds.modeOpModes.value;
  return (
    <Panel id="robot-state" title="Robot State">
      <div class="choices" role="group" aria-label="Robot state">
        {MODES.map(({ mode, label }) => (
          <button
            type="button"
            class={`choice${mode === current ? ' active' : ''}`}
            aria-pressed={mode === current}
            onClick={() => ds.setMode(mode)}
          >
            {label}
          </button>
        ))}
      </div>
      {store.protocol.value.opModes && options.length > 0 && (
        <div class="row">
          <label for="op-mode">Op mode</label>
          <span class="inline">
            <select id="op-mode" value={ds.opMode.value} onChange={(e) => ds.setOpMode(e.currentTarget.value)}>
              <option value="0">None</option>
              {groups(options).map(([group, list]) => (
                <optgroup label={group}>
                  {list.map((o) => (
                    <option value={o.id}>{o.name}</option>
                  ))}
                </optgroup>
              ))}
            </select>
            <span class="value" aria-label="Op mode of the robot program">{ds.opModeStatus.value}</span>
          </span>
        </div>
      )}
    </Panel>
  );
}

export function Fms() {
  const { ds } = useStore();
  const enabled = ds.enabled.value;
  return (
    <Panel id="fms" title="FMS">
      <div class="rows">
        <div class="row">
          <Check checked={ds.fms.value} onChange={(v) => ds.setFms(v)} label="FMS attached" />
        </div>
        <div class="row">
          <span>DS attached</span>
          <span class="value">{ds.attached.value ? 'Yes' : 'No'}</span>
        </div>
        <div class="row">
          <label for="fms-station">Alliance station</label>
          <select id="fms-station" value={ds.station.value} onChange={(e) => ds.setStation(e.currentTarget.value as Station)}>
            {STATIONS.map((s) => (
              <option value={s}>{STATION_LABELS[s]}</option>
            ))}
          </select>
        </div>
        <div class="row">
          <span>Match time</span>
          <span class="inline">
            <NumInput label="Match time in seconds" value={ds.matchTime.value} onCommit={(v) => ds.setMatchTime(v)} step={0.1} />
            <button type="button" class="btn" disabled={enabled} onClick={() => ds.setMatchTime(15)}>
              Auto
            </button>
            <button type="button" class="btn" disabled={enabled} onClick={() => ds.setMatchTime(135)}>
              Teleop
            </button>
          </span>
        </div>
        <div class="row">
          <span>Game specific</span>
          <TextInput label="Game specific message" value={ds.gameData.value} onCommit={(v) => ds.setGameData(v)} />
        </div>
      </div>
    </Panel>
  );
}

const REPEAT_DELAY_MS = 400;
const REPEAT_MS = 50;

export function Timing() {
  const store = useStore();
  const running = store.status.value === 'running';
  const paused = store.paused.value;
  const [time, setTime] = useState(0);
  const repeat = useRef<{ delay?: ReturnType<typeof setTimeout>; interval?: ReturnType<typeof setInterval> }>({});

  useEffect(() => {
    if (!running) return;
    // While paused the clock stands still; the newest topic timestamp follows steps.
    const update = () => setTime((paused ? store.nt.latestTimestamp() : store.nt.now()) / 1e6);
    update();
    const timer = setInterval(update, 250);
    return () => clearInterval(timer);
  }, [store, running, paused]);

  const stop = () => {
    clearTimeout(repeat.current.delay);
    clearInterval(repeat.current.interval);
  };
  useEffect(() => stop, []);

  const step = () => store.timing('step');
  const press = () => {
    step();
    repeat.current.delay = setTimeout(() => {
      repeat.current.interval = setInterval(step, REPEAT_MS);
    }, REPEAT_DELAY_MS);
  };

  return (
    <Panel id="timing" title="Timing">
      <div class="inline">
        <button type="button" class="btn" disabled={!running} aria-pressed={running && !paused} onClick={() => store.timing('resume')}>
          Run
        </button>
        <button type="button" class="btn" disabled={!running} aria-pressed={running && paused} onClick={() => store.timing('pause')}>
          Pause
        </button>
        <button
          type="button"
          class="btn"
          disabled={!running}
          onPointerDown={(e) => {
            if (e.button === 0) press();
          }}
          onPointerUp={stop}
          onPointerLeave={stop}
          onPointerCancel={stop}
          onBlur={stop}
          onClick={(e) => {
            if (e.detail === 0) step();
          }}
        >
          Step
        </button>
      </div>
      <div class="rows">
        <div class="row">
          <span>Time</span>
          <span class="value" aria-live="off">
            {running ? `${time.toFixed(3)} s` : '-'}
          </span>
        </div>
      </div>
    </Panel>
  );
}
