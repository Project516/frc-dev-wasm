# Bridge protocol

The robot JVM and the page exchange JSON messages through two native methods
on `dev.project516.frcdevwasm.bridge.Bridge` (`packages/java/bridge`). The runtime package
implements them in JS through CheerpJ's `natives` option.

```java
public static native void send(String json);  // Java to page
public static native String receive();         // page to Java, "[]" when empty
```

Both carry a JSON array of messages. Java batches: a bridge thread flushes
pending output and polls for input every 10 ms. JS never calls into Java
outside these two calls.

Every message is an object `{"type": string, "device": string, "data": object}`.
`device` is `""` when it does not apply.

## HAL devices

HAL device messages use the WPILib simulation WebSocket format unchanged:
`allwpilib/simulation/halsim_ws_core/doc/hardware_ws_api.md` at tag
`v2026.2.1`. Types include `PWM`, `DIO`, `AI`, `Encoder`, `DriverStation`,
`Joystick`, `SimDevice`, `CTREPCM`, `REVPH`, `PowerDistribution`, `RoboRIO`.

halsim_ws has no REV PH or power distribution devices. Those are ours, in the
same style as `CTREPCM` and `Solenoid`: `REVPH` (`<init`, `>on`, `<config`,
`>pressure_switch`, `>current`) and `REVPHSolenoid` (device `module,channel`,
`<output`), `PowerDistribution` (`<init`, `>temperature`, `>voltage`) and
`PDChannel` (device `module,channel`, `>current`).

- Data keys keep their direction prefix: `<` is a robot output, `>` is a robot
  input, `<>` is both.
- Data is sent as deltas. Java sends a device's full state when it is
  initialized and `"<init": false` when it is freed.
- After `{"type": "hello"}` from the page, Java resends the full state of every
  initialized device, as halsim_ws does on a new connection.
- `>new_data` with `>ds` false does not connect the driver station. The Sim GUI's first message after
  the resend is that, and halsim_gui connects only when a state is chosen in its Robot State window.

Examples:

```json
{"type": "PWM", "device": "0", "data": {"<init": true, "<speed": 0.5}}
{"type": "DriverStation", "device": "", "data": {">enabled": true, ">new_data": true}}
{"type": "Joystick", "device": "0", "data": {">axes": [0, -0.5], ">buttons": [false, true], ">povs": [-1]}}
```

## HAL devices in WPILib 2027

The 2027 shim follows halsim_ws_core at `v2027.0.0-alpha-8`, so some keys differ.
The page picks the keys by the robot project's year (`SimHost.year()`). The
classic Sim GUI keeps the differences in `packages/sim-gui/src/protocol.ts`.
Everything not listed here is as in 2026.

- Removed devices: `Accel`, `AO` and `Relay`.
- `PWM`: `<init`, `<raw` (pulse width in microseconds, 0 when disabled) and
  `<output_period` (the scale `PWM.setOutputPeriod` sends: 0 is 5 ms, 1 is 10 ms,
  3 is 20 ms). No `<speed`, `<position`, `<period_scale` or `<zero_latch`.
  `<duty_cycle` belongs to `dPWM`, unchanged.
- `Encoder`: `<init`, `<channel_a`, `<channel_b`, `>count`, `>rate` (distance per
  second), `<reverse_direction`. No `<samples_to_avg`. Java still accepts
  `>period` from the page and converts it to a rate.
- `AI`: `<init` and `>voltage` only.
- `AddressableLED`: `<init`, `<start`, `<length`, `<data`. There is no output port
  or `<running`. The buffer is shared by all outputs, 3 bytes per LED, at most
  1024 LEDs. `<data` is a list of `{"r", "g", "b"}`: the `<length` LEDs of the
  output's own range, from its `<start`. A write sends it to each initialized
  output whose range overlaps the write. (halsim_ws_core sends the written bytes,
  without their start, to every output, so a client cannot tell outputs apart.)
- `RoboRIO`: `>vin_voltage`, `>3v3_voltage`, `>3v3_current`, `>3v3_active` and
  `>3v3_faults` only.
- `PowerDistribution`, `PDChannel`, `REVPH` and `REVPHSolenoid` are ours, as in
  2026.
- `DriverStation`: `>robotMode` replaces `>autonomous` and `>test`. It is 0
  unknown, 1 autonomous, 2 teleoperated and 3 utility (test), as upstream's
  `WSProvider_DriverStation` sends and applies it. (From alpha-8 upstream also
  sends `>autonomous` and `>test` for older consumers such as the Romi server,
  and ignores them as input. The shim sends neither.) `>new_data` is also sent to
  the page. Upstream has no op mode keys, so ours extend it (the Sim GUI mirror
  and the classic GUI use them, 2027 only):
  - `>opmode` is the selected op mode id as a decimal string, `"0"` for none. It
    sets the op mode bits of the control word, and a change on the robot side is
    sent back like the other `>` keys. A string that is not a number is ignored.
  - `<opmode_options` is the list the robot publishes with `setOpModeOptions`:
    `{"id", "name", "group", "description", "textColor", "backgroundColor"}`, with
    `id` a decimal string (robot mode in bits 57-56 and a 56-bit name hash below)
    and the colors integers (0xRRGGBB, or -1 for the default). It is sent when the
    robot sets the options and in the full state on `hello`.
  - `<program_state` is the control word the program observed with
    `observeUserProgram`, as a decimal string. It is sent when it changes, and in
    the full state. Both GUIs compare its op mode with the selection to show GOOD,
    BAD or NONE. halsim_gui blocks Enable until it shows GOOD (when the robot
    publishes options); the classic GUI blocks nothing.
  The strings are there because ids and control words go past 2^53, which a JSON
  number cannot keep.
- `Joystick`: `>povs` are POVDirection bitmasks (1 up, 2 right, 4 down, 8 left,
  so up and right is 3, centered is 0) instead of angles. Up to 64 buttons, 12
  axes and 8 POVs. `<leds` (an integer, 0xRRGGBB) replaces `<outputs`.
  `<rumble_left` and `<rumble_right` are integers from 0 to 65535, and are sent
  only when their value changes.
- `sim.timing` from the page still carries `us` for a step.

Examples, as captured from the shim:

```json
{"type": "PWM", "device": "3", "data": {"<init": true, "<raw": 1750, "<output_period": 1}}
{"type": "Encoder", "device": "0", "data": {"<init": true, ">count": 12, ">rate": 3.5, "<reverse_direction": false, "<channel_a": 6, "<channel_b": 7}}
{"type": "DriverStation", "device": "", "data": {">ds": true, ">robotMode": 2, ">enabled": true, ">new_data": true}}
{"type": "Joystick", "device": "0", "data": {"<rumble_left": 65535, "<leds": 16711808}}
```

## NetworkTables

The shim runs an in-process NetworkTables instance. Topics are mirrored to the
page so the Sim GUI and dashboards can read and write them. Semantics follow
NT4 (`allwpilib/ntcore/doc/networktables4.adoc`).

Java to page:

```json
{"type": "nt.announce", "device": "", "data": {"id": 3, "name": "/SmartDashboard/x", "type": "double", "properties": {}}}
{"type": "nt.unannounce", "device": "", "data": {"id": 3, "name": "/SmartDashboard/x"}}
{"type": "nt.properties", "device": "", "data": {"name": "/SmartDashboard/x", "properties": {"persistent": true}}}
{"type": "nt.value", "device": "", "data": {"id": 3, "ts": 1520000, "value": 1.5}}
```

Page to Java, as if a remote NT client published the value:

```json
{"type": "nt.set", "device": "", "data": {"name": "/SmartDashboard/x", "type": "double", "value": 2.0}}
```

- `type` is the NT4 type string: `boolean`, `double`, `int`, `float`,
  `string`, `json`, `raw`, `boolean[]`, `double[]`, `int[]`, `float[]`,
  `string[]`, or a raw-backed type such as `struct:Pose2d`, `proto:...`,
  `msgpack`.
- `ts` is the NT timestamp in microseconds, on the same clock as
  `HALUtil.getFPGATime`.
- Values are JSON. Raw-backed types (`raw`, `struct:*`, `structschema`,
  `proto:*`, `msgpack`) are base64 strings.
- Java sends only changed values. After `hello`, it resends every announce and
  the latest value of each topic.

## NetworkTables client

A JVM that only reads and writes NetworkTables, such as SmartDashboard, is a
client of the page's NT4 server. It uses the same four message types as the
robot's mirror, with different roles: the JVM sends the topics it publishes, and
the page sends every topic on the server.

Java to page:

```json
{"type": "nt.hello", "device": "", "data": {"identity": "SmartDashboard"}}
{"type": "nt.announce", "device": "", "data": {"id": 3, "name": "/SmartDashboard/x", "type": "double", "properties": {}}}
{"type": "nt.value", "device": "", "data": {"id": 3, "ts": 0, "value": 1.5}}
{"type": "nt.properties", "device": "", "data": {"name": "/SmartDashboard/x", "properties": {"persistent": true}}}
{"type": "nt.unannounce", "device": "", "data": {"id": 3, "name": "/SmartDashboard/x"}}
{"type": "nt.bye", "device": "", "data": {}}
```

- `nt.hello` opens a connection to the server, subscribed to every topic, and
  `nt.bye` closes it. The server removes the topics the JVM published.
- `nt.announce` publishes a topic and `nt.unannounce` unpublishes it. `id` is
  chosen by the JVM. `nt.value` sets a value of a published topic. `ts` 0 means
  the server's clock.

Page to Java:

```json
{"type": "nt.connected", "device": "", "data": {}}
{"type": "nt.announce", "device": "", "data": {"id": 12, "name": "/SmartDashboard/y", "type": "int", "properties": {}}}
{"type": "nt.value", "device": "", "data": {"id": 12, "ts": 1520000, "value": 4}}
```

- `nt.connected` answers `nt.hello`. The announces and values of the topics
  already on the server follow it, then every change.
- The page's server serves only while a robot program runs. Until then the page
  keeps trying, and when the program stops it sends `nt.disconnected`. The JVM
  announces its topics again after the next `nt.connected`.
- `id` is the server's id of the topic, which differs from the id the JVM
  chose. The values of the JVM's own topics are not sent back.
- Types and values are as in the robot's mirror.

SmartDashboard's ntcore is the 2020 NetworkTables 3 API, so its shim
(`packages/java/smartdashboard`) gives each remote topic the nearest NT3 type:
`int` and `float` read as `double`, `json` as `string`, and every other
raw-backed type as `raw`. A topic is visible once it has a value.

## Console

Java redirects `System.out` and `System.err` through the bridge:

```json
{"type": "console", "device": "", "data": {"stream": "out", "text": "Robot program starting\n"}}
```

`System.in` is empty, as in the Gradle `JavaExec` tasks `simulateJava` and `run`, so a program that reads it
gets end of input.

## DataLog files

When a robot program starts a background DataLog, such as `DataLogManager.start()`,
Java tells the page where the file is. The shim flushes the log on its own
thread at the log's period, so the page can read the file at any time, and does
so when the program exits.

```json
{"type": "datalog.file", "device": "", "data": {"path": "/files/frcdevwasm/robot/logs/FRC_1.wpilog"}}
```

When the program moves the log to another directory (`DataLogBackgroundWriter.setDir`, 2027), Java
sends the message again with the new path. The page copies only logs under the
robot's working directory into the project, so a log moved out of it is not
copied.

## Lifecycle

- Page to Java `{"type": "hello"}`: the page is listening. Java replies with full
  state, as described above.
- Page to Java `{"type": "stop"}`: Java exits with status 0.
- Java to page `{"type": "exit", "device": "", "data": {"code": 0}}`: sent just
  before the JVM exits for any reason the shim can observe.

## Simulation timing

Page to Java, matching `SimulatorJNI`:

```json
{"type": "sim.timing", "device": "", "data": {"op": "pause"}}
{"type": "sim.timing", "device": "", "data": {"op": "resume"}}
{"type": "sim.timing", "device": "", "data": {"op": "step", "us": 20000}}
```

Java to page, when the state changes: `{"type": "sim.timing", "device": "",
"data": {"paused": true}}`.
