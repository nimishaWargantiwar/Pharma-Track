# PharmaTrack MES & eBR — Pharmaceutical Manufacturing Execution & Electronic Batch Record System

> **IMPORTANT DEMONSTRATION & REGULATORY DISCLAIMER**
>
> **PharmaTrack MES & eBR** is an educational and engineering demonstration / portfolio project modeled after pharmaceutical manufacturing execution (MES), electronic batch record (eBR), quality control, QA disposition, and deviation/CAPA workflows.
>
> **It is NOT certified, validated, or compliant for real GxP / cGMP manufacturing, GAMP 5 computer system validation, or FDA 21 CFR Part 11 / EU Annex 11 regulated production use.** Do not deploy or rely on this software for clinical, commercial, or regulated drug manufacturing operations without independent regulatory qualification, validated infrastructure, hardware/infrastructure clock controls, PKI/re-authentication electronic signature controls, and formal IQ/OQ/PQ documentation.

---

## 1. Project Architecture

PharmaTrack uses a decoupled three-tier web architecture designed for **Vercel** (frontend), **Render** (containerized backend), and **Managed MySQL 8 InnoDB** (relational persistence with Flyway schema migrations):

```text
┌─────────────────────────────────────────────────────────────────────────┐
│ Vercel (HTTPS Static SPA)                                               │
│ React 18 + Vite 5 + Tailwind CSS + vercel.json (SPA rewrites + CSP)     │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ HTTPS REST + Stateless JWT Bearer
                                     │ VITE_API_BASE_URL=https://.../api
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Render Web Service (Docker — backend/Dockerfile, non-root spring user)  │
│ Java 17 + Spring Boot 3.3.5 + Spring Security + JJWT + Hibernate 6      │
│ • SPRING_PROFILES_ACTIVE=prod (fail-closed startup guards)              │
│ • Explicit CORS origin validation (CorsOriginSupport)                   │
│ • Flyway schema migrations (validate-on-migrate, ddl-auto=validate)     │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ JDBC over TLS (sslMode=REQUIRED+)
                                     │ HikariCP + serverTimezone=UTC
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Managed MySQL 8.0+ (InnoDB, utf8mb4_unicode_ci)                         │
│ 13 core tables managed via Flyway V1__initial_schema.sql                │
└─────────────────────────────────────────────────────────────────────────┘
```

### Core Functional Modules & Cross-Cutting Guarantees
- **Authentication & RBAC (`ADMIN`, `PRODUCTION_SUPERVISOR`, `OPERATOR`, `QC_ANALYST`, `QA_REVIEWER`):** Stateless HS256 JWT tokens stored per browser tab (`sessionStorage` on frontend, `token_version` invalidation on backend). All REST endpoints except `GET /api/health` and `POST /api/auth/login` require valid JWT authentication; method- and route-level RBAC enforces least privilege.
- **Product Master & Master Recipes:** Product lifecycle governance and immutable versioned master recipes (recipe steps, parameter ranges, QC specifications).
- **Manufacturing Orders & Batch Release:** Order scheduling/release with automatic batch generation under transactional integrity.
- **Production Execution:** Ordered execution step lifecycle (`PENDING` -> `IN_PROGRESS` -> `COMPLETED` / `SKIPPED`), yield recording, and batch lifecycle transitions (`PLANNED` -> `IN_PROGRESS` -> `COMPLETED` / `CANCELLED`).
- **Quality Control & QA Disposition Release Gate:** Multi-parameter QC testing (`PASS` / `FAIL` + controlled corrections) and formal QA disposition (`APPROVED`, `REJECTED`, `HOLD`), enforcing blockers for open deviations or unresolved critical deviations.
- **Deviations & CAPA:** Controlled deviation lifecycle (`OPEN` -> `UNDER_INVESTIGATION` -> `CAPA_IN_PROGRESS` -> `PENDING_VERIFICATION` -> `CLOSED`), root-cause tracking, CAPA tasks, and effectiveness verification.
- **Append-Only Audit Trail & Sanitized Errors:** Server-derived authenticated actor attribution (`AuthenticatedActorResolver`), transactional audit logging (`audit_logs`), sanitized JSON `ErrorResponse` payloads (never leaking SQL, stack traces, secrets, or filesystem paths), and consistent `Asia/Kolkata` (`+05:30`) application timestamps paired with UTC database storage and timezone-safe `DATE` (`YYYY-MM-DD`) business dates.

### Repository Layout

```text
Pharma-Tracker/
├── .gitignore
├── README.md
├── backend/
│   ├── Dockerfile                             # Multi-stage Temurin 17 JDK -> JRE container build
│   ├── .dockerignore                          # Excludes local configs, secrets, logs, build artifacts
│   ├── .env.example                           # Environment variable template (placeholders only)
│   ├── mvnw / mvnw.cmd / .mvn/                # Maven Wrapper 3.3.4 (Maven 3.9.15)
│   ├── pom.xml
│   ├── setup_database.example.sql             # Template for local schema + least-privilege MySQL user
│   ├── config/                                # Gitignored local runtime config (application-local.properties)
│   └── src/
│       ├── main/
│       │   ├── java/com/pharmatrack/
│       │   │   ├── PharmaTrackApplication.java
│       │   │   ├── config/                    # Startup guards, EnvPostProcessor, Jackson IST config, bootstrappers
│       │   │   ├── controller/                # REST endpoints (Auth, Admin, Health, Product, Recipe, Order, Batch, Quality, Deviation)
│       │   │   ├── dto/
│       │   │   ├── entity/
│       │   │   ├── exception/                 # Sanitized GlobalExceptionHandler + SensitiveDataSanitizer
│       │   │   ├── repository/
│       │   │   ├── security/                  # SecurityConfig, CorsOriginSupport, JWT filter/service, actor resolver
│       │   │   ├── service/
│       │   │   └── util/                      # AppTime (Asia/Kolkata standardization)
│       │   └── resources/
│       │       ├── application.properties       # Shared base properties (defaults active profile to local)
│       │       ├── application-prod.properties  # Fail-closed production configuration
│       │       ├── META-INF/spring.factories    # Registers EnvironmentPostProcessor & Guard Initializer
│       │       └── db/migration/
│       │           └── V1__initial_schema.sql   # Flyway baseline creating all 13 InnoDB tables
│       └── test/                              # JUnit 5 / Spring Boot / MockMvc test suites (H2)
└── frontend/
    ├── package.json
    ├── vite.config.js                         # Build-time validation & elimination of localhost fallback in prod bundles
    ├── vercel.json                            # SPA fallback rewrites, CSP, security headers & cache control
    ├── .env.example                           # Frontend environment template
    ├── tailwind.config.js / postcss.config.js
    ├── index.html
    └── src/
        ├── App.jsx
        ├── main.jsx
        ├── components/                        # Login, RBAC navigation, workspace modals & tables
        ├── context/AuthContext.jsx            # Token persistence, session restore & cold-start retry handling
        ├── services/                          # apiClient.js + domain API modules
        └── utils/                             # apiUrlConfig.js, rbacUtils.js, dateTimeUtils.js, domain validators
```

---

## 2. Technology Stack

| Layer | Technology | Version / Notes |
| :--- | :--- | :--- |
| **Backend Language & Runtime** | Java | Java 17 target (`eclipse-temurin:17-jdk-jammy` build / `17-jre-jammy` runtime) |
| **Backend Framework** | Spring Boot | `3.3.5` (Web, Data JPA / Hibernate 6, Validation, Security) |
| **Authentication** | JWT (JJWT) + BCrypt | `io.jsonwebtoken:jjwt-*` `0.12.6`, HS256 (min 256-bit secret), stateless filter |
| **Database & Migrations** | MySQL 8.0+ (InnoDB) + Flyway | `mysql-connector-j`, `flyway-core`, `flyway-mysql` (`V1__initial_schema.sql`) |
| **Build & Containerization** | Maven Wrapper + Docker | Wrapper `3.3.4` (`only-script`, Apache Maven `3.9.15`), multi-stage `backend/Dockerfile` |
| **Backend Testing** | JUnit 5 + Spring Security Test + H2 | Isolated H2 in-memory tests via `./mvnw test` |
| **Frontend Framework** | React | `18.3.1` (ES6+ JavaScript, no TypeScript) |
| **Frontend Build & UI** | Vite + Tailwind CSS + Lucide | Vite `5.4.11`, Tailwind CSS `3.4.15`, PostCSS `8.4.49`, Lucide React `0.460.0` |
| **Frontend Testing** | Node.js Test Runner | `node --test` (`npm test`) |
| **Deployment Targets** | Vercel + Render + Managed MySQL | Vercel (static SPA) + Render (Docker web service) + MySQL 8 InnoDB |

---

## 3. Local Development Setup

### Prerequisites
- **Java Development Kit (JDK):** 17 or newer
- **Node.js & npm:** Node 18+ (tested on Node `20.20.2`) and npm 9+
- **MySQL Server:** 8.0+ running locally
- **Maven:** Not required globally; use the included `./mvnw` wrapper in `backend/`

### Step 1: Prepare the Local MySQL Database
Create the `pharmatrack_db` schema and a dedicated least-privilege application account (do **not** run the application as `root`). Use [`backend/setup_database.example.sql`](backend/setup_database.example.sql) as a template (copy it to a gitignored local file before running in MySQL):

```sql
CREATE DATABASE IF NOT EXISTS pharmatrack_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS '<LOCAL_DB_USERNAME>'@'localhost'
  IDENTIFIED BY '<LOCAL_STRONG_PASSWORD>';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
  ON pharmatrack_db.*
  TO '<LOCAL_DB_USERNAME>'@'localhost';

FLUSH PRIVILEGES;
```

### Step 2: Configure Local Backend Secrets (Outside `src/main/resources`)
Local secrets must **never** live inside `backend/src/main/resources/`. Create the gitignored file `backend/config/application-local.properties` (Spring Boot automatically loads `config/application-local.properties` when started inside `backend/` with the default `local` profile) or supply variables via shell exports using [`backend/.env.example`](backend/.env.example):

```properties
# Example backend/config/application-local.properties (DO NOT COMMIT)
spring.datasource.username=<LOCAL_DB_USERNAME>
spring.datasource.password=<LOCAL_STRONG_PASSWORD>
app.jwt.secret=base64:<OUTPUT_OF_openssl_rand_-base64_32>
app.security.bootstrap.admin-username=admin
app.security.bootstrap.admin-password=<STRONG_ADMIN_PASSWORD>

# Optional: auto-create the 5 role demo accounts on local profile startup
# Usernames created if missing: admin, supervisor, operator, qc_analyst, qa_reviewer
app.security.local-demo.enabled=true
app.security.local-demo.password=<LOCAL_DEMO_PASSWORD>
```

> **Note on `local` vs `prod` isolation:** `LocalDemoUserInitializer` only activates under `@Profile("local")` and never writes passwords hardcoded in source code. `application-prod.properties` hardcodes `app.security.local-demo.enabled=false`, and `ProductionConfigurationGuardInitializer` blocks startup if `local` and `prod` profiles are combined.

### Step 3: Start & Test the Backend
From `/backend`:

```bash
cd backend

# Run the full backend unit & integration test suite (H2 in-memory, never touches local MySQL)
./mvnw test

# Start the backend locally on http://localhost:8080 (uses SPRING_PROFILES_ACTIVE=local by default)
./mvnw spring-boot:run
```

Verify local health:
```bash
curl -i http://localhost:8080/api/health
```

### Step 4: Start & Test the Frontend
From `/frontend`:

```bash
cd frontend

# Install dependencies
npm install

# Run frontend unit, RBAC, timezone, and API URL validation tests
npm test

# Optional: copy .env.example to gitignored .env.local if overriding VITE_API_BASE_URL locally
# cp .env.example .env.local

# Start Vite dev server on http://localhost:5173
npm run dev
```

---

## 4. Required Backend Environment Variables (Production / Render)

When `SPRING_PROFILES_ACTIVE=prod` is active (the default inside `backend/Dockerfile`), [`ProductionConfigurationGuardInitializer`](backend/src/main/java/com/pharmatrack/config/ProductionConfigurationGuardInitializer.java) and [`ProductionEnvironmentPostProcessor`](backend/src/main/java/com/pharmatrack/config/ProductionEnvironmentPostProcessor.java) enforce **fail-closed** startup: missing or insecure settings abort startup before JPA or Flyway initializes, and never fall back to `localhost` or local defaults.

### Profile & Server
| Variable | Required in `prod` | Default in `application-prod.properties` | Description |
| :--- | :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | Yes | Set to `prod` in `Dockerfile` | Must include `prod` and must **not** combine `local`. |
| `PORT` | Injected by Render | `8080` | HTTP server port bound to `server.port=${PORT:8080}`. |

### Database Connection (Supply **Option A** OR **Option B**)
| Variable | Required in `prod` | Default / Constraint | Description |
| :--- | :--- | :--- | :--- |
| `DB_HOST` | Option A: Yes | None (no localhost fallback) | Managed MySQL hostname (ignored if `DB_URL` is non-empty). |
| `DB_PORT` | Option A: Optional | `3306` | Managed MySQL TCP port (`1..65535`). |
| `DB_NAME` | Option A: Yes | None | Target schema name (must already exist on managed MySQL). |
| `DB_SSL_MODE` | Option A: Optional | `REQUIRED` | Allowed values: `REQUIRED`, `VERIFY_CA`, `VERIFY_IDENTITY`, `PREFERRED`. (`DISABLED` is rejected.) |
| `DB_SSL_TRUSTSTORE_URL` | Optional | None | Optional JDBC `trustCertificateKeyStoreUrl` when using `VERIFY_CA` / `VERIFY_IDENTITY`. |
| `DB_SSL_TRUSTSTORE_PASSWORD` | Optional | None | Optional truststore password paired with `DB_SSL_TRUSTSTORE_URL`. |
| `DB_URL` | Option B: Yes | None | Full `jdbc:mysql://...` URL override. Must **not** contain `useSSL=false` or `sslMode=DISABLED`. Appends `serverTimezone=UTC` automatically if omitted. |
| `DB_USERNAME` | Always Yes | None | Dedicated MySQL application username. |
| `DB_PASSWORD` | Always Yes | None | MySQL password. |
| `DB_POOL_MAX_SIZE` | Optional | `10` | HikariCP `maximum-pool-size`. |
| `DB_POOL_MIN_IDLE` | Optional | `2` | HikariCP `minimum-idle`. |

### JWT, CORS & Admin Bootstrap
| Variable | Required in `prod` | Default / Constraint | Description |
| :--- | :--- | :--- | :--- |
| `JWT_SECRET` | Yes | None | HS256 signing key (min 256 bits / 32 bytes). Supply `base64:<key>` (generated via `openssl rand -base64 32`) or >= 32 UTF-8 chars. Weak or placeholder secrets fail startup. |
| `JWT_EXPIRATION_MS` | Optional | `3600000` (1 hour) | Access token lifetime in milliseconds. |
| `CORS_ALLOWED_ORIGINS` | Yes | None | Comma-separated exact browser origins (e.g., `https://your-app.vercel.app`). Trailing slashes are stripped; wildcards (`*`, `https://*.vercel.app`), paths, queries, and empty values fail startup. |
| `BOOTSTRAP_ADMIN_ENABLED` | Optional | `true` | Enables initial admin check at startup. |
| `BOOTSTRAP_ADMIN_USERNAME` | Yes (on fresh DB) | None | Initial `ADMIN` username created if `0` `ADMIN` accounts exist (`3-50` chars). |
| `BOOTSTRAP_ADMIN_PASSWORD` | Yes (on fresh DB) | None | Initial `ADMIN` password (>= 8 chars, recommend >= 14+ in prod, >= 1 letter, >= 1 digit, max 72 UTF-8 bytes). |
| `BOOTSTRAP_ADMIN_FULL_NAME` | Optional | `System Administrator` | Display name for the bootstrapped admin account. |
| `LOCAL_DEMO_USERS_ENABLED` | Forbidden in `prod` | `false` | Hard-disabled in `application-prod.properties` and blocked by startup guard. |

See [`backend/.env.example`](backend/.env.example) for a safe copy-ready reference with placeholders only.

---

## 5. Required Frontend Environment Variables (Vercel)

| Variable | Required in Production Build | Example Value | Notes |
| :--- | :--- | :--- | :--- |
| `VITE_API_BASE_URL` | **Yes** | `https://<your-backend-service>.onrender.com/api` | Must use `https://`, must **not** point to `localhost` / loopback / `.local`, must **not** contain embedded credentials or query strings, and must end with `/api`. Trailing slashes are trimmed automatically. |

### Build-Time Safety Enforcement
- [`frontend/vite.config.js`](frontend/vite.config.js) validates `VITE_API_BASE_URL` during every production `vite build` via [`validateProductionApiBaseUrl`](frontend/src/utils/apiUrlConfig.js) and injects `__PHARMATRACK_PROD_API_URL__`.
- Production builds **fail immediately** if `VITE_API_BASE_URL` is missing, uses `http://`, references `localhost` / `127.0.0.1` / `::1` / `.local`, or omits `/api`.
- [`apiClient.js`](frontend/src/services/apiClient.js) uses `__PHARMATRACK_PROD_API_URL__` directly in production bundles so the `http://localhost:8080/api` development fallback is dead-code eliminated from `dist/`.
- **Security rule:** Never put private keys, database credentials, or secrets into any `VITE_*` variable; Vite embeds `VITE_*` values into public browser JavaScript.

---

## 6. Render Deployment Configuration (Backend)

Deploy the backend as a **Render Web Service** using the Docker runtime:

1. **Service Type:** Web Service
2. **Root Directory:** `backend`
3. **Runtime / Language:** Docker (uses `backend/Dockerfile` + `backend/.dockerignore`)
4. **Health Check Path:** `/api/health`
5. **Dockerfile Behavior:**
   - Stage 1 (`eclipse-temurin:17-jdk-jammy`): Runs `./mvnw dependency:go-offline -B` followed by `./mvnw clean package -DskipTests -B`.
   - Stage 2 (`eclipse-temurin:17-jre-jammy`): Creates a non-root `spring:spring` user, copies the Spring Boot fat JAR (excluding `config/`, local properties, `.env`, and SQL setup scripts via `.dockerignore`), sets `SPRING_PROFILES_ACTIVE=prod` and `JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"`, and starts with `exec java $JAVA_OPTS -jar /app/app.jar`.
6. **Port Binding:** Render injects `PORT` dynamically at runtime; `application-prod.properties` binds `server.port=${PORT:8080}`.
7. **Environment Variables:** Configure all Section 4 environment variables in the Render Dashboard (**Environment** tab). Do not commit `.env` files.
8. **Post-Deploy CORS Sync:** After Vercel assigns your frontend URL (`https://<your-app>.vercel.app`), set it in Render's `CORS_ALLOWED_ORIGINS` (exact origin, comma-separated if multiple domains such as custom domain + Vercel production URL).

---

## 7. Vercel Deployment Configuration (Frontend)

Deploy the frontend as a **Vercel Project**:

1. **Root Directory:** `frontend`
2. **Framework Preset:** Vite
3. **Install Command:** `npm install`
4. **Build Command:** `npm run build`
5. **Output Directory:** `dist`
6. **Environment Variables:** Set `VITE_API_BASE_URL=https://<your-render-service>.onrender.com/api` in Vercel **Project Settings -> Environment Variables** (for **Production** and **Preview** if using previews) *before* triggering the build, because Vite bakes `VITE_API_BASE_URL` into the bundle at build time.
7. **Routing & Security Headers (`frontend/vercel.json`):**
   - **SPA Rewrite:** Routes non-asset URLs (`/((?!assets/).*)`) to `/index.html` while keeping `/assets/*` untouched so direct URL loads or refreshes do not 404 or mask missing JS/CSS bundles with HTML.
   - **Security Headers:** Applies `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`, `Strict-Transport-Security`, and `Content-Security-Policy` (`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self' https:; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'`).
   - **Caching:** `public, max-age=31536000, immutable` on `/assets/(.*)` and `no-cache` on `/index.html`.
8. **Navigation Compatibility:** Primary in-app routes use hash routing (`#/products`, `#/execution`, etc.), and `getTabFromLocation()` also accepts clean path aliases (`/products`, `/quality`) rewritten by `vercel.json`.

---

## 8. MySQL Database Preparation & Safe Flyway Migration / Baseline Procedure

Production runs with:
- `spring.jpa.hibernate.ddl-auto=validate`
- `spring.flyway.enabled=true`
- `spring.flyway.locations=classpath:db/migration`
- `spring.flyway.baseline-on-migrate=false`
- `spring.flyway.baseline-version=1`
- `spring.flyway.validate-on-migrate=true`

### Prepare the Managed MySQL 8.0+ Database
1. Ensure the database engine is **MySQL 8.0+** with default charset `utf8mb4` and collation `utf8mb4_unicode_ci`.
2. Create the target database schema (e.g., `pharmatrack_prod`) via your managed database provider console or SQL admin session:
   ```sql
   CREATE DATABASE IF NOT EXISTS pharmatrack_prod
     CHARACTER SET utf8mb4
     COLLATE utf8mb4_unicode_ci;
   ```
3. Create or assign a dedicated least-privilege database user (`DB_USERNAME`) scoped strictly to `pharmatrack_prod.*` with privileges needed for Flyway migrations and runtime CRUD (`SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES`).
4. Obtain the provider CA certificate / TLS setting if configuring `DB_SSL_MODE=VERIFY_CA` or `VERIFY_IDENTITY` (otherwise `DB_SSL_MODE=REQUIRED`).

### Scenario A: Fresh Production Database (Empty Schema)
No manual DDL scripts are required:
1. Deploy the backend with `SPRING_PROFILES_ACTIVE=prod` and valid `DB_*` credentials.
2. On first boot, Flyway creates `flyway_schema_history`, runs [`V1__initial_schema.sql`](backend/src/main/resources/db/migration/V1__initial_schema.sql) to create all 13 InnoDB tables in dependency order, and Hibernate validates every JPA entity (`ddl-auto=validate`).

### Scenario B: Adopting a Pre-Existing Un-Versioned Database (Explicit Manual Baseline)
Because `spring.flyway.baseline-on-migrate=false` in production, pointing the backend at an already-populated database that lacks `flyway_schema_history` will fail safely instead of silently guessing a baseline. To adopt a pre-existing database safely:
1. **Take a verified snapshot/backup first.**
2. Confirm the existing schema matches `V1__initial_schema.sql` (table names, column types, `BIT(1)` / `ENUM` definitions, constraints, and indexes).
3. Baseline Flyway explicitly at version `1` once from a trusted maintenance environment (never leave `baseline-on-migrate=true` enabled permanently in production):
   ```bash
   # Option 1: Using Maven Flyway plugin from a controlled admin workstation with prod TLS DB credentials
   ./mvnw flyway:baseline \
     -Dflyway.url="jdbc:mysql://<DB_HOST>:<DB_PORT>/<DB_NAME>?sslMode=REQUIRED&serverTimezone=UTC" \
     -Dflyway.user="<DB_USERNAME>" \
     -Dflyway.password="<DB_PASSWORD>" \
     -Dflyway.baselineVersion=1 \
     -Dflyway.baselineDescription="Verified V1 initial schema baseline"
   ```
4. Confirm `flyway_schema_history` contains version `1`, then start the Render service normally with `baseline-on-migrate=false` so Hibernate (`ddl-auto=validate`) verifies schema parity and future migrations (`V2__...`) execute in order.

---

## 9. Initial Administrator Bootstrap

[`SecurityBootstrapInitializer`](backend/src/main/java/com/pharmatrack/config/SecurityBootstrapInitializer.java) provisions the first administrator safely and idempotently:

1. On startup, it counts existing `Role.ADMIN` rows (`userAccountRepository.countByRole(Role.ADMIN)`).
2. If **0** `ADMIN` accounts exist:
   - In non-`local` profiles (including `prod`), startup **fails closed** unless both `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` are configured and pass user/password validation (`>= 8` characters, recommend `>= 14+` in prod, at least 1 letter and 1 digit, max 72 UTF-8 bytes, username `3-50` chars).
   - Creates the single initial `ADMIN` account with BCrypt password hashing and logs the username (never the password).
3. If **1 or more** `ADMIN` accounts already exist:
   - Bootstrap does nothing and never overwrites or resets existing `ADMIN` accounts or passwords.
4. **Provisioning operational roles:** Sign in as the initial `ADMIN` user and create accounts for `PRODUCTION_SUPERVISOR`, `OPERATOR`, `QC_ANALYST`, and `QA_REVIEWER` through the backend Admin User Management API (`/api/admin/users`). After verifying admin sign-in in production, you may optionally remove `BOOTSTRAP_ADMIN_PASSWORD` from Render environment variables (once an `ADMIN` row exists in the database, `SecurityBootstrapInitializer` skips creation).

---

## 10. Health-Check Verification

`GET /api/health` ([`HealthController`](backend/src/main/java/com/pharmatrack/controller/HealthController.java)) is publicly reachable for Render health probes and the frontend status badge.

- **Healthy Response (`HTTP 200 OK`):**
  ```json
  {
    "status": "UP",
    "message": "PharmaTrack backend service and database connectivity are operational",
    "timestamp": "2026-11-11T23:15:00.123456+05:30"
  }
  ```
- **Degraded / DB Unreachable Response (`HTTP 503 Service Unavailable`):**
  ```json
  {
    "status": "DOWN",
    "message": "Service unavailable: database connectivity check failed",
    "timestamp": "2026-11-11T23:15:00.123456+05:30"
  }
  ```
- **Information Hygiene:** Never leaks database catalog names, JDBC URLs, hostnames, credentials, SQL statements, or raw exception messages. Timestamps reflect the application standard (`Asia/Kolkata`, `+05:30`).

Post-deploy verification command:
```bash
curl -i https://<your-backend-service>.onrender.com/api/health
```

---

## 11. Common Deployment Errors & Troubleshooting

| Symptom / Error | Root Cause | Resolution |
| :--- | :--- | :--- |
| Browser blocks requests with CORS `No 'Access-Control-Allow-Origin'` | `CORS_ALLOWED_ORIGINS` on Render does not exactly match the Vercel origin (e.g., `http://` vs `https://`, missing Vercel preview/production domain, or wrong scheme). | Set `CORS_ALLOWED_ORIGINS` to the exact origin(s), e.g., `https://your-app.vercel.app` (trailing slashes are normalized automatically, but paths/wildcards are rejected). |
| Startup fails: `Production configuration error: Invalid CORS_ALLOWED_ORIGINS entry ... wildcard '*' is not permitted` | `CORS_ALLOWED_ORIGINS` contains `*`, `https://*.vercel.app`, or a URL path like `/login`. | Replace wildcards and paths with comma-separated exact `https://host` origins. |
| Vercel build fails: `[PharmaTrack Build Error] VITE_API_BASE_URL ...` | `VITE_API_BASE_URL` is unset, uses `http://`, points to `localhost`/`127.0.0.1`/`.local`, embeds credentials, or omits the `/api` suffix. | Set `VITE_API_BASE_URL=https://<your-backend-service>.onrender.com/api` in Vercel Environment Variables and redeploy. |
| Render startup fails: `Missing required MySQL database configuration` or `DB_USERNAME` / `DB_PASSWORD` | `SPRING_PROFILES_ACTIVE=prod` is active without `DB_URL` (or `DB_HOST` + `DB_NAME`) and `DB_USERNAME`/`DB_PASSWORD`. | Configure database environment variables in Render Dashboard using [`backend/.env.example`](backend/.env.example). |
| Render startup fails: `Disabling MySQL TLS ... is not permitted in production` or `DB_SSL_MODE` error | `DB_URL` contains `useSSL=false`/`sslMode=DISABLED` or `DB_SSL_MODE=DISABLED`. | Use `DB_SSL_MODE=REQUIRED` (or `VERIFY_CA`/`VERIFY_IDENTITY` with provider CA truststore). |
| Managed MySQL SSL handshake failure (`VERIFY_CA` / `VERIFY_IDENTITY`) | Missing/untrusted custom CA certificate on JVM when verifying server certificate. | Supply `DB_SSL_TRUSTSTORE_URL` and `DB_SSL_TRUSTSTORE_PASSWORD` (or use `DB_SSL_MODE=REQUIRED` if your managed provider handles CA validation via standard root CAs). |
| Render startup fails: `JWT_SECRET` weak or missing | `JWT_SECRET` is empty, < 256 bits, or matches insecure placeholder patterns. | Generate a cryptographically random 256-bit secret (`openssl rand -base64 32`) and prefix with `base64:`. |
| Flyway fails: `Found non-empty schema(s) without schema history table` | Database already has tables from prior setup without `flyway_schema_history`. | Verify schema parity against `V1__initial_schema.sql` on a backup and follow **Section 8 (Scenario B)** to baseline at version `1`. |
| Render startup fails: `Security bootstrap failed: zero ADMIN users exist...` | Fresh database has `0` `ADMIN` users and `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` are missing or violate password policy. | Set compliant `BOOTSTRAP_ADMIN_USERNAME` and `BOOTSTRAP_ADMIN_PASSWORD` environment variables before initial boot. |
| Browser refresh shows **"Session Verification Interrupted"** after backend sleep/cold start | Render free-tier instance is waking up (`503` / network timeout during `GET /api/auth/me`). | `AuthContext` preserves the tab JWT on transient network/`5xx` errors (`401`/`403` still log out). Wait a few seconds for Render to finish waking up and click **Retry Verification**. |
