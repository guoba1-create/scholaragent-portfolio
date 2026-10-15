<#
    项目级 Maven 包装器 —— 使用项目本地的 JDK 17 和 Maven
    
    用法（替代全局 mvn 命令）：
        .\mvnw.ps1 compile
        .\mvnw.ps1 spring-boot:run
        .\mvnw.ps1 clean package
        .\mvnw.ps1 test
    
    首次使用前，请先运行 .\setup-env.ps1 下载 JDK 和 Maven
#>

$ErrorActionPreference = "Stop"

# ======================== 路径配置 ========================
$JDK_DIR = "$PSScriptRoot\.jdk\temurin-17"
$MAVEN_DIR = "$PSScriptRoot\.maven\apache-maven-3.9.6"

# ======================== 环境检查 ========================
if (-not (Test-Path "$JDK_DIR\bin\java.exe")) {
    Write-Host "[ERROR] 未找到项目级 JDK 17，请先运行: .\setup-env.ps1" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path "$MAVEN_DIR\bin\mvn.cmd")) {
    Write-Host "[ERROR] 未找到项目级 Maven，请先运行: .\setup-env.ps1" -ForegroundColor Red
    exit 1
}

# ======================== 设置项目级环境变量 ========================
# 仅在当前进程中设置，不影响系统环境
$env:JAVA_HOME = $JDK_DIR
$env:Path = "$JDK_DIR\bin;$MAVEN_DIR\bin;$env:Path"

$SETTINGS_FILE = "$PSScriptRoot\.maven\settings\settings.xml"

Write-Host "[mvnw] JAVA_HOME = $env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "[mvnw] 执行: mvn $($args -join ' ')" -ForegroundColor DarkGray
Write-Host "" 

# ======================== 执行 Maven 命令 ========================
# 使用项目级 settings.xml（包含阿里云镜像加速）
if (Test-Path $SETTINGS_FILE) {
    & "$MAVEN_DIR\bin\mvn.cmd" --settings $SETTINGS_FILE @args
}
else {
    & "$MAVEN_DIR\bin\mvn.cmd" @args
}
exit $LASTEXITCODE
