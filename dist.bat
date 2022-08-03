del /S /F /Q public\dist 
cd vuexy-starter-kit
yarn build
cd ..
copy vuexy-starter-kit/disk public
sbt clean;dist

