# Security

LiteHelm provides privileged host management. Keep the bearer token private, publish the loopback API through a trusted HTTPS endpoint, and protect the phone used for administration.

The API does not expose arbitrary commands unless the administrator enables script jobs (`install.sh --enable-exec` or `-allow-exec`). With script jobs enabled, the bearer token is equivalent to a shell as the script user: by default the dedicated unprivileged `litehelm` account (no login shell, no sudo, `NoNewPrivileges`), or root only when explicitly configured with `--exec-user root`. A non-root LiteHelm runs scripts as itself. Job scripts and output are stored under `/var/lib/litehelm/jobs` with mode 0700 and are not readable by the script user. Critical system services and management processes are protected; additional process/service prefixes can be configured by the administrator. Power actions require explicit server opt-in and typed hostname confirmation.

For a suspected vulnerability, use the repository's private vulnerability reporting feature if enabled. Do not post tokens, private host addresses or exploit details in a public issue.
