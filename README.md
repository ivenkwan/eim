# Enterprise Incentive Management
### AI-native Enterprise Incentive Management (EIM) platform that combines the agility of no-code, spreadsheet-style modeling with enterprise-grade governance (ASC 606 / IFRS 15) and real-time seller behavioral motivation. By bridging the gap between Finance, RevOps, and Sales, our platform will reduce commission processing time by up to 80%, eliminate payout leakage, and turn compensation plans into active revenue drivers rather than static post-mortem statements.

## Documentation

| Document | Contents |
|---|---|
| [Business Proposal](doc/EIM%20Platform%20Business%20Proposal.pdf) | Market case, competitive positioning, phased roadmap, PRD, ASC 606 module spec, DAG engine design, UX wireframes (source material) |
| [Functional Requirements](doc/01-functional-requirements.md) | Full-roadmap FR catalog (Phase 1–3) with acceptance criteria and traceability |
| [Non-Functional Requirements](doc/02-non-functional-requirements.md) | Quality attributes: precision & determinism, performance, security, portability, compliance — each with measurable targets |
| [Architecture Plan](doc/03-architecture-plan.md) | Target architecture on the mandated stack: cloud-agnostic Kubernetes, PostgreSQL + AGE, APISIX gateway, strict TypeScript frontend, Drools (DRL + DMN) rules, Kogito process automation |
| [Manual Intervention & Approval Playbook](doc/04-manual-intervention-playbook.md) | Every human intervention/approval point (MI-xx) and all manual adjustment procedures (MA-01…14) with reason codes, SLAs, and audit expectations |
| [Build TODO & Tollgates](todo.md) | All build activities grouped into tollgates TG-0…TG-8 with workstreams, exit criteria, and FR/NFR traceability |
| [TG-0 Build Log](doc/build-log/TG-0.md) | Verification evidence for tollgate TG-0 (program setup): gate results, test totals, and the live-cluster-boot blocker diagnosis |

## Technology mandates

- **Cloud-agnostic**, deployed on CNCF-conformant **Kubernetes**
- **PostgreSQL** as the system of record, with the **Apache AGE** graph extension for hierarchy / credit / plan-dependency graphs
- **Apache APISIX** as the API gateway
- **Strict type-safe TypeScript** frontend
- **Drools** for forward- and backward-chaining rules plus **DMN** decision tables
- **Kogito** for cloud-native business process automation (BPMN)

