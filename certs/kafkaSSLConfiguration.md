### How to add SSL to Kafka
- Create a keystore and a truststore. Example:
```bash
# generate the certificates. The script certs/generate_ssl_keys.sh can used to generate the certificates
chmod +x certs/generate_ssl_keys.sh
./certs/generate_ssl_keys.sh
```
- Create a .env file and add the following environment variables:
    - `KAFKA_SSL_KEYSTORE_PASSWORD=your_keystore_password` (default is password)
    - `KAFKA_SSL_TRUSTSTORE_PASSWORD=your_truststore_password` (default is password)
    - `KAFKA_SSL_KEY_PASSWORD=your_key_password` (default is password)
- Run the application
```bash
docker compose --profile frontend --env-file .env -f docker-compose-kafka-ssl.yml‚‚ up -d --build
```
