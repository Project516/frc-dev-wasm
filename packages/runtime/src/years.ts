/** WPILib seasons the site serves, oldest first. Each has its own `runtime/<year>/` folder. */
export const WPILIB_YEARS: readonly string[] = ['2026', '2027'];

/** Seasons on java-web (Java 25), served from `runtime/java-web/`. The others run on CheerpJ. */
export const JAVA_WEB_YEARS: readonly string[] = ['2027'];

/** The season for a new project, and for anything that runs without a robot project open. */
export const DEFAULT_YEAR = '2026';

/** Maven group prefix of WPILib's own libraries, by season. A dependency in it is never downloaded. */
export const WPILIB_GROUP: Record<string, string> = { '2026': 'edu.wpi.first', '2027': 'org.wpilib' };
