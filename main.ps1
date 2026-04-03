Write-Host "1) Client"
Write-Host "2) Admin App"
Write-Host "3) Server"
$choice = Read-Host "Select"

switch ($choice) {
    "1" { ./gradlew :client:run }
    "2" { ./gradlew :admin:run }
    "3" { ./gradlew :server:run }
    default { Write-Host "Invalid selection." }
}
