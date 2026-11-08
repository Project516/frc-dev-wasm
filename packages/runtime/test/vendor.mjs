// Vendor jar downloads under Node, with a fake network, store and transform. Usage: node test/vendor.mjs
import assert from 'node:assert/strict';
import test from 'node:test';
import { zipSync } from 'fflate';
import { mayNeedTransform } from '../src/classfile.ts';
import { pomDependencies } from '../src/pom.ts';
import { VendorDownloadError, VendorLibraries, compareVersions, coordinate, isSafeCoordinate, memoryJarStore, resilientJarStore, vendorJarPath } from '../src/vendor.ts';

const INDEX = {
  vendordeps: [],
  processors: {},
  bundled: {},
  transitive: { 'com.acme:lib:1.0': ['com.acme:base:2.0'], 'com.acme:base:2.0': [] },
};
const realFetch = globalThis.fetch;
globalThis.fetch = async (url) => {
  assert.match(String(url), /^http:\/\/test\/runtime\/(2026|2027)\/vendor\/index\.json$/);
  return Response.json(INDEX);
};
test.after(() => (globalThis.fetch = realFetch));

const assets = { year: '2026', provided: new Set(['edu.wpi.first.wpiutil:wpiutil-java']), url: (file) => new URL(file, 'http://test/runtime/2026/') };
const repo = 'https://maven.acme.test/release';
const HOST = 'maven.acme.test';
const req = (artifact, version, group = 'com.acme', repos = [repo]) => ({ group, artifact, version, repos });
// What a downloaded jar starts with
const jarCalls = (net) => net.calls.filter((url) => url.endsWith('.jar')).length;
const jar = (body) => `PK\u0003\u0004${body}`;

// Answers GETs from `files` (URL to text, or a function returning a Response). Hosts in `blocked` fail like a browser does without CORS headers.
function network(files, blocked = []) {
  const calls = [];
  const fetcher = async (url) => {
    calls.push(String(url));
    if (blocked.includes(new URL(url).host)) throw new TypeError('Failed to fetch');
    const body = files[String(url)];
    if (typeof body === 'function') return body();
    return body === undefined ? new Response('', { status: 404 }) : new Response(body);
  };
  return { fetcher, calls };
}

function libraries(net, extra = {}) {
  const transformed = [];
  const store = extra.store ?? memoryJarStore();
  const lib = new VendorLibraries({
    assets: extra.assets ?? assets,
    store,
    fetch: net.fetcher,
    transform: async (data, name) => {
      transformed.push(name);
      return new TextEncoder().encode(`transformed ${new TextDecoder().decode(data)}`);
    },
    proxy: () => extra.proxy,
  });
  return { lib, store, transformed };
}

const text = async (j) => new TextDecoder().decode(await j.data());
const bodyOf = async (j) => (await text(j)).replace('transformed PK\u0003\u0004', '');

test('managedVersions follows nested BOM imports and substitutes their versions', async () => {
  const net = network({
    [`${repo}/com/acme/bom/1/bom-1.pom`]: `<project>
      <properties><inner.version>2</inner.version></properties>
      <dependencyManagement><dependencies><dependency>
        <groupId>com.acme</groupId><artifactId>inner</artifactId><version>\${inner.version}</version>
        <type>pom</type><scope>import</scope>
      </dependency></dependencies></dependencyManagement></project>`,
    [`${repo}/com/acme/inner/2/inner-2.pom`]: `<project><dependencyManagement><dependencies><dependency>
      <groupId>com.acme</groupId><artifactId>leaf</artifactId><version>3</version><type>pom</type><scope>import</scope>
      </dependency></dependencies></dependencyManagement></project>`,
    [`${repo}/com/acme/leaf/3/leaf-3.pom`]: `<project><dependencyManagement><dependencies><dependency>
      <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.2</version>
      </dependency></dependencies></dependencyManagement></project>`,
  });
  const managed = await libraries(net).lib.managedVersions(req('bom', '1'));
  assert.deepEqual(Object.fromEntries(managed), { 'org.junit.jupiter:junit-jupiter': '5.10.2' });
  assert.equal(net.calls.length, 3);
});

test('managedVersions prefers direct entries and the first imported BOM', async () => {
  const net = network({
    [`${repo}/com/acme/bom/1/bom-1.pom`]: `<project><dependencyManagement><dependencies>
      <dependency><groupId>com.acme</groupId><artifactId>first</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>
      <dependency><groupId>com.acme</groupId><artifactId>second</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>
      <dependency><groupId>com.acme</groupId><artifactId>override</artifactId><version>9</version></dependency>
      </dependencies></dependencyManagement></project>`,
    ...Object.fromEntries(['first', 'second'].map((name, i) => [
      `${repo}/com/acme/${name}/1/${name}-1.pom`,
      `<project><dependencyManagement><dependencies>
        <dependency><groupId>com.acme</groupId><artifactId>shared</artifactId><version>${i + 1}</version></dependency>
        <dependency><groupId>com.acme</groupId><artifactId>override</artifactId><version>${i + 1}</version></dependency>
        </dependencies></dependencyManagement></project>`,
    ])),
  });
  const managed = await libraries(net).lib.managedVersions(req('bom', '1'));
  assert.equal(managed.get('com.acme:shared'), '1');
  assert.equal(managed.get('com.acme:override'), '9');
});

test('managedVersions rejects cyclic BOM imports', async () => {
  const net = network(Object.fromEntries(['first', 'second'].map((name, i) => [
    `${repo}/com/acme/${name}/1/${name}-1.pom`,
    `<project><dependencyManagement><dependencies><dependency>
      <groupId>com.acme</groupId><artifactId>${i === 0 ? 'second' : 'first'}</artifactId><version>1</version><type>pom</type><scope>import</scope>
      </dependency></dependencies></dependencyManagement></project>`,
  ])));
  await assert.rejects(libraries(net).lib.managedVersions(req('first', '1')), /BOM import cycle/);
});

test('managedVersions inherits imported BOMs and their version properties from a parent', async () => {
  const net = network({
    [`${repo}/com/acme/bom/1/bom-1.pom`]: '<project><parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version></parent></project>',
    [`${repo}/com/acme/parent/1/parent-1.pom`]: `<project><properties><inner.version>2</inner.version></properties>
      <dependencyManagement><dependencies><dependency>
        <groupId>com.acme</groupId><artifactId>inner</artifactId><version>\${inner.version}</version><type>pom</type><scope>import</scope>
        </dependency></dependencies></dependencyManagement></project>`,
    [`${repo}/com/acme/inner/2/inner-2.pom`]: `<project><dependencyManagement><dependencies><dependency>
      <groupId>com.acme</groupId><artifactId>dep</artifactId><version>3</version>
      </dependency></dependencies></dependencyManagement></project>`,
  });
  assert.deepEqual(Object.fromEntries(await libraries(net).lib.managedVersions(req('bom', '1'))), { 'com.acme:dep': '3' });
});

test('managedVersions validates imported coordinates before using them in a URL', async () => {
  const net = network({
    [`${repo}/com/acme/bom/1/bom-1.pom`]: `<project><dependencyManagement><dependencies><dependency>
      <groupId>com.acme</groupId><artifactId>../invalid</artifactId><version>1</version><type>pom</type><scope>import</scope>
      </dependency></dependencies></dependencyManagement></project>`,
  });
  await assert.rejects(libraries(net).lib.managedVersions(req('bom', '1')), /not a valid maven coordinate/);
  assert.equal(net.calls.length, 1);
});

test('managedVersions keeps parent BOM imports scoped to the parent properties', async () => {
  const net = network({
    [`${repo}/com/acme/bom/1/bom-1.pom`]: `<project>
      <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version></parent>
      <properties><inner.version>99</inner.version></properties></project>`,
    [`${repo}/com/acme/parent/1/parent-1.pom`]: `<project><properties><inner.version>2</inner.version></properties>
      <dependencyManagement><dependencies><dependency>
        <groupId>com.acme</groupId><artifactId>inner</artifactId><version>\${inner.version}</version><type>pom</type><scope>import</scope>
        </dependency></dependencies></dependencyManagement></project>`,
    [`${repo}/com/acme/inner/2/inner-2.pom`]: `<project><dependencyManagement><dependencies><dependency>
      <groupId>com.acme</groupId><artifactId>dep</artifactId><version>3</version>
      </dependency></dependencies></dependencyManagement></project>`,
  });
  assert.deepEqual(Object.fromEntries(await libraries(net).lib.managedVersions(req('bom', '1'))), { 'com.acme:dep': '3' });
  assert.ok(!net.calls.some((url) => url.includes('/inner/99/')));
});

test('downloads a jar and its known dependency, transforms and caches them', async () => {
  const net = network({
    [`${repo}/com/acme/lib/1.0/lib-1.0.jar`]: jar('lib'),
    [`${repo}/com/acme/base/2.0/base-2.0.jar`]: jar('base'),
  });
  const { lib, store, transformed } = libraries(net);
  const progress = [];
  const jars = await lib.resolve([req('lib', '1.0')], { onProgress: (p) => progress.push(p) });
  assert.deepEqual(jars.map((j) => j.name), ['lib-1.0.jar', 'base-2.0.jar']);
  assert.equal(await bodyOf(jars[0]), 'lib');
  assert.deepEqual(transformed.sort(), ['base-2.0.jar', 'lib-1.0.jar']);
  assert.deepEqual((await store.info(`${HOST}|com.acme:lib:1.0`)).deps, ['com.acme:base:2.0']);
  assert.equal(progress.at(-1).done, 2);
  assert.equal(progress.at(-1).total, 2);
  assert.ok(progress.some((p) => p.downloaded));
  // The index knew the dependencies, so no pom was read
  assert.ok(net.calls.every((url) => url.endsWith('.jar')));

  const again = network({});
  const second = libraries(again, { store });
  const cached = await second.lib.resolve([req('lib', '1.0')], { onProgress: (p) => assert.equal(p.downloaded, false) });
  assert.equal(cached.length, 2);
  assert.deepEqual(again.calls, []);
  assert.deepEqual(second.transformed, []);
});

test('reads dependencies from the pom when the index does not know the jar', async () => {
  const pom = `<project><dependencies>
    <dependency><groupId>com.acme</groupId><artifactId>extra</artifactId><version>3.1</version></dependency>
    <dependency><groupId>edu.wpi.first.wpiutil</groupId><artifactId>wpiutil-java</artifactId><version>2026.2.1</version></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>test-only</artifactId><version>1</version><scope>test</scope></dependency>
  </dependencies></project>`;
  const net = network({
    [`${repo}/com/acme/other/5.0/other-5.0.jar`]: jar('other'),
    [`${repo}/com/acme/other/5.0/other-5.0.pom`]: pom,
    [`${repo}/com/acme/extra/3.1/extra-3.1.jar`]: jar('extra'),
  });
  const jars = await libraries(net).lib.resolve([req('other', '5.0')]);
  assert.deepEqual(jars.map((j) => j.name), ['other-5.0.jar', 'extra-3.1.jar']);
});

test('in 2027 the dependencies in the org.wpilib groups are left out, as edu.wpi.first ones are in 2026', async () => {
  const pom = `<project><dependencies>
    <dependency><groupId>com.acme</groupId><artifactId>extra</artifactId><version>3.1</version></dependency>
    <dependency><groupId>org.wpilib.commandsv2</groupId><artifactId>commandsv2-java</artifactId><version>2027.0.0-alpha-7</version></dependency>
    <dependency><groupId>org.wpilib</groupId><artifactId>annotations-java</artifactId><version>2027.0.0-alpha-7</version></dependency>
  </dependencies></project>`;
  const net = network({
    [`${repo}/com/acme/other/5.0/other-5.0.jar`]: jar('other'),
    [`${repo}/com/acme/other/5.0/other-5.0.pom`]: pom,
    [`${repo}/com/acme/extra/3.1/extra-3.1.jar`]: jar('extra'),
  });
  const assets2027 = { year: '2027', provided: new Set(), url: (file) => new URL(file, 'http://test/runtime/2027/') };
  const jars = await libraries(net, { assets: assets2027 }).lib.resolve([req('other', '5.0')]);
  assert.deepEqual(jars.map((j) => j.name), ['other-5.0.jar', 'extra-3.1.jar']);
  assert.ok(net.calls.every((url) => !url.includes('/org/wpilib/')));
});

test('a jar that no repository has a pom for has no dependencies', async () => {
  const net = network({ [`${repo}/com/acme/solo/1.0/solo-1.0.jar`]: jar('solo') });
  const jars = await libraries(net).lib.resolve([req('solo', '1.0')]);
  assert.deepEqual(jars.map((j) => j.name), ['solo-1.0.jar']);
});

test('a pom that cannot be read is an error and the jar is not cached', async () => {
  for (const failing of [() => new Response('', { status: 503 }), () => Promise.reject(new TypeError('Failed to fetch'))]) {
    const net = network({
      [`${repo}/com/acme/flaky/1.0/flaky-1.0.jar`]: jar('flaky'),
      [`${repo}/com/acme/flaky/1.0/flaky-1.0.pom`]: failing,
    });
    const { lib, store } = libraries(net);
    await assert.rejects(lib.resolve([req('flaky', '1.0')]), VendorDownloadError);
    assert.equal(await store.info(`${HOST}|com.acme:flaky:1.0`), undefined);
  }
  // Another repository without the pom does not make a failure at the first one a "not found"
  const mixed = network({
    [`${repo}/com/acme/flaky/1.0/flaky-1.0.jar`]: jar('flaky'),
    [`${repo}/com/acme/flaky/1.0/flaky-1.0.pom`]: () => new Response('', { status: 500 }),
  });
  const { lib, store } = libraries(mixed);
  await assert.rejects(lib.resolve([req('flaky', '1.0', 'com.acme', [repo, 'https://second.test'])]), VendorDownloadError);
  assert.equal(await store.info(`${HOST}|com.acme:flaky:1.0`), undefined);
});

test('warns about dependencies that are not plain jars or have no fixed version, and keeps going', async () => {
  const pom = `<project><properties><known>1.2</known></properties><dependencies>
    <dependency><groupId>com.acme</groupId><artifactId>ok</artifactId><version>\${known}</version></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>natives</artifactId><version>1</version><classifier>linuxx86-64</classifier></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>parent-managed</artifactId></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>unset</artifactId><version>\${missing}</version></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>range</artifactId><version>[1,2)</version></dependency>
    <dependency><groupId>com/evil</groupId><artifactId>x</artifactId><version>1</version></dependency>
  </dependencies></project>`;
  const net = network({
    [`${repo}/com/acme/top/1/top-1.jar`]: jar('top'),
    [`${repo}/com/acme/top/1/top-1.pom`]: pom,
    [`${repo}/com/acme/ok/1.2/ok-1.2.jar`]: jar('ok'),
  });
  const warnings = [];
  const jars = await libraries(net).lib.resolve([req('top', '1')], { onWarning: (w) => warnings.push(w) });
  assert.deepEqual(jars.map((j) => j.name), ['top-1.jar', 'ok-1.2.jar']);
  assert.equal(warnings.length, 4);
  assert.match(warnings.find((w) => w.includes('parent-managed')), /no fixed version.*parent pom/);
  assert.match(warnings.find((w) => w.includes('unset')), /no fixed version/);
  assert.match(warnings.find((w) => w.includes('range')), /no fixed version/);
  assert.match(warnings.find((w) => w.includes('com/evil')), /not a valid maven coordinate/);
  assert.ok(net.calls.every((url) => !url.includes('natives') && !url.includes('/bom/')));
});

test('a dependency that cannot be downloaded is a warning, a required jar is an error', async () => {
  const pom = '<project><dependencies><dependency><groupId>com.acme</groupId><artifactId>gone</artifactId><version>1</version></dependency></dependencies></project>';
  const net = network({
    [`${repo}/com/acme/top/1/top-1.jar`]: jar('top'),
    [`${repo}/com/acme/top/1/top-1.pom`]: pom,
  });
  const warnings = [];
  const jars = await libraries(net).lib.resolve([req('top', '1')], { onWarning: (w) => warnings.push(w) });
  assert.deepEqual(jars.map((j) => j.name), ['top-1.jar']);
  assert.match(warnings[0], /gone-1\.jar/);
  await assert.rejects(libraries(net).lib.resolve([req('gone', '1')]), VendorDownloadError);
});

test('rejects coordinates that could change a URL, a cache key or a file name', async () => {
  const net = network({});
  const { lib } = libraries(net);
  for (const bad of [req('a', '1', 'com/acme'), req('../a', '1'), req('a', '1:2'), req('a', '1 2'), req('a', ''), req('a', '1\n')]) {
    await assert.rejects(lib.resolve([bad]), /not a valid maven coordinate/);
    await assert.rejects(lib.importJars([bad], []), /not a valid maven coordinate/);
  }
  assert.deepEqual(net.calls, []);
  const ok = await libraries(network({ [`${repo}/com/acme/a-b_c/1.0+x-2/a-b_c-1.0+x-2.jar`]: jar('a') })).lib.resolve([req('a-b_c', '1.0+x-2')]);
  assert.equal(ok.length, 1);
});

test('a jar cached from one repository is not used for a project that does not list that repository', async () => {
  const evil = 'https://evil.test/maven';
  const store = memoryJarStore();
  const bad = libraries(network({ [`${evil}/com/acme/widget/1.0/widget-1.0.jar`]: jar('evil') }), { store });
  const [fake] = await bad.lib.resolve([req('widget', '1.0', 'com.acme', [evil])]);
  assert.equal(await bodyOf(fake), 'evil');
  const net = network({ [`${repo}/com/acme/widget/1.0/widget-1.0.jar`]: jar('real') });
  const [real] = await libraries(net, { store }).lib.resolve([req('widget', '1.0')]);
  assert.equal(await bodyOf(real), 'real');
  assert.notEqual(real.key, fake.key);
  assert.notEqual(vendorJarPath(real), vendorJarPath(fake));
  assert.ok(vendorJarPath(real).endsWith('~widget-1.0.jar'));
  assert.equal(jarCalls(net), 1);
});

test('a download that is not a zip is an error, is not cached and the next repository is tried', async () => {
  const html = network({ [`${repo}/com/acme/page/1/page-1.jar`]: '<html>Sign in</html>' });
  const { lib, store } = libraries(html);
  await assert.rejects(lib.resolve([req('page', '1')]), (error) => {
    assert.ok(error instanceof VendorDownloadError);
    assert.match(error.message, /did not answer with a jar file/);
    return true;
  });
  assert.equal(await store.info(`${HOST}|com.acme:page:1`), undefined);

  const other = 'https://repo.other.test';
  const next = network({
    [`${repo}/com/acme/page/1/page-1.jar`]: '<html>Sign in</html>',
    [`${other}/com/acme/page/1/page-1.jar`]: jar('real'),
  });
  const [real] = await libraries(next).lib.resolve([req('page', '1', 'com.acme', [repo, other])]);
  assert.equal(await bodyOf(real), 'real');
  await assert.rejects(libraries(network({})).lib.importJars([req('page', '1')], [{ name: 'page-1.jar', read: async () => new TextEncoder().encode('<html>') }]), /not a jar file/);
});

test('after the cache is cleared, the next resolve on the same instance downloads the jar again', async () => {
  const net = network({ [`${repo}/com/acme/solo/1.0/solo-1.0.jar`]: jar('solo') });
  let inner = memoryJarStore();
  const store = {
    info: (key) => inner.info(key),
    read: (key) => inner.read(key),
    write: (key, data, info) => inner.write(key, data, info),
    touch: (key, used) => inner.touch(key, used),
  };
  const { lib } = libraries(net, { store });
  const [first] = await lib.resolve([req('solo', '1.0')]);
  assert.equal(jarCalls(net), 1);
  inner = memoryJarStore();
  await assert.rejects(first.data(), /no longer in the vendor jar cache/);
  const [second] = await lib.resolve([req('solo', '1.0')]);
  assert.equal(jarCalls(net), 2);
  assert.equal(await bodyOf(second), 'solo');
});

test('keeps the classpath in requirement order and names each download, whichever finishes first', async () => {
  const slow = (body, ms) => () => new Promise((resolve) => setTimeout(() => resolve(new Response(body)), ms));
  const pom = (dep) => `<project><dependencies><dependency><groupId>com.acme</groupId><artifactId>${dep}</artifactId><version>1</version></dependency></dependencies></project>`;
  const net = network({
    [`${repo}/com/acme/a/1/a-1.jar`]: slow(jar('a'), 30),
    [`${repo}/com/acme/a/1/a-1.pom`]: pom('a-dep'),
    [`${repo}/com/acme/a-dep/1/a-dep-1.jar`]: slow(jar('ad'), 1),
    [`${repo}/com/acme/b/1/b-1.jar`]: slow(jar('b'), 1),
  });
  const downloaded = [];
  const jars = await libraries(net).lib.resolve([req('a', '1'), req('b', '1')], { onProgress: (p) => p.downloaded && downloaded.push(p.jar) });
  assert.deepEqual(jars.map((j) => j.name), ['a-1.jar', 'a-dep-1.jar', 'b-1.jar']);
  assert.deepEqual(downloaded.sort(), ['a-1.jar', 'a-dep-1.jar', 'b-1.jar']);
});

test('skips jars that are already on the WPILib classpath', async () => {
  const net = network({});
  const jars = await libraries(net).lib.resolve([req('wpiutil-java', '2026.2.1', 'edu.wpi.first.wpiutil')]);
  assert.deepEqual(jars, []);
  assert.deepEqual(net.calls, []);
});

test('falls back to the proxy when the browser blocks the request, then goes straight to it', async () => {
  const proxy = 'https://proxy.test/';
  const net = network({
    [`${proxy}maven/${HOST}/release/com/acme/one/1/one-1.jar`]: jar('one'),
    [`${proxy}maven/${HOST}/release/com/acme/two/1/two-1.jar`]: jar('two'),
  }, [HOST]);
  const { lib } = libraries(net, { proxy });
  await lib.resolve([req('one', '1')]);
  assert.ok(net.calls.filter((url) => url.startsWith(repo)).length >= 1);
  const before = net.calls.length;
  await lib.resolve([req('two', '1')]);
  assert.ok(net.calls.slice(before).every((url) => url.startsWith(`${proxy}maven/${HOST}/`)));
});

test('goes straight to the proxy after the first blocked request even when the proxy fails', async () => {
  const proxy = 'https://proxy.test';
  const net = network({ [`${proxy}/maven/${HOST}/release/com/acme/two/1/two-1.jar`]: jar('two') }, [HOST]);
  const { lib } = libraries(net, { proxy });
  await assert.rejects(lib.resolve([req('one', '1')]), VendorDownloadError);
  const before = net.calls.length;
  await lib.resolve([req('two', '1')]);
  assert.ok(net.calls.slice(before).every((url) => url.startsWith(`${proxy}/maven/`)));
});

test('says that the network is the problem when the proxy cannot be reached either', async () => {
  const net = network({}, [HOST, 'proxy.test']);
  await assert.rejects(libraries(net, { proxy: 'https://proxy.test' }).lib.resolve([req('one', '1')]), /neither the server nor the vendor proxy https:\/\/proxy\.test could be reached/);
});

test('tries the next repository after a 404', async () => {
  const other = 'https://repo.other.test';
  const net = network({ [`${other}/com/acme/lib2/1/lib2-1.jar`]: jar('lib2') });
  const jars = await libraries(net).lib.resolve([req('lib2', '1', 'com.acme', [repo, other])]);
  assert.equal(jars.length, 1);
  assert.ok(net.calls[0].startsWith(repo));
});

test('names the jar and URL when a download fails, and says when no proxy is set', async () => {
  const net = network({}, [HOST]);
  const { lib } = libraries(net);
  await assert.rejects(lib.resolve([req('gone', '9')]), (error) => {
    assert.ok(error instanceof VendorDownloadError);
    assert.equal(error.jar, 'gone-9.jar');
    assert.equal(error.url, `${repo}/com/acme/gone/9/gone-9.jar`);
    assert.match(error.message, /no vendor proxy is set/);
    return true;
  });
  // A failed download is retried by the next build
  const retry = network({ [`${repo}/com/acme/gone/9/gone-9.jar`]: jar('back') });
  const jars = await new VendorLibraries({ assets, store: memoryJarStore(), fetch: retry.fetcher, transform: async (d) => d }).resolve([req('gone', '9')]);
  assert.equal(jars.length, 1);
});

test('imports jars from disk by file name, with their dependencies, and resolves offline afterwards', async () => {
  const offline = network({}, [HOST]);
  const { lib, store, transformed } = libraries(offline);
  const disk = (name, body) => ({ name, read: async () => new TextEncoder().encode(jar(body)) });
  const result = await lib.importJars([req('lib', '1.0')], [disk('lib-1.0.jar', 'lib'), disk('unrelated-1.jar', 'x')]);
  assert.deepEqual(result.imported, ['lib-1.0.jar']);
  assert.deepEqual(result.missing, ['base-2.0.jar']);
  assert.deepEqual(transformed, ['lib-1.0.jar']);
  const more = await lib.importJars([req('lib', '1.0')], [disk('base-2.0.jar', 'base')]);
  assert.deepEqual(more, { imported: ['base-2.0.jar'], missing: [] });

  const jars = await libraries(offline, { store }).lib.resolve([req('lib', '1.0')]);
  assert.deepEqual(jars.map((j) => j.name), ['lib-1.0.jar', 'base-2.0.jar']);
  assert.equal(await bodyOf(jars[1]), 'base');
  assert.deepEqual(offline.calls, []);
});

test('builds with the jars in hand when the cache refuses to store them', async () => {
  const broken = {
    info: async () => {
      throw new Error('quota');
    },
    read: async () => {
      throw new Error('quota');
    },
    write: async () => {
      throw new Error('quota');
    },
    touch: async () => {
      throw new Error('quota');
    },
  };
  for (const primary of [broken, undefined]) {
    const net = network({ [`${repo}/com/acme/solo/1.0/solo-1.0.jar`]: jar('solo') });
    const { lib } = libraries(net, { store: resilientJarStore(primary) });
    const jars = await lib.resolve([req('solo', '1.0')]);
    assert.equal(await bodyOf(jars[0]), 'solo');
    await lib.resolve([req('solo', '1.0')]);
    assert.equal(jarCalls(net), 1);
  }
});

test('coordinates', () => {
  assert.equal(coordinate(req('a', '1')), 'com.acme:a:1');
});

test('pomDependencies resolves properties and managed versions and drops optional, test, classifier and non-jar dependencies', () => {
  const pom = `<project>
    <properties><gson.version>2.11.0</gson.version></properties>
    <dependencyManagement><dependencies>
      <dependency><groupId>org.managed</groupId><artifactId>m</artifactId><version>7</version></dependency>
    </dependencies></dependencyManagement>
    <dependencies>
      <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>\${gson.version}</version></dependency>
      <dependency><groupId>org.managed</groupId><artifactId>m</artifactId></dependency>
      <dependency><groupId>x</groupId><artifactId>opt</artifactId><version>1</version><optional>true</optional></dependency>
      <dependency><groupId>x</groupId><artifactId>t</artifactId><version>1</version><scope>test</scope></dependency>
      <dependency><groupId>x</groupId><artifactId>rt</artifactId><version>1</version><scope>runtime</scope></dependency>
      <dependency><groupId>x</groupId><artifactId>native</artifactId><version>1</version><classifier>linux</classifier></dependency>
      <dependency><groupId>x</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type></dependency>
      <dependency><groupId>x</groupId><artifactId>explicit</artifactId><version>1</version><type>jar</type></dependency>
    </dependencies></project>`;
  assert.deepEqual(pomDependencies(pom), [
    { group: 'com.google.code.gson', artifact: 'gson', version: '2.11.0' },
    { group: 'org.managed', artifact: 'm', version: '7' },
    { group: 'x', artifact: 'rt', version: '1' },
    { group: 'x', artifact: 'explicit', version: '1' },
  ]);
});

const pomOf = (...deps) =>
  `<project><dependencies>${deps
    .map((d) => {
      const [artifact, version] = d.split(':');
      return `<dependency><groupId>com.acme</groupId><artifactId>${artifact}</artifactId><version>${version}</version></dependency>`;
    })
    .join('')}</dependencies></project>`;

const plain = (artifact, version) => ({ ...req(artifact, version), plain: true });

test('the highest version of a library wins, as in Gradle', async () => {
  const files = {};
  const add = (artifact, version, ...deps) => {
    files[`${repo}/com/acme/${artifact}/${version}/${artifact}-${version}.jar`] = jar(`${artifact}-${version}`);
    files[`${repo}/com/acme/${artifact}/${version}/${artifact}-${version}.pom`] = pomOf(...deps);
  };
  add('a', '1', 'c:1', 'd:1');
  add('b', '1', 'e:2');
  add('c', '1');
  add('c', '1.10');
  add('d', '1', 'c:1.10', 'e:1');
  add('e', '1');
  add('e', '2');
  const net = network(files);
  const jars = await libraries(net).lib.resolve([plain('a', '1'), plain('b', '1')]);
  assert.deepEqual(jars.map((j) => j.name), ['a-1.jar', 'c-1.10.jar', 'd-1.jar', 'e-2.jar', 'b-1.jar']);
  const downloaded = net.calls.filter((url) => url.endsWith('.jar')).map((url) => url.split('/').pop());
  assert.deepEqual(downloaded.sort(), ['a-1.jar', 'b-1.jar', 'c-1.10.jar', 'c-1.jar', 'd-1.jar', 'e-2.jar']);
});

test('what only a replaced version needs is left out', async () => {
  const files = {};
  const add = (artifact, version, ...deps) => {
    files[`${repo}/com/acme/${artifact}/${version}/${artifact}-${version}.jar`] = jar(`${artifact}-${version}`);
    files[`${repo}/com/acme/${artifact}/${version}/${artifact}-${version}.pom`] = pomOf(...deps);
  };
  add('p', '1', 'x:1');
  add('q', '1', 'r:1');
  add('r', '1', 'x:2');
  add('x', '1', 'old:1');
  add('x', '2');
  add('old', '1');
  const net = network(files);
  const jars = await libraries(net).lib.resolve([plain('p', '1'), plain('q', '1'), plain('x', '1')]);
  assert.deepEqual(jars.map((j) => j.name), ['p-1.jar', 'x-2.jar', 'q-1.jar', 'r-1.jar']);
});

test('a vendordep keeps its version when a library asks for another', async () => {
  const net = network({
    [`${repo}/com/acme/lib/1/lib-1.jar`]: jar('lib'),
    [`${repo}/com/acme/lib/1/lib-1.pom`]: pomOf('base:9'),
    [`${repo}/com/acme/base/5/base-5.jar`]: jar('base5'),
    [`${repo}/com/acme/base/5/base-5.pom`]: pomOf(),
  });
  const jars = await libraries(net).lib.resolve([req('base', '5'), plain('lib', '1')]);
  assert.deepEqual(jars.map((j) => j.name), ['base-5.jar', 'lib-1.jar']);
});

test('compareVersions orders versions as Gradle does', () => {
  const ordered = ['1.0-dev', '1.0-alpha', '1.0-rc1', '1.0-SNAPSHOT', '1.0', '1.0.1', '1.1', '1.10', '2.0.0-beta', '2.0.0', '10'];
  for (let i = 0; i + 1 < ordered.length; i++) {
    assert.equal(compareVersions(ordered[i], ordered[i + 1]), -1, `${ordered[i]} < ${ordered[i + 1]}`);
    assert.equal(compareVersions(ordered[i + 1], ordered[i]), 1, `${ordered[i + 1]} > ${ordered[i]}`);
  }
  assert.equal(compareVersions('2.11.0', '2.11.0'), 0);
});

// A class file with one method and the given names in its constant pool
function classFile({ native = false, names = [] } = {}) {
  const bytes = [];
  const u2 = (n) => bytes.push(n >> 8, n & 255);
  const strings = ['Foo', 'run', '()V', ...names];
  bytes.push(0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 61);
  u2(strings.length + 4);
  for (const text of strings) {
    bytes.push(1);
    u2(text.length);
    bytes.push(...new TextEncoder().encode(text));
  }
  bytes.push(5, 0, 0, 0, 0, 0, 0, 0, 1);
  bytes.push(7);
  u2(1);
  u2(0x21);
  u2(strings.length + 3);
  u2(0);
  u2(0);
  u2(0);
  u2(1);
  u2(native ? 0x0109 : 0x0001);
  u2(2);
  u2(3);
  u2(1);
  u2(1);
  bytes.push(0, 0, 0, 2, 0, 0);
  u2(0);
  return Uint8Array.from(bytes);
}
const zipOf = (cls) => zipSync({ 'a/Foo.class': cls, 'META-INF/MANIFEST.MF': new TextEncoder().encode('Manifest-Version: 1.0\n') });

test('finds the jars that the native transform could change', () => {
  assert.equal(mayNeedTransform(zipOf(classFile())), false);
  assert.equal(mayNeedTransform(zipOf(classFile({ native: true }))), true);
  assert.equal(mayNeedTransform(zipOf(classFile({ names: ['edu/wpi/first/util/RuntimeLoader'] }))), true);
  assert.equal(mayNeedTransform(zipOf(classFile({ names: ['java/net/http/HttpClient'] }))), true);
  assert.equal(mayNeedTransform(zipOf(classFile({ names: ['java/lang/foreign/Linker'] }))), true);
  assert.equal(mayNeedTransform(zipOf(classFile({ names: ['loadLibrary'] }))), true);
  assert.equal(mayNeedTransform(zipOf(classFile({ names: ['java/lang/String'] }))), false);
  assert.equal(mayNeedTransform(new TextEncoder().encode('PK\u0003\u0004 not a zip')), true);
});

test('plain libraries are only transformed when they have native methods', async () => {
  const pure = zipOf(classFile());
  const natives = zipOf(classFile({ native: true }));
  const files = {
    [`${repo}/com/acme/pure/1/pure-1.jar`]: pure,
    [`${repo}/com/acme/pure/1/pure-1.pom`]: pomOf('nat:1'),
    [`${repo}/com/acme/nat/1/nat-1.jar`]: natives,
    [`${repo}/com/acme/nat/1/nat-1.pom`]: pomOf(),
    [`${repo}/com/acme/vend/1/vend-1.jar`]: pure,
    [`${repo}/com/acme/vend/1/vend-1.pom`]: pomOf(),
  };
  const { lib, transformed } = libraries(network(files));
  const jars = await lib.resolve([{ ...req('pure', '1'), plain: true }, req('vend', '1')]);
  assert.deepEqual(jars.map((j) => j.name), ['pure-1.jar', 'nat-1.jar', 'vend-1.jar']);
  // The dependency of a plain library is plain too. A vendordep jar is always transformed.
  assert.deepEqual(transformed.sort(), ['nat-1.jar', 'vend-1.jar']);
  assert.deepEqual(await jars[0].data(), pure);
});

test('pomDependencies resolves ${project.version} and the parent version', () => {
  const pom = `<project>
    <parent><groupId>org.parent</groupId><artifactId>p</artifactId><version>3.1</version></parent>
    <artifactId>child</artifactId>
    <dependencies>
      <dependency><groupId>org.parent</groupId><artifactId>core</artifactId><version>\${project.version}</version></dependency>
      <dependency><groupId>\${project.groupId}</groupId><artifactId>other</artifactId><version>\${project.parent.version}</version></dependency>
    </dependencies></project>`;
  assert.deepEqual(pomDependencies(pom), [
    { group: 'org.parent', artifact: 'core', version: '3.1' },
    { group: 'org.parent', artifact: 'other', version: '3.1' },
  ]);
  const own =
    '<project><groupId>g</groupId><version>2.0</version><dependencies><dependency><groupId>g</groupId><artifactId>x</artifactId><version>${version}</version></dependency></dependencies></project>';
  assert.equal(pomDependencies(own)[0].version, '2.0');
});

test('pomDependencies takes versions and properties from parent poms and ignores comments', () => {
  const parent = `<project>
    <properties><dep.version>4.2</dep.version></properties>
    <dependencyManagement><dependencies>
      <dependency><groupId>org.managed</groupId><artifactId>m</artifactId><version>7</version></dependency>
    </dependencies></dependencyManagement></project>`;
  const child = `<project>
    <parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>
    <dependencies>
      <!-- not <profiles> but a comment -->
      <dependency><groupId>org.managed</groupId><artifactId>m</artifactId></dependency>
      <dependency><groupId>x</groupId><artifactId>y</artifactId><version>\${dep.version}</version></dependency>
    </dependencies>
    <profiles><profile><dependencies><dependency><groupId>p</groupId><artifactId>p</artifactId><version>1</version></dependency></dependencies></profile></profiles>
    </project>`;
  assert.deepEqual(pomDependencies(child, [parent]), [
    { group: 'org.managed', artifact: 'm', version: '7' },
    { group: 'x', artifact: 'y', version: '4.2' },
  ]);
  assert.deepEqual(pomDependencies(child).map((d) => d.version), ['', '${dep.version}']);
});

test('pomDependencies follows a property that names another, as jackson-databind does', () => {
  const databind = `<project>
    <parent><groupId>com.fasterxml.jackson</groupId><artifactId>jackson-base</artifactId><version>2.17.2</version></parent>
    <dependencies>
      <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-annotations</artifactId><version>\${jackson.version.annotations}</version></dependency>
      <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-core</artifactId><version>\${jackson.version.core}</version></dependency>
    </dependencies></project>`;
  const base = `<project><parent><groupId>com.fasterxml.jackson</groupId><artifactId>jackson-bom</artifactId><version>2.17.2</version></parent>
    <properties><jackson.version.annotations>\${jackson.version}</jackson.version.annotations><jackson.version.core>\${jackson.version}</jackson.version.core></properties></project>`;
  const bom = `<project><properties><jackson.version>2.17.2</jackson.version></properties></project>`;
  assert.deepEqual(pomDependencies(databind, [base, bom]).map((d) => d.version), ['2.17.2', '2.17.2']);
});

test('reads the parent pom only when a dependency leaves its version to it', async () => {
  const net = network({
    [`${repo}/com/acme/kid/1/kid-1.jar`]: jar('kid'),
    [`${repo}/com/acme/kid/1/kid-1.pom`]: `<project><parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>5</version></parent>
      <dependencies><dependency><groupId>com.acme</groupId><artifactId>dep</artifactId></dependency></dependencies></project>`,
    [`${repo}/com/acme/parent/5/parent-5.pom`]: `<project><dependencyManagement><dependencies>
      <dependency><groupId>com.acme</groupId><artifactId>dep</artifactId><version>3</version></dependency></dependencies></dependencyManagement></project>`,
    [`${repo}/com/acme/dep/3/dep-3.jar`]: jar('dep'),
    [`${repo}/com/acme/dep/3/dep-3.pom`]: pomOf(),
    [`${repo}/com/acme/solo/1/solo-1.jar`]: jar('solo'),
    [`${repo}/com/acme/solo/1/solo-1.pom`]: `<project><parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>5</version></parent>${pomOf('dep:3').slice('<project>'.length)}`,
  });
  const warnings = [];
  const jars = await libraries(net).lib.resolve([req('kid', '1'), req('solo', '1')], { onWarning: (w) => warnings.push(w) });
  assert.deepEqual(jars.map((j) => j.name), ['kid-1.jar', 'dep-3.jar', 'solo-1.jar']);
  assert.deepEqual(warnings, []);
  assert.equal(net.calls.filter((url) => url.endsWith('parent-5.pom')).length, 1);
});

test('a coordinate part is never . or ..', () => {
  assert.ok(isSafeCoordinate({ group: 'com.ctre.phoenix6', artifact: 'wpiapi-java', version: '26.1.0' }));
  assert.ok(isSafeCoordinate({ group: 'a', artifact: '.hidden', version: '1..2' }));
  for (const part of ['.', '..']) {
    assert.ok(!isSafeCoordinate({ group: 'g', artifact: part, version: '1' }), `artifact ${part}`);
    assert.ok(!isSafeCoordinate({ group: 'g', artifact: 'a', version: part }), `version ${part}`);
    assert.ok(!isSafeCoordinate({ group: part, artifact: 'a', version: '1' }), `group ${part}`);
  }
});
