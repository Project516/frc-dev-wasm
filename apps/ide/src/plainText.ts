// Notifications turn `[text](command:...)` into a link that runs a command. Text from a link, a project or a
// remote must not form one.
export const plainText = (text: string): string => text.replace(/\]\s*\(/g, '] (')
