// The release-signing config plugin must rewrite the generated Gradle file exactly once and keep
// debug signing when no keystore is supplied.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { applyReleaseSigning } = require('../plugins/withReleaseSigning');

const gradle = `android {
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {
            // Caution!
            signingConfig signingConfigs.debug
            minifyEnabled enableMinifyInReleaseBuilds
        }
    }
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
        }
    }
}`;

describe('withReleaseSigning', () => {
  it('adds an env-driven release signing config and uses it only for release', () => {
    const out: string = applyReleaseSigning(gradle);
    expect(out).toContain('storeFile file(System.getenv("GC_RELEASE_STORE_FILE"))');
    expect(out).toContain('signingConfig System.getenv("GC_RELEASE_STORE_FILE") ? signingConfigs.release : signingConfigs.debug');
    // debug build type untouched
    expect(out).toMatch(/debug \{\n\s+signingConfig signingConfigs\.debug/);
    expect(out.match(/GC_RELEASE_STORE_FILE/g)).toHaveLength(3);
  });

  it('is idempotent', () => {
    const once = applyReleaseSigning(gradle);
    expect(applyReleaseSigning(once)).toBe(once);
  });

  it('contains no key material', () => {
    expect(applyReleaseSigning(gradle)).not.toMatch(/storePassword '[^']+'.*release/s);
  });
});
