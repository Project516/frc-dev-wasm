// The Pages project frcdev-proxy (https://frcdev-proxy.pages.dev) runs this file. It hands every request to the
// proxy Worker through the service binding PROXY. pages.dev is outside project516.dev, whose Bot Fight Mode
// challenges datacenter addresses such as CI runners and some school and office networks.
export default { fetch: (request, env) => env.PROXY.fetch(request) }
