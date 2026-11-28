cd /c/Users/oscar/Projects/hermes-client/HermesAndroid
timeout 1200 env JAVA_HOME="C:/Java/jdk21" ./gradlew assembleDebug --no-daemon -q > Temp/vb.log 2>&1
echo "gradle exit $?" >> Temp/vb.log
grep -E "^e: |What went wrong|gradle exit" Temp/vb.log | head -20 > Temp/vb.sum
ls -l app/build/outputs/apk/debug/app-debug.apk >> Temp/vb.sum
timeout 60 python -I -c "
import zipfile
z=zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk')
n=[x for x in z.namelist() if x.endswith('dev.oscar.hermes.ChatFeature')]
print(n); [print(z.read(x).decode()) for x in n]" >> Temp/vb.sum 2>&1
echo finished >> Temp/vb.sum
