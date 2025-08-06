#!/bin/bash

# This script generates the necessary keystore and truststore for the chat application
# Run this script before starting the server and clients

# Configuration variables
KEYSTORE_FILE="chatserver.jks"
TRUSTSTORE_FILE="chatclient.jks"
KEYSTORE_PASSWORD="password"
TRUSTSTORE_PASSWORD="password"
CERT_FILE="server.cer"
KEY_ALIAS="chatserver"
VALIDITY_DAYS=365
DNAME="CN=Chat Server,OU=Education,O=University,L=City,ST=State,C=Country"

echo "Generating SSL keystore and truststore for chat application..."

# Remove existing files if they exist
if [ -f "$KEYSTORE_FILE" ]; then
    rm "$KEYSTORE_FILE"
    echo "Removed existing keystore file."
fi

if [ -f "$TRUSTSTORE_FILE" ]; then
    rm "$TRUSTSTORE_FILE"
    echo "Removed existing truststore file."
fi

if [ -f "$CERT_FILE" ]; then
    rm "$CERT_FILE"
    echo "Removed existing certificate file."
fi

# Step 1: Generate the keystore with a private key
echo "Generating keystore with private key..."
keytool -genkeypair -alias $KEY_ALIAS -keyalg RSA -keysize 2048 \
        -validity $VALIDITY_DAYS -keystore $KEYSTORE_FILE \
        -storepass $KEYSTORE_PASSWORD -keypass $KEYSTORE_PASSWORD \
        -dname "$DNAME"

# Step 2: Export the server's public certificate
echo "Exporting server's public certificate..."
keytool -exportcert -alias $KEY_ALIAS -file $CERT_FILE \
        -keystore $KEYSTORE_FILE -storepass $KEYSTORE_PASSWORD

# Step 3: Import the server's certificate into the truststore
echo "Creating truststore and importing server's certificate..."
keytool -importcert -alias $KEY_ALIAS -file $CERT_FILE \
        -keystore $TRUSTSTORE_FILE -storepass $TRUSTSTORE_PASSWORD \
        -noprompt

echo "SSL setup completed successfully!"
echo "Server keystore: $KEYSTORE_FILE"
echo "Client truststore: $TRUSTSTORE_FILE"
echo ""
echo "To start the server: java -Djavax.net.ssl.keyStore=$KEYSTORE_FILE -Djavax.net.ssl.keyStorePassword=$KEYSTORE_PASSWORD ChatServer"
echo "To start the client: java -Djavax.net.ssl.trustStore=$TRUSTSTORE_FILE -Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASSWORD ChatClient"