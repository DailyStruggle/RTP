$path = 'docs\dev\TRACEABILITY.md'
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$old = @'
traversal errors) | `FailureModeTest`
'@
$new = @'
traversal errors), `LiveCommandDispatcherTestJob` (WARN on any Throwable escaping `Bukkit.dispatchCommand` during malformed-input dispatch; WARN on any malformed input that produces neither a sender message nor a WARN log record) | `FailureModeTest`
'@
if ($content.Contains($old)) {
    $content = $content.Replace($old, $new)
    [System.IO.File]::WriteAllText($path, $content, [System.Text.Encoding]::UTF8)
    Write-Output "OK"
} else {
    Write-Output "ANCHOR_NOT_FOUND"
}
