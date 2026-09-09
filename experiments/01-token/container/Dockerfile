# Development-only, no credentials, evaluator, reference answers or OML source.
FROM eclipse-temurin@sha256:75ce56643243c3db632be2ef259625fb42ee3be1334389659f7a1a61acb78783
RUN apt-get update && apt-get install -y --no-install-recommends python3 git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /seed && chmod 755 /seed
ENV HOME=/work/home TMPDIR=/work/tmp GRADLE_USER_HOME=/work/home/.gradle \
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    PYTHONDONTWRITEBYTECODE=1 LC_ALL=C TZ=UTC
USER 1000:1000
COPY egress.py /opt/oml-preparation/egress.py
WORKDIR /work
