import { unzipSync } from 'fflate';
import { DeviceState, JavaCompiler, Nt4JvmClient, Nt4Server, RobotSession, SIM_HOST_GLOBAL, TestRun, VendorLibraries, loadRobotProject, loadRuntimeAssets, loadTestSources, resolveProjectJars, resolveTestJars, testClassNames, type BridgeMessage, type SimHost, type SimStatus } from '../src/index.ts';
import type { RuntimeAssets } from '../src/assets.ts';

const decoder = new TextDecoder();

async function templateSources(name: string): Promise<Record<string, string>> {
  const assets = await loadRuntimeAssets('2026', '/runtime/');
  const zip = unzipSync(new Uint8Array(await (await fetch(assets.url('wpilib/templates.zip'))).arrayBuffer()));
  const prefix = `templates/${name}/`;
  const sources: Record<string, string> = {};
  for (const [path, data] of Object.entries(zip)) {
    if (!path.startsWith(prefix) || !path.endsWith('.java')) continue;
    const text = decoder.decode(data).replaceAll(`edu.wpi.first.wpilibj.templates.${name}`, 'frc.robot');
    sources[`frc/robot/${path.slice(prefix.length)}`] = text;
  }
  return sources;
}

const VENDOR_ROBOT = `package frc.robot;

import com.ctre.phoenix6.Utils;
import com.ctre.phoenix6.hardware.Pigeon2;
import com.ctre.phoenix6.hardware.TalonFX;
import com.reduxrobotics.sensors.canandmag.Canandmag;
import com.revrobotics.spark.SparkLowLevel;
import com.revrobotics.spark.SparkMax;
import com.revrobotics.spark.SparkSim;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.wpilibj.TimedRobot;

public class Robot extends TimedRobot {
  public Robot() {
    SparkMax spark = new SparkMax(4, SparkLowLevel.MotorType.kBrushless);
    spark.set(0.5);
    SparkSim sparkSim = new SparkSim(spark, DCMotor.getNEO(1));
    sparkSim.setPosition(3.0);
    System.out.println("VENDOR rev applied=" + spark.getAppliedOutput() + " position=" + spark.getEncoder().getPosition());

    new Canandmag(1).getPosition();
    System.out.println("VENDOR redux started");

    Pigeon2 pigeon = new Pigeon2(3);
    pigeon.getSimState().setRawYaw(30);
    System.out.println("VENDOR phoenix sim=" + Utils.isSimulation() + " yaw=" + pigeon.getYaw().refresh().getValueAsDouble());
    new TalonFX(5).getPosition().refresh();
    System.out.println("VENDOR done");
  }
}
`;

const PHOENIX_ROBOT = `package frc.robot;

import static edu.wpi.first.units.Units.*;

import com.ctre.phoenix6.configs.*;
import com.ctre.phoenix6.controls.PositionVoltage;
import com.ctre.phoenix6.hardware.*;
import com.ctre.phoenix6.signals.StaticFeedforwardSignValue;
import com.ctre.phoenix6.sim.TalonFXSimState;
import com.ctre.phoenix6.swerve.*;
import com.ctre.phoenix6.swerve.SwerveModuleConstants.*;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.math.system.plant.LinearSystemId;
import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.simulation.DCMotorSim;

public class Robot extends TimedRobot {
  private static final double GEAR = 10;
  private final TalonFX talon = new TalonFX(30);
  private final DCMotor gearbox = DCMotor.getKrakenX60Foc(1);
  private final DCMotorSim physics = new DCMotorSim(LinearSystemId.createDCMotorSystem(gearbox, 0.001, GEAR), gearbox);
  private final SwerveDrivetrain<TalonFX, TalonFX, CANcoder> drivetrain;
  private final Timer printTimer = new Timer();

  public Robot() {
    TalonFXConfiguration config = new TalonFXConfiguration();
    config.Slot0.kP = 20;
    config.Feedback.SensorToMechanismRatio = GEAR;
    talon.getConfigurator().apply(config);
    drivetrain = createDrivetrain();
    printTimer.start();
  }

  private static SwerveDrivetrain<TalonFX, TalonFX, CANcoder> createDrivetrain() {
    Slot0Configs steerGains = new Slot0Configs().withKP(100).withKI(0).withKD(0.5).withKS(0.1).withKV(1.91).withKA(0)
        .withStaticFeedforwardSign(StaticFeedforwardSignValue.UseClosedLoopSign);
    Slot0Configs driveGains = new Slot0Configs().withKP(0.1).withKI(0).withKD(0).withKS(0).withKV(0.124);
    var creator = new SwerveModuleConstantsFactory<TalonFXConfiguration, TalonFXConfiguration, CANcoderConfiguration>()
        .withDriveMotorGearRatio(5.14).withSteerMotorGearRatio(12.8).withCouplingGearRatio(3.5714)
        .withWheelRadius(Inches.of(2)).withSteerMotorGains(steerGains).withDriveMotorGains(driveGains)
        .withSteerMotorClosedLoopOutput(ClosedLoopOutputType.Voltage).withDriveMotorClosedLoopOutput(ClosedLoopOutputType.Voltage)
        .withSlipCurrent(Amps.of(120)).withSpeedAt12Volts(MetersPerSecond.of(4.69))
        .withDriveMotorType(DriveMotorArrangement.TalonFX_Integrated).withSteerMotorType(SteerMotorArrangement.TalonFX_Integrated)
        .withFeedbackSource(SteerFeedbackType.FusedCANcoder)
        .withDriveMotorInitialConfigs(new TalonFXConfiguration()).withSteerMotorInitialConfigs(new TalonFXConfiguration())
        .withEncoderInitialConfigs(new CANcoderConfiguration())
        .withSteerInertia(KilogramSquareMeters.of(0.004)).withDriveInertia(KilogramSquareMeters.of(0.025))
        .withSteerFrictionVoltage(Volts.of(0.2)).withDriveFrictionVoltage(Volts.of(0.2));
    double x = Inches.of(10.5).in(Meters);
    return new SwerveDrivetrain<TalonFX, TalonFX, CANcoder>(TalonFX::new, TalonFX::new, CANcoder::new,
        new SwerveDrivetrainConstants().withCANBusName("rio").withPigeon2Id(0),
        creator.createModuleConstants(1, 11, 21, Rotations.of(0.1), Meters.of(x), Meters.of(x), false, true, false),
        creator.createModuleConstants(2, 12, 22, Rotations.of(-0.2), Meters.of(x), Meters.of(-x), true, true, false),
        creator.createModuleConstants(3, 13, 23, Rotations.of(0.3), Meters.of(-x), Meters.of(x), false, true, false),
        creator.createModuleConstants(4, 14, 24, Rotations.of(0), Meters.of(-x), Meters.of(-x), true, true, false));
  }

  @Override
  public void simulationPeriodic() {
    TalonFXSimState state = talon.getSimState();
    state.setSupplyVoltage(12);
    talon.setControl(new PositionVoltage(2.0));
    physics.setInputVoltage(state.getMotorVoltage());
    physics.update(0.02);
    state.setRawRotorPosition(physics.getAngularPositionRotations() * GEAR);
    state.setRotorVelocity(physics.getAngularVelocityRPM() / 60 * GEAR);

    drivetrain.setControl(new SwerveRequest.RobotCentric().withDriveRequestType(SwerveModule.DriveRequestType.Velocity).withVelocityX(1.0));
    drivetrain.updateSimState(0.02, 12);
    if (isEnabled() && printTimer.advanceIfElapsed(0.5)) {
      System.out.println("PHOENIX position=" + talon.getPosition().refresh().getValueAsDouble() + " x=" + drivetrain.getState().Pose.getX());
    }
  }
}
`;

const OVERRUN_ROBOT = `package frc.robot;

import edu.wpi.first.wpilibj.TimedRobot;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Robot extends TimedRobot {
  private final ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(1000);
  private final AtomicLong consumed = new AtomicLong();
  private long produced;
  private long dropped;
  private int loops;

  public Robot() {
    Thread consumer = new Thread(() -> {
      try {
        while (true) {
          queue.take();
          consumed.incrementAndGet();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    consumer.setDaemon(true);
    consumer.start();
  }

  @Override
  public void robotPeriodic() {
    for (int i = 0; i < 200; i++) {
      produced++;
      if (!queue.offer(i)) {
        dropped++;
      }
    }
    long end = System.nanoTime() + 30_000_000L;
    while (System.nanoTime() < end) {}
    if (++loops % 25 == 0) {
      System.out.println("OVERRUN produced=" + produced + " consumed=" + consumed.get() + " dropped=" + dropped);
    }
  }
}
`;

// What Spectrum's telemetry does: a few hundred DogLog entries of mixed types every loop, published to
// NetworkTables. It reports the loop period and the free room in DogLog's queue every 50 loops.
const DOGLOG_ROBOT = `package frc.robot;

import dev.doglog.DogLog;
import dev.doglog.DogLogOptions;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.TimedRobot;
import edu.wpi.first.wpilibj.Timer;

public class Robot extends TimedRobot {
  private final SwerveModuleState[] states = new SwerveModuleState[4];
  private final double[] array = new double[6];
  private NetworkTableEntry room;
  private long lastStart;
  private long periodSum;
  private long minRoom = 1000;
  private int loops;

  public Robot() {
    DogLog.setOptions(new DogLogOptions().withNtPublish(true).withCaptureDs(true).withCaptureNt(false).withCaptureConsole(true).withLogExtras(false));
    for (int i = 0; i < 4; i++) {
      states[i] = new SwerveModuleState(1.0 + i, Rotation2d.fromDegrees(10 * i));
    }
    // One entry of each type, then a pause, so class loading is not counted as a slow loop.
    logAll(0);
    Timer.delay(2.0);
  }

  private void logAll(int loop) {
    double v = loop * 0.01;
    for (int i = 0; i < 150; i++) DogLog.log("Bench/double/" + i, v + i);
    for (int i = 0; i < 60; i++) DogLog.log("Bench/boolean/" + i, ((loop + i) & 1) == 0);
    for (int i = 0; i < 20; i++) DogLog.log("Bench/string/" + i, "state" + (loop % 7));
    for (int i = 0; i < 8; i++) DogLog.log("Bench/pose/" + i, new Pose2d(v, i, Rotation2d.fromDegrees(v)));
    for (int i = 0; i < 4; i++) DogLog.log("Bench/states/" + i, states);
    for (int i = 0; i < 8; i++) {
      array[0] = v;
      DogLog.log("Bench/doubleArray/" + i, array);
    }
  }

  @Override
  public void robotPeriodic() {
    long start = System.nanoTime();
    if (lastStart != 0) {
      periodSum += start - lastStart;
    }
    lastStart = start;
    logAll(loops);
    if (room == null && NetworkTableInstance.getDefault().getTopicInfo("/Robot/DogLog/QueueRemainingCapacity").length > 0) {
      room = NetworkTableInstance.getDefault().getEntry("/Robot/DogLog/QueueRemainingCapacity");
    }
    if (room != null) {
      minRoom = Math.min(minRoom, room.getInteger(1000));
    }
    if (++loops % 50 == 0) {
      System.out.println("DOGLOG loops=" + loops + " periodMs=" + periodSum / 49 / 100_000 / 10.0 + " minRoom=" + minRoom);
      periodSum = 0;
      minRoom = 1000;
    }
  }
}
`;

const VENDOR_MAIN = `package frc.robot;

import edu.wpi.first.wpilibj.RobotBase;

public final class Main {
  private Main() {}

  public static void main(String... args) {
    RobotBase.startRobot(Robot::new);
  }
}
`;

// A TimedRobot with a PWM motor and a joystick, for the Sim GUI tests. It prints
// what the program sees, one SIMGUI line per change.
const SIMGUI_ROBOT = `package frc.robot;

import edu.wpi.first.wpilibj.Joystick;
import edu.wpi.first.wpilibj.PWM;
import edu.wpi.first.wpilibj.TimedRobot;

public class Robot extends TimedRobot {
  private final PWM motor = new PWM(0);
  private final Joystick stick = new Joystick(0);
  private String last = "";

  private void report(String line) {
    if (!line.equals(last)) {
      last = line;
      System.out.println("SIMGUI " + line);
    }
  }

  @Override
  public void robotInit() {
    System.out.println("SIMGUI started");
  }

  @Override
  public void disabledPeriodic() {
    motor.setSpeed(0);
    report("disabled");
  }

  @Override
  public void teleopPeriodic() {
    motor.setSpeed(0.5);
    report("teleop enabled=" + isTeleopEnabled() + " axis0=" + stick.getRawAxis(0));
  }
}
`;

// The same program for WPILib 2027, where PWM takes a pulse width.
const SIMGUI_ROBOT_2027 = `package frc.robot;

import org.wpilib.driverstation.Joystick;
import org.wpilib.framework.TimedRobot;
import org.wpilib.hardware.discrete.PWM;

public class Robot extends TimedRobot {
  private final PWM motor = new PWM(0);
  private final Joystick stick = new Joystick(0);
  private String last = "";

  private void report(String line) {
    if (!line.equals(last)) {
      last = line;
      System.out.println("SIMGUI " + line);
    }
  }

  public Robot() {
    System.out.println("SIMGUI started");
  }

  @Override
  public void disabledPeriodic() {
    motor.setPulseTimeMicroseconds(1500);
    report("disabled");
  }

  @Override
  public void teleopPeriodic() {
    motor.setPulseTimeMicroseconds(1750);
    report("teleop enabled=" + isTeleopEnabled() + " axis0=" + stick.getRawAxis(0));
  }
}
`;

const MAIN_2027 = VENDOR_MAIN.replace('edu.wpi.first.wpilibj.RobotBase', 'org.wpilib.framework.RobotBase');

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

// Publishes what a simulated robot would: a Field2d, a moving value and a few plain topics.
// A 2027 Field2d publishes its pose as a Pose2d struct, 2026 as three doubles.
function simulatedRobot(year: string) {
  const server = new Nt4Server();
  (window as unknown as { frcDevWasmNt4: Nt4Server }).frcDevWasmNt4 = server;
  server.publish('/SmartDashboard/Field/.type', 'string', 'Field2d');
  server.publish('/SmartDashboard/Mode', 'string', 'teleop');
  server.publish('/SmartDashboard/Enabled', 'boolean', true);
  let tick = 0;
  setInterval(() => {
    tick += 1;
    const t = tick / 20;
    const x = 8 + 3 * Math.cos(t / 2);
    const y = 4 + 2 * Math.sin(t / 2);
    const degrees = (t * 30) % 360;
    if (year === '2027') {
      const pose = new DataView(new ArrayBuffer(24));
      [x, y, (degrees * Math.PI) / 180].forEach((value, i) => pose.setFloat64(i * 8, value, true));
      server.publish('/SmartDashboard/Field/robotPose', 'struct:Pose2d', new Uint8Array(pose.buffer));
    } else {
      server.publish('/SmartDashboard/Field/robotPose', 'double[]', [x, y, degrees]);
    }
    server.publish('/SmartDashboard/Sine', 'double', Math.sin(t));
  }, 50);
  const toRobot: BridgeMessage[] = [];
  server.attachRobot((message) => toRobot.push(message));
  (window as unknown as { toRobot: BridgeMessage[] }).toRobot = toRobot;
  return server;
}

// The workspace side of tool file access (apps/ide/src/frc/toolFiles.ts), over a
// map of workspace paths to bytes. The test queues `answers`, the paths each
// dialog returns, before the tool opens a dialog.
function workspaceFiles(initial: Record<string, number[] | string>) {
  const w = window as unknown as { workspace?: { files: Map<string, Uint8Array>; answers: string[][]; picks: unknown[] } };
  if (!w.workspace) {
    const files = new Map<string, Uint8Array>();
    const state = { files, answers: [] as string[][], picks: [] as unknown[] };
    w.workspace = state;
    window.addEventListener('message', (event) => {
      const message = event.data;
      if (!message?.frcToolFiles) return;
      if (message.type === 'pick') {
        state.picks.push(message);
        const paths = state.answers.shift() ?? [];
        const picked = paths.map((path) => ({ path, data: message.kind === 'open' ? files.get(path) : undefined }));
        (event.source as Window).postMessage({ frcToolFiles: true, type: 'picked', id: message.id, files: picked }, event.origin);
      } else if (message.type === 'read') {
        const found = (message.paths as string[]).flatMap((path) => (files.has(path) ? [{ path, data: files.get(path) }] : []));
        (event.source as Window).postMessage({ frcToolFiles: true, type: 'picked', id: message.id, files: found }, event.origin);
      } else if (message.type === 'write') {
        files.set(message.path, message.data);
      }
    });
  }
  for (const [path, data] of Object.entries(initial)) {
    w.workspace.files.set(path, typeof data === 'string' ? new TextEncoder().encode(data) : Uint8Array.from(data));
  }
  return w.workspace;
}

// Each scenario returns a JSON-serializable summary; test/run.mjs checks it.
const scenarios = {
  async robotStartupFailure() {
    const before = new Set(document.querySelectorAll('iframe'));
    const loader = URL.createObjectURL(new Blob([
      'globalThis.cheerpjInit = async () => {}; globalThis.cheerpOSAddStringFile = () => {};',
    ], { type: 'application/javascript' }));
    try {
      const assets: RuntimeAssets = {
        year: '2027', wpilibVersion: 'test', wpilibClasspath: [], provided: new Set(),
        tools: '', shim: '', junit: '', jvmLoader: loader,
        url: (file) => new URL(file, location.href),
      };
      const error = await RobotSession.start({
        assets,
        jar: new Uint8Array(),
        vendorJars: [{ key: 'test:vendor:1', name: 'vendor-1.jar', data: async () => { throw new Error('vendor cache read failed'); } }],
      }).then(() => '', (e: Error) => e.message);
      if (error !== 'vendor cache read failed') throw new Error(`Unexpected startup result: ${error}`);
      const remaining = [...document.querySelectorAll('iframe')].filter((frame) => !before.has(frame)).length;
      if (remaining !== 0) throw new Error(`Failed startup leaked ${remaining} JVM iframe(s)`);
      return { error, remaining };
    } finally {
      URL.revokeObjectURL(loader);
      for (const frame of document.querySelectorAll('iframe')) if (!before.has(frame)) frame.remove();
    }
  },

  // The IDE's server serves only while a robot program runs, as a robot's own server does.
  async nt4RobotOnly() {
    const server = new Nt4Server({ robotOnly: true });
    const peer = () => {
      const state = { closed: '' };
      return { state, peer: { text: () => undefined, binary: () => undefined, close: (code: number, reason: string) => (state.closed = `${code} ${reason}`) } };
    };
    const protocols = ['v4.1.networktables.first.wpi.edu'];
    const refused = server.connect('/nt/before', protocols, peer().peer) === null;
    server.attachRobot(() => undefined);
    const dashboard = peer();
    const accepted = server.connect('/nt/dash', protocols, dashboard.peer) !== null;

    const jvm: BridgeMessage[] = [];
    const client = new Nt4JvmClient(server);
    client.onMessages([{ type: 'nt.hello', device: '', data: { identity: 'sd' } }], (m) => jvm.push(m));
    server.detachRobot();
    const droppedJvm = jvm.some((m) => m.type === 'nt.disconnected');
    await wait(1500);
    const stillRefused = !jvm.slice(jvm.findIndex((m) => m.type === 'nt.disconnected')).some((m) => m.type === 'nt.connected');
    server.attachRobot(() => undefined);
    await wait(1500);
    const reconnected = jvm.filter((m) => m.type === 'nt.connected').length === 2;
    client.close();
    const result = { refused, accepted, dashboardClosed: dashboard.state.closed, droppedJvm, stillRefused, reconnected };
    if (!refused || !accepted || !dashboard.state.closed.startsWith('1001') || !droppedJvm || !stillRefused || !reconnected) throw new Error(JSON.stringify(result));
    return result;
  },

  async nt4Server() {
    const server = new Nt4Server();
    const toRobot: BridgeMessage[] = [];
    server.attachRobot((m) => toRobot.push(m));
    (window as unknown as { frcDevWasmNt4: Nt4Server }).frcDevWasmNt4 = server;
    const robot = (type: string, data: Record<string, unknown>) => server.handleRobotMessage({ type, device: '', data });
    robot('nt.announce', { id: 1, name: '/SmartDashboard/speed', type: 'double', properties: {} });
    robot('nt.value', { id: 1, ts: 1000, value: 0.5 });
    robot('nt.announce', { id: 2, name: '/SmartDashboard/mode', type: 'string', properties: {} });
    robot('nt.value', { id: 2, ts: 1000, value: 'teleop' });

    const frame = document.createElement('iframe');
    frame.src = './dashboard.html';
    document.body.append(frame);
    await wait(2000);
    robot('nt.announce', { id: 3, name: '/Robot/pose', type: 'double[]', properties: {} });
    robot('nt.value', { id: 3, ts: 2000, value: [1, 2, 3] });
    robot('nt.value', { id: 1, ts: 2000, value: 3 });
    await wait(3000);
    const client = frame.contentWindow as unknown as { ntSeen: Record<string, unknown>; ntConnected: boolean };
    const result = { connected: client.ntConnected, seen: { ...client.ntSeen }, toRobot, topics: server.getTopics().map((t) => t.name) };
    frame.remove();
    return result;
  },

  async elastic(year: string) {
    const server = new Nt4Server();
    const connections: string[] = [];
    const connect = server.connect.bind(server);
    server.connect = (path, protocols, peer) => {
      connections.push(`${path} ${protocols.join(',')}`);
      return connect(path, protocols, peer);
    };
    (window as unknown as { frcDevWasmNt4: Nt4Server }).frcDevWasmNt4 = server;
    server.handleRobotMessage({ type: 'nt.announce', device: '', data: { id: 1, name: '/SmartDashboard/Speed', type: 'double', properties: {} } });
    server.handleRobotMessage({ type: 'nt.value', device: '', data: { id: 1, ts: 1, value: 1.25 } });
    const frame = document.createElement('iframe');
    frame.style.cssText = 'width:1200px;height:800px';
    frame.src = `/runtime/${year}/tools/elastic/index.html`;
    document.body.append(frame);
    await wait(25000);
    return { connections, topics: server.getTopics().map((t) => t.name) };
  },

  async advantagescope() {
    const server = new Nt4Server();
    (window as unknown as { frcDevWasmNt4: Nt4Server }).frcDevWasmNt4 = server;
    const pose = new DataView(new ArrayBuffer(24));
    [1.5, 2.5, 0.5].forEach((v, i) => pose.setFloat64(i * 8, v, true));
    const encoder = new TextEncoder();
    server.publish('/.schema/struct:Rotation2d', 'structschema', encoder.encode('double value'));
    server.publish('/.schema/struct:Pose2d', 'structschema', encoder.encode('double x;double y;Rotation2d rotation'));
    server.publish('/SmartDashboard/Speed', 'double', 1.25);
    server.publish('/SmartDashboard/Mode', 'string', 'teleop');
    server.publish('/Robot/Pose', 'struct:Pose2d', new Uint8Array(pose.buffer));
    const frame = document.createElement('iframe');
    frame.id = 'advantagescope';
    frame.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;border:0';
    frame.src = '/runtime/tools/advantagescope/index.html';
    document.body.append(frame);
    return { topics: server.getTopics().map((t) => `${t.name} ${t.type}`) };
  },

  // Glass in an iframe against a simulated robot, see simulatedRobot. Driven by
  // packages/wpigui-tools/test/glass.mjs.
  async glassStart(year = '2026') {
    simulatedRobot(year);
    const frame = document.createElement('iframe');
    frame.id = 'glass';
    frame.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;border:0';
    frame.src = `/runtime/${year}/tools/glass/index.html`;
    document.body.append(frame);
    return 'started';
  },

  // Any wpigui tool in an iframe next to simulatedRobot, with an in-memory
  // workspace holding `files` (bytes as number arrays). See workspaceFiles.
  async toolStart(app: string, files: Record<string, number[] | string> = {}, year = '2026') {
    simulatedRobot(year);
    workspaceFiles(files);
    const frame = document.createElement('iframe');
    frame.id = 'tool';
    frame.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;border:0';
    frame.src = `/runtime/${year}/tools/${app}/index.html`;
    document.body.append(frame);
    return 'started';
  },

  // SIMGUI_ROBOT simulated for the Sim GUI, which opens in an iframe the way the IDE
  // opens it: the window offers a SimHost and the NT4 server, then the robot starts.
  // Driven by packages/wpigui-tools/test/simgui.mjs, which reads window.simTest.
  async simGuiStart(app = 'simgui', year = '2026') {
    const assets = await loadRuntimeAssets(year, '/runtime/');
    const compiler = new JavaCompiler(assets);
    const built = await compiler.compile(
      year === '2027'
        ? { 'frc/robot/Main.java': MAIN_2027, 'frc/robot/Robot.java': SIMGUI_ROBOT_2027 }
        : { 'frc/robot/Main.java': VENDOR_MAIN, 'frc/robot/Robot.java': SIMGUI_ROBOT },
    );
    compiler.dispose();
    if (!built.jar) throw new Error(`build failed: ${JSON.stringify(built.diagnostics.slice(0, 5))}`);

    const nt = new Nt4Server();
    const devices = new DeviceState();
    const listeners = new Set<(messages: BridgeMessage[]) => void>();
    const statusListeners = new Set<(status: SimStatus) => void>();
    let status: SimStatus = 'starting';
    let robot: RobotSession | undefined;
    const test = { status: status as SimStatus, console: '', toRobot: [] as BridgeMessage[], robot: undefined as RobotSession | undefined };
    const setStatus = (next: SimStatus) => {
      status = test.status = next;
      for (const listener of statusListeners) listener(next);
    };
    const host: SimHost = {
      listen: (listener) => (listeners.add(listener), () => listeners.delete(listener)),
      send(message) {
        test.toRobot.push(message);
        robot?.send(message);
        if (robot && message.type !== 'sim.timing') devices.apply(message);
      },
      snapshot: () => devices.snapshot(),
      status: () => status,
      year: () => year,
      projectRoot: () => '/workspace/MyRobot',
      onStatus: (listener) => (statusListeners.add(listener), () => statusListeners.delete(listener)),
      nt,
    };
    workspaceFiles({});
    Object.assign(window, { [SIM_HOST_GLOBAL]: host, frcDevWasmNt4: nt, simTest: test });

    const frame = document.createElement('iframe');
    frame.id = 'tool';
    frame.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;border:0';
    frame.src = `/runtime/${year}/tools/${app}/index.html`;
    document.body.append(frame);

    nt.attachRobot((message) => robot?.send(message));
    robot = await RobotSession.start({
      assets,
      jar: built.jar,
      onMessages: (messages) => {
        if (status === 'starting') setStatus('running');
        for (const message of messages) {
          if (message.type === 'console') test.console += String(message.data.text ?? '');
          else if (message.type.startsWith('nt.')) nt.handleRobotMessage(message);
          else devices.apply(message);
        }
        for (const listener of listeners) listener(messages);
      },
    });
    test.robot = robot;
    return 'started';
  },

  // A robot program that logs with DataLogManager, then stops. The log lands in the
  // in-memory workspace under /workspace/MyRobot, as the IDE puts it in the project.
  async robotDataLog() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const compiler = new JavaCompiler(assets);
    const built = await compiler.compile({
      'frc/robot/Main.java': 'package frc.robot; import edu.wpi.first.wpilibj.RobotBase; public final class Main { public static void main(String... args) { RobotBase.startRobot(Robot::new); } }',
      'frc/robot/Robot.java': `package frc.robot;
import edu.wpi.first.util.datalog.DoubleLogEntry;
import edu.wpi.first.wpilibj.DataLogManager;
import edu.wpi.first.wpilibj.TimedRobot;
public class Robot extends TimedRobot {
  private DoubleLogEntry value;
  private int count;
  @Override public void robotInit() {
    DataLogManager.start();
    DataLogManager.log("sim log started");
    value = new DoubleLogEntry(DataLogManager.getLog(), "/Test/Counter");
  }
  @Override public void robotPeriodic() { value.append(++count); }
}`,
    });
    compiler.dispose();
    if (!built.jar) return { built: false, diagnostics: built.diagnostics };
    const saved: { path: string; size: number }[] = [];
    let output = '';
    const robot = await RobotSession.start({
      assets,
      jar: built.jar,
      onMessages: (messages) => {
        for (const m of messages) if (m.type === 'console') output += String(m.data.text ?? '');
      },
      onLogFiles: (files) => {
        for (const file of files) {
          workspaceFiles({}).files.set(`/workspace/MyRobot/${file.path}`, file.data);
          saved.push({ path: `/workspace/MyRobot/${file.path}`, size: file.data.length });
        }
      },
    });
    for (let i = 0; i < 240 && !output.includes('sim log started'); i++) await wait(500);
    await wait(2000);
    await robot.stop();
    return { built: true, started: output.includes('sim log started'), saved };
  },

  async compileTemplate() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const compiler = new JavaCompiler(assets);
    const sources = await templateSources('commandbased');
    const first = await compiler.compile(sources);
    const second = await compiler.compile(sources);
    const broken = await compiler.compile({ ...sources, 'frc/robot/Robot.java': `${sources['frc/robot/Robot.java']}\nclass Broken { int x = "no"; }` });
    compiler.dispose();
    return {
      first: { success: first.success, millis: first.millis, jarBytes: first.jar?.length ?? 0, diagnostics: first.diagnostics },
      secondMillis: second.millis,
      broken: { success: broken.success, diagnostics: broken.diagnostics },
    };
  },

  // A robot using REVLib, ReduxLib and Phoenix 6, resolved from vendordeps the way a real project is.
  async simulateVendors() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const index = (await (await fetch(assets.url('vendor/index.json'))).json()) as { vendordeps: Array<{ file: string; name: string }> };
    const wanted = ['REVLib', 'ReduxLib', 'CTRE-Phoenix (v6)'];
    const files = new Map<string, Uint8Array>();
    files.set('build.gradle', new TextEncoder().encode('plugins { id "edu.wpi.first.GradleRIO" version "2026.2.1" }\ndef ROBOT_MAIN_CLASS = "frc.robot.Main"\n'));
    for (const dep of index.vendordeps.filter((v) => wanted.includes(v.name))) {
      files.set(`vendordeps/${dep.file.split('/').pop()}`, new Uint8Array(await (await fetch(assets.url(dep.file))).arrayBuffer()));
    }
    files.set('src/main/java/frc/robot/Robot.java', new TextEncoder().encode(VENDOR_ROBOT));
    files.set('src/main/java/frc/robot/Main.java', new TextEncoder().encode(VENDOR_MAIN));
    const project = await loadRobotProject(
      {
        read: async (path) => files.get(path),
        list: async (dir) => [...files.keys()].filter((path) => path.startsWith(dir)),
      },
      assets,
      'vendors',
    );
    const compiler = new JavaCompiler(assets);
    const vendorJars = await new VendorLibraries({
      assets,
      transform: (jar, jarName) => compiler.transformVendorJar(jar, jarName),
      proxy: () => 'https://frc-proxy.project516.dev',
    }).resolve(project.vendor);
    const built = await compiler.compile(project.sources, { classpath: project.classpath, processors: project.processors, vendorJars });
    compiler.dispose();
    if (!built.jar) throw new Error(`build failed: ${JSON.stringify(built.diagnostics.slice(0, 5))}`);

    const messages: BridgeMessage[] = [];
    const robot = await RobotSession.start({
      assets,
      jar: built.jar,
      classpath: project.classpath,
      vendorJars,
      mainClass: project.mainClass,
      onMessages: (batch) => messages.push(...batch),
    });
    await wait(12000);
    await robot.stop();
    const consoleText = messages.filter((m) => m.type === 'console').map((m) => m.data.text).join('');
    const lines = consoleText.split('\n').filter((l) => l.startsWith('VENDOR'));
    const expected = ['VENDOR rev applied=0.5 position=3.0', 'VENDOR redux started', 'VENDOR phoenix sim=true yaw=30.0', 'VENDOR done'];
    for (const line of expected) {
      if (!lines.includes(line)) throw new Error(`missing "${line}" in robot output:\n${consoleText.slice(0, 3000)}`);
    }
    return { lines, warnings: project.warnings };
  },

  // Maven dependencies from build.gradle: commons-math3 on both classpaths and AdvantageKit's @AutoLog processor.
  async simulateMaven() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const index = (await (await fetch(assets.url('vendor/index.json'))).json()) as { vendordeps: Array<{ file: string; name: string }> };
    const files = new Map<string, Uint8Array>();
    const put = (path: string, text: string) => files.set(path, new TextEncoder().encode(text));
    put(
      'build.gradle',
      `plugins { id "edu.wpi.first.GradleRIO" version "2026.2.1" }
def ROBOT_MAIN_CLASS = "frc.robot.Main"
repositories { mavenCentral() }
dependencies {
  implementation wpi.java.deps.wpilib()
  implementation wpi.java.vendor.java()
  implementation 'org.apache.commons:commons-math3:3.6.1'
  def akitJson = new groovy.json.JsonSlurper().parseText(new File(projectDir.getAbsolutePath() + "/vendordeps/AdvantageKit.json").text)
  annotationProcessor "org.littletonrobotics.akit:akit-autolog:$akitJson.version"
}
`,
    );
    for (const dep of index.vendordeps.filter((v) => v.name === 'AdvantageKit')) {
      files.set('vendordeps/AdvantageKit.json', new Uint8Array(await (await fetch(assets.url(dep.file))).arrayBuffer()));
    }
    put(
      'src/main/java/frc/robot/ArmIO.java',
      `package frc.robot;

import org.littletonrobotics.junction.AutoLog;

public interface ArmIO {
  @AutoLog
  class ArmIOInputs {
    public double position = 0.0;
  }

  default void updateInputs(ArmIOInputs inputs) {}
}
`,
    );
    put(
      'src/main/java/frc/robot/Robot.java',
      `package frc.robot;

import edu.wpi.first.wpilibj.TimedRobot;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.littletonrobotics.junction.LogTable;

public class Robot extends TimedRobot {
  record Setpoint(double angle, int stage) {}

  public Robot() {
    double det = new LUDecomposition(new Array2DRowRealMatrix(new double[][] {{4, 3}, {6, 3}})).getDeterminant();
    DescriptiveStatistics stats = new DescriptiveStatistics(new double[] {1, 2, 3, 4});
    ArmIOInputsAutoLogged inputs = new ArmIOInputsAutoLogged();
    inputs.position = 2.5;
    System.out.println("MAVEN det=" + Math.round(det) + " mean=" + stats.getMean() + " position=" + inputs.position);
    // AdvantageKit logs records through record reflection
    LogTable table = new LogTable(0);
    table.put("Setpoint", new Setpoint(1.5, 2));
    Setpoint back = table.get("Setpoint", new Setpoint(0, 0));
    System.out.println("MAVEN record=" + back.angle() + "," + back.stage());
    System.out.println("MAVEN done");
  }
}
`,
    );
    put('src/main/java/frc/robot/Main.java', VENDOR_MAIN);
    const project = await loadRobotProject(
      { read: async (path) => files.get(path), list: async (dir) => [...files.keys()].filter((path) => path.startsWith(dir)) },
      assets,
      'maven',
    );
    if (project.libraries.compile.length !== 1 || project.libraries.processors.length !== 1) {
      throw new Error(`libraries not read: ${JSON.stringify(project.libraries)} ${project.warnings.join('; ')}`);
    }
    const compiler = new JavaCompiler(assets);
    const libraries = new VendorLibraries({
      assets,
      transform: (jar, jarName) => compiler.transformVendorJar(jar, jarName),
      proxy: () => 'https://frc-proxy.project516.dev',
    });
    const jars = await resolveProjectJars(project, (requirements) => libraries.resolve(requirements));
    const built = await compiler.compile(project.sources, {
      classpath: project.classpath,
      processors: project.processors,
      vendorJars: jars.compile,
      processorJars: jars.processors,
    });
    const generated = built.jar ? Object.keys(unzipSync(built.jar)).filter((name) => name.includes('AutoLogged')) : [];
    compiler.dispose();
    if (!built.jar) throw new Error(`build failed: ${JSON.stringify(built.diagnostics.slice(0, 5))}`);
    if (!generated.includes('frc/robot/ArmIOInputsAutoLogged.class')) throw new Error(`no generated class in ${generated}`);

    const messages: BridgeMessage[] = [];
    const robot = await RobotSession.start({
      assets,
      jar: built.jar,
      classpath: project.classpath,
      vendorJars: jars.runtime,
      mainClass: project.mainClass,
      onMessages: (batch) => messages.push(...batch),
      onOutput: (text) => messages.push({ type: 'console', device: '', data: { text: `[cheerpj] ${text}\n` } }),
    });
    const consoleOf = () => messages.filter((m) => m.type === 'console').map((m) => m.data.text).join('');
    for (let waited = 0; waited < 60000 && !consoleOf().includes('MAVEN done'); waited += 500) await wait(500);
    await robot.stop();
    const consoleText = consoleOf();
    const lines = consoleText.split('\n').filter((l) => l.startsWith('MAVEN'));
    for (const line of ['MAVEN det=-6 mean=2.5 position=2.5', 'MAVEN record=1.5,2', 'MAVEN done']) {
      if (!lines.includes(line)) throw new Error(`missing "${line}" in robot output:\n${consoleText.slice(0, 3000)}`);
    }
    return { lines, generated, jars: jars.runtime.map((j) => j.name), processors: jars.processors.map((j) => j.name), warnings: project.warnings };
  },

  // Builds, and optionally runs, the robot project that PROJECT_DIR points at. Reports the first errors instead of throwing.
  async buildProject(files: Record<string, string>, simulateMs = 0) {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const encoder = new TextEncoder();
    const project = await loadRobotProject(
      {
        read: async (path) => (path in files ? encoder.encode(files[path]) : undefined),
        list: async (dir) => Object.keys(files).filter((path) => path.startsWith(dir)),
      },
      assets,
      'project',
    );
    const started = performance.now();
    const compiler = new JavaCompiler(assets);
    const libraries = new VendorLibraries({
      assets,
      transform: (jar, jarName) => compiler.transformVendorJar(jar, jarName),
      proxy: () => 'https://frc-proxy.project516.dev',
    });
    const warnings = [...project.warnings];
    const summary = {
      libraries: Object.fromEntries(Object.entries(project.libraries).map(([k, v]) => [k, v.map((r: { group: string; artifact: string; version: string }) => `${r.group}:${r.artifact}:${r.version}`)])),
      repositories: project.libraries.compile[0]?.repos ?? project.libraries.processors[0]?.repos,
    };
    try {
      const jars = await resolveProjectJars(project, (requirements) =>
        libraries.resolve(requirements, { onWarning: (message) => warnings.push(message) }),
      );
      const built = await compiler.compile(project.sources, {
        classpath: project.classpath,
        processors: project.processors,
        vendorJars: jars.compile,
        processorJars: jars.processors,
      });
      compiler.dispose();
      const errors = built.diagnostics.filter((d) => d.kind === 'ERROR');
      const result: Record<string, unknown> = {
        success: built.success,
        seconds: Math.round((performance.now() - started) / 1000),
        errors: errors.slice(0, 5).map((d) => `${d.file}:${d.line}: ${d.message}`),
        errorCount: errors.length,
        warnings,
        ...summary,
      };
      if (built.jar && simulateMs > 0) {
        const messages: BridgeMessage[] = [];
        const robot = await RobotSession.start({
          assets,
          jar: built.jar,
          classpath: project.classpath,
          vendorJars: jars.runtime,
          mainClass: project.mainClass,
          onMessages: (batch) => messages.push(...batch),
          onOutput: (text) => messages.push({ type: 'console', device: '', data: { text: `[cheerpj] ${text}\n` } }),
        });
        await wait(simulateMs);
        await robot.stop();
        const output = messages.filter((m) => m.type === 'console').map((m) => String(m.data.text)).join('');
        result.running = messages.length > 0;
        result.output = output.split('\n').slice(0, 25);
      }
      return result;
    } catch (error) {
      compiler.dispose();
      return { success: false, resolveError: error instanceof Error ? error.message : String(error), warnings, ...summary };
    }
  },

  // A robot whose Phoenix 6 TalonFX closed loop and swerve drivetrain move in simulation.
  async simulatePhoenixMotion() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const index = (await (await fetch(assets.url('vendor/index.json'))).json()) as { vendordeps: Array<{ file: string; name: string }> };
    const files = new Map<string, Uint8Array>();
    files.set('build.gradle', new TextEncoder().encode('plugins { id "edu.wpi.first.GradleRIO" version "2026.2.1" }\ndef ROBOT_MAIN_CLASS = "frc.robot.Main"\n'));
    for (const dep of index.vendordeps.filter((v) => v.name === 'CTRE-Phoenix (v6)')) {
      files.set(`vendordeps/${dep.file.split('/').pop()}`, new Uint8Array(await (await fetch(assets.url(dep.file))).arrayBuffer()));
    }
    files.set('src/main/java/frc/robot/Robot.java', new TextEncoder().encode(PHOENIX_ROBOT));
    files.set('src/main/java/frc/robot/Main.java', new TextEncoder().encode(VENDOR_MAIN));
    const project = await loadRobotProject(
      {
        read: async (path) => files.get(path),
        list: async (dir) => [...files.keys()].filter((path) => path.startsWith(dir)),
      },
      assets,
      'phoenix',
    );
    const compiler = new JavaCompiler(assets);
    const vendorJars = await new VendorLibraries({
      assets,
      transform: (jar, jarName) => compiler.transformVendorJar(jar, jarName),
      proxy: () => 'https://frc-proxy.project516.dev',
    }).resolve(project.vendor);
    const built = await compiler.compile(project.sources, { classpath: project.classpath, processors: project.processors, vendorJars });
    compiler.dispose();
    if (!built.jar) throw new Error(`build failed: ${JSON.stringify(built.diagnostics.slice(0, 5))}`);

    const messages: BridgeMessage[] = [];
    const robot = await RobotSession.start({
      assets,
      jar: built.jar,
      classpath: project.classpath,
      vendorJars,
      mainClass: project.mainClass,
      onMessages: (batch) => messages.push(...batch),
    });
    await wait(10000);
    robot.send({ type: 'DriverStation', device: '', data: { '>enabled': true, '>autonomous': false, '>new_data': true } });
    await wait(10000);
    await robot.stop();
    const consoleText = messages.filter((m) => m.type === 'console').map((m) => m.data.text).join('');
    const samples = consoleText
      .split('\n')
      .filter((l) => l.startsWith('PHOENIX'))
      .map((l) => /position=(\S+) x=(\S+)/.exec(l))
      .filter((m): m is RegExpExecArray => m !== null)
      .map((m) => ({ position: Number(m[1]), x: Number(m[2]) }));
    const last = samples.at(-1);
    if (!last) throw new Error(`no PHOENIX output in:\n${consoleText.slice(0, 3000)}`);
    if (Math.abs(last.position - 2) > 0.1) throw new Error(`TalonFX did not reach its position target: ${JSON.stringify(last)}\n${consoleText.slice(0, 3000)}`);
    if (last.x < 0.3) throw new Error(`swerve robot did not move: ${JSON.stringify(last)}\n${consoleText.slice(0, 3000)}`);
    return { samples: samples.length, last };
  },

  // A robot whose loop always overruns must still let its other threads run, as DogLog's writer does.
  async simulateOverrun() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const compiler = new JavaCompiler(assets);
    const built = await compiler.compile({ 'frc/robot/Robot.java': OVERRUN_ROBOT, 'frc/robot/Main.java': VENDOR_MAIN });
    compiler.dispose();
    if (!built.jar) throw new Error(`build failed: ${JSON.stringify(built.diagnostics.slice(0, 5))}`);

    const messages: BridgeMessage[] = [];
    const robot = await RobotSession.start({ assets, jar: built.jar, onMessages: (batch) => messages.push(...batch) });
    await wait(15000);
    await robot.stop();
    const consoleText = messages.filter((m) => m.type === 'console').map((m) => m.data.text).join('');
    const last = consoleText.split('\n').filter((l) => l.startsWith('OVERRUN')).at(-1);
    const m = last && /produced=(\d+) consumed=(\d+) dropped=(\d+)/.exec(last);
    if (!m) throw new Error(`no OVERRUN output in:\n${consoleText.slice(0, 3000)}`);
    const [produced, consumed, dropped] = m.slice(1).map(Number);
    if (dropped > 0 || consumed < produced - 1000) throw new Error(`the consumer thread starved: ${last}`);
    return { produced, consumed, dropped };
  },

  // DogLog publishing to NetworkTables with Spectrum's logging load. The loop must stay near its period
  // and the log queue must never fill, which printed [DogLog] MAX_QUEUED_LOGS errors and slowed the loop
  // to 0.2 s when string concatenation compiled to invokedynamic.
  async simulateDogLog() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const index = (await (await fetch(assets.url('vendor/index.json'))).json()) as { vendordeps: Array<{ file: string; name: string }> };
    const files = new Map<string, Uint8Array>();
    files.set('build.gradle', new TextEncoder().encode('plugins { id "edu.wpi.first.GradleRIO" version "2026.2.1" }\ndef ROBOT_MAIN_CLASS = "frc.robot.Main"\n'));
    for (const dep of index.vendordeps.filter((v) => v.name === 'DogLog')) {
      files.set(`vendordeps/${dep.file.split('/').pop()}`, new Uint8Array(await (await fetch(assets.url(dep.file))).arrayBuffer()));
    }
    files.set('src/main/java/frc/robot/Robot.java', new TextEncoder().encode(DOGLOG_ROBOT));
    files.set('src/main/java/frc/robot/Main.java', new TextEncoder().encode(VENDOR_MAIN));
    const project = await loadRobotProject(
      { read: async (path) => files.get(path), list: async (dir) => [...files.keys()].filter((path) => path.startsWith(dir)) },
      assets,
      'doglog',
    );
    const compiler = new JavaCompiler(assets);
    const vendorJars = await new VendorLibraries({
      assets,
      transform: (jar, jarName) => compiler.transformVendorJar(jar, jarName),
      proxy: () => 'https://frc-proxy.project516.dev',
    }).resolve(project.vendor);
    const built = await compiler.compile(project.sources, { classpath: project.classpath, processors: project.processors, vendorJars });
    compiler.dispose();
    if (!built.jar) throw new Error(`build failed: ${JSON.stringify(built.diagnostics.slice(0, 5))}`);
    const indyConcat = Object.entries(unzipSync(built.jar)).filter(([name, data]) => name.endsWith('.class') && decoder.decode(data).includes('makeConcatWithConstants'));
    if (indyConcat.length > 0) throw new Error(`the compiler used invokedynamic string concatenation in ${indyConcat.map(([name]) => name).join(', ')}`);

    const messages: BridgeMessage[] = [];
    const robot = await RobotSession.start({
      assets,
      jar: built.jar,
      classpath: project.classpath,
      vendorJars,
      mainClass: project.mainClass,
      onMessages: (batch) => messages.push(...batch),
    });
    await wait(30000);
    await robot.stop();
    const lines = messages.filter((m) => m.type === 'console').map((m) => m.data.text).join('').split('\n');
    const reports = lines.flatMap((l) => {
      const m = /^DOGLOG loops=(\d+) periodMs=([\d.]+) minRoom=(\d+)/.exec(l);
      return m ? [{ loops: Number(m[1]), periodMs: Number(m[2]), minRoom: Number(m[3]) }] : [];
    });
    const settled = reports.filter((r) => r.loops > 100);
    if (settled.length < 5) throw new Error(`too few loops reported: ${JSON.stringify(reports)}\n${lines.slice(0, 20).join('\n')}`);
    const periods = settled.map((r) => r.periodMs).sort((a, b) => a - b);
    const medianMs = periods[Math.floor(periods.length / 2)];
    const queueFull = lines.filter((l) => l.includes('MAX_QUEUED_LOGS')).length;
    // 20 ms asked; 25 ms measured on a slow computer. Concatenation through invokedynamic gave 56 ms.
    if (medianMs > 40) throw new Error(`the loop takes ${medianMs} ms for a 20 ms period: ${JSON.stringify(settled)}`);
    if (queueFull > 0) throw new Error(`DogLog's queue filled ${queueFull} times: ${lines.filter((l) => l.includes('MAX_QUEUED_LOGS')).slice(0, 3).join(' | ')}`);
    return { loops: settled.at(-1)?.loops, medianMs, minRoom: Math.min(...settled.map((r) => r.minRoom)), queueFull };
  },

  async simulateTemplate() {
    const assets = await loadRuntimeAssets('2026', '/runtime/');
    const compiler = new JavaCompiler(assets);
    const built = await compiler.compile(await templateSources('commandbased'));
    compiler.dispose();
    if (!built.jar) return { built: false, diagnostics: built.diagnostics };

    const messages: BridgeMessage[] = [];
    const output: string[] = [];
    let exitCode: number | null = null;
    const started = performance.now();
    const robot = await RobotSession.start({
      assets,
      jar: built.jar,
      onMessages: (batch) => messages.push(...batch),
      onOutput: (text) => output.push(text),
      onExit: (code) => (exitCode = code),
    });
    await wait(8000);
    robot.send({ type: 'DriverStation', device: '', data: { '>enabled': true, '>autonomous': false, '>new_data': true } });
    await wait(4000);
    const stopCode = await robot.stop();
    const types: Record<string, number> = {};
    for (const m of messages) types[m.type] = (types[m.type] ?? 0) + 1;
    const consoleText = messages.filter((m) => m.type === 'console').map((m) => m.data.text).join('');
    return {
      built: true,
      startMillis: Math.round(performance.now() - started),
      types,
      console: consoleText.slice(0, 4000),
      cheerpjOutput: output.join('\n').slice(0, 2000),
      ntTopics: messages.filter((m) => m.type === 'nt.announce').map((m) => m.data.name).slice(0, 40),
      exitCode,
      stopCode,
    };
  },

  // `./gradlew test` on a project with JUnit 5 tests: resolves, compiles and runs them in a JVM of their own
  async junit(year: string, variant = '') {
    return junit(year, variant);
  },
};

// A project with the stock template's test lines, and tests that pass, fail, are skipped, print, call the HAL and nest.
const JUNIT_BUILD_GRADLE = (year: string, mockito: string, jupiter: string) => `plugins {
    id "java"
    id "${year === '2027' ? 'org.wpilib' : 'edu.wpi.first'}.GradleRIO" version "${year === '2027' ? '2027.0.0-alpha-7' : '2026.2.1'}"
}
def ROBOT_MAIN_CLASS = "frc.robot.Main"
dependencies {
    implementation wpi.java.deps.wpilib()
    testImplementation 'org.junit.jupiter:junit-jupiter:${jupiter}'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'${mockito ? `\n    testImplementation 'org.mockito:mockito-core:${mockito}'` : ''}
}
test {
    useJUnitPlatform()
    systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true'
}
`;

const JUNIT_MAIN = `package frc.robot;

public class Calculator {
  public static int add(int a, int b) {
    return a + b;
  }

  public int twice(int a) {
    return 2 * a;
  }
}
`;

const MOCKITO_TEST = `package frc.robot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MockTest {
  @Test
  void mocksAnInterface() {
    List<String> list = mock(List.class);
    when(list.size()).thenReturn(3);
    assertEquals(3, list.size());
  }

  @Test
  void mocksAClass() {
    Calculator calculator = mock(Calculator.class);
    when(calculator.twice(2)).thenReturn(5);
    assertEquals(5, calculator.twice(2));
    verify(calculator).twice(2);
  }
}
`;

const JUNIT_TESTS = (year: string) => `package frc.robot;

import static org.junit.jupiter.api.Assertions.*;

import ${year === '2027' ? 'org.wpilib.hardware.hal.HAL' : 'edu.wpi.first.hal.HAL'};
import org.junit.jupiter.api.*;

class CalculatorTest {
  @Test
  void adds() {
    assertEquals(4, Calculator.add(2, 2));
  }

  @Test
  void failsOnPurpose() {
    assertEquals(5, Calculator.add(2, 2), "two and two");
  }

  @Test
  @Disabled("not today")
  void skipped() {}

  @Test
  void prints() {
    System.out.println("hello from a test");
  }

  @Test
  void halInitializes() {
    ${year === '2027' ? 'HAL.initialize();' : 'assertTrue(HAL.initialize(500, 0));'}
    HAL.shutdown();
  }

  @Nested
  class Inner {
    @Test
    void nestedPasses() {}
  }
}
`;

async function junit(year: string, variant: string) {
  const assets = await loadRuntimeAssets(year, '/runtime/');
  const encoder = new TextEncoder();
  const files = new Map<string, Uint8Array>();
  const put = (path: string, text: string) => files.set(path, encoder.encode(text));
  const mockito = variant.startsWith('mockito-') ? variant.slice('mockito-'.length) : '';
  const jupiter = variant.startsWith('jupiter-') ? variant.slice('jupiter-'.length) : '5.10.1';
  put('build.gradle', JUNIT_BUILD_GRADLE(year, mockito, jupiter));
  if (mockito) put('src/test/java/frc/robot/MockTest.java', MOCKITO_TEST);
  put('src/main/java/frc/robot/Calculator.java', JUNIT_MAIN);
  put('src/test/java/frc/robot/CalculatorTest.java', JUNIT_TESTS(year));
  put('src/test/resources/junit-platform.properties', 'junit.jupiter.execution.parallel.enabled=false\n');
  const projectFiles = { read: async (path: string) => files.get(path), list: async (dir: string) => [...files.keys()].filter((path) => path.startsWith(dir)) };
  const project = await loadRobotProject(projectFiles, assets, 'junit');
  const compiler = new JavaCompiler(assets);
  const libraries = new VendorLibraries({ assets, transform: (jar, name) => compiler.transformVendorJar(jar, name), proxy: () => 'https://frc-proxy.project516.dev' });
  const timings: Record<string, number> = {};
  const lap = (name: string, since: number) => (timings[name] = Math.round(performance.now() - since));
  try {
    let t = performance.now();
    const jars = await resolveProjectJars(project, (requirements) => libraries.resolve(requirements));
    const main = await compiler.compile(project.sources, { classpath: project.classpath, vendorJars: jars.compile });
    if (!main.jar) throw new Error(`main build failed: ${JSON.stringify(main.diagnostics.slice(0, 5))}`);
    lap('main', t);
    t = performance.now();
    const warnings: string[] = [...project.warnings];
    const resolver = { resolve: (requirements: Parameters<typeof libraries.resolve>[0]) => libraries.resolve(requirements), managedVersions: (bom: Parameters<typeof libraries.managedVersions>[0]) => libraries.managedVersions(bom) };
    const testJars = await resolveTestJars(project, project.test.framework, resolver, warnings);
    lap('testJars', t);
    t = performance.now();
    const sources = await loadTestSources(projectFiles);
    const tests = await compiler.compile(sources, { classes: main.jar, classpath: project.classpath, vendorJars: testJars.compile });
    if (!tests.jar) throw new Error(`test build failed: ${JSON.stringify(tests.diagnostics.slice(0, 5))}`);
    lap('compileTests', t);
    t = performance.now();
    const output: string[] = [];
    const run = await TestRun.start({
      assets,
      mainJar: main.jar,
      testJar: tests.jar,
      jars: testJars.runtime,
      classpath: project.classpath,
      classes: testClassNames(tests.jar),
      systemProperties: project.test.systemProperties,
      onOutput: (text) => {
        output.push(text);
        console.log(`junit output: ${text.trimEnd()}`);
      },
    });
    const timeout = new Promise<'timeout'>((resolve) => setTimeout(() => resolve('timeout'), 600_000));
    const code = await Promise.race([run.exit, timeout]);
    if (code === 'timeout') run.kill();
    lap('run', t);
    return { code, timings, warnings, jars: testJars.runtime.map((j) => j.name), output };
  } finally {
    compiler.dispose();
  }
}

(window as unknown as { scenarios: typeof scenarios }).scenarios = scenarios;
