"use strict";
const el = (id) => document.getElementById(id);
const routes = { MAINTAINER: "/", DEVELOPER: "/developer/", REPORTER: "/reporter/" };
const status = (message) => { el("status").textContent = message; };
function signedOut() {
  el("workspace").classList.add("role-workspace--anonymous");
  el("workspace-account").hidden = true;
  el("workspace-navigation").hidden = true;
  el("workspace-role").hidden = true;
  el("account").hidden = true;
  el("signed-out").hidden = false;
  document.querySelectorAll('input[type="password"]').forEach((input) => { input.value = ""; });
}
el("sign-out").addEventListener("click", async () => {
  const button = el("sign-out");
  button.disabled = true;
  button.textContent = "Signing out…";
  try {
    const bootstrap = await fetch("/api/auth/csrf", { credentials: "same-origin", cache: "no-store" });
    if (!bootstrap.ok) throw new Error("Unable to check your session. Please try again.");
    const csrf = await bootstrap.json();
    const response = await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin", cache: "no-store", headers: { "X-CSRF-TOKEN": csrf.token } });
    if (!response.ok) throw new Error("Unable to sign out. Please try again.");
    signedOut();
    window.location.assign("/");
  } catch (error) {
    status(error.message || "Unable to sign out. Please try again.");
  } finally {
    button.disabled = false;
    button.textContent = "Sign out";
  }
});
async function request(path, body) {
  const headers = {};
  if (body) {
    const bootstrap = await fetch("/api/auth/csrf", { credentials: "same-origin", cache: "no-store" });
    if (!bootstrap.ok) throw new Error("Unable to check your session. Please try again.");
    const csrf = await bootstrap.json();
    headers["X-CSRF-TOKEN"] = csrf.token;
    headers["Content-Type"] = "application/json";
  }
  const response = await fetch(path, { method: body ? "POST" : "GET", credentials: "same-origin", cache: "no-store", headers, ...(body ? { body: JSON.stringify(body) } : {}) });
  if (response.status === 204) return null;
  const result = await response.json();
  if (!response.ok) {
    if (response.status === 401) signedOut();
    const messages = { CURRENT_PASSWORD_INVALID: "Current password is incorrect.", ACCOUNT_EXISTS: "That username is already in use.", DEMO_ACCOUNT_LOCKED: "The shared demo account cannot be changed.", VALIDATION_FAILED: "Check the display name and account fields. Passwords must be 12–72 UTF-8 bytes.", UNAUTHENTICATED: "Please sign in to continue.", FORBIDDEN: "You do not have permission for this action.", CSRF_INVALID: "Your session changed. Refresh and try again." };
    throw new Error(messages[result.code] || "Account service is unavailable. Please try again.");
  }
  return result;
}
function validPassword(value) {
  const bytes = new TextEncoder().encode(value).length;
  if (bytes < 12 || bytes > 72) throw new Error("Password must be 12–72 UTF-8 bytes.");
}
async function submit(form, action) {
  const button = form.querySelector("button");
  button.disabled = true;
  try { await action(); } catch (error) { status(error.message || "Request failed. Please try again."); }
  finally { button.disabled = false; }
}
el("create-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  void submit(form, async () => {
    const fields = Object.fromEntries(new FormData(form));
    validPassword(fields.password);
    const result = await request("/api/maintainer/accounts", fields);
    form.reset();
    status(`Created ${result.username} as ${result.role.toLowerCase()}. Give them their initial credentials privately.`);
  });
});
el("password-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  void submit(form, async () => {
    const fields = Object.fromEntries(new FormData(form));
    if (fields.newPassword !== fields.confirmation) throw new Error("New passwords do not match.");
    validPassword(fields.newPassword);
    await request("/api/account/password", { currentPassword: fields.currentPassword, newPassword: fields.newPassword });
    form.reset(); signedOut(); status("Password changed. Sign in again with your new password.");
  });
});
el("display-name-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  void submit(form, async () => {
    const fields = Object.fromEntries(new FormData(form));
    await request("/api/account/display-name", { displayName: fields.displayName });
    const user = await request("/api/account");
    el("identity").textContent = `${user.displayName} · ${user.username} · ${user.role.toLowerCase()}`;
    form.reset();
    el("display-name").value = user.displayName;
    status("Display name updated.");
  });
});
void request("/api/account").then((user) => {
  el("identity").textContent = `${user.displayName} · ${user.username} · ${user.role.toLowerCase()}`;
  el("display-name").value = user.displayName;
  el("back").href = routes[user.role] || "/";
  el("account").hidden = false;
  el("display-name-section").hidden = user.demoLocked;
  el("password-section").hidden = user.demoLocked;
  el("create-section").hidden = user.role !== "MAINTAINER";
  status("");
}).catch((error) => status(error.message || "Unable to load your account."));
