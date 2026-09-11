# Удаляет прерванные OTA-файлы при загрузке (не вторая копия прошивки)
import os
for _n in ('main.tmp', 'update.py', 'update.tmp', 'backup.py'):
    try:
        os.remove(_n)
    except OSError:
        pass
