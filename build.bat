@echo off
echo Building MindrayCL1000i application...

REM Create output directory
if not exist "target\classes" mkdir "target\classes"

REM Note: This is a basic compilation check
REM In production, use Maven with proper dependency management
echo.
echo Note: This project requires HAPI HL7 libraries which need to be downloaded separately.
echo Please use Maven for proper dependency management: mvn clean compile
echo.
echo Checking Java syntax...

REM Basic syntax check (will fail due to missing dependencies, but shows syntax errors)
javac -d target\classes -cp "src\main\java" src\main\java\org\carecode\mw\lims\mw\indiko\*.java 2>compile_errors.txt

if %ERRORLEVEL% neq 0 (
    echo Compilation failed. Check compile_errors.txt for details.
    echo Note: Missing dependencies expected - use Maven to resolve them.
    type compile_errors.txt
) else (
    echo Java syntax check passed!
)

pause