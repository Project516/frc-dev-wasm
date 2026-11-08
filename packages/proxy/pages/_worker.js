// Runs in the Pages project behind CI's proxy address and hands every request to the proxy Worker
// through the service binding PROXY.
export default { fetch: (request, env) => env.PROXY.fetch(request) }
