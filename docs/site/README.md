# BugBusters product website

This static site is prepared for GitHub Pages. It describes the product and links to the user and developer guides on the repository's `main` branch.

## Preview locally

From the repository root:

```sh
python3 -m http.server 5192 --bind 127.0.0.1 --directory docs/site
```

Open `http://127.0.0.1:5192/`. Press Ctrl+C to stop the server.

## Add the deployed application URL

When the application is deployed, set the HTTPS URL in `site-config.js`:

```js
const APP_URL = "https://your-deployed-app.example";
```

The site shows a coming-soon state while this value is empty or is not an HTTPS URL. Replace the example with the real address; do not add credentials or secrets.

## Publish with GitHub Pages

Commit and merge `docs/site/` and `.github/workflows/pages.yml` into `main`. In the repository's **Settings → Pages → Build and deployment**, select **GitHub Actions** as the source. A push to `main` touching the site or workflow, or a manual workflow dispatch, uploads `docs/site/` and deploys it to the `github-pages` environment. Check the Actions run and use its reported page URL; the repository settings and deployed site have not yet been verified.
