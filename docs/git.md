# Git remotes

The IDE runs git in the page. A browser page can only reach a server that
allows cross-origin requests (CORS), and that decides which remotes work.

## GitHub

GitHub works with no setup. GitHub sends no CORS headers for git requests, so
for projects in the browser git reaches it through a proxy: the
`git.corsProxy` setting, or the git route of `wpilib.vendorProxy` when that is
empty, which is this project's own proxy by default. Every request, including
your token, passes through it. To keep the token between you and GitHub, run
your own proxy (see below) and set both settings.

Private repositories and pushes need a token. When a request needs one, the
IDE signs you in with GitHub's device sign-in, or asks for a personal access
token when the proxy has no sign-in route, and keeps it in this browser's
storage for the site. For a personal access token, use a fine-grained token
limited to the repositories you work on, with read and write access to
contents. `Git: Sign Out of GitHub` removes it.

## Other hosts

GitLab, Bitbucket, self-hosted servers and the rest do not send CORS headers
for git requests either, so the page goes through a CORS proxy for them too.
Set it in the `git.corsProxy` setting. It is empty by default, and the first
clone, pull or push to another host asks whether to use one of the proxies
below or set your own.

Every request to the remote, including your username and password or token,
passes through the proxy. Use one you trust.

- `https://frc-proxy.project516.dev/git` is this project's proxy, a
  Cloudflare Worker that only forwards git requests and stores nothing. Its
  code is in `packages/proxy`.
- `https://cors.isomorphic-git.org` is a free public proxy run by the
  isomorphic-git project. It only forwards git requests. It is shared and
  rate limited, and you are trusting its operator with your credentials.
- Your own proxy keeps credentials between you and the remote. The Worker in
  `packages/proxy` is free to run on Cloudflare; its README has the steps.
  Set `wpilib.vendorProxy` to `https://<your proxy>`; git then uses its `/git` route.
  `git.corsProxy` overrides that for git alone.

The IDE turns a remote URL such as `https://gitlab.com/team/robot.git` into
`<proxy>/gitlab.com/team/robot.git/...`, which is the path the proxy reads
the destination from.
