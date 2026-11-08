// Loaded into the CI test processes with `node --import`. Cloudflare's Bot Fight Mode answers requests from
// datacenter addresses, such as CI runners, to the proxy's hostname with a challenge page, which a browser
// fetch reports as a network error. Every browser the tests start sends those requests to the same Worker
// at the address in CI_PROXY_URL (https://frcdev-proxy.pages.dev, outside that zone). Routes a test sets
// itself, such as mocked sign-in, are added later and take precedence.
import { createRequire } from 'node:module'

const target = process.env.CI_PROXY_URL?.replace(/\/$/, '')
if (target) {
  const require = createRequire(new URL('../apps/ide/package.json', import.meta.url))
  const { chromium } = require('playwright-core')
  const PROXY = 'https://frc-proxy.project516.dev'
  const reroute = async (route) => {
    try {
      const response = await route.fetch({ url: target + route.request().url().slice(PROXY.length), maxRedirects: 0 })
      await route.fulfill({ response })
    } catch {
      await route.abort('failed').catch(() => {})
    }
  }
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
