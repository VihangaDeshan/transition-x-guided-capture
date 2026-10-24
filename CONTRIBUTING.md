# Contributing

- Licence: Apache-2.0. New files start with `// SPDX-License-Identifier: Apache-2.0`.
- Dependencies: permissive licences only (Apache-2.0, MIT, BSD). CI fails otherwise.
- Translations / wording: edit `src/i18n/strings.ts` only — no Android toolchain needed. Every rule
  needs a `short` text in every language (a test enforces it).
- Thresholds: edit `config/thresholds.v1.json`, bump `version`. Each manifest records the version used.
- Contracts, threshold keys and the export format change only by agreement (they affect several
  modules, and the export format affects Rysera). Record export changes in `docs/EXPORT_FORMAT.md`.
- Before a PR: `npm run check` and `gradle -p tools/engine-tests test`.
