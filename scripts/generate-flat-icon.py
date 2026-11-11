"""Generate the editable flat-brick icon and Android notification silhouette."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def brick(x, y, width, height, count=4, stud=12, rise=6, radius=1.5):
    top = y + rise
    pitch = width / count
    commands = [f'M{x},{top}']
    for n in range(count):
        left = x + pitch * (n + .5) - stud / 2
        commands += [f'H{left}', f'V{y+radius}', f'Q{left},{y} {left+radius},{y}',
                     f'H{left+stud-radius}', f'Q{left+stud},{y} {left+stud},{y+radius}', f'V{top}']
    return ' '.join(commands + [f'H{x+width}', f'V{y+height}', f'H{x}', 'Z'])


bottom = brick(28, 57, 92, 49)
top = brick(8, 14, 92, 49)
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="1024" height="1024">
<title>WhenThen flat interlocking bricks</title>
<path d="{bottom}" fill="#B99EE7" stroke="#000" stroke-width="1.5"/>
<path d="{top}" fill="#FFC000" stroke="#000" stroke-width="1.5"/>
</svg>
'''
art = ROOT / 'docs/art'
art.mkdir(parents=True, exist_ok=True)
(art / 'whenthen-flat-icon.svg').write_text(svg, encoding='utf-8')
(ROOT / 'app/src/main/res/drawable/ic_status.xml').write_text(f'''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="128" android:viewportHeight="128">
    <path android:fillColor="#FFFFFFFF" android:pathData="{bottom} {top}"/>
</vector>
''', encoding='utf-8')
print('Generated flat icon SVG and notification vector.')
