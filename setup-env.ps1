
<#
    Team RAG Knowledge Base Project -- Environment Initialization Script
    
    Feature: Automatically download and configure project-level JDK 17 and Maven 3.9.6
    No system environment variables modification required. Toolchain is isolated within the project directory.
    
    Usage:
        .\setup-env.ps1
    
    After completion, use the following commands to build/run:
        .\mvnw.ps1 compile
        .\mvnw.ps1 spring-boot:run
    
    Supported JDK Download Sources (Auto fallback):
        1. Huawei Cloud Adoptium Mirror
        2. Tsinghua University Adoptium Mirror
        3. Adoptium Official API
    
    Supported Maven Download Sources (Auto fallback):
        1. Aliyun Maven Mirror
        2. Huawei Cloud Maven Mirror
        3. Apache Official CDN
#>

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# ======================== Config ========================
$JDK_MAJOR = "17"
$MAVEN_VERSION = "3.9.6"

$JDK_DIR = "$PSScriptRoot\.jdk\temurin-$JDK_MAJOR"
$MAVEN_DIR = "$PSScriptRoot\.maven\apache-maven-$MAVEN_VERSION"

# JDK Download URLs (Priority ordered)
$JDK_URLS = @(
    "https://repo.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip",
    "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip"
)

# Maven Download URLs (Priority ordered)
$MAVEN_URLS = @(
    "https://mirrors.aliyun.com/apache/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip",
    "https://repo.huaweicloud.com/apache/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip",
    "https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip"
)

# ======================== Utils ========================
function Write-Step($message) {
    Write-Host "`n===> $message" -ForegroundColor Cyan
}

function Write-Done($message) {
    Write-Host "  [OK] $message" -ForegroundColor Green
}

function Write-Warn($message) {
    Write-Host "  [WARN] $message" -ForegroundColor Yellow
}

# Try downloading from multiple mirrors, return true on first success
function Download-WithFallback {
    param(
        [string[]]$Urls,
        [string]$OutFile,
        [string]$Name
    )
    
    foreach ($url in $Urls) {
        try {
            Write-Host "  Trying: $url" -ForegroundColor Gray
            Invoke-WebRequest -Uri $url -OutFile $OutFile -UseBasicParsing -TimeoutSec 120
            Write-Done "$Name downloaded successfully"
            return $true
        }
        catch {
            Write-Warn "Download failed: $($_.Exception.Message)"
        }
    }
    
    Write-Host "  [ERROR] All mirrors failed!" -ForegroundColor Red
    Write-Host "  Please manually download $Name and extract to the correct directory." -ForegroundColor Red
    return $false
}

# ======================== JDK 17 Download ========================
if (Test-Path "$JDK_DIR\bin\java.exe") {
    Write-Done "JDK 17 already exists at $JDK_DIR, skipping download"
}
else {
    Write-Step "Downloading JDK $JDK_MAJOR ..."
    
    $jdkZip = "$env:TEMP\jdk$JDK_MAJOR-download.zip"
    $downloaded = Download-WithFallback -Urls $JDK_URLS -OutFile $jdkZip -Name "JDK $JDK_MAJOR"
    
    if ($downloaded) {
        Write-Step "Extracting JDK to $PSScriptRoot\.jdk\ ..."
        $extractPath = "$PSScriptRoot\.jdk\_extract"
        if (Test-Path $extractPath) { Remove-Item $extractPath -Recurse -Force }
        New-Item -ItemType Directory -Path "$PSScriptRoot\.jdk" -Force | Out-Null
        Expand-Archive $jdkZip -DestinationPath $extractPath -Force
        
        # Locate the actual JDK root dir (usually jdk-17.x.x or openjdk-17.x.x)
        $extractedDir = Get-ChildItem $extractPath -Directory | Select-Object -First 1
        if (Test-Path $JDK_DIR) { Remove-Item $JDK_DIR -Recurse -Force }
        Move-Item $extractedDir.FullName $JDK_DIR
        
        # Cleanup
        Remove-Item $extractPath -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
        
        Write-Done "JDK 17 installed: $JDK_DIR"
    }
    else {
        Write-Host "`n  Manual Installation Instructions:" -ForegroundColor Yellow
        Write-Host "    1. Download JDK 17 (Windows x64 zip) from: https://adoptium.net/temurin/releases/" -ForegroundColor Yellow
        Write-Host "    2. Extract to: $JDK_DIR" -ForegroundColor Yellow
        Write-Host "    3. Ensure $JDK_DIR\bin\java.exe exists" -ForegroundColor Yellow
    }
}

# ======================== Maven Download ========================
if (Test-Path "$MAVEN_DIR\bin\mvn.cmd") {
    Write-Done "Maven $MAVEN_VERSION already exists at $MAVEN_DIR, skipping download"
}
else {
    Write-Step "Downloading Apache Maven $MAVEN_VERSION ..."
    
    $mavenZip = "$env:TEMP\apache-maven-$MAVEN_VERSION-download.zip"
    $downloaded = Download-WithFallback -Urls $MAVEN_URLS -OutFile $mavenZip -Name "Maven $MAVEN_VERSION"
    
    if ($downloaded) {
        Write-Step "Extracting Maven to $PSScriptRoot\.maven\ ..."
        New-Item -ItemType Directory -Path "$PSScriptRoot\.maven" -Force | Out-Null
        Expand-Archive $mavenZip -DestinationPath "$PSScriptRoot\.maven" -Force
        
        # Cleanup
        Remove-Item $mavenZip -Force -ErrorAction SilentlyContinue
        
        Write-Done "Maven $MAVEN_VERSION installed: $MAVEN_DIR"
    }
    else {
        Write-Host "`n  Manual Installation Instructions:" -ForegroundColor Yellow
        Write-Host "    1. Download Maven $MAVEN_VERSION (Binary zip) from: https://maven.apache.org/download.cgi" -ForegroundColor Yellow
        Write-Host "    2. Extract to: $PSScriptRoot\.maven\" -ForegroundColor Yellow
        Write-Host "    3. Ensure $MAVEN_DIR\bin\mvn.cmd exists" -ForegroundColor Yellow
    }
}

# ======================== Configure Maven Aliyun Mirror ========================
$mavenSettingsDir = "$PSScriptRoot\.maven\settings"
$mavenSettingsFile = "$mavenSettingsDir\settings.xml"

if (-not (Test-Path $mavenSettingsFile)) {
    Write-Step "Configuring Maven Aliyun Mirror ..."
    New-Item -ItemType Directory -Path $mavenSettingsDir -Force | Out-Null
    
    @"
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0
                              https://maven.apache.org/xsd/settings-1.2.0.xsd">
    <mirrors>
        <mirror>
            <id>aliyun-central</id>
            <mirrorOf>central</mirrorOf>
            <name>Aliyun Maven Central Mirror</name>
            <url>https://maven.aliyun.com/repository/central</url>
        </mirror>
        <mirror>
            <id>aliyun-public</id>
            <mirrorOf>public</mirrorOf>
            <name>Aliyun Maven Public Repo</name>
            <url>https://maven.aliyun.com/repository/public</url>
        </mirror>
    </mirrors>
</settings>
"@ | Set-Content -Path $mavenSettingsFile -Encoding UTF8
    
    Write-Done "Maven settings.xml generated (Using Aliyun mirror for acceleration)"
}

# ======================== Verify Installation ========================
Write-Step "Verifying installation ..."

$allGood = $true

if (Test-Path "$JDK_DIR\bin\java.exe") {
    $env:JAVA_HOME = $JDK_DIR
    $env:Path = "$JDK_DIR\bin;$MAVEN_DIR\bin;$env:Path"
    
    Write-Host ""
    Write-Host "  JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Yellow
    & "$JDK_DIR\bin\java.exe" -version 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
}
else {
    Write-Host "  [SKIP] JDK not installed" -ForegroundColor Red
    $allGood = $false
}

Write-Host ""

if (Test-Path "$MAVEN_DIR\bin\mvn.cmd") {
    $env:Path = "$MAVEN_DIR\bin;$env:Path"
    & "$MAVEN_DIR\bin\mvn.cmd" --version 2>&1 | Select-Object -First 3 | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
}
else {
    Write-Host "  [SKIP] Maven not installed" -ForegroundColor Red
    $allGood = $false
}

# ======================== Finish ========================
Write-Host ""
if ($allGood) {
    Write-Host "=============================================" -ForegroundColor Green
    Write-Host "  Environment initialization complete!" -ForegroundColor Green
    Write-Host "" -ForegroundColor Green
    Write-Host "  Next steps use these commands:" -ForegroundColor Green
    Write-Host "    .\mvnw.ps1 compile          # Compile" -ForegroundColor Yellow
    Write-Host "    .\mvnw.ps1 spring-boot:run  # Run" -ForegroundColor Yellow
    Write-Host "    .\mvnw.ps1 clean package    # Package" -ForegroundColor Yellow
    Write-Host "=============================================" -ForegroundColor Green
}
else {
    Write-Host "=============================================" -ForegroundColor Yellow
    Write-Host "  Partial installation failed. Please see above for manual steps." -ForegroundColor Yellow
    Write-Host "  Re-run this script after manual installation." -ForegroundColor Yellow
    Write-Host "=============================================" -ForegroundColor Yellow
}