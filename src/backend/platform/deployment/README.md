# Development deployment

Prepared the development packaging and deployment files for the Maintainer-owned
Render environment. The container builds the Maintainer and Developer React assets
with Node 22, packages the Java 17 application and runtime libraries with the
repository Gradle wrapper, and starts the application directly on Render's `PORT`.
The Maintainer app is served at `/`; the Developer app is served at `/developer/`.
The public `/assets/revision.txt` asset identifies the source revision passed to the image build.

## Local container build

From the repository root:

```sh
docker build \
  --build-arg RENDER_GIT_COMMIT="$(git rev-parse HEAD)" \
  -f src/backend/platform/deployment/Dockerfile \
  -t mp4-auth-development .
```

Run with local disposable PostgreSQL settings supplied through the environment.
`MP4_AUTH_DB_HOST`, `MP4_AUTH_DB_PORT`, `MP4_AUTH_DB_NAME`, `MP4_AUTH_DB_USER`,
`MP4_AUTH_DB_PASSWORD`, and `MP4_AUTH_RATE_LIMIT_SECRET` are required. The start
script constructs a password-free JDBC URL from the database host, port and name
when `MP4_AUTH_JDBC_URL` is absent. It never prints the URL or credentials.

## Render setup

The Blueprint defines only the `mp4/development` project environment: one free web
service and one separate free PostgreSQL database in Singapore. The database allows
private network connections only. Render provides the database host, port, database,
user and password as separate service environment values; the container assembles
the JDBC URL at startup. Render generates `MP4_AUTH_RATE_LIMIT_SECRET`. Set
`MP4_AUTH_PUBLIC_ORIGIN` to the service's actual HTTPS `onrender.com` URL in the
Render Dashboard. Keep these values in the development environment and never copy
them into production.

Free Render services are for development only. The web service may spin down while
idle and its filesystem is ephemeral. Free Postgres has a 1 GB limit, no backups,
and expires 30 days after creation. Treat its data as disposable. The Blueprint
contains no production service or database. Its project protection setting prevents
some destructive actions; it is not an approval gate.

Automatic deploys are disabled. To request a development deploy, manually dispatch
the `Deploy development` workflow from `main`. It checks the successful `CI` workflow
run for the exact `main` push SHA before reading the environment secret
`RENDER_DEV_DEPLOY_HOOK`. Configure that secret as the Render deploy hook URL in the
GitHub `development` environment. The workflow requests that exact SHA and reports
only the returned deploy ID (when Render returns one). Configure GitHub environment
protection rules separately if human approval is required; naming an environment
does not enable approval protection.

## Verification status

Verified image build and packaged health/frontend/revision checks in GitHub CI
run `42512619045` on source candidate `61cb733`. Have not deployed this Blueprint
or created Render resources. The repository has no
Render deploy hook or GitHub environment secrets available to this work, and Docker
is unavailable in the current machine. A live deployed revision, host-to-database
connectivity, login/read/wrong-role smoke checks, production setup and rollback have
not been verified. The configuration does not complete OpenSpec tasks 0.3 or 6.2.
