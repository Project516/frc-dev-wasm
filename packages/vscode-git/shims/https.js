// Octokit sends requests with fetch, which takes no agent
export class Agent {}
export const globalAgent = undefined
export default { Agent, globalAgent }
