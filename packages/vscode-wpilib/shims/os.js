// The browser as a Linux machine. The WPILib home does not exist, as on a machine where the installer
// was not run.
export const homedir = () => '/home/frc'
export const tmpdir = () => '/tmp'
export const platform = () => 'linux'
export const type = () => 'Linux'
export const release = () => ''
export const arch = () => 'wasm32'
export const EOL = '\n'
export default { homedir, tmpdir, platform, type, release, arch, EOL }
