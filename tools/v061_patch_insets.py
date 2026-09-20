from pathlib import Path

root = Path('app/src/main/java/com/forgemanager/app/features')
changed = []
for path in root.rglob('*.kt'):
    text = path.read_text()
    if ': Activity()' not in text:
        continue
    if 'com.forgemanager.app.core.ui.ForgeActivity' not in text:
        marker = text.find('\n', text.find('package '))
        text = text[:marker + 1] + '\nimport com.forgemanager.app.core.ui.ForgeActivity\n' + text[marker + 1:]
    text = text.replace(': Activity()', ': ForgeActivity()')
    path.write_text(text)
    changed.append(str(path))
print('Migrated to ForgeActivity:')
print('\n'.join(changed))
