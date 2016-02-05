#!/bin/bash

#
# This script is only intended to run in the IBM DevOps Services Pipeline Environment.
#

#!/bin/bash
echo Informing slack...
curl -X 'POST' --silent --data-binary '{"text":"A new build for the room service has started."}' $WEBHOOK > /dev/null

echo Setting up Docker...
mkdir dockercfg ; cd dockercfg
echo -e $KEY > key.pem
echo -e $CA_CERT > ca.pem
echo -e $CERT > cert.pem
cd ..
	 
echo Building projects using gradle...
./gradlew build

echo Building and Starting Room Docker Image...
cd room-wlpcfg
../gradlew buildDockerImage
../gradlew stopCurrentContainer
../gradlew removeCurrentContainer
../gradlew startNewEtcdContainer

cd ..
rm -rf dockercfg