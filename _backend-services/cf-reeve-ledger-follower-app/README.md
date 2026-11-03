# Ledger Follower App

Backend service that **follows the Cardano ledger** for the Reeve (LOB) platform: it keeps a live
ChainSync connection (via [Yaci Store](https://github.com/bloxbean/yaci-store)), indexes blocks,
transactions, metadata and UTXOs into the same PostgreSQL database it runs against, and exposes the
indexed state to the rest of the platform over a small JSON API plus Prometheus metrics.

> This is one module of [cf-reeve-platform](../README.md) — see the platform README for the overall
> architecture. The app is built with Gradle + Spring Boot (Java 21) and ships as a Docker image.

## What it does

| Concern            | What happens                                                                                              |
|--------------------|-----------------------------------------------------------------------------------------------------------|
| Chain sync         | `ChainSyncService` follows the chain from a configured start block/slot (`store.cardano.*` properties).      |
| Indexing           | Yaci Store writes blocks, transactions, metadata and UTXOs into the app's database (`DB_URL`).              |
| JSON API           | `GET /api/v1/tip`, `GET /api/v1/tx-details/{txHash}`, `POST /api/v1/on-chain-statuses` on port `9090`.      |
| Diagnostics        | `GET /actuator/health`, `/actuator/metrics`, `/actuator/prometheus` (see `management.endpoints.web.exposure.include`). |
| **Feature focus**  | **Address balance monitor** — live lovelace balances of a configured set of addresses.                     |

---

## Feature: Address balance monitor

The monitor answers one question, continuously and cheaply:

> **How much is currently held (in lovelace) by each of these Cardano addresses?**

Balances are published as a single Prometheus gauge with one time series per monitored address:

```
cardano_address_balance{address="addr_test1qppcuzs25ghufz2mzg37hcm77px4matwz2hz072z4ms7umqasylaf2uure0h5dw6zmm4ctnxfm0m9gf8lst6fflthlhq5hrg7w"} 5000000
```

### How it works

The set of time series is **dynamic**: when an address newly holds a UTXO a series is registered;
when an address spends down to nothing its series is **removed** (so a spent-down address is absent,
not reported as `0`). Refresh failures keep the last known values instead of wiping the series.

| Component                                                | Role                                                            |
|----------------------------------------------------------|-----------------------------------------------------------------|
| `plugin/AddressUtxoFilterPlugin.java`                      | Yaci Store filter: keeps only allow-listed `ownerAddr` UTXOs.       |
| `plugin/JavaPluginFactory.java`                            | Binds `lang: java` plugins in `application.yaml`; reads the allow-list. |
| `repository/AddressBalanceRepository.java`, `AddressBalance.java` | JPA aggregate query over `address_utxo` / `tx_input`.      |
| `service/AddressBalanceService.java`                       | Turns the aggregate rows into `Map<address, lovelace>`.             |
| `metrics/AddressBalanceMetrics.java`                       | Registers/reconciles the gauges and re-queries on the refresh cadence. |

### Configuration

| Property (`application.properties` / `application.yaml`) | Environment variable                     | Default   | Meaning                                                                                     |
|----------------------------------------------------------|------------------------------------------|-----------|---------------------------------------------------------------------------------------------|
| `lob.utxo.filter.addresses`                              | `LOB_UTXO_FILTER_ADDRESSES`              | *(empty)* | Addresses to monitor — **comma-separated only** (Spring splits on `,`). |
| `lob.metrics.address-balance.refresh-interval`           | `LOB_ADDRESS_BALANCE_REFRESH_INTERVAL`   | `PT30S`   | How often balances are re-queried (ISO-8601 duration, e.g. `PT10S`, `PT5M`).                |

> **Empty allow-list = nothing is monitored.** The filter drops every unspent UTXO, `address_utxo`
> stays empty and **no `cardano_address_balance` series are published**. Monitoring is opt-in: you
> must list at least one address. The allow-list is captured once when the plugin is created, so
> **changing it requires a restart** of the app.

### Usage — local dev (Yaci DevKit, no external services)

The bundled `docker-compose.yml` runs the follower app against a local Yaci DevKit chain, its indexer
and a PostgreSQL 17 instance, with the devkit's minter address already monitored:

```bash
cd _backend-services/cf-reeve-ledger-follower-app
docker compose up --build
```

Then read the live balances from the app's actuator endpoints (the app listens on port `9090`, which
the compose file maps to the host):

```bash
# all monitored addresses, one line each
curl -s http://localhost:9090/actuator/prometheus | grep cardano_address_balance
# cardano_address_balance{address="addr_test1qppcuz..."} 5000000  (lovelace)

# same metric as JSON, one entry per address
curl -s "http://localhost:9090/actuator/metrics/cardano.address.balance?showDetail=true&groupBy=address"
```

Watch an address over time — top it up on the devkit (see `yaci-config/env` → `topup_addresses`) and
see the gauge move on the next refresh tick:

```bash
until true; do
  curl -s http://localhost:9090/actuator/prometheus | grep -o 'cardano_address_balance.*'
  sleep 10
done
```

To monitor more/fewer addresses, override the allow-list in `docker-compose.yml` (`follower-app` →
`LOB_UTXO_FILTER_ADDRESSES`) and restart. Keep it in sync with the devkit's `topup_addresses` when
testing locally, so the address you monitor is one that actually holds UTXOs.

### Usage — real network (preprod / mainnet)

Set the network profile and the allow-list in the environment (or in the matching
`application-<profile>.properties`; profiles are `preprod`, `mainnet`, `preview`, `devnet`,
`dev--yaci-dev-kit`, and the default in `application.properties` is `dev--preprod`):

```bash
export SPRING_PROFILES_ACTIVE=mainnet
export LOB_UTXO_FILTER_ADDRESSES="addr1qx...,addr1qy..."   # only what you need to watch
export LOB_ADDRESS_BALANCE_REFRESH_INTERVAL=PT1M            # trade latency for query load
./gradlew clean build
java -jar build/libs/*-all.jar
```

Or supply the same variables to the Docker image. Only chain-sync settings change per network
(`store.cardano.host`, `store.cardano.port`, `store.cardano.protocol-magic`,
`store.cardano.sync-start-blockhash`/`slot`); the address-balance settings are network-independent.

### Consuming the metric (Grafana / Prometheus)

The gauge is an ordinary Prometheus gauge, so it can be graphed and diffed directly:

```text
# current balances, all monitored addresses
cardano_address_balance

# one address, filtered
cardano_address_balance{address="addr_test1qppcuz..."}

# net lovelace change over the last hour, per address
cardano_address_balance - (cardano_address_balance offset 1h)
```

Notes for consumers:

- Values are **lovelace** (divide by `1e6` for ADA), and are as fresh as the last refresh — a scrape
  between ticks reports the previous value. Expect up to `refresh-interval` of lag.
- An address with no unspent UTXOs is **absent**, so `cardano_address_balance` alone does not
  distinguish "monitored and spent down" from "not monitored".
- Only the address's own unspent UTXOs count: contract/market-order UTXOs are included when their
  `ownerAddr` is on the allow-list; anything else is filtered out before it is ever stored, which is
  what keeps `address_utxo` small enough to aggregate on every tick.
