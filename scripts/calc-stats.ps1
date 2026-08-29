$exts = @('.java', '.ts', '.html', '.css', '.sql', '.xml', '.yml', '.yaml', '.md', '.json')
$results = @()
foreach ($ext in $exts) {
    $matched = Get-ChildItem -Path . -Recurse -File | Where-Object { 
        $_.Extension -eq $ext -and 
        $_.FullName -notmatch '\\(\.git|node_modules|dist|target|\.angular)\\' 
    }
    $fileCount = $matched.Count
    $lineCount = 0
    if ($fileCount -gt 0) {
        $lineCount = ($matched | Get-Content | Measure-Object -Line).Lines
    }
    $results += [PSCustomObject]@{
        Extension = $ext
        Files = $fileCount
        Lines = $lineCount
    }
}
$results | Sort-Object Lines -Descending | Format-Table -AutoSize
