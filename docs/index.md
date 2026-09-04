# URL Shortener Architecture

Explore the verified runtime architecture of the URL Shortener application.
These interactive diagrams are generated from repository-backed Archify specifications and support
search, guided views, light and dark themes, zoom, and SVG or image export.

## Architecture diagrams

- [High-level system architecture](architecture/system-architecture.html)  
  Browser-to-EC2 request routing, the Docker Compose services, storage, asynchronous analytics, and
  observability.

- [URL redirect request sequence](architecture/url-redirect-sequence.html)  
  The complete redirect lifecycle, including Redis hits, PostgreSQL fallback, expiration checks,
  asynchronous Kafka publication, and the `302` response.

- [Kafka click analytics data flow](architecture/kafka-click-analytics-flow.html)  
  Click-event publication, Kafka consumption, GeoIP enrichment, PostgreSQL persistence, and
  analytics queries.

- [CI/CD and AWS deployment architecture](architecture/cicd-aws-deployment.html)  
  GitHub Actions validation, AWS OIDC authentication, SSM deployment to EC2, HTTPS routing, and the
  public health gate.

## Project links

- [Source repository](https://github.com/vimaneti-ai/ai-url-shortener)
- [Live application](https://short.vinodmaneti.com)
- [Architecture documentation](architecture.md)
- [Deployment guide](../DEPLOYMENT.md)

The diagrams describe components and relationships verified from the repository. Host-managed
configuration—such as IONOS DNS, the Elastic IP, EC2 nginx, Certbot, IAM trust configuration, and
security-group rules—is explicitly identified as documented external infrastructure rather than
repository-provisioned infrastructure.
