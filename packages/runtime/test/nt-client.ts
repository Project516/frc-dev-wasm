// A third-party NT4 client, used to check the in-page server.
import { NetworkTables, NetworkTablesTypeInfos } from 'ntcore-ts-client';

const nt = NetworkTables.getInstanceByURI('localhost');
const seen: Record<string, unknown> = {};
const report = window as unknown as { ntSeen: typeof seen; ntConnected: boolean };
report.ntSeen = seen;
const errors: string[] = [];
seen.errors = errors;
window.addEventListener('error', (e) => errors.push(String(e.error?.stack ?? e.message).slice(0, 400)));
window.addEventListener('unhandledrejection', (e) => errors.push(String(e.reason?.stack ?? e.reason).slice(0, 400)));
nt.addRobotConnectionListener((connected) => (report.ntConnected = connected), true);

nt.createTopic<number>('/SmartDashboard/speed', NetworkTablesTypeInfos.kDouble).subscribe((v) => (seen.speed = v));
nt.createTopic<string>('/SmartDashboard/mode', NetworkTablesTypeInfos.kString).subscribe((v) => (seen.mode = v));
nt.createTopic<number[]>('/Robot/pose', NetworkTablesTypeInfos.kDoubleArray).subscribe((v) => (seen.pose = v));

const target = nt.createTopic<number>('/Dashboard/target', NetworkTablesTypeInfos.kDouble);
target.publish().then(() => {
  seen.published = true;
  target.setValue(42);
}).catch((err) => (seen.error = String(err)));
