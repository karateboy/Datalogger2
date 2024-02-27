@echo off
if exist public\dist (
del /S /F /Q public\dist 
)

cd vuexy-starter-kit
call yarn build
cd ../public
mkdir dist
cd dist
xcopy /E /I ..\..\vuexy-starter-kit\dist
cd ../..
call sbt clean;dist
@echo on
