# Security Policy

This repository contains the **device-side** components of [OpenMDM](https://openmdm.dev): the `:agent` app and the embeddable `:library` SDK for Android. For **server-side** security issues (dashboard, API, `@openmdm/*` packages), please report to the main repository instead: [github.com/azoila/openmdm](https://github.com/azoila/openmdm).

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.2.x   | :white_check_mark: |
| < 0.2   | :x:                |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, use one of these private channels:

1. **GitHub private vulnerability reporting** (preferred): use the ["Report a vulnerability"](https://github.com/azoila/openmdm-android/security/advisories/new) button on this repository's Security tab.
2. **Email**: [security@openmdm.dev](mailto:security@openmdm.dev) (same contact as the main OpenMDM repository).

Please include:

- A description of the vulnerability and its potential impact
- Steps to reproduce (proof of concept if possible)
- Affected module (`:agent`, `:library`) and version
- Any suggested mitigation

## Response Process

- **Acknowledgment**: within **48 hours** of your report
- **Initial assessment**: within **7 days**, including severity and planned next steps
- **Fix & disclosure**: we follow **coordinated disclosure** — we will work with you on a fix and agree on a disclosure timeline before any public announcement. Please keep the issue confidential until a patched release is available.

We will credit reporters in the release notes unless you prefer to remain anonymous.

## Scope Notes

Vulnerabilities of particular interest for this repository include: enrollment signature bypasses, device-policy escalation, insecure storage of tokens/secrets, and kiosk/lock-task escapes.
