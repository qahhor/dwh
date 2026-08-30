function Get-DotEnvValue {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Key
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }

    $pattern = '^\s*' + [regex]::Escape($Key) + '\s*=\s*(.*)$'
    $match = Get-Content -LiteralPath $Path |
        Select-String -Pattern $pattern |
        Select-Object -First 1
    if (-not $match) { return $null }

    $raw = $match.Matches[0].Groups[1].Value.Trim()
    if ($raw.Length -eq 0) { return "" }

    if ($raw[0] -eq '"' -or $raw[0] -eq "'") {
        $quote = $raw[0]
        $closingIndex = -1
        for ($index = 1; $index -lt $raw.Length; $index++) {
            if ($raw[$index] -eq $quote -and $raw[$index - 1] -ne [char]92) {
                $closingIndex = $index
                break
            }
        }
        if ($closingIndex -lt 0) { throw "Unterminated quoted dotenv value for $Key" }

        $tail = $raw.Substring($closingIndex + 1).Trim()
        if ($tail -and -not $tail.StartsWith('#')) {
            throw "Unexpected text after quoted dotenv value for $Key"
        }
        return $raw.Substring(1, $closingIndex - 1)
    }

    $comment = [regex]::Match($raw, '\s+#')
    if ($comment.Success) { $raw = $raw.Substring(0, $comment.Index) }
    return $raw.Trim()
}
