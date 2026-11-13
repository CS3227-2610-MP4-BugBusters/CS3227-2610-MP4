// Set this to the deployed application's HTTPS URL when it is available.
const APP_URL = "https://cs3227-2610-mp4.onrender.com/";

if (APP_URL) {
  try {
    const url = new URL(APP_URL);
    if (url.protocol === "https:") {
      const link = document.createElement("a");
      link.className = "button button-primary";
      link.href = url.href;
      link.textContent = "Open BugBusters ↗";
      document.getElementById("app-link-container").replaceChildren(link);
      document.getElementById("app-status").textContent = "BugBusters is available online";
    }
  } catch {
    // Leave the coming-soon state in place for an invalid URL.
  }
}
