<#
    Build-KNN.ps1
    -----------------------------
    • Compila le classi Java essenziali (KD‑Tree, scaler, builder, driver)
    • Genera i KD‑Tree segmentati con SegmentKDBuilder (nessun scr.jar richiesto)
#>

param(
    [ValidateSet("basic","sensors","all")]
    [string]$Config      = "sensors",                 # default feature set

    [string]$DatasetPath = "classes/dataset_union.csv",
    [int]   $Segments    = 35                          # KD‑Tree da creare
)

Write-Host "`n=== BUILD KNN SEGMENTED MODEL ===" -ForegroundColor Green
Write-Host "Configurazione feature: $Config" -ForegroundColor Yellow
Write-Host "Dataset           : $DatasetPath" -ForegroundColor Yellow
Write-Host "Segmenti          : $Segments" -ForegroundColor Yellow
Write-Host "(nessun scr.jar necessario)" -ForegroundColor Yellow
Write-Host ""

# ─── STEP 1: Compilazione classi core ────────────────────────────────
Write-Host "Step 1: Compilazione classi core..." -ForegroundColor Cyan
javac -d classes `
      src/scr/ai/DataPoint.java `
      src/scr/ai/FeatureScaler.java `
      src/scr/ai/KDTree.java `
      src/scr/ai/DatasetBuilder.java `
      src/scr/ai/SegmentKDBuilder.java
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRORE: compilazione core fallita" -ForegroundColor Red; exit 1
}

# ─── STEP 2: Genera KD‑Tree segmentati ───────────────────────────────
Write-Host "Step 2: Creazione KD‑Tree segmentati..." -ForegroundColor Cyan
Push-Location classes
try {
    java scr.ai.SegmentKDBuilder "../$DatasetPath" $Segments
    $ret = $LASTEXITCODE
} finally { Pop-Location }
if ($ret -ne 0) { Write-Host "ERRORE: builder fallito" -ForegroundColor Red; exit 1 }

# ─── STEP 3: Compilazione driver ─────────────────────────────────────
Write-Host "Step 3: Compilazione KNNDriver..." -ForegroundColor Cyan
javac -d classes -cp classes `
      src/scr/ai/KNNDriver.java `
      src/scr/ai/SimpleGear.java `
      src/scr/ai/ActionCache.java 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRORE: driver fallito (ignora se manca ActionCache)" -ForegroundColor Red; exit 1
}

# ─── Output finale ───────────────────────────────────────────────────
Write-Host "`n=== BUILD COMPLETATO ===" -ForegroundColor Green
Write-Host ("Generati: knn_seg_00.tree … knn_seg_{0:D2}.tree" -f ($Segments-1)) -ForegroundColor Yellow
Write-Host ""
Write-Host "Per testare il driver:" -ForegroundColor Cyan
Write-Host -ForegroundColor White "  cd classes"
Write-Host -ForegroundColor White "  java -cp . scr.Client scr.ai.KNNDriver localhost:3001 verbose:on"
Write-Host ""
