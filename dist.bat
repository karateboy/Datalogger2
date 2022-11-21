@echo off
if exist public\dist (
del /S /F /Q public\dist 
)

if exist public\tt (
del /S /F /Q public\tt
)

cd vuexy-starter-kit
call yarn build
cd ../public
mkdir dist
cd dist
xcopy /E /I ..\..\vuexy-starter-kit\dist
cd ../..
cd tt-front
call yarn build
cd ../public
mkdir tt
cd tt
xcopy /E /I ..\..\tt-front\dist
cd ../..
call sbt clean;dist
@echo on
