// DOM regression for the packaged Account page. Uses synthetic API responses.
// Run from the repository root: node src/backend/auth/test/account-page.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const frontendRequire = createRequire(path.resolve(__dirname, '../../../frontend/developer/package.json'));
const { JSDOM } = frontendRequire('jsdom');
const root = path.resolve(__dirname, '../src/main/resources/static/account');
const flush = () => new Promise((resolve) => setImmediate(resolve));

async function page(role, { demo = false, anonymous = false, logoutFails = false } = {}) {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), { url: 'http://localhost:18082/account/' });
  const user = { displayName: 'Sample User', username: 'sample.user', role, demoLocked: demo };
  const redirects = [];
  const calls = [];
  const fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url === '/api/auth/csrf') return Response.json({ token: 'synthetic-csrf' });
    if (url === '/api/auth/logout') return new Response(null, { status: logoutFails ? 503 : 204 });
    if (url === '/api/account/display-name') {
      user.displayName = JSON.parse(options.body).displayName;
      return new Response(null, { status: 204 });
    }
    if (url === '/api/account') return Response.json(anonymous ? { code: 'UNAUTHENTICATED' } : user, { status: anonymous ? 401 : 200 });
    throw new Error(`Unexpected request: ${url}`);
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'account.js'), 'utf8'), {
    document: dom.window.document, window: { location: { replace: (url) => redirects.push(url) } },
    fetch, TextEncoder, FormData: dom.window.FormData,
  });
  await flush();
  return { dom, user, redirects, calls, el: (id) => dom.window.document.getElementById(id) };
}

async function main() {
  for (const [role, dashboard] of [['MAINTAINER', '/'], ['DEVELOPER', '/developer/'], ['REPORTER', '/reporter/']]) {
    const p = await page(role);
    assert.equal(p.dom.window.document.querySelectorAll('#identity').length, 1);
    assert.equal(p.el('identity').textContent, 'Sample User');
    assert.equal(p.el('username').textContent, 'sample.user');
    assert.equal(p.el('account-role').textContent.toUpperCase(), role);
    for (const id of ['workspace-account', 'workspace-navigation', 'workspace-role', 'account']) assert.equal(p.el(id).hidden, false, id);
    assert.equal(p.el('workspace').classList.contains('role-workspace--anonymous'), false);
    assert.equal(p.el('back').getAttribute('href'), dashboard);
    assert.equal(p.el('create-section').hidden, role !== 'MAINTAINER');
    assert.equal(p.el('display-name-section').hidden, false);
    assert.equal(p.el('password-section').hidden, false);
    p.el('display-name').value = 'Updated Name';
    p.el('display-name-form').dispatchEvent(new p.dom.window.Event('submit', { bubbles: true, cancelable: true }));
    await flush();
    assert.equal(p.el('identity').textContent, 'Updated Name');
    assert.equal(p.el('workspace-account').hidden, false);
    p.el('sign-out').click();
    await flush();
    assert.deepEqual(p.redirects, ['/']);
    assert.equal(p.el('account').hidden, true);
    assert.equal(p.el('workspace-account').hidden, true);
    const logout = p.calls.find((call) => call.url === '/api/auth/logout');
    assert.equal(logout.options.method, 'POST');
    assert.equal(logout.options.headers['X-CSRF-TOKEN'], 'synthetic-csrf');
    p.dom.window.close();
  }
  const demo = await page('MAINTAINER', { demo: true });
  assert.equal(demo.el('demo-notice').hidden, false);
  assert.equal(demo.el('display-name-section').hidden, true);
  assert.equal(demo.el('password-section').hidden, true);
  demo.dom.window.close();
  const anonymous = await page('REPORTER', { anonymous: true });
  assert.deepEqual(anonymous.redirects, ['/']);
  assert.equal(anonymous.el('account').hidden, true);
  anonymous.dom.window.close();
  const failed = await page('DEVELOPER', { logoutFails: true });
  failed.el('sign-out').click();
  await flush();
  assert.deepEqual(failed.redirects, []);
  assert.match(failed.el('status').textContent, /Unable to sign out/);
  assert.equal(failed.el('sign-out').disabled, false);
  failed.dom.window.close();
  console.log('Account DOM checks passed: all roles, profile update, demo, anonymous, logout success/failure.');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
