# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Quick Start

### Infrastructure (Docker)
```bash
docker-compose up -d  # Start all services
docker-compose ps     # Verify status
```

### Backend (Java 21)
```bash
cd services/strategy-service && mvn spring-boot:run
```

### Frontend (Vue 3)
```bash
cd web-ui && npm install && npm run dev
```

### Data Pipeline (Python)
```bash
cd scripts
python3 setup_clickhouse.py      # Create tables
python3 final_data_import.py     # Import sample data
```

---

## Architecture Overview

**AgenticHarness** is an AI-driven quantitative trading system built with Harness Engineering methodology.

### Core Stack
| Layer | Technology |
|-------|------------|
| Backend | Java 21 + Spring Cloud Alibaba + Netty + Disruptor |
| AI Agents | Python + AgentScope + MCP Protocol |
| Frontend | Vue 3 + ECharts + Pinia |
| Messaging | Kafka (A2A) + RocketMQ (Orders) |
| Storage | Redis 8.0 → PostgreSQL → ClickHouse → MinIO → Neo4j |
| Observability | Prometheus + Grafana + SkyWalking 10 |

### Service Structure
```
services/
├── quote-service/      # Netty + Disruptor real-time quotes
├── strategy-service/   # Ta4j backtesting + strategy management (implemented)
├── order-service/      # RocketMQ order processing
├── account-service/    # Account management
├── risk-service/       # Risk control
└── backtest-service/   # Historical backtesting

agents/                 # Python AI agents (orchestrator, technical, sentiment, etc.)
web-ui/                 # Vue 3 frontend
harness/                # MCP servers, validators, feedback loop
simulation/             # Phase 2: scenario simulation engine (MiroFish-style)
```

### Strategy Service Architecture (Implemented)
```
StrategyController (/api/strategy)
├── GET /list          → List enabled strategies
├── POST /backtest     → Run backtest with Ta4j
└── GET /signal        → Get trading signal

BacktestApplicationService
└── BacktestExecutor → Executes Ta4j strategy on BarSeries

Data Flow: ClickHouse → BarSeries → Strategy → Signal/Result
```

### Key Design Decisions
- **ClickHouse** for time-series K-line data ( MergeTree engine, partitioned by symbol/date)
- **Ta4j** for strategy backtesting (technical indicators, trading rules)
- **Disruptor** for lock-free event passing (P99 < 50μs in quote processing)
- **Virtual threads** (Java 21) for high-concurrency order handling
- **MCP Protocol** for standardized AI agent tool access

---

## Development Commands

### Build & Test
```bash
# Build all services
cd services && mvn clean install

# Run single service tests
cd services/strategy-service && mvn test

# Run specific test class
mvn test -Dtest=BacktestExecutorTest
```

### Database Operations
```bash
# ClickHouse CLI (container)
docker exec -it harness-clickhouse clickhouse-client --password harness123

# Query K-line data
SELECT * FROM agentic.daily_kline WHERE stock_code='000001' ORDER BY trade_date;

# Reset database
docker compose down clickhouse && docker compose up -d clickhouse
```

### Data Import
```bash
# Full pipeline test
cd scripts
python3 data_pipeline.py --symbol 000001 --days 730

# Quick sample data import
python3 final_data_import.py
```

---

## Configuration Notes

### ClickHouse Authentication
- Password: `harness123` (configured in `docker/config/clickhouse/users.xml`)
- JDBC: `jdbc:clickhouse://localhost:8123/quant`
- HTTP: `http://localhost:8123` (for curl/browser)
- Native: `localhost:9000` (for JDBC/ODBC clients)

### Docker Data Paths
All Docker data stored centrally at `/Users/chinazhouwy/doc/docker/`:
- Config: `/Users/chinazhouwy/doc/docker/config/`
- Data: `/Users/chinazhouwy/doc/docker/data/`

### Service Ports
| Service | Port |
|---------|------|
| Strategy Service | 8082 |
| Redis | 6379 |
| PostgreSQL | 5432 |
| ClickHouse (HTTP) | 8123 |
| ClickHouse (Native) | 9000 |
| Kafka | 9092 |
| MongoDB | 27017 |
| MinIO | 9002 |
| RocketMQ | 9876 |
| Neo4j | 7474/7687 |
| Grafana | 3000 |
| Prometheus | 9090 |
| SkyWalking UI | 8080 |

---

## Project Status

**Phase 1 (In Progress)**: Core infrastructure + Strategy service implemented
- [x] Docker infrastructure (11 services)
- [x] ClickHouse table structure (8 tables)
- [x] Data pipeline (mootdx → ClickHouse)
- [x] Strategy service with Ta4j backtesting
- [ ] Quote service (Netty + Disruptor)
- [ ] AI Agent integration
- [ ] Frontend dashboard

**Phase 2 (Planned)**: Scenario simulation engine (MiroFish-style multi-agent simulation)

---

## Common Pitfalls

1. **ClickHouse Connection**: JDBC driver requires explicit password (not empty). Use `harness123`.

2. **Docker Volume Mount**: After modifying `users.xml`, run `docker compose restart clickhouse` (not just `restart`).

3. **Maven Build**: Parent POM must be built first: `cd services && mvn install` before running individual services.

4. **Data Pipeline**: mootdx may require network access; use `test_direct_fetch.py` to verify connectivity first.

5. **Eureka Discovery**: All services register with Eureka at `http://localhost:8761/eureka` - ensure it's running before starting microservices.

---

## File References

- `AGENTS.md` - Harness Engineering specification (AI action guidelines)
- `README.md` - High-level architecture and interview prep
- `FAIL.md` - Troubleshooting log (Docker/ClickHouse issues)
- `TODOLIST.md` - Task checklist with dates
- `docker-compose.yml` - Infrastructure as code (11 services)
- `scripts/DATA_PIPELINE_README.md` - Data import pipeline documentation
