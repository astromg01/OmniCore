# Development signing only

`omnicore-dev.keystore.b64` is intentionally a **public development-only** signing key used by GitHub Actions so sideloaded OmniCore DEV builds (0.6.0+) keep the same Android signing identity and can be installed as updates over earlier DEV builds.

It provides **no production security** because the private key material is public. Never use this key for Google Play, production releases, private distribution, or any build that users should trust as publisher-authenticated.

Before a production/Play release, OmniCore must switch to a private production upload/signing key (preferably with Google Play App Signing) and keep that private key outside this repository.
