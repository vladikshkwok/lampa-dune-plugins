# Third-party components

The standalone `dune-voice/` applications and shared source in this directory
are licensed under Apache-2.0 (see LICENSE). The existing Lampa/PHP plugins
outside this directory retain their own GPL-2.0 license.

The phone APK bundles these unmodified public components:

- Vosk Android 0.3.75, Alpha Cephei Inc., Apache-2.0.
  Source: https://github.com/alphacep/vosk-api
- Java Native Access 5.18.1, JNA contributors. Used under its Apache-2.0 option.
  Source: https://github.com/java-native-access/jna
- vosk-model-small-ru-0.22, Alpha Cephei, Apache-2.0.
  Source: https://alphacephei.com/vosk/models

Exact archive URLs and SHA-256 hashes are recorded in dependencies.json.
The phone APK includes this notice and the Apache-2.0 license in assets.
The original JNA license notice is preserved in JNA-LICENSE.txt.
No cloud account, subscription or API key is bundled or required.
