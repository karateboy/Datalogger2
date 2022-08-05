@echo off
if exist public\dist (
del /S /F /Q public\dist 
)

cd vuexy-starter-kit
call yarn build
cd ../public
mkdir dist
xcopy /E /I ..\..\vuexy-starter-kit\dist dist
cd ..
call sbt clean;dist
@echo on
