---
status: accepted
---

# Keep deployment lightweight and Compose-capable

AyuVahak must retain Docker Compose as a first-class deployment and local development path. Kubernetes is optional, not a prerequisite. A small or low-resource operator should be able to assess and run the system without a cluster platform; larger deployments can adopt orchestration when their operational needs justify it. This decision constrains infrastructure assumptions but does not prescribe a production topology or observability vendor.
