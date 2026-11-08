# Proxy

A Cloudflare Worker for the requests a browser page cannot make on its own,
because the server sends no CORS headers:

- `/maven/<repository>/<artifact>`: downloads from the vendor library
  repositories in the WPILib marketplace (CTRE, REV, AdvantageKit, DogLog and
  others). Only GET and HEAD of artifact files (`.jar`, `.pom`, `.json`,
  checksums, `maven-metadata.xml`) under the repositories listed in
  `src/index.js` are allowed. Responses are cached at the edge for a week. Maven
  Central blocks Cloudflare's addresses, so its files come from Google's mirror of it.
- `/git/<host>/<repository>/<git endpoint>`: git clone, fetch and push, GitHub
  included. Only git's three smart HTTP endpoints on a public host name (no
  address, port or user name) are allowed, redirects are followed only to the
  same host, and only the headers git needs (including `authorization`) are
  forwarded, so the Worker sees the tokens of the users who push through it.
- `/github/login/device/code` and `/github/login/oauth/access_token`: the two
  POST requests of GitHub's device sign-in.

Requests must come from an address in `ALLOWED_ORIGINS` or from
`http://localhost`. Nothing is stored or logged by the Worker.

The project's instance runs at `https://frc-proxy.project516.dev`.

## Deploy your own

You need a Cloudflare account. The free Workers plan (100,000 requests a day)
is enough for a team.

1. In `wrangler.jsonc`, set `ALLOWED_ORIGINS` to the address your IDE is served
   from, for example `https://example.github.io`. Separate several with commas.
2. In `wrangler.jsonc`, change `routes` to a hostname in a zone on your
   account, or remove `routes` and set `"workers_dev": true` to use
   `https://frc-dev-wasm-proxy.<your subdomain>.workers.dev`.
3. From `packages/proxy`, run `pnpm dlx wrangler@4 login`, then
   `pnpm run deploy`.
4. Check it:
   `curl -H 'Origin: <your IDE address>' https://<your proxy>/maven/maven.revrobotics.com/com/revrobotics/frc/REVLib-java/2026.0.5/REVLib-java-2026.0.5.pom`
   prints a pom file. Without the `Origin` header it answers 403.
5. In the IDE, set `git.corsProxy` to `https://<your proxy>/git` and
   `wpilib.vendorProxy` to `https://<your proxy>`.
6. The `Origin` check keeps other websites out, not scripts. Add a rate
   limiting rule for the proxy's hostname in the Cloudflare dashboard
   (Security, WAF, Rate limiting rules), so a script cannot use up the daily
   requests.

Without wrangler: create a Worker in the Cloudflare dashboard, paste
`src/index.js` as its code, add a plain text variable `ALLOWED_ORIGINS`, and
add a custom domain or enable its workers.dev route.

## Tests

`pnpm --filter @frc-dev-wasm/proxy test` runs the Worker under Node with a
stubbed upstream. After `pnpm fetch-deps` it also checks that every repository
in the vendor marketplace is in `MAVEN_REPOSITORIES`; add new ones there and
redeploy.
