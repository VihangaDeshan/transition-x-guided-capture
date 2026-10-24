// SPDX-License-Identifier: Apache-2.0
// Config plugin: sign release builds with a real keystore when one is supplied through
// environment variables (CI secrets), and fall back to the debug key otherwise. No key material
// is ever committed. Variables: GC_RELEASE_STORE_FILE, GC_RELEASE_STORE_PASSWORD,
// GC_RELEASE_KEY_ALIAS, GC_RELEASE_KEY_PASSWORD.
const { withAppBuildGradle } = require('expo/config-plugins');

const MARKER = 'GC_RELEASE_STORE_FILE';

function applyReleaseSigning(gradle) {
  if (gradle.includes(MARKER)) return gradle;
  const signingBlock = /signingConfigs\s*\{/;
  if (!signingBlock.test(gradle)) throw new Error('withReleaseSigning: signingConfigs block not found');
  let out = gradle.replace(
    signingBlock,
    `signingConfigs {
        release {
            if (System.getenv("${MARKER}")) {
                storeFile file(System.getenv("${MARKER}"))
                storePassword System.getenv("GC_RELEASE_STORE_PASSWORD")
                keyAlias System.getenv("GC_RELEASE_KEY_ALIAS")
                keyPassword System.getenv("GC_RELEASE_KEY_PASSWORD")
            }
        }`,
  );
  // Inside buildTypes { release { ... } }, the first debug signing reference.
  const releaseType = /(buildTypes\s*\{[\s\S]*?release\s*\{[\s\S]*?)signingConfig signingConfigs\.debug/;
  if (!releaseType.test(out)) throw new Error('withReleaseSigning: release buildType signing not found');
  out = out.replace(
    releaseType,
    `$1signingConfig System.getenv("${MARKER}") ? signingConfigs.release : signingConfigs.debug`,
  );
  return out;
}

const withReleaseSigning = (config) =>
  withAppBuildGradle(config, (cfg) => {
    cfg.modResults.contents = applyReleaseSigning(cfg.modResults.contents);
    return cfg;
  });

module.exports = withReleaseSigning;
module.exports.applyReleaseSigning = applyReleaseSigning;
