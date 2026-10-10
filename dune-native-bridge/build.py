"""Build a portable native Dune plugin; excludes local configuration/credentials."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

root = Path(__file__).resolve().parent
output = root.parent / 'dist' / 'dune_plugin_lampa_dune_bridge.zip'
output.parent.mkdir(exist_ok=True)
with ZipFile(output, 'w', ZIP_DEFLATED) as archive:
    for name in ['dune_plugin.xml', 'icon.png', 'main.php', 'bridge.php', 'www/launch.m3u', 'www/cgi-bin/api', 'www/cgi-bin/api.php']:
        entry = ZipInfo(name, (2026, 10, 10, 0, 0, 0))
        entry.compress_type = ZIP_DEFLATED
        entry.external_attr = (0o100755 if name == 'www/cgi-bin/api' else 0o100644) << 16
        archive.writestr(entry, (root / name).read_bytes())
print(output)
kit = output.parent / 'lampa-dune-kit.zip'
with ZipFile(kit, 'w', ZIP_DEFLATED) as archive:
    for name in ['dist/dune_plugin_lampa_dune_bridge.zip', 'plugins/dune-local-progress.js',
                 'plugins/dune-native-player.js', 'README.md', 'LICENSE']:
        entry = ZipInfo(name, (2026, 10, 10, 0, 0, 0))
        entry.compress_type = ZIP_DEFLATED; entry.external_attr = 0o100644 << 16
        archive.writestr(entry, (root.parent / name).read_bytes())
print(kit)
