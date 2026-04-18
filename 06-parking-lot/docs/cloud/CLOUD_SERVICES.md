# Cloud Services -- Parking Lot System

> Parking Lot is primarily an **LLD problem**. Cloud is secondary, but know it for follow-ups.

---

## Cloud Service Mapping

| Component | AWS | GCP | Azure | Notes |
|-----------|-----|-----|-------|-------|
| **Backend** | ECS / Lambda | Cloud Run | Container Apps | Simple CRUD, lightweight |
| **Database** | RDS (PostgreSQL) | Cloud SQL | SQL Database | ACID for payments |
| **IoT (Sensors)** | IoT Core | IoT Core | IoT Hub | Spot sensors -> status |
| **Payment** | -- | -- | -- | Stripe/Square (3rd party) |
| **Display** | IoT + MQTT | IoT + MQTT | IoT + MQTT | Push updates to boards |
| **Monitoring** | CloudWatch | Cloud Monitoring | Azure Monitor | Occupancy, revenue |
| **Mobile API** | API Gateway | Cloud Endpoints | API Management | For mobile app |

---

## When Does Cloud Make Sense?

### Single Lot: Cloud is Overkill
- Could run entirely on a **Raspberry Pi + local PostgreSQL**
- Sensor reads -> local DB -> display board update
- No internet dependency, no latency, no monthly bill
- **Cost: $0/month** (hardware is one-time)

### Parking Chain (100+ Lots): Cloud Makes Sense
- **Centralized dashboard** -- real-time occupancy across all lots
- **Mobile app** -- "find nearest lot with available spots"
- **Analytics** -- peak hours, revenue trends, pricing optimization
- **Remote management** -- change pricing, monitor cameras
- **Cost: $50-100/month per lot on cloud**

### Architecture for Chain

```
[Lot 1 Sensors] --MQTT--> [IoT Core] --> [Lambda/Cloud Run] --> [RDS/Cloud SQL]
[Lot 2 Sensors] --MQTT--> [IoT Core] --> [Lambda/Cloud Run] --> [RDS/Cloud SQL]
       ...                                                            |
[Lot N Sensors] --MQTT--> [IoT Core] --> [Lambda/Cloud Run] ----+    |
                                                                     v
                                                          [Central Dashboard]
                                                          [Mobile App API]
                                                          [Analytics/Reports]
```

---

## Interview Tip

> "For a single parking lot, I'd keep it local -- a Raspberry Pi with PostgreSQL handles everything.
> But if this scales to a chain of 100+ lots, cloud makes sense for centralized monitoring,
> a mobile app, and analytics. The LLD stays the same either way -- the cloud is just the
> deployment layer."

This shows you understand **when to use cloud vs. when it's overkill** -- a senior-level distinction.

---

## Cost Comparison

| Scenario | Infrastructure | Monthly Cost | Latency |
|----------|---------------|-------------|---------|
| Single lot (local) | Raspberry Pi + local DB | ~$0 | <1ms |
| Single lot (cloud) | Lambda + RDS | $50-100 | 50-200ms |
| Chain (100 lots) | ECS + RDS + IoT Core | $2,000-5,000 total | 50-200ms |

The latency difference matters for gate operations -- a 200ms cloud round-trip feels sluggish at a parking gate.
