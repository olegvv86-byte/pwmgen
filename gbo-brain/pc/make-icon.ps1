# Рисует app.ico из того же образа, что и иконка Android-приложения:
# тёмная сетка приборной панели и жёлтая сегментная «G».
#
# Векторы Android (ic_launcher_background.xml, ic_launcher_foreground.xml)
# заданы в поле 108x108. Лаунчер показывает центральные 72 единицы, остальное
# обрезает, поэтому здесь берём тот же кусок — иначе на компьютере буква
# выглядела бы мельче, чем на телефоне.

Add-Type -AssemblyName System.Drawing

$sizes = 16, 24, 32, 48, 64, 128, 256
$pngs = @()

foreach ($size in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

    # показываем центральные 72 единицы поля 108x108
    $k = $size / 72.0
    $g.ScaleTransform($k, $k)
    $g.TranslateTransform(-18.0, -18.0)

    # ── фон: диагональный градиент ──
    $rect = New-Object System.Drawing.RectangleF(0, 0, 108, 108)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF(0, 0)),
        (New-Object System.Drawing.PointF(108, 108)),
        [System.Drawing.Color]::FromArgb(255, 12, 27, 51),
        [System.Drawing.Color]::FromArgb(255, 2, 4, 10))
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend(3)
    $blend.Colors = @(
        [System.Drawing.Color]::FromArgb(255, 12, 27, 51),
        [System.Drawing.Color]::FromArgb(255, 6, 12, 26),
        [System.Drawing.Color]::FromArgb(255, 2, 4, 10))
    $blend.Positions = @(0.0, 0.55, 1.0)
    $brush.InterpolationColors = $blend
    $g.FillRectangle($brush, $rect)
    $brush.Dispose()

    # ── сетка ──
    $penGrid = New-Object System.Drawing.Pen(
        [System.Drawing.Color]::FromArgb(255, 21, 38, 63), 0.9)
    foreach ($v in 18, 36, 54, 72, 90) {
        $g.DrawLine($penGrid, [float]$v, 0.0, [float]$v, 108.0)
        $g.DrawLine($penGrid, 0.0, [float]$v, 108.0, [float]$v)
    }
    $penGrid.Dispose()

    # ── центральные оси чуть ярче ──
    $penAxis = New-Object System.Drawing.Pen(
        [System.Drawing.Color]::FromArgb(255, 30, 52, 89), 1.4)
    $g.DrawLine($penAxis, 54.0, 0.0, 54.0, 108.0)
    $g.DrawLine($penAxis, 0.0, 54.0, 108.0, 54.0)
    $penAxis.Dispose()

    # ── буква G сегментным шрифтом ──
    # те же отрезки, что в ic_launcher_foreground.xml
    $segments = @(
        @(38.0, 30.0, 70.0, 30.0),   # верх
        @(36.0, 32.0, 36.0, 52.0),   # левый верх
        @(36.0, 56.0, 36.0, 76.0),   # левый низ
        @(38.0, 78.0, 70.0, 78.0),   # низ
        @(72.0, 56.0, 72.0, 76.0),   # правый низ
        @(56.0, 54.0, 72.0, 54.0)    # средний, правая половина
    )

    # свечение под буквой — мелкие размеры от него плывут, поэтому только крупные
    if ($size -ge 48) {
        $penGlow = New-Object System.Drawing.Pen(
            [System.Drawing.Color]::FromArgb(51, 255, 195, 0), 15.0)
        $penGlow.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $penGlow.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        foreach ($s in $segments) { $g.DrawLine($penGlow, $s[0], $s[1], $s[2], $s[3]) }
        $penGlow.Dispose()
    }

    $penG = New-Object System.Drawing.Pen(
        [System.Drawing.Color]::FromArgb(255, 255, 195, 0), 7.0)
    $penG.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $penG.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    foreach ($s in $segments) { $g.DrawLine($penG, $s[0], $s[1], $s[2], $s[3]) }
    $penG.Dispose()

    $g.Dispose()

    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += , $ms.ToArray()
    $ms.Dispose()
    $bmp.Dispose()
}

# ── склеиваем ICO ──
# Формат: заголовок 6 байт, по 16 байт на размер, дальше сами PNG.
# Windows Vista и новее понимают PNG внутри ICO, так что сжатие бесплатное.
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($out)

$w.Write([UInt16]0)               # зарезервировано
$w.Write([UInt16]1)               # тип: иконка
$w.Write([UInt16]$sizes.Count)

$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]
    $w.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))   # ширина, 0 значит 256
    $w.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))   # высота
    $w.Write([Byte]0)             # цветов в палитре
    $w.Write([Byte]0)             # зарезервировано
    $w.Write([UInt16]1)           # плоскостей
    $w.Write([UInt16]32)          # бит на точку
    $w.Write([UInt32]$pngs[$i].Length)
    $w.Write([UInt32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $w.Write($p) }

$w.Flush()
$path = Join-Path $PSScriptRoot "app.ico"
[System.IO.File]::WriteAllBytes($path, $out.ToArray())
$w.Dispose()
$out.Dispose()

"{0} — {1:N0} байт, размеры: {2}" -f $path, (Get-Item $path).Length, ($sizes -join ", ")
