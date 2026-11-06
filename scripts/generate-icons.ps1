param(
    [string]$SourcePath = (Join-Path $PSScriptRoot "..\resources\OCR.png")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$source = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $SourcePath))
$resRoot = Join-Path $PSScriptRoot "..\android\app\src\main\res"
$legacySizes = @{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }
$foregroundSizes = @{ mdpi = 108; hdpi = 162; xhdpi = 216; xxhdpi = 324; xxxhdpi = 432 }

function Save-LauncherBitmap {
    param(
        [int]$CanvasSize,
        [double]$ContentRatio,
        [string]$Path,
        [bool]$OpaqueBackground
    )

    $bitmap = New-Object System.Drawing.Bitmap($CanvasSize, $CanvasSize)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        if ($OpaqueBackground) {
            $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml("#F3F5F7"))
        } else {
            $graphics.Clear([System.Drawing.Color]::Transparent)
        }

        $contentSize = [int][Math]::Round($CanvasSize * $ContentRatio)
        $offset = [int][Math]::Round(($CanvasSize - $contentSize) / 2)
        $graphics.DrawImage($source, $offset, $offset, $contentSize, $contentSize)
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

try {
    foreach ($density in $legacySizes.Keys) {
        $directory = Join-Path $resRoot "mipmap-$density"
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        Save-LauncherBitmap $legacySizes[$density] 0.84 (Join-Path $directory "ic_launcher.png") $true
        Save-LauncherBitmap $legacySizes[$density] 0.84 (Join-Path $directory "ic_launcher_round.png") $true
        Save-LauncherBitmap $foregroundSizes[$density] 0.66 (Join-Path $directory "ic_launcher_foreground.png") $false
    }
} finally {
    $source.Dispose()
}
