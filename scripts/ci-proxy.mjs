// Loaded into the CI test processes with `node --import`. Every browser the tests start sends its requests
// for the proxy to the same Worker at CI's own address, CI_PROXY_URL (see packages/proxy/README.md). Routes a
// test sets itself, such as mocked sign-in, are added later and take precedence.
import { createRequire } from 'node:module'

const target = process.env.CI_PROXY_URL?.replace(/\/$/, '')
if (target) {
  const require = createRequire(new URL('../apps/ide/package.json', import.meta.url))
  const { chromium } = require('playwright-core')
  const PROXY = 'https://frc-proxy.project516.dev'
  // The browser fetches the rewritten URL itself, so large git packs never pass through this process
  // A failed reroute fails the request, so the test sees an error instead of waiting
  const reroute = (route) =>
    route.continue({ url: target + route.request().url().slice(PROXY.length) }).catch((error) => {
      console.error(`ci-proxy: ${error.message}`)
      return route.abort('failed').catch((abortError) => console.error(`ci-proxy: abort: ${abortError.message}`))
    })
  const withRoute = async (context) => {
    await context.route(`${PROXY}/**`, reroute)
    return context
  }

  const launch = chromium.launch.bind(chromium)
  chromium.launch = async (...args) => {
    const browser = await launch(...args)
    const newContext = browser.newContext.bind(browser)
    browser.newContext = async (...options) => withRoute(await newContext(...options))
    return browser
  }
  const launchPersistent = chromium.launchPersistentContext.bind(chromium)
  chromium.launchPersistentContext = async (...args) => withRoute(await launchPersistent(...args))
}
