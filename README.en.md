<div align="center">

# AI-JobPilot-Cloud

**An independent, multi-user cloud SaaS for human-in-the-loop job searching.**
Manage accounts, resumes, preferences, job pools, AI matching, confirmed delivery tasks, plugin execution, quotas, and basic administration from one cloud deployment.

> This repository is the independent cloud edition of “投递牛马”. The original `AI-JobPilot` project is a separate local Windows application; its local SQLite and local-browser workflows are not the architecture or persistence model of this cloud repository.

[简体中文](README.md) · [Quick Start](#quick-start-docker) · [Cloud Docker guide](README_DOCKER.md) · [Cloud roadmap](CLOUD_ROADMAP.md) · [Cloud security](CLOUD_SECURITY.md)

[![Version](https://img.shields.io/badge/version-1.3.0-4f46e5.svg)](CHANGELOG.md)
[![Platform](https://img.shields.io/badge/platform-Docker%20%7C%20Linux%20%7C%20Windows-0078D4.svg)](README_DOCKER.md)
[![Java](https://img.shields.io/badge/Java-21-E76F00.svg)](build.gradle.kts)
[![Node](https://img.shields.io/badge/Node.js-20.19%2B-339933.svg)](front/package.json)
[![CI](https://github.com/damingishere-coder/AI-JobPilot-Cloud/actions/workflows/ci.yml/badge.svg)](https://github.com/damingishere-coder/AI-JobPilot-Cloud/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damingishere-coder/AI-JobPilot-Cloud/actions/workflows/codeql.yml/badge.svg)](https://github.com/damingishere-coder/AI-JobPilot-Cloud/actions/workflows/codeql.yml)
[![Release](https://github.com/damingishere-coder/AI-JobPilot-Cloud/actions/workflows/release.yml/badge.svg)](https://github.com/damingishere-coder/AI-JobPilot-Cloud/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-Non--Commercial-f59e0b.svg)](LICENSE)

</div>

![AI-JobPilot-Cloud cover](docs/images/hero.svg)

> AI-JobPilot-Cloud does not bypass sign-in checks, CAPTCHAs, anti-abuse controls, or platform limits. Jobs enter a review queue first, and the user decides whether an application action should proceed. The cloud service never stores recruitment-platform cookies or account passwords.

## Why AI-JobPilot-Cloud

Job searching involves more than discovering vacancies. Candidates repeatedly filter listings, compare requirements with their resume, switch between platforms, and track what happened after each application.

AI-JobPilot-Cloud brings these steps into a tenant-isolated cloud workspace:

- **Reduce repetitive screening** with resume- and preference-based job analysis.
- **Keep the user in control** by placing matched jobs in a review queue before application actions.
- **Use the user's browser context** through a least-privilege extension instead of storing platform cookies or account passwords in the cloud.
- **Keep tenant data isolated** with PostgreSQL-backed profiles, jobs, tasks, quota records, and audit records.
- **Understand failures** through status views, filters, statistics, and diagnostic information.

## How it works

![AI JobPilot workflow](docs/images/workflow.svg)

## Product preview

> These illustrations are temporary placeholders based on the current frontend code. Replace them with real screenshots later while keeping the same filenames, and the README links will continue to work.

### Application dashboard

![Application dashboard placeholder](docs/images/screenshots/dashboard-placeholder.svg)

### Boss Zhipin collection

![Boss Zhipin collection placeholder](docs/images/screenshots/boss-scan-placeholder.svg)

### AI analysis and human review

![Boss analysis placeholder](docs/images/screenshots/analysis-placeholder.svg)

### AI configuration and candidate profile

![AI configuration placeholder](docs/images/screenshots/ai-config-placeholder.svg)

## Core capabilities

- User accounts, encrypted resume data, preferences, job pools, quotas, and audit records with tenant isolation.
- Browser extension binding, structured job capture, and result delivery for supported platforms using the user's already authenticated browser page.
- AI-assisted job fit analysis, scoring, filtering, and review queues.
- Human confirmation before each application action; the extension cannot confirm tasks on the user's behalf.
- PostgreSQL as the business source of truth, Redis for sessions, rate limits, and queue support, and private encrypted file storage for resumes.
- Docker Compose for local/pre-release validation and a documented Nginx/HTTPS deployment path.

## Platform support

| Platform | Collection | AI analysis | Human review | Current status |
| --- | :---: | :---: | :---: | --- |
| Boss Zhipin | ✅ | ✅ | ✅ | Main Chrome Bridge flow; limited API POC and page fallback collection |
| Zhaopin | ✅ | ✅ | ✅ | Main Chrome Bridge flow |

`✅` means the current primary Cloud workflow is supported. Only the platforms listed above are confirmed in this repository's Cloud scope.

## Downloads and releases

The GitHub Release workflow runs backend tests, frontend lint and build, Chrome extension validation, and SHA256 generation before publishing a tagged version.

Current automated artifacts are:

```text
AI-JobPilot-vX.Y.Z.jar
AI-JobPilot-vX.Y.Z-chrome-extension.zip
AI-JobPilot-vX.Y.Z-frontend-static.zip
AI-JobPilot-vX.Y.Z-source.zip
SHA256SUMS.txt
```

These are technical preview artifacts and do not by themselves indicate that a production SaaS deployment has passed the P10 launch, backup, recovery, and security checks. See [README_DOCKER.md](README_DOCKER.md) and the `CLOUD_*` deployment documents for the current release boundaries.

Published versions are available from [GitHub Releases](https://github.com/damingishere-coder/AI-JobPilot-Cloud/releases).

## Quick start (Docker)

The supported repository quick start uses Docker Compose. Docker Desktop on Windows is supported for local validation; the cloud architecture uses PostgreSQL, Redis, private storage, and an internal API/Web network.

Requirements:

- Docker Desktop or Docker Engine with Compose v2
- Git

After cloning the repository, copy `.env.example` to a local `.env` and run from the project root:

```powershell
Copy-Item .env.example .env
.\start_docker.ps1
```

Open the local unified entry point:

```powershell
http://localhost:8080
```

The Docker guide contains the service topology, health checks, and backup/recovery commands:

See [README_DOCKER.md](README_DOCKER.md).

Never commit real API keys, account credentials, cookies, resume files, or browser profiles.

## Chrome Bridge setup

1. Open `chrome://extensions/`.
2. Enable Developer mode.
3. Choose **Load unpacked**.
4. Select the repository's `chrome-extension` directory.
5. Open the cloud Web entry point (local validation: `http://localhost:8080`) and verify that the extension is connected.
6. Sign in to the supported recruitment platform in Chrome, then start collection from the workspace.

The extension is a cloud client for the user's browser context. It does not upload cookies, account passwords, browser storage, or full page contents, and it does not bypass platform verification or usage limits.

## Development

Backend:

```powershell
.\gradlew.bat bootRun
```

Frontend:

```powershell
cd front
pnpm install
pnpm dev
```

Checks:

```powershell
.\gradlew.bat test
.\gradlew.bat build

cd front
pnpm lint
```

## Privacy and security

This repository is a multi-user cloud service and must be deployed behind HTTPS with the documented tenant isolation and secret-management controls. The original `AI-JobPilot` local edition remains a separate project and is not a substitute for this cloud deployment's controls.

Do not commit:

- `.env` files, API keys, passwords, cookies, or tokens
- PostgreSQL/Redis data, backups, or private storage volumes
- Resumes, screenshots, chat records, or other personal data
- Chrome profiles, browser caches, or recruitment-platform cookies

Read [SECURITY.md](SECURITY.md) before reporting a security issue or sharing diagnostics.

## Current limitations

- Recruitment website changes can break selectors and collection logic.
- Stability is not guaranteed for every platform, account, region, or listing type.
- The project does not bypass authentication, CAPTCHAs, anti-abuse controls, or application rate limits.
- Production Tencent Cloud deployment, backup/recovery rehearsal, and launch security review are still P10 work and are not represented as complete here.
- Account-level self-service deletion and final legal documents remain explicit pre-launch risks until completed or formally disclosed.

## Documentation

| Document | Purpose |
| --- | --- |
| [CLOUD_ARCHITECTURE.md](CLOUD_ARCHITECTURE.md) | Cloud topology, module boundaries, data flow, and plugin responsibilities |
| [CLOUD_API_DESIGN.md](CLOUD_API_DESIGN.md) | Cloud API contracts, scopes, and tenant boundaries |
| [CLOUD_SECURITY.md](CLOUD_SECURITY.md) | Cloud data classification, credentials, logs, and plugin security |
| [CLOUD_ROADMAP.md](CLOUD_ROADMAP.md) | P0-P10 cloud milestones and non-goals |
| [README_DOCKER.md](README_DOCKER.md) | Local/pre-release Docker startup and verification |
| [CLOUD_PRIVACY_CHECKLIST.md](CLOUD_PRIVACY_CHECKLIST.md) | Privacy and security launch checks |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Bug reports, documentation improvements, and contributions |

## Project status

P9 cloud capabilities include multi-user accounts, tenant-isolated resumes and job pools, AI matching, confirmed delivery tasks, plugin binding/capture/execution, quota accounting, and an ADMIN-only basic backend. Docker and CI checks are available, but P10 production launch validation is not complete.

See [README.md](README.md), [README_DOCKER.md](README_DOCKER.md), and the `CLOUD_*` documents for the cloud roadmap, deployment boundaries, backup plan, and security checklist.

## Contributing

Bug reports, documentation improvements, reproducible platform issues, and adapter contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

Never post real credentials, cookies, API keys, resumes, or personal account information in a public issue.

## License

This repository uses the custom **TOUDI NIUMA Non-Commercial License 1.0**.

Non-commercial use, copying, modification, and distribution are allowed when attribution and the license notice are retained. Commercial use, paid hosting, commercial product integration, or paid consulting requires authorization from the copyright owner. See [LICENSE](LICENSE) for the full terms.

## Disclaimer

This project is intended for personal job-search assistance, technical research, and learning. Users are responsible for complying with recruitment platform rules and applicable laws, and for all actions performed with their accounts and data.
