# FlowPilot — Architecture & Planning Docs

This directory holds the six planning documents and the main architecture reference.

| File | Contents |
|------|----------|
| `01-vision.md` | Product vision, goals, and non-goals |
| `02-architecture.md` | System architecture — modules, boundaries, ADRs |
| `03-data-model.md` | Entity model, schema decisions |
| `04-api-design.md` | REST / WebSocket surface, versioning strategy |
| `05-ai-integration.md` | AI module design, prompt strategy, model routing |
| `06-roadmap.md` | Milestone plan, phased delivery |

## Module Map

```
backend/src/main/java/com/iacross/flowpilot/
├── identity/    — auth, users, tenants, API keys
├── channel/     — channel adapters (web, WhatsApp, Slack, email …)
├── flow/        — conversation flow definitions & versioning
├── engine/      — runtime execution of flows
├── ai/          — LLM routing, prompt templates, RAG
├── commerce/    — billing, plans, usage metering
├── delivery/    — outbound message dispatch & retries
├── analytics/   — event collection, aggregation, reporting
└── shared/      — cross-cutting concerns (domain events, value objects)
```
