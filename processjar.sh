#!/bin/bash

ALIAS=iridium
PASSWORD=password

./gradlew clean shadowJar --refresh-dependencies
mv build/libs/WebAppTesting.jar build/libs/webapptesting-signed.jar
pack200 --repack build/libs/webapptesting-signed.jar
keytool -genkey -noprompt -dname "CN=Auto and General, OU=Online Systems, O=Auto and General, L=Brisbane, S=Queensland, C=AU" -alias ${ALIAS} -keyalg RSA -keystore keystore -keysize 2048 -storepass ${PASSWORD} -keypass ${PASSWORD} -validity 3650
jarsigner -keystore keystore -storepass password -keypass ${PASSWORD} build/libs/webapptesting-signed.jar ${ALIAS}
pack200 build/libs/webapptesting-signed.jar.pack.gz build/libs/webapptesting-signed.jar
rm keystore
