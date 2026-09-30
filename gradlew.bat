@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem Setup the command line

@rem Transparent concurrency serialization: delegate through PowerShell mutex if not already holding lock
if not "%RTP_GRADLE_LOCKED%"=="1" (
    set RTP_GRADLE_LOCKED=1
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$rawArgs = @($args); $targets = @($rawArgs | Where-Object { $_ -match '^:[a-zA-Z0-9_\-\.:]+' -and $_ -notmatch '^-' }); $topModules = @(); foreach ($t in $targets) { $parts = $t.TrimStart(':').Split(':'); if ($parts.Length -gt 1) { $topModules += $parts[0] } else { $topModules += '__ROOT__' } }; $distinctModules = @($topModules | Select-Object -Unique); $hasNonModuleTasks = @($rawArgs | Where-Object { $_ -notmatch '^-' -and $_ -notmatch '^:' }).Count -gt 0; if ($distinctModules.Count -eq 1 -and -not $hasNonModuleTasks -and $distinctModules[0] -ne '__ROOT__') { $mod = $distinctModules[0]; $globalMutex = [System.Threading.Mutex]::new($false, 'Global\RTP_Gradle_Build_Mutex'); $modMutex = [System.Threading.Mutex]::new($false, ('Global\RTP_Gradle_Lock_' + $mod)); $hasGlobal = $false; $hasMod = $false; try { $waited = 0; while (-not $hasMod) { try { $hasGlobal = $globalMutex.WaitOne(500) } catch [System.Threading.AbandonedMutexException] { $hasGlobal = $true }; if ($hasGlobal) { try { $hasMod = $modMutex.WaitOne(4500) } catch [System.Threading.AbandonedMutexException] { $hasMod = $true }; try { $globalMutex.ReleaseMutex() } catch {}; $hasGlobal = $false }; if ($hasMod) { break }; $waited += 5; [Console]::Out.WriteLine('[gradlew] Waiting for module lock :' + $mod + '... (' + $waited + 's)'); if ($waited -ge 600) { Write-Error ('[gradlew] Timed out waiting for module lock :' + $mod); exit 1 } }; & cmd.exe /c \"\"%~f0\" %*\"; exit $LASTEXITCODE } finally { if ($hasGlobal) { try { $globalMutex.ReleaseMutex() } catch {} }; if ($hasMod) { try { $modMutex.ReleaseMutex() } catch {} }; $globalMutex.Dispose(); $modMutex.Dispose() } } else { $mutex = [System.Threading.Mutex]::new($false, 'Global\RTP_Gradle_Build_Mutex'); $hasLock = $false; try { $waited = 0; while (-not $hasLock) { try { $hasLock = $mutex.WaitOne(5000) } catch [System.Threading.AbandonedMutexException] { $hasLock = $true }; if ($hasLock) { break }; $waited += 5; [Console]::Out.WriteLine('[gradlew] Waiting for global build lock... (' + $waited + 's)'); if ($waited -ge 600) { Write-Error '[gradlew] Timed out waiting for Gradle build lock.'; exit 1 } }; & cmd.exe /c \"\"%~f0\" %*\"; exit $LASTEXITCODE } finally { if ($hasLock) { try { $mutex.ReleaseMutex() } catch {} }; $mutex.Dispose() } }" %*
    goto exitWithErrorLevel
)

@rem Execute Gradle
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
