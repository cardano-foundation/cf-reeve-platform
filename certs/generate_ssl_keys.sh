#!/bin/bash
# Create directories for SSL certificate generation
mkdir -p ssl
cd ssl

# Generate a CA key and certificate
openssl req -new -x509 -keyout ca-key -out ca-cert -days 365 -subj "/CN=Kafka-CA" -passout pass:password -passin pass:password

# Generate Kafka broker keystore
keytool -keystore kafka.broker.keystore.jks -alias localhost -validity 365 -genkey -keyalg RSA \
  -storepass password -keypass password -dname "CN=localhost" -ext SAN=DNS:localhost,DNS:kafka,IP:127.0.0.1

# Import CA certificate to broker keystore
keytool -keystore kafka.broker.keystore.jks -alias CARoot -import -file ca-cert -storepass password -noprompt

# Create CSR for broker
keytool -keystore kafka.broker.keystore.jks -alias localhost -certreq -file broker.csr -storepass password

# Sign broker certificate with CA
openssl x509 -req -CA ca-cert -CAkey ca-key -in broker.csr -out broker.crt -days 365 -CAcreateserial -passin pass:password

# Import signed broker certificate to broker keystore
# First, import the CA certificate again (if not already imported)
# Then import the signed certificate
keytool -keystore kafka.broker.keystore.jks -alias CARoot -import -file ca-cert -storepass password -noprompt 2>/dev/null || echo "CA cert already in keystore"
keytool -keystore kafka.broker.keystore.jks -alias localhost -import -file broker.crt -storepass password -noprompt

# Create truststore for Kafka broker and client
keytool -keystore kafka.broker.truststore.jks -alias CARoot -import -file ca-cert -storepass password -noprompt
keytool -keystore kafka.client.truststore.jks -alias CARoot -import -file ca-cert -storepass password -noprompt

# Generate client keystore
keytool -keystore kafka.client.keystore.jks -alias client -validity 365 -genkey -keyalg RSA \
  -storepass password -keypass password -dname "CN=client" -ext SAN=DNS:client

# Create CSR for client
keytool -keystore kafka.client.keystore.jks -alias client -certreq -file client.csr -storepass password

# Sign client certificate with CA
openssl x509 -req -CA ca-cert -CAkey ca-key -in client.csr -out client.crt -days 365 -CAcreateserial -passin pass:password

# Import signed client certificate to client keystore
# First, import the CA certificate
# Then import the signed certificate
keytool -keystore kafka.client.keystore.jks -alias CARoot -import -file ca-cert -storepass password -noprompt
keytool -keystore kafka.client.keystore.jks -alias client -import -file client.crt -storepass password -noprompt

echo "All certificates generated successfully!"
cd ..